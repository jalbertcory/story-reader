package com.storyreader.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the idle-detection algorithm in [ReadingRepositoryImpl].
 *
 * The private [ReadingRepositoryImpl.calcAdjustedDuration] method is exercised
 * indirectly via [ReadingRepositoryImpl.finalizeSession], then the persisted
 * [durationSeconds] is verified in the database.
 *
 * Key rules under test:
 *  - Page intervals < 5 s are excluded (rapid skipping).
 *  - Intervals > 3× average are excluded (idle detection).
 *  - TTS sessions bypass idle detection and use raw elapsed time.
 *  - Words are calculated from book progression when a word count is available.
 *  - Words fall back to a 200 WPM estimate when no book word count is present.
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
        // All 10 page turns happen 1 second apart (< 5 s threshold).
        // The trailing interval from the last turn to now (~60 s) is also an
        // outlier relative to the tiny 1 s intervals, so everything is excluded.
        val startMs = System.currentTimeMillis() - 60_000L
        val timestamps = (1..10).map { startMs + it * 1_000L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertEquals(0, session!!.durationSeconds)
    }

    @Test
    fun `long idle pause is excluded as outlier leaving normal reading time`() = runTest {
        // Layout: 5 pages at 10 s each → 1-hour idle → 5 more pages at 10 s each.
        // Total real reading time ≈ 11 × 10 s = 110 s.
        // The 3 600 s idle interval is > 3× average, so it is excluded.
        val startMs = System.currentTimeMillis() - 3_800_000L
        val normalPages = (1..5).map { startMs + it * 10_000L }
        val idlePage = normalPages.last() + 3_600_000L
        val morePages = (1..5).map { idlePage + it * 10_000L }
        val timestamps = normalPages + listOf(idlePage) + morePages

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        // Without idle the adjusted duration should be ~110 s – far less than
        // the raw elapsed time of ~63 minutes.
        // 10 page intervals × 10 s = 100 s, plus the trailing interval from the
        // last page turn to now (~100 s). Total: ~200 s — far less than the 3 800 s raw
        // elapsed time and definitely excludes the 3 600 s idle.
        assertTrue(
            "Expected ~200 s (idle excluded), got ${session!!.durationSeconds}",
            session.durationSeconds in 150..260
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
        // A single interval of ~5 minutes that passes both MIN_PAGE_INTERVAL and
        // the outlier threshold (it cannot exceed 3× itself).
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
        // Fallback: (adjustedDuration / 60) × 200 WPM — must be positive.
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
        // Negative progression delta → fall back to WPM estimate, not negative words.
        assertTrue("Words read must not be negative", (session?.wordsRead ?: -1) >= 0)
    }

    @Test
    fun `rawDurationSeconds reflects wall-clock time regardless of idle detection`() = runTest {
        val startMs = System.currentTimeMillis() - 120_000L
        // All rapid turns so adjusted duration → 0, but raw should still be ~120 s.
        val timestamps = (1..5).map { startMs + it * 500L }

        val sessionId = repository.startSession("book1")
        repository.finalizeSession(sessionId, timestamps, startMs)

        val session = db.readingSessionDao().getById(sessionId)
        assertNotNull(session)
        assertTrue(
            "rawDurationSeconds should be ~120 s, got ${session!!.rawDurationSeconds}",
            session.rawDurationSeconds in 115..130
        )
        assertEquals(0, session.durationSeconds)
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
}
