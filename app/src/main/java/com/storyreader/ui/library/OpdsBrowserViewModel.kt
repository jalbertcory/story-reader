package com.storyreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.catalog.OpdsCatalogEntry
import com.storyreader.data.catalog.OpdsCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class OpdsBrowserUiState(
    val catalogTitle: String = "OPDS Catalog",
    val configuredUrl: String = "",
    val configuredUsername: String = "",
    val isStoryManagerBackend: Boolean = false,
    val items: List<OpdsCatalogEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pathStack: List<Pair<String, String>> = emptyList(),
    val downloadingItems: Set<String> = emptySet(),
    val downloadedCount: Int = 0,
    val totalToDownload: Int = 0,
    val hasSavedConfiguration: Boolean = false
)

class OpdsBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val opdsRepository = app.opdsCatalogRepository
    private val opdsCredentialsManager = app.opdsCredentialsManager
    private val bookRepository = app.bookRepository

    private val _uiState = MutableStateFlow(
        opdsCredentialsManager.currentCredentials()?.let { stored ->
            OpdsBrowserUiState(
                configuredUrl = stored.baseUrl,
                configuredUsername = stored.username,
                isStoryManagerBackend = stored.isStoryManagerBackend,
                pathStack = listOf(stored.baseUrl to "OPDS Catalog"),
                hasSavedConfiguration = true
            )
        } ?: OpdsBrowserUiState()
    )
    val uiState: StateFlow<OpdsBrowserUiState> = _uiState.asStateFlow()

    init {
        opdsCredentialsManager.currentCredentials()?.let { stored ->
            loadCatalog(stored.baseUrl, resetPath = true)
        }
    }

    fun connect() {
        val credentials = opdsCredentialsManager.currentCredentials() ?: run {
            _uiState.value = _uiState.value.copy(error = "Configure OPDS in Settings first")
            return
        }
        loadCatalog(credentials.baseUrl, resetPath = true)
    }

    fun navigateTo(entry: OpdsCatalogEntry) {
        val targetUrl = entry.navigationUrl ?: return
        _uiState.value = _uiState.value.copy(
            pathStack = _uiState.value.pathStack + (targetUrl to entry.title)
        )
        loadCatalog(targetUrl, resetPath = false)
    }

    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.size <= 1) return false
        val newStack = stack.dropLast(1)
        _uiState.value = _uiState.value.copy(pathStack = newStack)
        loadCatalog(newStack.last().first, resetPath = false)
        return true
    }

    fun downloadEntry(entry: OpdsCatalogEntry) {
        viewModelScope.launch {
            val credentials = opdsCredentialsManager.currentCredentials() ?: return@launch
            _uiState.value = _uiState.value.copy(
                downloadingItems = _uiState.value.downloadingItems + entry.id
            )
            val booksDir = File(getApplication<Application>().filesDir, "books")
            opdsRepository.downloadPublication(credentials, entry, booksDir)
                .onSuccess { file ->
                    bookRepository.importFromUri(Uri.fromFile(file))
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to download ${entry.title}: ${error.message}"
                    )
                }
            _uiState.value = _uiState.value.copy(
                downloadingItems = _uiState.value.downloadingItems - entry.id,
                downloadedCount = _uiState.value.downloadedCount + 1
            )
        }
    }

    private fun loadCatalog(url: String, resetPath: Boolean) {
        viewModelScope.launch {
            val credentials = opdsCredentialsManager.currentCredentials() ?: run {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Configure OPDS in Settings first"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            opdsRepository.fetchCatalog(credentials, url)
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        catalogTitle = page.title,
                        configuredUrl = credentials.baseUrl,
                        configuredUsername = credentials.username,
                        isStoryManagerBackend = credentials.isStoryManagerBackend,
                        items = page.entries,
                        isLoading = false,
                        error = null,
                        totalToDownload = 0,
                        downloadedCount = 0,
                        pathStack = if (resetPath) listOf(url to page.title) else _uiState.value.pathStack.dropLast(1) + (url to page.title),
                        hasSavedConfiguration = true
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load OPDS catalog"
                    )
                }
        }
    }
}
