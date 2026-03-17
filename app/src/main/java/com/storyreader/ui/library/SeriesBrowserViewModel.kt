package com.storyreader.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.catalog.ServerBook
import com.storyreader.data.catalog.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SeriesBrowserUiState(
    val allSeries: List<SeriesSummary> = emptyList(),
    val filteredSeries: List<SeriesSummary> = emptyList(),
    val standaloneBooks: List<ServerBook> = emptyList(),
    val filteredStandaloneBooks: List<ServerBook> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val importingSeriesName: String? = null,
    val importingBookId: Int? = null,
    val importProgress: Pair<Int, Int>? = null,
    val importSuccessMessage: String? = null,
    val serverBaseUrl: String = "",
    val authHeader: String? = null
)

class SeriesBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val apiClient = app.storyManagerApiClient
    private val repository = app.storyManagerRepository
    private val credentialsManager = app.opdsCredentialsManager

    private val _uiState = MutableStateFlow(
        SeriesBrowserUiState(
            serverBaseUrl = credentialsManager.url?.trimEnd('/').orEmpty(),
            authHeader = credentialsManager.currentCredentials()?.let { creds ->
                if (creds.username.isNotBlank()) {
                    okhttp3.Credentials.basic(creds.username, creds.password)
                } else null
            }
        )
    )
    val uiState: StateFlow<SeriesBrowserUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val seriesResult = apiClient.fetchSeries()
            val standaloneResult = apiClient.fetchStandaloneBooks()

            val seriesError = seriesResult.exceptionOrNull()
            val standaloneError = standaloneResult.exceptionOrNull()
            val error = seriesError ?: standaloneError

            if (error != null && seriesResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Failed to load"
                )
                return@launch
            }

            val series = seriesResult.getOrDefault(emptyList())
            val standalone = standaloneResult.getOrDefault(emptyList())
            val query = _uiState.value.searchQuery
            _uiState.value = _uiState.value.copy(
                allSeries = series,
                filteredSeries = filterSeries(series, query),
                standaloneBooks = standalone,
                filteredStandaloneBooks = filterStandaloneBooks(standalone, query),
                isLoading = false
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredSeries = filterSeries(_uiState.value.allSeries, query),
            filteredStandaloneBooks = filterStandaloneBooks(_uiState.value.standaloneBooks, query)
        )
    }

    fun importSeries(seriesName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                importingSeriesName = seriesName,
                importProgress = 0 to 0,
                importSuccessMessage = null
            )
            repository.importSeriesBooks(seriesName) { current, total ->
                _uiState.value = _uiState.value.copy(importProgress = current to total)
            }.fold(
                onSuccess = { count ->
                    _uiState.value = _uiState.value.copy(
                        importingSeriesName = null,
                        importProgress = null,
                        importSuccessMessage = "Imported $count books from $seriesName"
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        importingSeriesName = null,
                        importProgress = null,
                        error = e.message ?: "Failed to import series"
                    )
                }
            )
        }
    }

    fun importStandaloneBook(serverBook: ServerBook) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                importingBookId = serverBook.id,
                importSuccessMessage = null
            )
            repository.importSingleBook(serverBook).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        importingBookId = null,
                        importSuccessMessage = "Imported \"${serverBook.title}\""
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        importingBookId = null,
                        error = e.message ?: "Failed to import book"
                    )
                }
            )
        }
    }

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(importSuccessMessage = null)
    }

    private fun filterSeries(series: List<SeriesSummary>, query: String): List<SeriesSummary> {
        if (query.isBlank()) return series
        val lowerQuery = query.lowercase()
        return series.filter { it.name.lowercase().contains(lowerQuery) }
    }

    private fun filterStandaloneBooks(books: List<ServerBook>, query: String): List<ServerBook> {
        if (query.isBlank()) return books
        val lowerQuery = query.lowercase()
        return books.filter {
            it.title.lowercase().contains(lowerQuery) ||
                it.author.lowercase().contains(lowerQuery)
        }
    }
}
