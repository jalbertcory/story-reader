package com.storyreader.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.catalog.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SeriesBrowserUiState(
    val allSeries: List<SeriesSummary> = emptyList(),
    val filteredSeries: List<SeriesSummary> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val importingSeriesName: String? = null,
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
        loadSeries()
    }

    fun loadSeries() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            apiClient.fetchSeries().fold(
                onSuccess = { series ->
                    _uiState.value = _uiState.value.copy(
                        allSeries = series,
                        filteredSeries = filterSeries(series, _uiState.value.searchQuery),
                        isLoading = false
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load series"
                    )
                }
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredSeries = filterSeries(_uiState.value.allSeries, query)
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

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(importSuccessMessage = null)
    }

    private fun filterSeries(series: List<SeriesSummary>, query: String): List<SeriesSummary> {
        if (query.isBlank()) return series
        val lowerQuery = query.lowercase()
        return series.filter { it.name.lowercase().contains(lowerQuery) }
    }
}
