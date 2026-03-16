package com.storyreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import com.storyreader.data.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class BookImportSource(
    val label: String,
    val requiresCloudDivider: Boolean = false
) {
    DEVICE("From Device"),
    GOOGLE_DRIVE("From Google Drive"),
    OPDS("From OPDS Catalog"),
    NEXTCLOUD("From Nextcloud", requiresCloudDivider = true)
}

enum class LibrarySortOption(val label: String) {
    LAST_READ("Last Read"),
    TITLE("Title"),
    AUTHOR("Author"),
    PROGRESS("Progress")
}

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val lastReadTimes: Map<String, Long> = emptyMap(),
    val sortOption: LibrarySortOption = LibrarySortOption.LAST_READ,
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasNextcloudCredentials: Boolean = false,
    val importSources: List<BookImportSource> = listOf(
        BookImportSource.DEVICE,
        BookImportSource.GOOGLE_DRIVE,
        BookImportSource.OPDS
    )
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val repository: BookRepository = app.bookRepository
    private val sessionDao = app.database.readingSessionDao()

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _sortOption = MutableStateFlow(LibrarySortOption.LAST_READ)

    init {
        viewModelScope.launch {
            combine(
                repository.observeAll(),
                sessionDao.getLastReadTimes(),
                _sortOption
            ) { books, lastReads, sort ->
                val lastReadMap = lastReads.associate { it.bookId to it.lastReadAt }
                val sortedBooks = sortBooks(books, lastReadMap, sort)
                _uiState.value.copy(
                    books = sortedBooks,
                    lastReadTimes = lastReadMap,
                    sortOption = sort,
                    isLoading = false,
                    hasNextcloudCredentials = app.credentialsManager.hasCredentials,
                    importSources = buildImportSources(app.credentialsManager.hasCredentials)
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
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

    fun refreshCredentials() {
        _uiState.value = _uiState.value.copy(
            hasNextcloudCredentials = app.credentialsManager.hasCredentials,
            importSources = buildImportSources(app.credentialsManager.hasCredentials)
        )
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.importFromUri(uri)
                .onSuccess { _uiState.value = _uiState.value.copy(isLoading = false) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to import book"
                    )
                }
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
        return buildList {
            add(BookImportSource.DEVICE)
            add(BookImportSource.GOOGLE_DRIVE)
            add(BookImportSource.OPDS)
            if (hasNextcloudCredentials) {
                add(BookImportSource.NEXTCLOUD)
            }
        }
    }
}
