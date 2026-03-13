package com.storyreader.ui.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.reader.epub.EpubRepository
import com.storyreader.reader.tts.TtsManager
import com.storyreader.reader.tts.TtsMediaService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

enum class TtsPlaybackState { STOPPED, PLAYING, PAUSED }

data class ReaderUiState(
    val publication: Publication? = null,
    val book: BookEntity? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialLocatorJson: String? = null
)

private const val PREFS_NAME = "reader_preferences"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_THEME = "theme"
private const val KEY_FONT_FAMILY = "font_family"

@OptIn(ExperimentalReadiumApi::class)
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val bookRepository: BookRepository = app.bookRepository
    private val readingRepository: ReadingRepository = app.readingRepository
    private val epubRepository: EpubRepository = app.epubRepository
    private val prefStore = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _preferences = MutableStateFlow(loadSavedPreferences())

    val preferences: StateFlow<EpubPreferences> = _preferences.asStateFlow()

    private fun loadSavedPreferences(): EpubPreferences {
        val fontSize = prefStore.getFloat(KEY_FONT_SIZE, Float.MIN_VALUE)
            .takeIf { it != Float.MIN_VALUE }?.toDouble()
        val theme = when (prefStore.getString(KEY_THEME, null)) {
            "DARK" -> Theme.DARK
            "SEPIA" -> Theme.SEPIA
            else -> null
        }
        val fontFamily = prefStore.getString(KEY_FONT_FAMILY, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { FontFamily(it) }
        return EpubPreferences(fontSize = fontSize, theme = theme, fontFamily = fontFamily)
    }

    private fun savePreferences(prefs: EpubPreferences) {
        prefStore.edit().apply {
            if (prefs.fontSize != null) putFloat(KEY_FONT_SIZE, prefs.fontSize!!.toFloat())
            else remove(KEY_FONT_SIZE)
            putString(KEY_THEME, when (prefs.theme) {
                Theme.DARK -> "DARK"
                Theme.SEPIA -> "SEPIA"
                else -> null
            })
            putString(KEY_FONT_FAMILY, prefs.fontFamily?.name ?: "")
        }.apply()
    }

    // TTS state
    private val _ttsState = MutableStateFlow(TtsPlaybackState.STOPPED)
    val ttsState: StateFlow<TtsPlaybackState> = _ttsState.asStateFlow()

    private val ttsManager = TtsManager(application)
    private var ttsNavigator: AndroidTtsNavigator? = null
    private var ttsServiceBinder: TtsMediaService.LocalBinder? = null
    private var ttsJob: Job? = null

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _showBars = MutableStateFlow(true)
    val showBars: StateFlow<Boolean> = _showBars.asStateFlow()

    fun toggleBars() {
        _showBars.value = !_showBars.value
    }

    private var navigator: EpubNavigatorFragment? = null
    private var locatorJob: Job? = null
    private var preferencesJob: Job? = null
    private var savePositionJob: Job? = null
    private var currentBookId: String? = null
    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    private val pageTurnTimestamps = mutableListOf<Long>()

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
                    pageTurnTimestamps.clear()
                    ttsManager.initialize(publication)
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
        pageTurnTimestamps.add(System.currentTimeMillis())
        // Debounce saves by 300 ms to absorb rapid updates during webview reflow
        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            delay(300)
            readingRepository.savePosition(bookId, locator.toJSON().toString())
            bookRepository.updateProgression(
                bookId,
                locator.locations.totalProgression?.toFloat() ?: 0f
            )
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
        preferencesJob?.cancel()
        preferencesJob = _preferences
            .onEach { prefs -> nav.submitPreferences(prefs) }
            .launchIn(viewModelScope)

        val overflowableNav = nav as? OverflowableNavigator
        if (overflowableNav != null) {
            nav.addInputListener(DirectionalNavigationAdapter(overflowableNav))
        }
        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val viewWidth = nav.publicationView.width.toFloat()
                val edgeSize = viewWidth * 0.3f
                if (event.point.x in edgeSize..(viewWidth - edgeSize)) {
                    _showBars.value = !_showBars.value
                    return true
                }
                return false
            }
        })
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
        val updated = transform(_preferences.value)
        _preferences.value = updated
        savePreferences(updated)
    }

    // TTS controls

    fun startTts() {
        if (_ttsState.value != TtsPlaybackState.STOPPED) return
        ttsJob = viewModelScope.launch {
            val startLocator = _currentLocator.value
            val nav = ttsManager.start(startLocator) ?: return@launch
            ttsNavigator = nav

            val binder = TtsMediaService.bind(getApplication())
            ttsServiceBinder = binder
            binder?.openSession(nav)

            nav.play()
            _ttsState.value = TtsPlaybackState.PLAYING

            nav.playback.collect { playback ->
                when (playback.state) {
                    is TtsNavigator.State.Ended -> stopTts()
                    is TtsNavigator.State.Failure -> stopTts()
                    else -> {
                        _ttsState.value = if (playback.playWhenReady)
                            TtsPlaybackState.PLAYING
                        else
                            TtsPlaybackState.PAUSED
                    }
                }
            }
        }
    }

    fun ttsPlayPause() {
        val nav = ttsNavigator ?: return
        when (_ttsState.value) {
            TtsPlaybackState.PLAYING -> nav.pause()
            TtsPlaybackState.PAUSED -> nav.play()
            TtsPlaybackState.STOPPED -> startTts()
        }
    }

    fun stopTts() {
        ttsJob?.cancel()
        ttsJob = null
        ttsNavigator?.close()
        ttsNavigator = null
        ttsManager.stop()
        ttsServiceBinder?.closeSession()
        ttsServiceBinder = null
        _ttsState.value = TtsPlaybackState.STOPPED
    }

    fun ttsSkipPrevious() = ttsNavigator?.skipToPreviousUtterance()
    fun ttsSkipNext() = ttsNavigator?.skipToNextUtterance()

    fun finalizeSession() {
        val sessionId = currentSessionId ?: return
        val capturedTimestamps = pageTurnTimestamps.toList()
        val capturedStartMs = sessionStartMs
        viewModelScope.launch {
            readingRepository.finalizeSession(sessionId, capturedTimestamps, capturedStartMs)
        }
        currentSessionId = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTts()
        finalizeSession()
    }
}
