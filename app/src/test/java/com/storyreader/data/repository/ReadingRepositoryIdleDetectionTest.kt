package com.storyreader.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for idle detection via [ReadingRepositoryImpl.finalizeSession].
 *
 * These tests verify the full flow: start session → finalize → read persisted entity.
 * For direct algorithm tests (no DB), see [IdleDetectionAlgorithmTest].
 */
@RunWith(RobolectricTestRunner::class)
class ReadingRepositoryIdleDetectionTest {

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
        runTest {
            db.bookDao().insert(BookEntity(bookId = "book1", title = "Test", author = "Author"))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `rapid page turns below minimum interval are excluded`() = runTest {
        // All 10 page turns 1 s apart (< 5 s threshold), session ends immediately
        // after the last turn so the trailing interval is also < 5 s.
        val startMs = System.currentTimeMillis() - 11_500L
        val timestamps = (1..10).map { startMs + it * 1_000L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertEquals(0, session!!.durationSeconds)
    }

    @Test
    fun `long idle pause is replaced with median leaving realistic reading time`() = runTest {
        // Layout: 5 pages at 10s each → 1-hour idle → 5 more pages at 10s each.
        // 11 intervals of 10s + one of 3600s.
        // Median = 10s, threshold = 30s → 3600s replaced with 10s.
        // Total: 12 × 10s = 120s.
        val startMs = System.currentTimeMillis() - 3_800_000L
        val normalPages = (1..5).map { startMs + it * 10_000L }
        val idlePage = normalPages.last() + 3_600_000L
        val morePages = (1..5).map { idlePage + it * 10_000L }
        val timestamps = normalPages + listOf(idlePage) + morePages

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        // With median replacement: ~12 intervals × 10s = ~120s
        // The trailing interval (last turn → now) varies, but the idle is gone.
        assertTrue(
            "Expected ~120-200s (idle replaced), got ${session!!.durationSeconds}",
            session.durationSeconds in 100..250
        )
    }

    @Test
    fun `tts session uses raw elapsed time not adjusted duration`() = runTest {
        // All page turns are rapid (< 5 s) — calcAdjustedDuration would give 0.
        // TTS sessions must use rawDuration instead.
        val startMs = System.currentTimeMillis() - 60_000L
        val timestamps = (1..5).map { startMs + it * 1_000L }

        val sessionId = repository.startSession("book1", isTts = true)
        repository.finalizeSession(sessionId, timestamps, startMs, isTts = true)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertTrue(
            "TTS duration should reflect raw elapsed time (~60 s), got ${session!!.durationSeconds}",
            session.durationSeconds in 55..70
        )
    }

    @Test
    fun `session with no page turns counts start to end as one interval`() = runTest {
        val startMs = System.currentTimeMillis() - 300_000L

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, emptyList(), startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertTrue(
            "Expected ~300 s for a 5-minute no-page-turn session, got ${session!!.durationSeconds}",
            session.durationSeconds in 280..325
        )
    }

    @Test
    fun `words read calculated from book progression when word count is provided`() = runTest {
        val startMs = System.currentTimeMillis() - 600_000L
        val timestamps = (1..5).map { startMs + it * 60_000L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(
            sessionId = sessionId,
            pageTurnTimestampsMs = timestamps,
            sessionStartMs = startMs,
            progressionStart = 0.2f,
            progressionEnd = 0.5f,
            bookWordCount = 100_000
        )

        // (0.5 - 0.2) × 100 000 = 30 000 words
        val session = db.readingSessionDao().getById(sessionId)
        assertEquals(30_000, session?.wordsRead)
    }

    @Test
    fun `words read falls back to 200 wpm estimate when no book word count`() = runTest {
        val startMs = System.currentTimeMillis() - 600_000L
        val timestamps = (1..10).map { startMs + it * 30_000L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(
            sessionId = sessionId,
            pageTurnTimestampsMs = timestamps,
            sessionStartMs = startMs,
            bookWordCount = 0
        )

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertTrue(
            "Expected WPM-based word count > 0, got ${session?.wordsRead}",
            (session?.wordsRead ?: 0) > 0
        )
    }

    @Test
    fun `words read is zero when progression does not advance`() = runTest {
        val startMs = System.currentTimeMillis() - 300_000L

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(
            sessionId = sessionId,
            pageTurnTimestampsMs = emptyList(),
            sessionStartMs = startMs,
            progressionStart = 0.5f,
            progressionEnd = 0.3f, // end < start
            bookWordCount = 100_000
        )

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertTrue("Words read must not be negative", (session?.wordsRead ?: -1) >= 0)
    }

    @Test
    fun `rawDurationSeconds reflects wall-clock time regardless of idle detection`() = runTest {
        // 5 rapid turns (500 ms apart) with a long trailing interval.
        // rawDuration = full wall-clock; adjusted counts only the trailing interval.
        val startMs = System.currentTimeMillis() - 120_000L
        val timestamps = (1..5).map { startMs + it * 500L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertTrue(
            "rawDurationSeconds should be ~120 s, got ${session!!.rawDurationSeconds}",
            session.rawDurationSeconds in 115..130
        )
        // Trailing interval (~117 s) is the only valid interval
        assertTrue(
            "durationSeconds should capture trailing interval (~117 s), got ${session.durationSeconds}",
            session.durationSeconds in 110..125
        )
    }

    @Test
    fun `pages turned count matches the timestamp list size`() = runTest {
        val startMs = System.currentTimeMillis() - 600_000L
        val timestamps = (1..7).map { startMs + it * 60_000L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertEquals(7, session?.pagesTurned)
    }

    @Test
    fun `trivial session with no page turns and short duration is discarded`() = runTest {
        val startMs = System.currentTimeMillis() - 10_000L // 10 seconds

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, emptyList(), startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNull("Trivial session should be deleted", session)
    }
}
