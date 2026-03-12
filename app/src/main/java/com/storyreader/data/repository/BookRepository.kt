package com.storyreader.data.repository

import android.net.Uri
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.reader.epub.EpubRepository
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun observeAll(): Flow<List<BookEntity>>
    fun observeById(bookId: String): Flow<BookEntity?>
    suspend fun insert(book: BookEntity)
    suspend fun delete(book: BookEntity)
    suspend fun updateProgression(bookId: String, progression: Float)
    suspend fun importFromUri(uri: Uri): Result<BookEntity>
}

class BookRepositoryImpl(
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
            BookEntity(
                bookId = uri.toString(),
                title = publication.metadata.title ?: "Untitled",
                author = publication.metadata.authors.joinToString { it.name },
                coverUri = null
            ).also { bookDao.insert(it) }
        }
    }
}
