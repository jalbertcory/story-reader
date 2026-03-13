package com.storyreader.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ReadingRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = ReadingRepositoryImpl(
            positionDao = db.readingPositionDao(),
            sessionDao = db.readingSessionDao()
        )
        // Seed a book so FK constraints pass
        runTest {
            db.bookDao().insert(BookEntity(bookId = "book1", title = "Test Book", author = "Author"))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `savePosition persists locator json`() = runTest {
        repository.savePosition("book1", """{"href":"chapter1.html","progression":0.5}""")

        val position = repository.observeLatestPosition("book1").first()
        assertNotNull(position)
        assertEquals("""{"href":"chapter1.html","progression":0.5}""", position?.locatorJson)
    }

    @Test
    fun `observeLatestPosition returns most recent when multiple saved`() = runTest {
        repository.savePosition("book1", """{"page":1}""")
        repository.savePosition("book1", """{"page":10}""")

        val latest = repository.observeLatestPosition("book1").first()
        assertEquals("""{"page":10}""", latest?.locatorJson)
    }

    @Test
    fun `observeLatestPosition returns null for book with no positions`() = runTest {
        val position = repository.observeLatestPosition("book1").first()
        assertNull(position)
    }

    @Test
    fun `startSession returns valid session id`() = runTest {
        val id = repository.startSession("book1")
        assert(id > 0L) { "Expected positive session id, got $id" }
    }

    @Test
    fun `finalizeSession updates duration and pagesTurned via timestamps`() = runTest {
        val startMs = System.currentTimeMillis() - 420_000L
        val sessionId = repository.startSession("book1")
        // 15 evenly-spaced page turns over 420 seconds
        val stepMs = 420_000L / 15
        val timestamps = (1..15).map { startMs + it * stepMs }
        repository.finalizeSession(sessionId, pageTurnTimestampsMs = timestamps, sessionStartMs = startMs)

        val session = db.readingSessionDao().getById(sessionId)
        // Adjusted duration should be close to 420s (no outliers since pages are evenly spaced)
        assertNotNull(session)
        assert((session!!.durationSeconds) in 400..430) {
            "Expected ~420s, got ${session.durationSeconds}"
        }
        assertEquals(15, session.pagesTurned)
    }

    @Test
    fun `finalizeSession on missing id does nothing`() = runTest {
        // Should not throw
        repository.finalizeSession(sessionId = 9999L, pageTurnTimestampsMs = emptyList(), sessionStartMs = System.currentTimeMillis())
    }

    @Test
    fun `observeSessionsForBook returns only sessions for that book`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "book2", title = "Other", author = "Author"))
        repository.startSession("book1")
        repository.startSession("book1")
        repository.startSession("book2")

        val sessions = repository.observeSessionsForBook("book1").first()
        assertEquals(2, sessions.size)
    }
}
