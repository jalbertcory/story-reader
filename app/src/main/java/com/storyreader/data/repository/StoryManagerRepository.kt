package com.storyreader.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.storyreader.data.catalog.ServerBook
import com.storyreader.data.catalog.StoryManagerApiClient
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.sync.BookSyncMetadata
import com.storyreader.data.sync.SyncSourceKinds
import com.storyreader.reader.epub.EpubRepository
import org.readium.r2.shared.publication.services.cover
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

private const val TAG = "StoryManagerRepo"

class StoryManagerRepository(
    private val apiClient: StoryManagerApiClient,
    private val bookDao: BookDao,
    private val epubRepository: EpubRepository,
    private val downloadDir: File,
    private val coversDir: File
) {

    suspend fun importSeriesBooks(
        seriesName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Int> = runCatching {
        val books = apiClient.fetchSeriesBooks(seriesName).getOrThrow()
        var imported = 0
        for ((index, serverBook) in books.withIndex()) {
            onProgress(index, books.size)
            importSingleBook(serverBook)
            imported++
        }
        onProgress(books.size, books.size)
        imported
    }

    suspend fun importSingleBook(serverBook: ServerBook): Result<BookEntity> = runCatching {
        val existing = bookDao.getByServerBookId(serverBook.id)

        val file = apiClient.downloadBook(serverBook.id, downloadDir).getOrThrow()
        val fileUri = Uri.fromFile(file)
        val publication = epubRepository.openPublication(fileUri).getOrThrow()
        val coverUri = saveCover(publication.cover())
        val wordCount = serverBook.currentWordCount ?: 0

        // Use the file URI as bookId so the reader can open it.
        // On re-import (update), delete the old entity if the file path changed,
        // then insert with the new path while preserving reading progress.
        if (existing != null && existing.bookId != fileUri.toString()) {
            bookDao.delete(existing)
        }

        val entity = BookEntity(
            bookId = fileUri.toString(),
            title = serverBook.title,
            author = serverBook.author,
            coverUri = coverUri ?: existing?.coverUri,
            syncId = existing?.syncId ?: BookSyncMetadata.syncIdFor(serverBook.title, serverBook.author),
            syncSourceKind = SyncSourceKinds.STORY_MANAGER,
            syncSourceUrl = BookSyncMetadata.normalizeRemoteUrl(serverBook.downloadUrl),
            originalFileName = file.name,
            totalProgression = existing?.totalProgression ?: 0f,
            wordCount = existing?.wordCount ?: wordCount,
            hidden = false,
            series = serverBook.series,
            seriesIndex = serverBook.seriesIndex,
            sourceType = serverBook.sourceType,
            serverBookId = serverBook.id,
            contentVersion = serverBook.contentVersion,
            contentUpdatedAt = parseServerTimestamp(serverBook.contentUpdatedAt),
            serverWordCount = wordCount,
            lastSyncedAt = System.currentTimeMillis(),
            lastChapterTitle = existing?.lastChapterTitle,
            lastChapterProgression = existing?.lastChapterProgression
        )
        bookDao.insert(entity)
        entity
    }

    suspend fun importStandaloneBooks(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Int> = runCatching {
        val books = apiClient.fetchStandaloneBooks().getOrThrow()
        var imported = 0
        for ((index, serverBook) in books.withIndex()) {
            onProgress(index, books.size)
            importSingleBook(serverBook)
            imported++
        }
        onProgress(books.size, books.size)
        imported
    }

    suspend fun checkForNewBooks(): Result<List<String>> = withContext(Dispatchers.IO) { runCatching {
        // Single request to get all books from the server
        val allServerBooks = apiClient.fetchAllBooks().getOrThrow()
        val existingServerIds = bookDao.getAllServerBookIds().toSet()
        val localSeriesNames = bookDao.getLocalSeriesNames().toSet()
        val allLocalBooks = bookDao.getAllOnce()
        Log.d(TAG, "checkForNewBooks: ${allServerBooks.size} server books, ${localSeriesNames.size} local series, ${allLocalBooks.size} local books")

        // Index local books by title+author for duplicate detection
        val localByTitleAuthor = allLocalBooks.associateBy {
            "${it.title.lowercase()}|${it.author.lowercase()}"
        }

        val importedTitles = mutableListOf<String>()

        // Index local books by serverBookId for series order updates
        val localByServerId = allLocalBooks.filter { it.serverBookId != null }
            .associateBy { it.serverBookId }

        // 1. For each server book, check if it already exists locally (by serverBookId or title+author)
        //    - If matched by title+author but missing serverBookId/series, backfill them
        //    - If truly new and belongs to a local series, import it
        //    - If already linked, update series order if changed
        for (serverBook in allServerBooks) {
            val key = "${serverBook.title.lowercase()}|${serverBook.author.lowercase()}"
            val localMatch = localByTitleAuthor[key]

            if (serverBook.id in existingServerIds) {
                // Already linked — update series index if changed
                val local = localByServerId[serverBook.id]
                if (local != null && local.seriesIndex != serverBook.seriesIndex) {
                    bookDao.updateSeriesIndex(local.bookId, serverBook.seriesIndex)
                }
                continue
            }

            if (localMatch != null) {
                // Book exists locally but wasn't linked to the server — backfill
                Log.d(TAG, "checkForNewBooks: linking '${localMatch.title}' to serverBookId=${serverBook.id}")
                bookDao.updateServerBookId(localMatch.bookId, serverBook.id)
                if (localMatch.series == null && serverBook.series != null) {
                    bookDao.updateSeries(localMatch.bookId, serverBook.series)
                }
                bookDao.updateSeriesIndex(localMatch.bookId, serverBook.seriesIndex)
                continue
            }

            if (serverBook.series == null || serverBook.series !in localSeriesNames) continue
            Log.d(TAG, "checkForNewBooks: new book '${serverBook.title}' in series '${serverBook.series}'")
            importSingleBook(serverBook)
                .onFailure { e -> Log.w(TAG, "Failed to import '${serverBook.title}'", e) }
                .getOrNull() ?: continue
            importedTitles.add(serverBook.title)
        }

        // Backfill missing covers from server
        val booksWithoutCovers = allLocalBooks.filter { it.coverUri == null && it.serverBookId != null }
        if (booksWithoutCovers.isNotEmpty()) {
            val serverBooksById = allServerBooks.associateBy { it.id }
            for (book in booksWithoutCovers) {
                val serverBook = serverBooksById[book.serverBookId] ?: continue
                val coverUrl = serverBook.coverUrl ?: continue
                runCatching {
                    val bytes = apiClient.downloadCoverImage(coverUrl).getOrThrow()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    saveCover(bitmap)?.let { path ->
                        bookDao.updateCoverUri(book.bookId, path)
                        Log.d(TAG, "checkForNewBooks: backfilled cover for '${book.title}'")
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Failed to backfill cover for '${book.title}'", e)
                }
            }
        }

        Log.d(TAG, "checkForNewBooks: imported ${importedTitles.size} new books")
        importedTitles
    } }

    suspend fun checkForUpdates(): Result<Int> = withContext(Dispatchers.IO) { runCatching {
        val webBooks = bookDao.getWebBooksForSync()
        Log.d(TAG, "checkForUpdates: ${webBooks.size} web books")
        if (webBooks.isEmpty()) return@runCatching 0

        val maxSynced = webBooks.mapNotNull { it.lastSyncedAt }.maxOrNull()
        Log.d(TAG, "checkForUpdates: maxSynced=$maxSynced (${maxSynced?.let { Instant.ofEpochMilli(it) }})")
        val serverUpdates = apiClient.fetchUpdates(maxSynced).getOrThrow()
        Log.d(TAG, "checkForUpdates: server returned ${serverUpdates.size} updates")

        val localByServerId = webBooks.associateBy { it.serverBookId }
        var updatedCount = 0

        for (serverBook in serverUpdates) {
            val local = localByServerId[serverBook.id]
            if (local == null) {
                Log.d(TAG, "checkForUpdates: serverBookId=${serverBook.id} '${serverBook.title}' not in local DB, skipping")
                continue
            }
            val localVersion = local.contentVersion ?: 0
            Log.d(TAG, "checkForUpdates: '${serverBook.title}' serverVersion=${serverBook.contentVersion} localVersion=$localVersion")
            if (serverBook.contentVersion > localVersion) {
                Log.d(TAG, "checkForUpdates: updating '${serverBook.title}'")
                importSingleBook(serverBook).getOrThrow()
                updatedCount++
            }
        }
        Log.d(TAG, "checkForUpdates: updated $updatedCount books")
        updatedCount
    } }

    private fun parseServerTimestamp(timestamp: String): Long = try {
        Instant.parse(timestamp).toEpochMilli()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse server timestamp '$timestamp'", e)
        System.currentTimeMillis()
    }

    private fun saveCover(bitmap: Bitmap?): String? {
        bitmap ?: return null
        return try {
            coversDir.mkdirs()
            val file = File(coversDir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cover image", e)
            null
        }
    }
}
