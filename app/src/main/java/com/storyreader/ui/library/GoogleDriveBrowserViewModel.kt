package com.storyreader.ui.library

import android.app.Application
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.sync.GoogleDriveAuthorizationOutcome
import com.storyreader.data.sync.GoogleDriveItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class GoogleDriveFolderNode(
    val id: String,
    val name: String
)

data class GoogleDriveBrowserUiState(
    val items: List<GoogleDriveItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentFolderName: String = "Google Drive",
    val pathStack: List<GoogleDriveFolderNode> = listOf(GoogleDriveFolderNode("root", "Google Drive")),
    val downloadingItems: Set<String> = emptySet(),
    val downloadedCount: Int = 0,
    val totalToDownload: Int = 0,
    val accountLabel: String? = null
)

sealed interface GoogleDriveBrowserEvent {
    data class LaunchAuthorization(val request: IntentSenderRequest) : GoogleDriveBrowserEvent
}

class GoogleDriveBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val authManager = app.googleDriveAuthManager
    private val googleDriveApi = app.googleDriveApi
    private val bookRepository = app.bookRepository
    private val googleDriveCredentials = app.googleDriveCredentialsManager

    private val _uiState = MutableStateFlow(
        GoogleDriveBrowserUiState(accountLabel = googleDriveCredentials.displayLabel())
    )
    val uiState: StateFlow<GoogleDriveBrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GoogleDriveBrowserEvent>()
    val events: SharedFlow<GoogleDriveBrowserEvent> = _events.asSharedFlow()

    fun loadInitialFolder() {
        if (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading) return
        loadFolder(_uiState.value.pathStack.last())
    }

    fun navigateTo(item: GoogleDriveItem) {
        val node = GoogleDriveFolderNode(item.id, item.name)
        _uiState.value = _uiState.value.copy(pathStack = _uiState.value.pathStack + node)
        loadFolder(node)
    }

    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.size <= 1) return false
        val newStack = stack.dropLast(1)
        _uiState.value = _uiState.value.copy(pathStack = newStack)
        loadFolder(newStack.last())
        return true
    }

    fun onAuthorizationResult(data: Intent?) {
        viewModelScope.launch {
            authManager.consumeAuthorizationResult(data)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        accountLabel = googleDriveCredentials.displayLabel()
                    )
                    loadFolder(_uiState.value.pathStack.last())
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Google Drive sign-in failed"
                    )
                }
        }
    }

    fun downloadItem(item: GoogleDriveItem) {
        viewModelScope.launch {
            resolveAccessToken(
                onAuthorized = { accessToken ->
                    if (item.isFolder) {
                        downloadFolder(accessToken, item)
                    } else {
                        downloadSingleFile(accessToken, item)
                    }
                }
            )
        }
    }

    private fun loadFolder(folder: GoogleDriveFolderNode) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                currentFolderName = folder.name
            )
            resolveAccessToken(
                onAuthorized = { accessToken ->
                    googleDriveApi.listFolder(accessToken, folder.id)
                        .onSuccess { items ->
                            _uiState.value = _uiState.value.copy(
                                items = items,
                                isLoading = false,
                                error = null,
                                currentFolderName = folder.name,
                                accountLabel = googleDriveCredentials.displayLabel()
                            )
                        }
                        .onFailure { error ->
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                error = error.message ?: "Failed to list Google Drive folder"
                            )
                        }
                }
            )
        }
    }

    private suspend fun downloadSingleFile(accessToken: String, item: GoogleDriveItem) {
        _uiState.value = _uiState.value.copy(
            downloadingItems = _uiState.value.downloadingItems + item.id
        )
        val booksDir = File(getApplication<Application>().filesDir, "books")
        booksDir.mkdirs()
        val destination = File(booksDir, item.name)
        googleDriveApi.downloadFile(accessToken, item.id, destination)
            .onSuccess {
                bookRepository.importFromUri(android.net.Uri.fromFile(destination))
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download ${item.name}: ${error.message}"
                )
            }
        _uiState.value = _uiState.value.copy(
            downloadingItems = _uiState.value.downloadingItems - item.id,
            downloadedCount = _uiState.value.downloadedCount + 1
        )
    }

    private suspend fun downloadFolder(accessToken: String, item: GoogleDriveItem) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        googleDriveApi.listEpubsRecursively(accessToken, item.id)
            .onSuccess { epubs ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    totalToDownload = epubs.size,
                    downloadedCount = 0
                )
                epubs.forEach { epub ->
                    downloadSingleFile(accessToken, epub)
                }
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Failed to list Google Drive folder contents"
                )
            }
    }

    private suspend fun resolveAccessToken(onAuthorized: suspend (String) -> Unit) {
        authManager.authorize()
            .onSuccess { outcome ->
                when (outcome) {
                    is GoogleDriveAuthorizationOutcome.Authorized -> onAuthorized(outcome.accessToken)
                    is GoogleDriveAuthorizationOutcome.NeedsResolution -> {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        _events.emit(
                            GoogleDriveBrowserEvent.LaunchAuthorization(
                                IntentSenderRequest.Builder(outcome.intentSender).build()
                            )
                        )
                    }
                }
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Google Drive authorization failed"
                )
            }
    }

}
