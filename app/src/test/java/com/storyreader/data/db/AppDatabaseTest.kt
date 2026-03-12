package com.storyreader.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
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
 * Tests the Room DAOs using an in-memory database (via Robolectric).
 * Validates schema, CRUD operations, FK constraints, and Flow emissions.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() = db.close()

    // ── BookDao ─────────────────────────────────────────────────────────────

    @Test
    fun `insert and observe all books`() = runTest {
        val book = BookEntity(bookId = "id1", title = "Dune", author = "Herbert")
        db.bookDao().insert(book)

        val books = db.bookDao().getAll().first()
        assertEquals(1, books.size)
        assertEquals("Dune", books[0].title)
    }

    @Test
    fun `getById returns null when book does not exist`() = runTest {
        val result = db.bookDao().getById("missing").first()
        assertNull(result)
    }

    @Test
    fun `updateProgression persists new value`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "b1", title = "Book", author = "Author"))
        db.bookDao().updateProgression("b1", 0.42f)

        val book = db.bookDao().getById("b1").first()
        assertEquals(0.42f, book?.totalProgression ?: 0f, 0.001f)
    }

    @Test
    fun `delete removes book from database`() = runTest {
        val book = BookEntity(bookId = "del1", title = "Gone", author = "Author")
        db.bookDao().insert(book)
        db.bookDao().delete(book)

        val books = db.bookDao().getAll().first()
        assertEquals(0, books.size)
    }

    @Test
    fun `insert replace overwrites existing book`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "b1", title = "Old Title", author = "A"))
        db.bookDao().insert(BookEntity(bookId = "b1", title = "New Title", author = "A"))

        val books = db.bookDao().getAll().first()
        assertEquals(1, books.size)
        assertEquals("New Title", books[0].title)
    }

    // ── ReadingPositionDao ───────────────────────────────────────────────────

    @Test
    fun `latest position returns most recent entry`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "book1", title = "T", author = "A"))
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":1}", timestamp = 1000L)
        )
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":5}", timestamp = 2000L)
        )

        val latest = db.readingPositionDao().getLatestPosition("book1").first()
        assertEquals("{\"page\":5}", latest?.locatorJson)
    }

    @Test
    fun `latest position returns null when no positions exist`() = runTest {
        val result = db.readingPositionDao().getLatestPosition("nonexistent").first()
        assertNull(result)
    }

    @Test
    fun `cascade delete removes reading positions when book deleted`() = runTest {
        val book = BookEntity(bookId = "b2", title = "T", author = "A")
        db.bookDao().insert(book)
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "b2", locatorJson = "{}")
        )
        db.bookDao().delete(book)

        val positions = db.readingPositionDao().getAllPositions().first()
        assertEquals(0, positions.size)
    }

    // ── ReadingSessionDao ────────────────────────────────────────────────────

    @Test
    fun `insert session returns generated id`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "b3", title = "T", author = "A"))
        val sessionId = db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = "b3", startTime = 1000L)
        )
        assert(sessionId > 0)
    }

    @Test
    fun `getById returns correct session`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "b4", title = "T", author = "A"))
        val id = db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = "b4", startTime = 5000L)
        )

        val session = db.readingSessionDao().getById(id)
        assertEquals(5000L, session?.startTime)
    }

    @Test
    fun `updateSession persists duration and pages`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "b5", title = "T", author = "A"))
        val id = db.readingSessionDao().insert(ReadingSessionEntity(bookId = "b5"))
        val session = db.readingSessionDao().getById(id)!!
        db.readingSessionDao().updateSession(
            session.copy(durationSeconds = 300, pagesTurned = 12)
        )

        val updated = db.readingSessionDao().getById(id)
        assertEquals(300, updated?.durationSeconds)
        assertEquals(12, updated?.pagesTurned)
    }

    @Test
    fun `getSessionsForBook filters by bookId`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "bA", title = "T", author = "A"))
        db.bookDao().insert(BookEntity(bookId = "bB", title = "T", author = "A"))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "bA"))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "bA"))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "bB"))

        val sessions = db.readingSessionDao().getSessionsForBook("bA").first()
        assertEquals(2, sessions.size)
        assert(sessions.all { it.bookId == "bA" })
    }
}
