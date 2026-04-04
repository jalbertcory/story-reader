package com.storyreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.sync.BookImportMetadata
import com.storyreader.data.sync.BookSyncMetadata
import com.storyreader.data.sync.SyncSourceKinds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class BookImportSource(
    val label: String,
    val requiresCloudDivider: Boolean = false
) {
    DEVICE("From Device"),
    GOOGLE_DRIVE("From Google Drive"),
    OPDS("From OPDS Catalog"),
    NEXTCLOUD("From Nextcloud", requiresCloudDivider = true),
    STORY_MANAGER("From Story Manager", requiresCloudDivider = true)
}

enum class LibrarySortOption(val label: String) {
    LAST_READ("Last Read"),
    TITLE("Title"),
    AUTHOR("Author"),
    PROGRESS("Progress")
}

data class LibrarySeriesGroup(
    val seriesName: String?,
    val books: List<BookEntity>,
    val lastReadTime: Long?,
    val totalWordCount: Int = 0,
    val totalProgression: Float = 0f
)

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val libraryGroups: List<LibrarySeriesGroup> = emptyList(),
    val lastReadTimes: Map<String, Long> = emptyMap(),
    val sortOption: LibrarySortOption = LibrarySortOption.LAST_READ,
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasNextcloudCredentials: Boolean = false,
    val importSources: List<BookImportSource> = listOf(
        BookImportSource.DEVICE,
        BookImportSource.GOOGLE_DRIVE,
        BookImportSource.OPDS
    ),
    val selectedTab: Int = 0,
    val webBooks: List<BookEntity> = emptyList(),
    val isStoryManagerBackend: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val isCheckingNewBooks: Boolean = false,
    val newBooksMessage: String? = null
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val repository: BookRepository = app.bookRepository
    private val sessionDao = app.database.readingSessionDao()
    private val bookDao = app.database.bookDao()

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _sortOption = MutableStateFlow(LibrarySortOption.LAST_READ)

    init {
        val isStoryManager = app.opdsCredentialsManager.isStoryManagerBackend
        viewModelScope.launch {
            combine(
                repository.observeAll(),
                sessionDao.getLastReadTimes(),
                _sortOption,
                bookDao.getWebBooks()
            ) { books, lastReads, sort, webBooks ->
                val lastReadMap = lastReads.associate { it.bookId to it.lastReadAt }
                val sortedBooks = sortBooks(books, lastReadMap, sort)
                val libraryGroups = buildLibraryGroups(sortedBooks, lastReadMap, sort)
                _uiState.value.copy(
                    books = sortedBooks,
                    libraryGroups = libraryGroups,
                    lastReadTimes = lastReadMap,
                    sortOption = sort,
                    isLoading = false,
                    hasNextcloudCredentials = app.credentialsManager.hasCredentials,
                    importSources = buildImportSources(app.credentialsManager.hasCredentials),
                    webBooks = webBooks,
                    isStoryManagerBackend = isStoryManager
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun setSortOption(option: LibrarySortOption) {
        _sortOption.value = option
    }

    private fun sortBooks(
        books: List<BookEntity>,
        lastReadTimes: Map<String, Long>,
        sort: LibrarySortOption
    ): List<BookEntity> = when (sort) {
        LibrarySortOption.LAST_READ -> books.sortedByDescending { lastReadTimes[it.bookId] ?: 0L }
        LibrarySortOption.TITLE -> books.sortedBy { it.title.lowercase() }
        LibrarySortOption.AUTHOR -> books.sortedBy { it.author.lowercase() }
        LibrarySortOption.PROGRESS -> books.sortedByDescending { it.totalProgression }
    }

    private fun sortSeriesBooks(books: List<BookEntity>): List<BookEntity> =
        books.sortedWith(compareBy<BookEntity> { it.seriesIndex == null }
            .thenBy { it.seriesIndex }
            .thenBy { it.title.lowercase() })

    fun refreshCredentials() {
        _uiState.value = _uiState.value.copy(
            hasNextcloudCredentials = app.credentialsManager.hasCredentials,
            importSources = buildImportSources(app.credentialsManager.hasCredentials)
        )
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.importFromUri(
                uri,
                BookImportMetadata(
                    sourceKind = SyncSourceKinds.DEVICE,
                    sourceUrl = uri.toString(),
                    originalFileName = BookSyncMetadata.extractOriginalFileName(uri.toString())
                )
            )
                .onSuccess { _uiState.value = _uiState.value.copy(isLoading = false) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to import book"
                    )
                }
        }
    }

    fun checkForWebUpdates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingUpdates = true, error = null)
            app.storyManagerRepository.checkForUpdates()
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        error = "Update check failed: ${e.message}"
                    )
                }
            _uiState.value = _uiState.value.copy(isCheckingUpdates = false)
        }
    }

    fun checkForNewBooks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingNewBooks = true, error = null, newBooksMessage = null)
            app.storyManagerRepository.checkForNewBooks()
                .onSuccess { titles ->
                    _uiState.value = _uiState.value.copy(
                        newBooksMessage = if (titles.isNotEmpty()) {
                            "Added: ${titles.joinToString(", ")}"
                        } else {
                            "No new books found"
                        }
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        error = "Check failed: ${e.message}"
                    )
                }
            _uiState.value = _uiState.value.copy(isCheckingNewBooks = false)
            if (_uiState.value.newBooksMessage != null) {
                delay(4000L)
                clearNewBooksMessage()
            }
        }
    }

    fun clearNewBooksMessage() {
        _uiState.value = _uiState.value.copy(newBooksMessage = null)
    }

    fun markAsRead(bookId: String) {
        viewModelScope.launch {
            repository.updateProgression(bookId, 1f)
        }
    }

    fun hideBook(bookId: String) {
        viewModelScope.launch {
            repository.hideBook(bookId)
        }
    }

    fun getSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> =
        sessionDao.getSessionsForBook(bookId)

    private fun buildImportSources(hasNextcloudCredentials: Boolean): List<BookImportSource> {
        val isStoryManager = app.opdsCredentialsManager.isStoryManagerBackend
        return buildList {
            add(BookImportSource.DEVICE)
            add(BookImportSource.GOOGLE_DRIVE)
            if (!isStoryManager) {
                add(BookImportSource.OPDS)
            }
            if (hasNextcloudCredentials) {
                add(BookImportSource.NEXTCLOUD)
            }
            if (isStoryManager) {
                add(BookImportSource.STORY_MANAGER)
            }
        }
    }

    private fun buildLibraryGroups(
        sortedBooks: List<BookEntity>,
        lastReadTimes: Map<String, Long>,
        sort: LibrarySortOption
    ): List<LibrarySeriesGroup> {
        // Separate books with a series from standalone books
        val (seriesBooks, standaloneBooks) = sortedBooks.partition { it.series != null }

        // Build series groups
        val seriesGroups = seriesBooks
            .groupBy { it.series }
            .map { (seriesName, books) ->
                val groupLastRead = books.mapNotNull { lastReadTimes[it.bookId] }.maxOrNull()
                val totalWordCount = books.sumOf { it.wordCount.coerceAtLeast(0) }
                val totalWordsRead = books.sumOf { book ->
                    (book.wordCount.coerceAtLeast(0) * book.totalProgression.coerceIn(0f, 1f)).toDouble()
                }
                LibrarySeriesGroup(
                    seriesName = seriesName,
                    books = sortSeriesBooks(books),
                    lastReadTime = groupLastRead,
                    totalWordCount = totalWordCount,
                    totalProgression = if (totalWordCount > 0) {
                        (totalWordsRead / totalWordCount).toFloat()
                    } else {
                        0f
                    }
                )
            }

        // Standalone books become single-book groups
        val standaloneGroups = standaloneBooks.map { book ->
            LibrarySeriesGroup(
                seriesName = null,
                books = listOf(book),
                lastReadTime = lastReadTimes[book.bookId],
                totalWordCount = book.wordCount,
                totalProgression = book.totalProgression
            )
        }

        // Merge and sort all groups together
        val allGroups = seriesGroups + standaloneGroups
        return when (sort) {
            LibrarySortOption.LAST_READ -> allGroups.sortedByDescending { it.lastReadTime ?: 0L }
            LibrarySortOption.TITLE -> allGroups.sortedBy {
                (it.seriesName ?: it.books.firstOrNull()?.title ?: "").lowercase()
            }
            LibrarySortOption.AUTHOR -> allGroups.sortedBy {
                it.books.firstOrNull()?.author?.lowercase() ?: ""
            }
            LibrarySortOption.PROGRESS -> allGroups.sortedByDescending {
                it.totalProgression
            }
        }
    }

}
