package com.storyreader.ui.library

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {

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

    private fun createViewModel(): LibraryViewModel = LibraryViewModel(app)

    private suspend fun insertBook(
        bookId: String,
        title: String = "Book $bookId",
        author: String = "Author",
        series: String? = null,
        seriesIndex: Float? = null,
        progression: Float = 0f,
        wordCount: Int = 10000
    ) {
        db.bookDao().insert(
            BookEntity(
                bookId = bookId,
                title = title,
                author = author,
                series = series,
                seriesIndex = seriesIndex,
                totalProgression = progression,
                wordCount = wordCount
            )
        )
    }

    private suspend fun insertSession(bookId: String, startTime: Long = System.currentTimeMillis()) {
        db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = bookId, startTime = startTime, durationSeconds = 60)
        )
    }

    /** Wait for Room's invalidation tracker to fire and ViewModel to process. */
    private fun waitForEmission() {
        Thread.sleep(100)
    }

    @Test
    fun `initial state has isLoading true`() {
        val vm = createViewModel()
        assertTrue(vm.uiState.value.isLoading)
    }

    @Test
    fun `uiState shows books after insert`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Test Book")
        waitForEmission()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.books.size)
        assertEquals("Test Book", vm.uiState.value.books[0].title)
    }

    @Test
    fun `hidden books are excluded from library`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Visible")
        insertBook("b2", "Hidden")
        db.bookDao().setHidden("b2", true)
        waitForEmission()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.books.size)
        assertEquals("Visible", vm.uiState.value.books[0].title)
    }

    @Test
    fun `sort by title orders alphabetically`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Zebra")
        insertBook("b2", "Apple")
        waitForEmission()
        advanceUntilIdle()

        vm.setSortOption(LibrarySortOption.TITLE)
        waitForEmission()
        advanceUntilIdle()

        assertEquals("Apple", vm.uiState.value.books[0].title)
        assertEquals("Zebra", vm.uiState.value.books[1].title)
    }

    @Test
    fun `sort by author orders alphabetically`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Book A", author = "Zelda")
        insertBook("b2", "Book B", author = "Alice")
        waitForEmission()
        advanceUntilIdle()

        vm.setSortOption(LibrarySortOption.AUTHOR)
        waitForEmission()
        advanceUntilIdle()

        assertEquals("Alice", vm.uiState.value.books[0].author)
        assertEquals("Zelda", vm.uiState.value.books[1].author)
    }

    @Test
    fun `sort by progress orders descending`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Low", progression = 0.1f)
        insertBook("b2", "High", progression = 0.9f)
        waitForEmission()
        advanceUntilIdle()

        vm.setSortOption(LibrarySortOption.PROGRESS)
        waitForEmission()
        advanceUntilIdle()

        assertEquals("High", vm.uiState.value.books[0].title)
        assertEquals("Low", vm.uiState.value.books[1].title)
    }

    @Test
    fun `complete standalone books move to bottom of title sort`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "A Complete", progression = 1f)
        insertBook("b2", "Z In Progress", progression = 0.4f)
        waitForEmission()
        advanceUntilIdle()

        vm.setSortOption(LibrarySortOption.TITLE)
        waitForEmission()
        advanceUntilIdle()

        val groups = vm.uiState.value.libraryGroups
        assertEquals("Z In Progress", groups[0].books.single().title)
        assertEquals("A Complete", groups[1].books.single().title)
    }

    @Test
    fun `sort by last read orders by session time descending`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Older")
        insertBook("b2", "Newer")
        insertSession("b1", startTime = 1000L)
        insertSession("b2", startTime = 2000L)
        waitForEmission()
        advanceUntilIdle()

        vm.setSortOption(LibrarySortOption.LAST_READ)
        waitForEmission()
        advanceUntilIdle()

        assertEquals("Newer", vm.uiState.value.books[0].title)
        assertEquals("Older", vm.uiState.value.books[1].title)
    }

    @Test
    fun `complete series move to bottom of library groups even if most recently read`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Series Book 1", series = "Complete Series", seriesIndex = 1f, progression = 1f)
        insertBook("b2", "Series Book 2", series = "Complete Series", seriesIndex = 2f, progression = 1f)
        insertBook("b3", "Current Read", progression = 0.5f)
        insertSession("b1", startTime = 3000L)
        insertSession("b2", startTime = 4000L)
        insertSession("b3", startTime = 1000L)
        waitForEmission()
        advanceUntilIdle()

        vm.setSortOption(LibrarySortOption.LAST_READ)
        waitForEmission()
        advanceUntilIdle()

        val groups = vm.uiState.value.libraryGroups
        assertNull(groups[0].seriesName)
        assertEquals("Current Read", groups[0].books.single().title)
        assertEquals("Complete Series", groups[1].seriesName)
    }

    @Test
    fun `series books are grouped together`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Book 1", series = "My Series", seriesIndex = 1f)
        insertBook("b2", "Book 2", series = "My Series", seriesIndex = 2f)
        insertBook("b3", "Standalone")
        waitForEmission()
        advanceUntilIdle()

        val groups = vm.uiState.value.libraryGroups
        assertEquals(2, groups.size) // 1 series group + 1 standalone

        val seriesGroup = groups.find { it.seriesName == "My Series" }!!
        assertEquals(2, seriesGroup.books.size)
        assertEquals("Book 1", seriesGroup.books[0].title)
        assertEquals("Book 2", seriesGroup.books[1].title)
    }

    @Test
    fun `standalone books are single-book groups`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Standalone A")
        insertBook("b2", "Standalone B")
        waitForEmission()
        advanceUntilIdle()

        val groups = vm.uiState.value.libraryGroups
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.seriesName == null })
        assertTrue(groups.all { it.books.size == 1 })
    }

    @Test
    fun `series group progression is word-count weighted`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Book 1", series = "S", progression = 1.0f, wordCount = 1000)
        insertBook("b2", "Book 2", series = "S", progression = 0.0f, wordCount = 3000)
        waitForEmission()
        advanceUntilIdle()

        val group = vm.uiState.value.libraryGroups.find { it.seriesName == "S" }!!
        assertEquals(0.25f, group.totalProgression, 0.01f)
    }

    @Test
    fun `selectTab updates selectedTab`() {
        val vm = createViewModel()
        vm.selectTab(1)
        assertEquals(1, vm.uiState.value.selectedTab)

        vm.selectTab(0)
        assertEquals(0, vm.uiState.value.selectedTab)
    }

    @Test
    fun `default import sources include files and opds`() = runTest {
        val vm = createViewModel()
        waitForEmission()
        advanceUntilIdle()

        val sources = vm.uiState.value.importSources
        assertTrue(sources.contains(BookImportSource.DEVICE))
        assertTrue(sources.contains(BookImportSource.OPDS))
    }

    @Test
    fun `hideBook removes book from library`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "To Hide")
        waitForEmission()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.books.size)

        vm.hideBook("b1")
        waitForEmission()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.books.size)
    }

    @Test
    fun `markAsRead sets progression to 1`() = runTest {
        val vm = createViewModel()
        insertBook("b1", "Unread", progression = 0.5f)
        waitForEmission()
        advanceUntilIdle()

        vm.markAsRead("b1")
        waitForEmission()
        advanceUntilIdle()

        val book = vm.uiState.value.books.find { it.bookId == "b1" }
        assertEquals(1f, book?.totalProgression ?: 0f, 0.001f)
    }

    @Test
    fun `deleteSession removes saved session`() = runTest {
        insertBook("b1", "Test Book")
        insertSession("b1", startTime = 1000L)
        val sessionId = db.readingSessionDao().getAllSessionsOnce().single().sessionId
        val vm = createViewModel()
        waitForEmission()
        advanceUntilIdle()

        vm.deleteSession(sessionId)
        waitForEmission()
        advanceUntilIdle()

        assertNull(db.readingSessionDao().getById(sessionId))
    }
}
