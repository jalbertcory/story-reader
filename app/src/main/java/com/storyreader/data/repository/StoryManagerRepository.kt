package com.storyreader.data.repository

import android.graphics.Bitmap
import android.net.Uri
import com.storyreader.data.catalog.ServerBook
import com.storyreader.data.catalog.StoryManagerApiClient
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.reader.epub.EpubRepository
import org.readium.r2.shared.publication.services.cover
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

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
            totalProgression = existing?.totalProgression ?: 0f,
            wordCount = existing?.wordCount ?: wordCount,
            hidden = false,
            series = serverBook.series,
            sourceType = serverBook.sourceType,
            serverBookId = serverBook.id,
            contentVersion = serverBook.contentVersion,
            contentUpdatedAt = parseServerTimestamp(serverBook.contentUpdatedAt),
            serverWordCount = wordCount,
            lastSyncedAt = System.currentTimeMillis()
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

    suspend fun checkForUpdates(): Result<Int> = runCatching {
        val webBooks = bookDao.getWebBooksForSync()
        if (webBooks.isEmpty()) return@runCatching 0

        val maxSynced = webBooks.mapNotNull { it.lastSyncedAt }.maxOrNull()
        val serverUpdates = apiClient.fetchUpdates(maxSynced).getOrThrow()

        val localByServerId = webBooks.associateBy { it.serverBookId }
        var updatedCount = 0

        for (serverBook in serverUpdates) {
            val local = localByServerId[serverBook.id] ?: continue
            val localVersion = local.contentVersion ?: 0
            if (serverBook.contentVersion > localVersion) {
                importSingleBook(serverBook)
                updatedCount++
            }
        }
        updatedCount
    }

    private fun parseServerTimestamp(timestamp: String): Long = try {
        Instant.parse(timestamp).toEpochMilli()
    } catch (_: Exception) {
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
        } catch (_: Exception) {
            null
        }
    }
}
