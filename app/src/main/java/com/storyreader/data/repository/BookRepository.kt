package com.storyreader.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.sync.BookImportMetadata
import com.storyreader.data.sync.BookSyncMetadata
import com.storyreader.data.sync.SyncSourceKinds
import com.storyreader.reader.epub.EpubRepository
import android.util.Log
import kotlinx.coroutines.flow.Flow
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

interface BookRepository {
    fun observeAll(): Flow<List<BookEntity>>
    fun observeAllIncludingHidden(): Flow<List<BookEntity>>
    fun observeById(bookId: String): Flow<BookEntity?>
    suspend fun insert(book: BookEntity)
    suspend fun delete(book: BookEntity)
    suspend fun hideBook(bookId: String)
    suspend fun unhideBook(bookId: String)
    suspend fun updateProgression(bookId: String, progression: Float)
    suspend fun importFromUri(uri: Uri, importMetadata: BookImportMetadata? = null): Result<BookEntity>
    suspend fun getWordCount(bookId: String): Int
    suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?)
    suspend fun resetBookProgress(bookId: String, restartAt: Long = System.currentTimeMillis())
}

class BookRepositoryImpl(
    private val context: Context,
    private val bookDao: BookDao,
    private val positionDao: ReadingPositionDao,
    private val sessionDao: ReadingSessionDao,
    private val epubRepository: EpubRepository
) : BookRepository {

    override fun observeAll(): Flow<List<BookEntity>> = bookDao.getAll()

    override fun observeAllIncludingHidden(): Flow<List<BookEntity>> = bookDao.getAllIncludingHidden()

    override fun observeById(bookId: String): Flow<BookEntity?> = bookDao.getById(bookId)

    override suspend fun insert(book: BookEntity) = bookDao.insert(book)

    override suspend fun delete(book: BookEntity) = bookDao.delete(book)

    override suspend fun hideBook(bookId: String) = bookDao.setHidden(bookId, true)

    override suspend fun unhideBook(bookId: String) = bookDao.setHidden(bookId, false)

    override suspend fun updateProgression(bookId: String, progression: Float) {
        bookDao.updateProgression(bookId, progression)
        if (progression >= 1f) {
            bookDao.getByIdOnce(bookId)
                ?.copy(totalProgression = progression)
                ?.takeIf { it.shouldDeleteCompletedLocalFile() }
                ?.let(::deleteLocalBookFile)
        }
    }

    override suspend fun getWordCount(bookId: String): Int =
        bookDao.getWordCountById(bookId) ?: 0

    override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) =
        bookDao.updateChapterPosition(bookId, title, progression)

    override suspend fun resetBookProgress(bookId: String, restartAt: Long) {
        val existing = bookDao.getByIdOnce(bookId) ?: return
        positionDao.deleteForBook(bookId)
        sessionDao.deleteForBook(bookId)
        bookDao.update(
            existing.copy(
                totalProgression = 0f,
                lastChapterTitle = null,
                lastChapterProgression = null,
                restartAt = restartAt
            )
        )
    }

    override suspend fun importFromUri(uri: Uri, importMetadata: BookImportMetadata?): Result<BookEntity> {
        return epubRepository.openPublication(uri).map { publication ->
            val existing = bookDao.getByIdOnce(uri.toString())
            val coverUri = saveCover(publication.cover())
            val wordCount = countPublicationWords(publication)
            val title = publication.metadata.title ?: "Untitled"
            val author = publication.metadata.authors.joinToString { it.name }
            val syncId = importMetadata?.syncId ?: existing?.syncId ?: BookSyncMetadata.syncIdFor(title, author)
            val sourceKind = importMetadata?.sourceKind
                ?: if (uri.scheme == "content" || uri.scheme == "file") SyncSourceKinds.DEVICE else null
            val sourceUrl = importMetadata?.sourceUrl ?: uri.toString()
            val originalFileName = importMetadata?.originalFileName
                ?: BookSyncMetadata.extractOriginalFileName(uri.toString())
            BookEntity(
                bookId = uri.toString(),
                title = title,
                author = author,
                coverUri = coverUri ?: existing?.coverUri,
                syncId = syncId,
                syncSourceKind = sourceKind ?: existing?.syncSourceKind,
                syncSourceUrl = sourceUrl.ifBlank { existing?.syncSourceUrl.orEmpty() }.ifBlank { null },
                originalFileName = originalFileName ?: existing?.originalFileName,
                totalProgression = existing?.totalProgression ?: 0f,
                wordCount = existing?.wordCount ?: wordCount,
                hidden = existing?.hidden ?: false,
                series = existing?.series,
                sourceType = existing?.sourceType,
                serverBookId = existing?.serverBookId,
                contentVersion = existing?.contentVersion,
                contentUpdatedAt = existing?.contentUpdatedAt,
                serverWordCount = existing?.serverWordCount,
                lastSyncedAt = existing?.lastSyncedAt,
                lastChapterTitle = existing?.lastChapterTitle,
                lastChapterProgression = existing?.lastChapterProgression,
                seriesIndex = existing?.seriesIndex,
                restartAt = existing?.restartAt
            ).also { bookDao.insert(it) }
        }
    }

    private suspend fun countPublicationWords(publication: Publication): Int {
        var total = 0
        for (link in publication.readingOrder) {
            try {
                val bytes = publication.get(link)?.read()?.getOrNull() ?: continue
                total += countWordsInHtml(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) { Log.w(TAG, "Failed to count words in ${link.href}", e) }
        }
        return total
    }

    private fun countWordsInHtml(html: String): Int {
        val text = html
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&[a-zA-Z0-9#]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (text.isBlank()) 0 else text.split(" ").size
    }

    private fun saveCover(bitmap: Bitmap?): String? {
        bitmap ?: return null
        return try {
            val dir = File(context.filesDir, "covers")
            dir.mkdirs()
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cover image", e)
            null
        }
    }

    companion object {
        private const val TAG = "BookRepository"
    }
}
