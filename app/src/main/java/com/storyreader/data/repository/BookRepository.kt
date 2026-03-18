package com.storyreader.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.reader.epub.EpubRepository
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
    suspend fun importFromUri(uri: Uri): Result<BookEntity>
    suspend fun getWordCount(bookId: String): Int
    suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?)
}

class BookRepositoryImpl(
    private val context: Context,
    private val bookDao: BookDao,
    private val epubRepository: EpubRepository
) : BookRepository {

    override fun observeAll(): Flow<List<BookEntity>> = bookDao.getAll()

    override fun observeAllIncludingHidden(): Flow<List<BookEntity>> = bookDao.getAllIncludingHidden()

    override fun observeById(bookId: String): Flow<BookEntity?> = bookDao.getById(bookId)

    override suspend fun insert(book: BookEntity) = bookDao.insert(book)

    override suspend fun delete(book: BookEntity) = bookDao.delete(book)

    override suspend fun hideBook(bookId: String) = bookDao.setHidden(bookId, true)

    override suspend fun unhideBook(bookId: String) = bookDao.setHidden(bookId, false)

    override suspend fun updateProgression(bookId: String, progression: Float) =
        bookDao.updateProgression(bookId, progression)

    override suspend fun getWordCount(bookId: String): Int =
        bookDao.getWordCountById(bookId) ?: 0

    override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) =
        bookDao.updateChapterPosition(bookId, title, progression)

    override suspend fun importFromUri(uri: Uri): Result<BookEntity> {
        return epubRepository.openPublication(uri).map { publication ->
            val coverUri = saveCover(publication.cover())
            val wordCount = countPublicationWords(publication)
            BookEntity(
                bookId = uri.toString(),
                title = publication.metadata.title ?: "Untitled",
                author = publication.metadata.authors.joinToString { it.name },
                coverUri = coverUri,
                wordCount = wordCount
            ).also { bookDao.insert(it) }
        }
    }

    private suspend fun countPublicationWords(publication: Publication): Int {
        var total = 0
        for (link in publication.readingOrder) {
            try {
                val bytes = publication.get(link)?.read()?.getOrNull() ?: continue
                total += countWordsInHtml(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) { /* skip resource on error */ }
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
        } catch (_: Exception) {
            null
        }
    }
}
