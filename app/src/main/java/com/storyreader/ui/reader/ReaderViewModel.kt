package com.storyreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.reader.epub.EpubRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

data class ReaderUiState(
    val publication: Publication? = null,
    val book: BookEntity? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialLocatorJson: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val bookRepository: BookRepository = app.bookRepository
    private val readingRepository: ReadingRepository = app.readingRepository
    private val epubRepository: EpubRepository = app.epubRepository

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _preferences = MutableStateFlow(EpubPreferences())
    val preferences: StateFlow<EpubPreferences> = _preferences.asStateFlow()

    private val _ttsPlaying = MutableStateFlow(false)
    val ttsPlaying: StateFlow<Boolean> = _ttsPlaying.asStateFlow()

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private var navigator: EpubNavigatorFragment? = null
    private var locatorJob: Job? = null
    private var currentBookId: String? = null
    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    private var pagesTurned: Int = 0

    fun openBook(bookId: String) {
        if (currentBookId == bookId) return
        currentBookId = bookId

        viewModelScope.launch {
            _uiState.value = ReaderUiState(isLoading = true)

            val savedPosition = readingRepository.observeLatestPosition(bookId).firstOrNull()
            val uri = Uri.parse(bookId)

            epubRepository.openPublication(uri)
                .onSuccess { publication ->
                    _uiState.value = ReaderUiState(
                        publication = publication,
                        isLoading = false,
                        initialLocatorJson = savedPosition?.locatorJson
                    )
                    currentSessionId = readingRepository.startSession(bookId)
                    sessionStartMs = System.currentTimeMillis()
                    pagesTurned = 0
                }
                .onFailure { e ->
                    _uiState.value = ReaderUiState(
                        isLoading = false,
                        error = e.message ?: "Failed to open book"
                    )
                }
        }
    }

    fun onProgressionChanged(bookId: String, locator: Locator) {
        _currentLocator.value = locator
        viewModelScope.launch {
            readingRepository.savePosition(bookId, locator.toJSON().toString())
            bookRepository.updateProgression(
                bookId,
                locator.locations.totalProgression?.toFloat() ?: 0f
            )
            pagesTurned++
        }
    }

    fun onNavigatorReady(nav: EpubNavigatorFragment) {
        if (navigator === nav) return
        navigator = nav
        val bookId = currentBookId ?: return
        locatorJob?.cancel()
        locatorJob = viewModelScope.launch {
            nav.currentLocator.collect { locator ->
                onProgressionChanged(bookId, locator)
            }
        }
    }

    fun navigateToLink(link: Link) {
        val nav = navigator ?: return
        val pub = _uiState.value.publication ?: return
        val locator = pub.locatorFromLink(link) ?: return
        viewModelScope.launch {
            nav.go(locator)
        }
    }

    fun updatePreferences(transform: (EpubPreferences) -> EpubPreferences) {
        _preferences.value = transform(_preferences.value)
    }

    fun setTtsPlaying(playing: Boolean) {
        _ttsPlaying.value = playing
    }

    fun finalizeSession() {
        val sessionId = currentSessionId ?: return
        val bookId = currentBookId ?: return
        val duration = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()
        val captured = pagesTurned
        viewModelScope.launch {
            readingRepository.finalizeSession(sessionId, duration, captured)
        }
        currentSessionId = null
    }

    override fun onCleared() {
        super.onCleared()
        finalizeSession()
    }
}
