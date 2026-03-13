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
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasNextcloudCredentials: Boolean = false
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val repository: BookRepository = app.bookRepository

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { books ->
                _uiState.value = _uiState.value.copy(
                    books = books,
                    isLoading = false,
                    hasNextcloudCredentials = app.credentialsManager.hasCredentials
                )
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
