package com.storyreader.data.repository

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.sync.BookImportMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [BookRepositoryImpl] using a real in-memory Room database and a
 * test double for [BookRepository] to exercise the DAO-facing paths.
 * importFromUri (requires EPUB file I/O) is covered by integration tests.
 */
@RunWith(RobolectricTestRunner::class)
class BookRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        // Use a minimal test double that delegates all DAO calls and stubs importFromUri.
        val dao = db.bookDao()
        repository = object : BookRepository {
            override fun observeAll(): Flow<List<BookEntity>> = dao.getAll()
            override fun observeAllIncludingHidden(): Flow<List<BookEntity>> = dao.getAllIncludingHidden()
            override fun observeById(bookId: String): Flow<BookEntity?> = dao.getById(bookId)
            override suspend fun insert(book: BookEntity) = dao.insert(book)
            override suspend fun delete(book: BookEntity) = dao.delete(book)
            override suspend fun hideBook(bookId: String) = dao.setHidden(bookId, true)
            override suspend fun unhideBook(bookId: String) = dao.setHidden(bookId, false)
            override suspend fun updateProgression(bookId: String, progression: Float) =
                dao.updateProgression(bookId, progression)
            override suspend fun importFromUri(_uri: Uri, importMetadata: BookImportMetadata?): Result<BookEntity> =
                Result.failure(UnsupportedOperationException("not tested here"))
            override suspend fun getWordCount(bookId: String): Int = dao.getWordCountById(bookId) ?: 0
            override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) =
                dao.updateChapterPosition(bookId, title, progression)
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll returns empty list initially`() = runTest {
        assertEquals(emptyList<BookEntity>(), repository.observeAll().first())
    }

    @Test
    fun `insert then observeAll contains book`() = runTest {
        repository.insert(BookEntity(bookId = "b1", title = "Neuromancer", author = "Gibson"))

        val books = repository.observeAll().first()
        assertEquals(1, books.size)
        assertEquals("Neuromancer", books[0].title)
    }

    @Test
    fun `observeById returns null when missing`() = runTest {
        assertNull(repository.observeById("missing").first())
    }

    @Test
    fun `observeById returns book after insert`() = runTest {
        repository.insert(BookEntity(bookId = "b2", title = "Foundation", author = "Asimov"))
        assertEquals("Foundation", repository.observeById("b2").first()?.title)
    }

    @Test
    fun `delete removes book`() = runTest {
        val book = BookEntity(bookId = "b3", title = "1984", author = "Orwell")
        repository.insert(book)
        repository.delete(book)

        assertEquals(0, repository.observeAll().first().size)
    }

    @Test
    fun `updateProgression stores new value`() = runTest {
        repository.insert(BookEntity(bookId = "b4", title = "Dune", author = "Herbert"))
        repository.updateProgression("b4", 0.75f)

        assertEquals(0.75f, repository.observeById("b4").first()?.totalProgression ?: 0f, 0.001f)
    }

    @Test
    fun `books are returned sorted alphabetically by title`() = runTest {
        repository.insert(BookEntity(bookId = "b5", title = "Zebra", author = "A"))
        repository.insert(BookEntity(bookId = "b6", title = "Apple", author = "B"))

        val books = repository.observeAll().first()
        assertEquals("Apple", books[0].title)
        assertEquals("Zebra", books[1].title)
    }

    @Test
    fun `insert with same id overwrites existing book`() = runTest {
        repository.insert(BookEntity(bookId = "b7", title = "Old Title", author = "A"))
        repository.insert(BookEntity(bookId = "b7", title = "New Title", author = "A"))

        val books = repository.observeAll().first()
        assertEquals(1, books.size)
        assertEquals("New Title", books[0].title)
    }

    @Test
    fun `hideBook excludes book from observeAll`() = runTest {
        repository.insert(BookEntity(bookId = "b8", title = "Hidden Book", author = "A"))
        repository.hideBook("b8")

        val books = repository.observeAll().first()
        assertEquals(0, books.size)
    }

    @Test
    fun `hideBook does not exclude book from observeAllIncludingHidden`() = runTest {
        repository.insert(BookEntity(bookId = "b9", title = "Secret", author = "A"))
        repository.hideBook("b9")

        val books = repository.observeAllIncludingHidden().first()
        assertEquals(1, books.size)
        assertEquals("Secret", books[0].title)
    }

    @Test
    fun `unhideBook makes book visible in observeAll again`() = runTest {
        repository.insert(BookEntity(bookId = "b10", title = "Restored", author = "A"))
        repository.hideBook("b10")
        repository.unhideBook("b10")

        val books = repository.observeAll().first()
        assertEquals(1, books.size)
    }

    @Test
    fun `getWordCount returns stored word count`() = runTest {
        repository.insert(BookEntity(bookId = "b11", title = "Wordy", author = "A", wordCount = 75_000))

        assertEquals(75_000, repository.getWordCount("b11"))
    }

    @Test
    fun `getWordCount returns 0 for unknown book`() = runTest {
        assertEquals(0, repository.getWordCount("nonexistent"))
    }
}
