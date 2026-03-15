package com.storyreader.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.first
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
import java.util.Calendar

/**
 * Tests the complex aggregation and time-range queries in [ReadingSessionDao].
 *
 * Covers:
 *  - getBookSessionStats: per-book totals with TTS vs manual breakdown
 *  - getTotalReadingSeconds / getTotalWordsRead: global sums with ≥10 s filter
 *  - getMonthlyStats: SQLite strftime grouping by month
 *  - getReadingYears: distinct year extraction
 *  - getReadingSecondsBetween / getWordsReadBetween: bounded range queries
 *  - findByBookIdAndStartTime: exact-match lookup
 *  - getLastReadTimes: per-book max start time
 */
@RunWith(RobolectricTestRunner::class)
class ReadingSessionDaoStatsTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertBook(bookId: String) {
        db.bookDao().insert(BookEntity(bookId = bookId, title = "Book $bookId", author = "Author"))
    }

    private suspend fun insertSession(
        bookId: String,
        durationSeconds: Int = 60,
        wordsRead: Int = 200,
        isTts: Boolean = false,
        startTime: Long = System.currentTimeMillis()
    ): Long = db.readingSessionDao().insert(
        ReadingSessionEntity(
            bookId = bookId,
            startTime = startTime,
            durationSeconds = durationSeconds,
            rawDurationSeconds = durationSeconds,
            pagesTurned = 5,
            wordsRead = wordsRead,
            isTts = isTts
        )
    )

    // ── getBookSessionStats ──────────────────────────────────────────────────

    @Test
    fun `getBookSessionStats is empty when there are no sessions`() = runTest {
        assertEquals(0, db.readingSessionDao().getBookSessionStats().first().size)
    }

    @Test
    fun `getBookSessionStats excludes sessions shorter than 10 seconds`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 5)   // below threshold
        insertSession("b1", durationSeconds = 10)  // exactly 10 s — included

        val stats = db.readingSessionDao().getBookSessionStats().first()
        assertEquals(1, stats.size)
        assertEquals(1, stats[0].sessionCount)
        assertEquals(10, stats[0].totalDurationSeconds)
    }

    @Test
    fun `getBookSessionStats separates manual and tts durations and words`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 100, wordsRead = 300, isTts = false)
        insertSession("b1", durationSeconds = 200, wordsRead = 600, isTts = true)

        val stats = db.readingSessionDao().getBookSessionStats().first()
        assertEquals(1, stats.size)
        with(stats[0]) {
            assertEquals(300, totalDurationSeconds)
            assertEquals(900, totalWordsRead)
            assertEquals(100, manualDurationSeconds)
            assertEquals(300, manualWordsRead)
            assertEquals(200, ttsDurationSeconds)
            assertEquals(600, ttsWordsRead)
        }
    }

    @Test
    fun `getBookSessionStats tracks first and last session start times`() = runTest {
        insertBook("b1")
        insertSession("b1", startTime = 3000L)
        insertSession("b1", startTime = 1000L)
        insertSession("b1", startTime = 5000L)

        val stats = db.readingSessionDao().getBookSessionStats().first()
        assertEquals(1000L, stats[0].firstSessionStart)
        assertEquals(5000L, stats[0].lastSessionStart)
    }

    @Test
    fun `getBookSessionStats groups correctly by book`() = runTest {
        insertBook("b1")
        insertBook("b2")
        insertSession("b1", durationSeconds = 100)
        insertSession("b1", durationSeconds = 150)
        insertSession("b2", durationSeconds = 200)

        val stats = db.readingSessionDao().getBookSessionStats().first()
        assertEquals(2, stats.size)

        val b1 = stats.first { it.bookId == "b1" }
        val b2 = stats.first { it.bookId == "b2" }
        assertEquals(250, b1.totalDurationSeconds)
        assertEquals(2, b1.sessionCount)
        assertEquals(200, b2.totalDurationSeconds)
        assertEquals(1, b2.sessionCount)
    }

    // ── getTotalReadingSeconds / getTotalWordsRead ───────────────────────────

    @Test
    fun `getTotalReadingSeconds sums only sessions at least 10 seconds long`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 5)    // excluded
        insertSession("b1", durationSeconds = 100)  // included
        insertSession("b1", durationSeconds = 200)  // included

        val total = db.readingSessionDao().getTotalReadingSeconds().first()
        assertEquals(300L, total)
    }

    @Test
    fun `getTotalReadingSeconds returns null when no qualifying sessions exist`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 9)

        assertNull(db.readingSessionDao().getTotalReadingSeconds().first())
    }

    @Test
    fun `getTotalWordsRead sums only sessions at least 10 seconds long`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 5, wordsRead = 999)   // excluded
        insertSession("b1", durationSeconds = 60, wordsRead = 300)  // included
        insertSession("b1", durationSeconds = 120, wordsRead = 500) // included

        val total = db.readingSessionDao().getTotalWordsRead().first()
        assertEquals(800L, total)
    }

    // ── getMonthlyStats ──────────────────────────────────────────────────────

    @Test
    fun `getMonthlyStats groups sessions by month`() = runTest {
        insertBook("b1")
        val jan = utcMs(2024, Calendar.JANUARY, 15)
        val feb = utcMs(2024, Calendar.FEBRUARY, 15)
        insertSession("b1", durationSeconds = 100, wordsRead = 300, startTime = jan)
        insertSession("b1", durationSeconds = 150, wordsRead = 450, startTime = jan + 1_000)
        insertSession("b1", durationSeconds = 200, wordsRead = 600, startTime = feb)

        val monthly = db.readingSessionDao().getMonthlyStats(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )

        assertEquals(2, monthly.size)
        val janStat = monthly.first { it.month == 1 }
        val febStat = monthly.first { it.month == 2 }
        assertEquals(250L, janStat.totalSeconds)
        assertEquals(750L, janStat.totalWords)
        assertEquals(200L, febStat.totalSeconds)
    }

    @Test
    fun `getMonthlyStats excludes sessions outside the date range`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2023, Calendar.DECEMBER, 15))
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2024, Calendar.JUNE, 15))
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2025, Calendar.JUNE, 15))

        val monthly = db.readingSessionDao().getMonthlyStats(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )

        assertEquals(1, monthly.size)
        assertEquals(6, monthly[0].month)
    }

    @Test
    fun `getMonthlyStats excludes short sessions`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 5, startTime = utcMs(2024, Calendar.MARCH, 10))
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2024, Calendar.MARCH, 11))

        val monthly = db.readingSessionDao().getMonthlyStats(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )

        assertEquals(1, monthly.size)
        assertEquals(60L, monthly[0].totalSeconds)
    }

    @Test
    fun `getMonthlyStats returns empty list when no data in range`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2022, Calendar.JUNE, 1))

        val monthly = db.readingSessionDao().getMonthlyStats(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )

        assertTrue(monthly.isEmpty())
    }

    // ── getReadingYears ──────────────────────────────────────────────────────

    @Test
    fun `getReadingYears returns distinct years in descending order`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2022, Calendar.JUNE, 1))
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2024, Calendar.MARCH, 1))
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2024, Calendar.JULY, 1)) // duplicate year

        val years = db.readingSessionDao().getReadingYears()
        assertEquals(listOf(2024, 2022), years)
    }

    @Test
    fun `getReadingYears excludes years with only short sessions`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 5, startTime = utcMs(2020, Calendar.JANUARY, 15))
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2023, Calendar.JANUARY, 15))

        val years = db.readingSessionDao().getReadingYears()
        assertEquals(listOf(2023), years)
    }

    @Test
    fun `getReadingYears returns empty list when no qualifying sessions`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 9)

        assertTrue(db.readingSessionDao().getReadingYears().isEmpty())
    }

    // ── getReadingSecondsBetween / getWordsReadBetween ───────────────────────

    @Test
    fun `getReadingSecondsBetween returns sum of qualifying sessions within range`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 100, startTime = utcMs(2024, Calendar.JANUARY, 15))
        insertSession("b1", durationSeconds = 200, startTime = utcMs(2024, Calendar.JUNE, 15))
        insertSession("b1", durationSeconds = 999, startTime = utcMs(2025, Calendar.JUNE, 15)) // outside

        val secs = db.readingSessionDao().getReadingSecondsBetween(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )
        assertEquals(300L, secs)
    }

    @Test
    fun `getReadingSecondsBetween returns null when no qualifying sessions in range`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, startTime = utcMs(2020, Calendar.JUNE, 1))

        val secs = db.readingSessionDao().getReadingSecondsBetween(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )
        assertNull(secs)
    }

    @Test
    fun `getWordsReadBetween returns sum within range excluding short sessions`() = runTest {
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, wordsRead = 400, startTime = utcMs(2024, Calendar.MARCH, 10))
        insertSession("b1", durationSeconds = 5, wordsRead = 999, startTime = utcMs(2024, Calendar.APRIL, 10)) // excluded
        insertSession("b1", durationSeconds = 60, wordsRead = 600, startTime = utcMs(2025, Calendar.MARCH, 10)) // outside

        val words = db.readingSessionDao().getWordsReadBetween(
            utcMs(2024, Calendar.JANUARY, 1),
            utcMs(2025, Calendar.JANUARY, 1)
        )
        assertEquals(400L, words)
    }

    // ── findByBookIdAndStartTime ─────────────────────────────────────────────

    @Test
    fun `findByBookIdAndStartTime returns session when bookId and startTime match`() = runTest {
        insertBook("b1")
        db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = "b1", startTime = 12345L, durationSeconds = 60)
        )

        val found = db.readingSessionDao().findByBookIdAndStartTime("b1", 12345L)
        assertNotNull(found)
        assertEquals(12345L, found?.startTime)
    }

    @Test
    fun `findByBookIdAndStartTime returns null when startTime does not match`() = runTest {
        insertBook("b1")
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "b1", startTime = 12345L))

        assertNull(db.readingSessionDao().findByBookIdAndStartTime("b1", 99999L))
    }

    @Test
    fun `findByBookIdAndStartTime returns null when bookId does not match`() = runTest {
        insertBook("b1")
        insertBook("b2")
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "b1", startTime = 12345L))

        assertNull(db.readingSessionDao().findByBookIdAndStartTime("b2", 12345L))
    }

    // ── getLastReadTimes ─────────────────────────────────────────────────────

    @Test
    fun `getLastReadTimes returns max startTime per book`() = runTest {
        insertBook("b1")
        insertBook("b2")
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "b1", startTime = 1000L))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "b1", startTime = 5000L))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "b2", startTime = 3000L))

        val lastReadTimes = db.readingSessionDao().getLastReadTimes().first()
        val b1 = lastReadTimes.first { it.bookId == "b1" }
        val b2 = lastReadTimes.first { it.bookId == "b2" }
        assertEquals(5000L, b1.lastReadAt)
        assertEquals(3000L, b2.lastReadAt)
    }

    @Test
    fun `getLastReadTimes returns empty when no sessions exist`() = runTest {
        assertTrue(db.readingSessionDao().getLastReadTimes().first().isEmpty())
    }

    // ── getMostRecentlyReadVisibleBookId ─────────────────────────────────────

    @Test
    fun `getMostRecentlyReadVisibleBookId returns null when there are no sessions`() = runTest {
        assertNull(db.readingSessionDao().getMostRecentlyReadVisibleBookId())
    }

    @Test
    fun `getMostRecentlyReadVisibleBookId returns book with highest startTime`() = runTest {
        insertBook("older")
        insertBook("newer")
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "older", startTime = 1000L))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "newer", startTime = 2000L))

        assertEquals("newer", db.readingSessionDao().getMostRecentlyReadVisibleBookId())
    }

    @Test
    fun `getMostRecentlyReadVisibleBookId skips hidden books`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "hidden", title = "H", author = "A", hidden = true))
        insertBook("visible")
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "hidden", startTime = 9000L))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "visible", startTime = 1000L))

        assertEquals("visible", db.readingSessionDao().getMostRecentlyReadVisibleBookId())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns a UTC noon timestamp for the given date to avoid timezone-boundary issues. */
    private fun utcMs(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(year, month, day, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
