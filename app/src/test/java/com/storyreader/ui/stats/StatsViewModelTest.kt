package com.storyreader.ui.stats

import androidx.test.core.app.ApplicationProvider
import com.storyreader.MainDispatcherRule
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var app: StoryReaderApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<StoryReaderApplication>()
        db = app.database
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createViewModel(): StatsViewModel = StatsViewModel(app)

    private suspend fun insertBook(bookId: String, title: String = "Book $bookId") {
        db.bookDao().insert(BookEntity(bookId = bookId, title = title, author = "Author"))
    }

    private suspend fun insertSession(
        bookId: String,
        durationSeconds: Int = 60,
        wordsRead: Int = 200,
        isTts: Boolean = false,
        startTime: Long = System.currentTimeMillis()
    ) {
        db.readingSessionDao().insert(
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
    }

    /** Wait for Room's invalidation tracker to fire and ViewModel to process. */
    private fun waitForEmission() {
        Thread.sleep(100)
    }

    @Test
    fun `initial state is accessible`() {
        val vm = createViewModel()
        assertNotNull(vm.uiState.value)
    }

    @Test
    fun `uiState shows empty book stats when no data`() = runTest {
        val vm = createViewModel()
        waitForEmission()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.bookStats.isEmpty())
    }

    @Test
    fun `uiState contains book stats after inserting sessions`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Test Book")
        insertSession("b1", durationSeconds = 120, wordsRead = 500)
        insertSession("b1", durationSeconds = 180, wordsRead = 700)
        waitForEmission()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.bookStats.size)
        assertEquals("Test Book", state.bookStats[0].book.title)
        assertEquals(300, state.bookStats[0].totalReadingSeconds)
        assertEquals(1200, state.bookStats[0].totalWordsRead)
    }

    @Test
    fun `globalStats tracks all-time totals`() = runTest {
        val vm = createViewModel()
        insertBook("b1")
        insertSession("b1", durationSeconds = 100, wordsRead = 400)
        insertSession("b1", durationSeconds = 200, wordsRead = 600)
        waitForEmission()
        advanceUntilIdle()

        val global = vm.uiState.value.globalStats
        assertNotNull(global)
        assertEquals(300L, global!!.allTimeTotalSeconds)
        assertEquals(1000L, global.allTimeTotalWords)
    }

    @Test
    fun `setGoalHours updates globalStats`() = runTest {
        val vm = createViewModel()
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, wordsRead = 200)
        waitForEmission()
        advanceUntilIdle()

        vm.setGoalHours(100)
        assertEquals(100, vm.uiState.value.globalStats?.goalHoursPerYear)
    }

    @Test
    fun `setGoalWords updates globalStats`() = runTest {
        val vm = createViewModel()
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, wordsRead = 200)
        waitForEmission()
        advanceUntilIdle()

        vm.setGoalWords(1_000_000)
        assertEquals(1_000_000, vm.uiState.value.globalStats?.goalWordsPerYear)
    }

    @Test
    fun `selectYear changes selectedYear in state`() = runTest {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val vm = createViewModel()
        insertBook("b1")
        insertSession("b1", durationSeconds = 60, startTime = utcMs(currentYear - 1, Calendar.JUNE, 15))
        waitForEmission()
        advanceUntilIdle()

        vm.selectYear(currentYear - 1)
        advanceUntilIdle()

        assertEquals(currentYear - 1, vm.uiState.value.selectedYear)
    }

    @Test
    fun `bookStats separates manual and tts durations`() = runTest {
        val vm = createViewModel()
        insertBook("b1")
        insertSession("b1", durationSeconds = 100, wordsRead = 300, isTts = false)
        insertSession("b1", durationSeconds = 200, wordsRead = 600, isTts = true)
        waitForEmission()
        advanceUntilIdle()

        val stat = vm.uiState.value.bookStats.first()
        assertEquals(100, stat.manualDurationSeconds)
        assertEquals(300, stat.manualWordsRead)
        assertEquals(200, stat.ttsDurationSeconds)
        assertEquals(600, stat.ttsWordsRead)
    }

    @Test
    fun `bookStats sorted by last read descending`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Older Book")
        insertBook("b2", "Newer Book")
        insertSession("b1", durationSeconds = 60, startTime = 1000L)
        insertSession("b2", durationSeconds = 60, startTime = 2000L)
        waitForEmission()
        advanceUntilIdle()

        val stats = vm.uiState.value.bookStats
        assertEquals(2, stats.size)
        assertEquals("Newer Book", stats[0].book.title)
        assertEquals("Older Book", stats[1].book.title)
    }

    @Test
    fun `hidden books still appear in stats`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Hidden Book")
        db.bookDao().setHidden("b1", true)
        insertSession("b1", durationSeconds = 60, wordsRead = 200)
        waitForEmission()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.bookStats.size)
        assertEquals("Hidden Book", vm.uiState.value.bookStats[0].book.title)
    }

    @Test
    fun `sessions under 10 seconds are excluded from stats`() = runTest {
        val vm = createViewModel()
        insertBook("b1")
        insertSession("b1", durationSeconds = 5, wordsRead = 100) // too short
        insertSession("b1", durationSeconds = 60, wordsRead = 300) // included
        waitForEmission()
        advanceUntilIdle()

        val stat = vm.uiState.value.bookStats.first()
        assertEquals(1, stat.sessionCount)
        assertEquals(60, stat.totalReadingSeconds)
    }

    private fun utcMs(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(year, month, day, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
