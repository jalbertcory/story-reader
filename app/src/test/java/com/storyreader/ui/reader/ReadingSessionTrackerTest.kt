package com.storyreader.ui.reader

import android.net.Uri
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.sync.BookImportMetadata
import com.storyreader.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadingSessionTrackerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val testClock = TestClock()

    private lateinit var fakeReadingRepo: FakeReadingRepository
    private lateinit var fakeBookRepo: FakeBookRepository
    private lateinit var tracker: ReadingSessionTracker

    @Before
    fun setUp() {
        fakeReadingRepo = FakeReadingRepository()
        fakeBookRepo = FakeBookRepository()
        tracker = ReadingSessionTracker(
            fakeReadingRepo, fakeBookRepo, testScope, testClock
        )
    }

    // ── Basic session lifecycle ───────────────────────────────────────────────

    @Test
    fun `startSession calls repository startSession`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        assertEquals(1, fakeReadingRepo.startedSessions.size)
        assertEquals("book1", fakeReadingRepo.startedSessions[0].first)
        assertFalse(fakeReadingRepo.startedSessions[0].second) // isTts = false
    }

    @Test
    fun `startSession with tts flag passes isTts true`() = testScope.runTest {
        tracker.startSession("book1", isTts = true)
        advanceUntilIdle()

        assertTrue(fakeReadingRepo.startedSessions[0].second)
    }

    @Test
    fun `startSession stores progression`() = testScope.runTest {
        tracker.startSession("book1", currentProgression = 0.42f)
        advanceUntilIdle()

        assertEquals(0.42f, tracker.sessionStartProgression, 0.001f)
    }

    @Test
    fun `recordPageTurn adds timestamps`() {
        tracker.recordPageTurn()
        tracker.recordPageTurn()
        tracker.recordPageTurn()
        // No crash, timestamps accumulate internally
    }

    @Test
    fun `finalize does nothing when no session started`() = testScope.runTest {
        tracker.finalize("book1", null, null, false)
        advanceUntilIdle()

        assertEquals(0, fakeReadingRepo.savedPositions.size)
        assertEquals(0, fakeReadingRepo.finalizedSessions.size)
    }

    @Test
    fun `finalize saves position and updates progression`() = testScope.runTest {
        tracker.startSession("book1", currentProgression = 0.1f)
        advanceUntilIdle()

        val locator = makeLocator(totalProgression = 0.5)
        tracker.finalize("book1", locator, "Chapter 1", false)
        advanceUntilIdle()

        assertEquals(1, fakeReadingRepo.savedPositions.size)
        assertEquals("book1", fakeReadingRepo.savedPositions[0].first)
        assertEquals(1, fakeBookRepo.progressionUpdates.size)
        assertEquals("book1", fakeBookRepo.progressionUpdates[0].first)
        assertEquals(0.5f, fakeBookRepo.progressionUpdates[0].second, 0.001f)
    }

    @Test
    fun `finalize calls finalizeSession on repository`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        tracker.recordPageTurn()
        val locator = makeLocator(totalProgression = 0.3)
        tracker.finalize("book1", locator, null, false)
        advanceUntilIdle()

        assertEquals(1, fakeReadingRepo.finalizedSessions.size)
    }

    @Test
    fun `finalize updates chapter position for web books`() = testScope.runTest {
        tracker.startSession("webbook1")
        advanceUntilIdle()

        val locator = makeLocator(totalProgression = 0.5, chapterProgression = 0.7)
        tracker.finalize("webbook1", locator, "Chapter 5", isWebBook = true)
        advanceUntilIdle()

        assertEquals(1, fakeBookRepo.chapterUpdates.size)
        assertEquals("webbook1", fakeBookRepo.chapterUpdates[0].bookId)
        assertEquals("Chapter 5", fakeBookRepo.chapterUpdates[0].title)
        assertEquals(0.7f, fakeBookRepo.chapterUpdates[0].progression!!, 0.001f)
    }

    @Test
    fun `finalize does not update chapter position for non-web books`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        val locator = makeLocator(totalProgression = 0.5)
        tracker.finalize("book1", locator, "Chapter 1", isWebBook = false)
        advanceUntilIdle()

        assertEquals(0, fakeBookRepo.chapterUpdates.size)
    }

    @Test
    fun `finalize with null locator skips position save`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        tracker.finalize("book1", null, null, false)
        advanceUntilIdle()

        assertEquals(0, fakeReadingRepo.savedPositions.size)
        assertEquals(0, fakeBookRepo.progressionUpdates.size)
        assertEquals(1, fakeReadingRepo.finalizedSessions.size)
    }

    @Test
    fun `finalize invokes onPositionSave callback`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        var savedLocator: Locator? = null
        val locator = makeLocator(totalProgression = 0.6)
        tracker.finalize("book1", locator, null, false) { savedLocator = it }
        advanceUntilIdle()

        assertEquals(locator, savedLocator)
    }

    @Test
    fun `second finalize after first is a no-op`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        val locator = makeLocator(totalProgression = 0.5)
        tracker.finalize("book1", locator, null, false)
        advanceUntilIdle()

        tracker.finalize("book1", locator, null, false)
        advanceUntilIdle()

        assertEquals(1, fakeReadingRepo.finalizedSessions.size)
    }

    @Test
    fun `startSessionSync returns suspend block that starts session`() = testScope.runTest {
        val block = tracker.startSessionSync("book1", isTts = true, currentProgression = 0.3f)
        assertEquals(0, fakeReadingRepo.startedSessions.size)

        block()

        assertEquals(1, fakeReadingRepo.startedSessions.size)
        assertEquals("book1", fakeReadingRepo.startedSessions[0].first)
        assertTrue(fakeReadingRepo.startedSessions[0].second)
        assertEquals(0.3f, tracker.sessionStartProgression, 0.001f)
    }

    // ── Pause / Resume ───────────────────────────────────────────────────────

    @Test
    fun `onPause before session start is a no-op`() {
        tracker.onPause()
        // No crash, no state change
        assertFalse(tracker.onResume())
    }

    @Test
    fun `onPause is idempotent - second call does not change pause start`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 1000L
        tracker.onPause()
        testClock.timeMs = 5000L
        tracker.onPause() // should be ignored

        testClock.timeMs = 6000L
        assertFalse(tracker.onResume()) // 6000 - 1000 = 5000ms, under split threshold
    }

    @Test
    fun `onResume without onPause returns false`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        assertFalse(tracker.onResume())
    }

    @Test
    fun `short pause is recorded and onResume returns false`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 10_000L
        tracker.onPause()
        testClock.timeMs = 70_000L // 60s pause
        assertFalse(tracker.onResume())
    }

    @Test
    fun `long pause triggers session split - onResume returns true`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 10_000L
        tracker.onPause()
        testClock.timeMs = 10_000L + ReadingSessionTracker.SESSION_SPLIT_THRESHOLD_MS
        assertTrue(tracker.onResume()) // exactly at threshold
    }

    @Test
    fun `finalize passes pause intervals to repository`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 10_000L
        tracker.onPause()
        testClock.timeMs = 70_000L
        tracker.onResume()

        testClock.timeMs = 100_000L
        tracker.onPause()
        testClock.timeMs = 130_000L
        tracker.onResume()

        tracker.finalize("book1", null, null, false)
        advanceUntilIdle()

        assertEquals(1, fakeReadingRepo.finalizedSessions.size)
        val finalized = fakeReadingRepo.finalizedSessions[0]
        assertEquals(2, finalized.pauseIntervalsMs.size)
        assertEquals(10_000L to 70_000L, finalized.pauseIntervalsMs[0])
        assertEquals(100_000L to 130_000L, finalized.pauseIntervalsMs[1])
    }

    @Test
    fun `finalize closes active pause`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 10_000L
        tracker.onPause()
        // Don't resume — finalize should close the pause
        testClock.timeMs = 50_000L
        tracker.finalize("book1", null, null, false)
        advanceUntilIdle()

        val finalized = fakeReadingRepo.finalizedSessions[0]
        assertEquals(1, finalized.pauseIntervalsMs.size)
        assertEquals(10_000L to 50_000L, finalized.pauseIntervalsMs[0])
    }

    @Test
    fun `startSession clears pause state from previous session`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 10_000L
        tracker.onPause()
        testClock.timeMs = 50_000L
        tracker.onResume()

        // Start a new session — should clear all pause state
        tracker.startSession("book2")
        advanceUntilIdle()

        tracker.finalize("book2", null, null, false)
        advanceUntilIdle()

        // The finalized session for book2 should have no pause intervals
        val finalized = fakeReadingRepo.finalizedSessions[0]
        assertEquals(0, finalized.pauseIntervalsMs.size)
    }

    @Test
    fun `recordPageTurn uses injected clock`() = testScope.runTest {
        tracker.startSession("book1")
        advanceUntilIdle()

        testClock.timeMs = 5000L
        tracker.recordPageTurn()
        testClock.timeMs = 15000L
        tracker.recordPageTurn()

        tracker.finalize("book1", null, null, false)
        advanceUntilIdle()

        val finalized = fakeReadingRepo.finalizedSessions[0]
        assertEquals(listOf(5000L, 15000L), finalized.pageTurnTimestampsMs)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeLocator(
        totalProgression: Double = 0.0,
        chapterProgression: Double? = null
    ): Locator = Locator(
        href = org.readium.r2.shared.util.Url("chapter1.xhtml")!!,
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(
            totalProgression = totalProgression,
            progression = chapterProgression ?: 0.0
        )
    )

    // ── Test clock ──────────────────────────────────────────────────────────

    private class TestClock : Clock {
        var timeMs: Long = 0L
        override fun currentTimeMillis(): Long = timeMs
    }

    // ── Fake repositories ────────────────────────────────────────────────────

    data class FinalizedSession(
        val sessionId: Long,
        val pageTurnTimestampsMs: List<Long>,
        val sessionStartMs: Long,
        val isTts: Boolean,
        val pauseIntervalsMs: List<Pair<Long, Long>>,
        val progressionStart: Float,
        val progressionEnd: Float,
        val bookWordCount: Int
    )

    private class FakeReadingRepository : ReadingRepository {
        var nextSessionId = 1L
        val startedSessions = mutableListOf<Pair<String, Boolean>>() // bookId, isTts
        val savedPositions = mutableListOf<Pair<String, String>>() // bookId, locatorJson
        val finalizedSessions = mutableListOf<FinalizedSession>()

        override fun observeLatestPosition(bookId: String): Flow<ReadingPositionEntity?> = emptyFlow()

        override suspend fun savePosition(bookId: String, locatorJson: String) {
            savedPositions.add(bookId to locatorJson)
        }

        override suspend fun startSession(bookId: String, isTts: Boolean): Long {
            startedSessions.add(bookId to isTts)
            return nextSessionId++
        }

        override suspend fun finalizeSession(
            sessionId: Long,
            pageTurnTimestampsMs: List<Long>,
            sessionStartMs: Long,
            isTts: Boolean,
            pauseIntervalsMs: List<Pair<Long, Long>>,
            progressionStart: Float,
            progressionEnd: Float,
            bookWordCount: Int
        ) {
            finalizedSessions.add(
                FinalizedSession(
                    sessionId, pageTurnTimestampsMs, sessionStartMs,
                    isTts, pauseIntervalsMs, progressionStart, progressionEnd, bookWordCount
                )
            )
        }

        override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> = emptyFlow()
    }

    private class FakeBookRepository : BookRepository {
        val progressionUpdates = mutableListOf<Pair<String, Float>>()
        val chapterUpdates = mutableListOf<ChapterUpdate>()

        data class ChapterUpdate(val bookId: String, val title: String?, val progression: Float?)

        override fun observeAll(): Flow<List<BookEntity>> = flowOf(emptyList())
        override fun observeAllIncludingHidden(): Flow<List<BookEntity>> = flowOf(emptyList())
        override fun observeById(bookId: String): Flow<BookEntity?> = flowOf(null)
        override suspend fun insert(book: BookEntity) {}
        override suspend fun delete(book: BookEntity) {}
        override suspend fun hideBook(bookId: String) {}
        override suspend fun unhideBook(bookId: String) {}
        override suspend fun updateProgression(bookId: String, progression: Float) {
            progressionUpdates.add(bookId to progression)
        }
        override suspend fun importFromUri(uri: Uri, importMetadata: BookImportMetadata?): Result<BookEntity> =
            Result.failure(UnsupportedOperationException())
        override suspend fun getWordCount(bookId: String): Int = 10000
        override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) {
            chapterUpdates.add(ChapterUpdate(bookId, title, progression))
        }
    }
}
