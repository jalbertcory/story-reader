package com.storyreader.ui.reader

import android.net.Uri
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import com.storyreader.data.db.entity.ReadingPositionEntity
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

    private lateinit var fakeReadingRepo: FakeReadingRepository
    private lateinit var fakeBookRepo: FakeBookRepository
    private lateinit var tracker: ReadingSessionTracker

    @Before
    fun setUp() {
        fakeReadingRepo = FakeReadingRepository()
        fakeBookRepo = FakeBookRepository()
        tracker = ReadingSessionTracker(fakeReadingRepo, fakeBookRepo, testScope)
    }

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
        // finalize without startSession should be a no-op
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
        // finalizeSession is still called
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

        // Second finalize — currentSessionId is already null
        tracker.finalize("book1", locator, null, false)
        advanceUntilIdle()

        assertEquals(1, fakeReadingRepo.finalizedSessions.size)
    }

    @Test
    fun `startSessionSync returns suspend block that starts session`() = testScope.runTest {
        val block = tracker.startSessionSync("book1", isTts = true, currentProgression = 0.3f)
        // Session not started yet
        assertEquals(0, fakeReadingRepo.startedSessions.size)

        // Execute the block
        block()

        assertEquals(1, fakeReadingRepo.startedSessions.size)
        assertEquals("book1", fakeReadingRepo.startedSessions[0].first)
        assertTrue(fakeReadingRepo.startedSessions[0].second)
        assertEquals(0.3f, tracker.sessionStartProgression, 0.001f)
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

    // ── Fake repositories ────────────────────────────────────────────────────

    private class FakeReadingRepository : ReadingRepository {
        var nextSessionId = 1L
        val startedSessions = mutableListOf<Pair<String, Boolean>>() // bookId, isTts
        val savedPositions = mutableListOf<Pair<String, String>>() // bookId, locatorJson
        val finalizedSessions = mutableListOf<Long>()

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
            progressionStart: Float,
            progressionEnd: Float,
            bookWordCount: Int
        ) {
            finalizedSessions.add(sessionId)
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
        override suspend fun importFromUri(uri: Uri): Result<BookEntity> =
            Result.failure(UnsupportedOperationException())
        override suspend fun getWordCount(bookId: String): Int = 10000
        override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) {
            chapterUpdates.add(ChapterUpdate(bookId, title, progression))
        }
    }
}
