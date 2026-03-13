package com.storyreader.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.reader.epub.EpubRepository
import kotlinx.coroutines.flow.Flow
import org.readium.r2.shared.publication.services.cover
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

interface BookRepository {
    fun observeAll(): Flow<List<BookEntity>>
    fun observeById(bookId: String): Flow<BookEntity?>
    suspend fun insert(book: BookEntity)
    suspend fun delete(book: BookEntity)
    suspend fun updateProgression(bookId: String, progression: Float)
    suspend fun importFromUri(uri: Uri): Result<BookEntity>
}

class BookRepositoryImpl(
    private val context: Context,
    private val bookDao: BookDao,
    private val epubRepository: EpubRepository
) : BookRepository {

    override fun observeAll(): Flow<List<BookEntity>> = bookDao.getAll()

    override fun observeById(bookId: String): Flow<BookEntity?> = bookDao.getById(bookId)

    override suspend fun insert(book: BookEntity) = bookDao.insert(book)

    override suspend fun delete(book: BookEntity) = bookDao.delete(book)

    override suspend fun updateProgression(bookId: String, progression: Float) =
        bookDao.updateProgression(bookId, progression)

    override suspend fun importFromUri(uri: Uri): Result<BookEntity> {
        return epubRepository.openPublication(uri).map { publication ->
            val coverUri = saveCover(publication.cover())
            BookEntity(
                bookId = uri.toString(),
                title = publication.metadata.title ?: "Untitled",
                author = publication.metadata.authors.joinToString { it.name },
                coverUri = coverUri
            ).also { bookDao.insert(it) }
        }
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
