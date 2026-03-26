package com.storyreader.ui.library

import android.app.Application
import android.util.Log
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

private const val TAG = "SeriesBrowserVM"

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
    val authHeader: String? = null,
    val expandedSeries: Set<String> = emptySet(),
    val seriesBooks: Map<String, List<ServerBook>> = emptyMap(),
    val loadingSeriesBooks: Set<String> = emptySet(),
    /** Series matched by book-level search — shown expanded with matching books */
    val seriesMatchedByBook: Map<String, List<ServerBook>> = emptyMap()
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

    /** All books across all series, fetched once for search */
    private var allSeriesBooks: Map<String, List<ServerBook>> = emptyMap()
    private var allBooksFetched = false

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

            // Prefetch all series books in background for search
            fetchAllSeriesBooks(series)
        }
    }

    private suspend fun fetchAllSeriesBooks(series: List<SeriesSummary>) {
        val result = mutableMapOf<String, List<ServerBook>>()
        for (s in series) {
            apiClient.fetchSeriesBooks(s.name)
                .onFailure { e -> Log.w(TAG, "Failed to fetch books for series '${s.name}'", e) }
                .getOrNull()?.let { books ->
                    result[s.name] = books
                }
        }
        allSeriesBooks = result
        allBooksFetched = true
        // Re-apply search if there's an active query
        val query = _uiState.value.searchQuery
        if (query.isNotBlank()) {
            applySearch(query)
        }
    }

    fun toggleSeriesExpanded(seriesName: String) {
        val current = _uiState.value.expandedSeries
        val newExpanded = if (seriesName in current) {
            current - seriesName
        } else {
            current + seriesName
        }
        _uiState.value = _uiState.value.copy(expandedSeries = newExpanded)

        // Fetch books for this series if not already loaded
        if (seriesName in newExpanded && seriesName !in _uiState.value.seriesBooks) {
            loadSeriesBooks(seriesName)
        }
    }

    private fun loadSeriesBooks(seriesName: String) {
        if (seriesName in _uiState.value.loadingSeriesBooks) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                loadingSeriesBooks = _uiState.value.loadingSeriesBooks + seriesName
            )
            // Use cached data if available
            val books = allSeriesBooks[seriesName]
                ?: apiClient.fetchSeriesBooks(seriesName)
                    .onFailure { e -> Log.w(TAG, "Failed to load books for series '$seriesName'", e) }
                    .getOrNull()
                ?: emptyList()

            // Cache for future use
            if (seriesName !in allSeriesBooks) {
                allSeriesBooks = allSeriesBooks + (seriesName to books)
            }

            _uiState.value = _uiState.value.copy(
                seriesBooks = _uiState.value.seriesBooks + (seriesName to books),
                loadingSeriesBooks = _uiState.value.loadingSeriesBooks - seriesName
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applySearch(query)
    }

    private fun applySearch(query: String) {
        val state = _uiState.value
        val filteredSeries = filterSeries(state.allSeries, query)
        val filteredStandalone = filterStandaloneBooks(state.standaloneBooks, query)

        // Find series where individual books match the query
        val seriesMatchedByBook = if (query.isNotBlank() && allBooksFetched) {
            val lowerQuery = query.lowercase()
            allSeriesBooks.mapNotNull { (seriesName, books) ->
                // Skip series already matched by name
                if (filteredSeries.any { it.name == seriesName }) return@mapNotNull null
                val matchingBooks = books.filter {
                    it.title.lowercase().contains(lowerQuery) ||
                        it.author.lowercase().contains(lowerQuery)
                }
                if (matchingBooks.isNotEmpty()) seriesName to matchingBooks else null
            }.toMap()
        } else {
            emptyMap()
        }

        _uiState.value = state.copy(
            filteredSeries = filteredSeries,
            filteredStandaloneBooks = filteredStandalone,
            seriesMatchedByBook = seriesMatchedByBook,
            // Auto-expand series matched by book search
            expandedSeries = if (seriesMatchedByBook.isNotEmpty()) {
                state.expandedSeries + seriesMatchedByBook.keys
            } else if (query.isBlank()) {
                state.expandedSeries
            } else {
                state.expandedSeries
            },
            // Ensure books are loaded for matched series
            seriesBooks = state.seriesBooks + seriesMatchedByBook.mapValues { (seriesName, _) ->
                allSeriesBooks[seriesName] ?: state.seriesBooks[seriesName] ?: emptyList()
            }
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

    fun importBook(serverBook: ServerBook) {
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
