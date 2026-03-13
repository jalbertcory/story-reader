package com.storyreader.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.sync.NextcloudItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class NextcloudBrowserUiState(
    val items: List<NextcloudItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPath: String = "",
    val pathStack: List<String> = emptyList(),
    val downloadingItems: Set<String> = emptySet(),
    val downloadedCount: Int = 0,
    val totalToDownload: Int = 0
)

class NextcloudBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val webDavRepo = app.webDavSyncRepository
    private val bookRepository = app.bookRepository

    private val _uiState = MutableStateFlow(NextcloudBrowserUiState())
    val uiState: StateFlow<NextcloudBrowserUiState> = _uiState.asStateFlow()

    init {
        navigateTo(webDavRepo.buildUserRootUrl())
    }

    fun navigateTo(folderUrl: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                currentPath = folderUrl,
                pathStack = _uiState.value.pathStack + folderUrl
            )
            webDavRepo.listFolder(folderUrl)
                .onSuccess { items ->
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        isLoading = false
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to list folder"
                    )
                }
        }
    }

    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.size <= 1) return false
        val newStack = stack.dropLast(1)
        _uiState.value = _uiState.value.copy(pathStack = newStack)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                currentPath = newStack.last()
            )
            webDavRepo.listFolder(newStack.last())
                .onSuccess { items ->
                    _uiState.value = _uiState.value.copy(items = items, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to list folder"
                    )
                }
        }
        return true
    }

    fun downloadItem(item: NextcloudItem) {
        viewModelScope.launch {
            if (item.isFolder) {
                downloadFolder(item.url)
            } else {
                downloadSingleEpub(item)
            }
        }
    }

    private suspend fun downloadSingleEpub(item: NextcloudItem) {
        _uiState.value = _uiState.value.copy(
            downloadingItems = _uiState.value.downloadingItems + item.url
        )
        val booksDir = File(getApplication<Application>().filesDir, "books")
        booksDir.mkdirs()
        val destFile = File(booksDir, item.name)
        webDavRepo.downloadEpub(item.url, destFile)
            .onSuccess {
                bookRepository.importFromUri(android.net.Uri.fromFile(destFile))
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = "Failed to download ${item.name}: ${e.message}"
                )
            }
        _uiState.value = _uiState.value.copy(
            downloadingItems = _uiState.value.downloadingItems - item.url,
            downloadedCount = _uiState.value.downloadedCount + 1
        )
    }

    private suspend fun downloadFolder(folderUrl: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        webDavRepo.listEpubsInFolder(folderUrl)
            .onSuccess { epubs ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    totalToDownload = epubs.size,
                    downloadedCount = 0
                )
                for (epub in epubs) {
                    downloadSingleEpub(epub)
                }
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to list folder contents"
                )
            }
    }
}
