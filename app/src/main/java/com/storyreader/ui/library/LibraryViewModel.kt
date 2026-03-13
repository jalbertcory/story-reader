package com.storyreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val lastReadTimes: Map<String, Long> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasNextcloudCredentials: Boolean = false,
    val hasGoogleDriveEnabled: Boolean = true  // Google Drive accessible via system file picker
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val repository: BookRepository = app.bookRepository
    private val sessionDao = app.database.readingSessionDao()

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAll(),
                sessionDao.getLastReadTimes()
            ) { books, lastReads ->
                val lastReadMap = lastReads.associate { it.bookId to it.lastReadAt }
                _uiState.value.copy(
                    books = books,
                    lastReadTimes = lastReadMap,
                    isLoading = false,
                    hasNextcloudCredentials = app.credentialsManager.hasCredentials
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun refreshCredentials() {
        _uiState.value = _uiState.value.copy(
            hasNextcloudCredentials = app.credentialsManager.hasCredentials
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
}
