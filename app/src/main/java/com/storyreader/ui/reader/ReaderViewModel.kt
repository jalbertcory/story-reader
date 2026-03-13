package com.storyreader.ui.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.reader.epub.EpubRepository
import com.storyreader.reader.tts.TtsCatalog
import com.storyreader.reader.tts.TtsEngineOption
import com.storyreader.reader.tts.TtsHighlightSynchronizer
import com.storyreader.reader.tts.TtsManager
import com.storyreader.reader.tts.TtsMediaService
import com.storyreader.reader.tts.TtsVoiceOption
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
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesSerializer
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.VisualNavigator
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
import org.readium.r2.shared.util.Language
import java.util.Locale

enum class TtsPlaybackState { STOPPED, PLAYING, PAUSED }

data class TtsSettingsUiState(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val enginePackageName: String? = null,
    val availableEngines: List<TtsEngineOption> = emptyList(),
    val selectedVoiceId: String? = null,
    val availableVoices: List<TtsVoiceOption> = emptyList(),
    val languageLabel: String = Locale.getDefault().displayName,
    val isLoadingVoices: Boolean = false
)

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
private const val KEY_IS_NIGHT = "is_night_theme"
private const val KEY_TTS_PREFS = "tts_prefs_json"
private const val KEY_TTS_ENGINE = "tts_engine"

@OptIn(ExperimentalReadiumApi::class)
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val bookRepository: BookRepository = app.bookRepository
    private val readingRepository: ReadingRepository = app.readingRepository
    private val epubRepository: EpubRepository = app.epubRepository
    private val prefStore = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ttsCatalog = TtsCatalog(application)
    private val ttsPreferencesSerializer = AndroidTtsPreferencesSerializer()

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _preferences = MutableStateFlow(loadSavedPreferences())
    val preferences: StateFlow<EpubPreferences> = _preferences.asStateFlow()

    private val _ttsPreferences = MutableStateFlow(loadSavedTtsPreferences())
    private var selectedTtsEnginePackageName: String? = prefStore.getString(KEY_TTS_ENGINE, null)
    private val _ttsSettings = MutableStateFlow(
        TtsSettingsUiState(
            speed = (_ttsPreferences.value.speed ?: 1.0).toFloat(),
            pitch = (_ttsPreferences.value.pitch ?: 1.0).toFloat(),
            enginePackageName = selectedTtsEnginePackageName,
            selectedVoiceId = selectedVoiceFor(currentTtsLanguage())
        )
    )
    val ttsSettings: StateFlow<TtsSettingsUiState> = _ttsSettings.asStateFlow()

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
        val isNight = prefStore.getBoolean(KEY_IS_NIGHT, false)
        return if (isNight) {
            EpubPreferences(
                fontSize = fontSize,
                fontFamily = fontFamily,
                theme = null,
                backgroundColor = org.readium.r2.navigator.preferences.Color(0xFF000000.toInt()),
                textColor = org.readium.r2.navigator.preferences.Color(0xFFFF7722.toInt()),
                publisherStyles = false
            )
        } else {
            EpubPreferences(fontSize = fontSize, theme = theme, fontFamily = fontFamily)
        }
    }

    private fun loadSavedTtsPreferences(): AndroidTtsPreferences {
        val serialized = prefStore.getString(KEY_TTS_PREFS, null) ?: return AndroidTtsPreferences()
        return runCatching { ttsPreferencesSerializer.deserialize(serialized) }
            .getOrDefault(AndroidTtsPreferences())
    }

    private fun savePreferences(prefs: EpubPreferences) {
        val isNight = prefs.backgroundColor?.int == 0xFF000000.toInt()
        prefStore.edit().apply {
            if (prefs.fontSize != null) putFloat(KEY_FONT_SIZE, prefs.fontSize!!.toFloat())
            else remove(KEY_FONT_SIZE)
            putString(KEY_THEME, when (prefs.theme) {
                Theme.DARK -> "DARK"
                Theme.SEPIA -> "SEPIA"
                else -> null
            })
            putString(KEY_FONT_FAMILY, prefs.fontFamily?.name ?: "")
            putBoolean(KEY_IS_NIGHT, isNight)
        }.apply()
        app.isDarkReadingTheme.value = prefs.theme == Theme.DARK || isNight
    }

    private fun saveTtsPreferences() {
        prefStore.edit()
            .putString(KEY_TTS_PREFS, ttsPreferencesSerializer.serialize(_ttsPreferences.value))
            .putString(KEY_TTS_ENGINE, selectedTtsEnginePackageName)
            .apply()
    }

    private val _ttsState = MutableStateFlow(TtsPlaybackState.STOPPED)
    val ttsState: StateFlow<TtsPlaybackState> = _ttsState.asStateFlow()

    private val ttsManager = TtsManager(application)
    private val ttsHighlightSynchronizer = TtsHighlightSynchronizer(viewModelScope)
    private var ttsNavigator: AndroidTtsNavigator? = null
    private var ttsServiceBinder: TtsMediaService.LocalBinder? = null
    private var ttsJob: Job? = null

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _showBars = MutableStateFlow(true)
    val showBars: StateFlow<Boolean> = _showBars.asStateFlow()

    private var navigator: EpubNavigatorFragment? = null
    private var locatorJob: Job? = null
    private var preferencesJob: Job? = null
    private var savePositionJob: Job? = null
    private var ttsCatalogJob: Job? = null
    private var currentBookId: String? = null
    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    private var sessionStartProgression: Float = 0f
    private val pageTurnTimestamps = mutableListOf<Long>()

    init {
        refreshTtsCatalog()
    }

    fun toggleBars() {
        _showBars.value = !_showBars.value
    }

    fun openBook(bookId: String) {
        if (currentBookId == bookId) return
        currentBookId = bookId

        viewModelScope.launch {
            _uiState.value = ReaderUiState(isLoading = true)

            val savedPosition = readingRepository.observeLatestPosition(bookId).firstOrNull()
            val uri = Uri.parse(bookId)

            sessionStartProgression = savedPosition?.let { pos ->
                try {
                    val json = org.json.JSONObject(pos.locatorJson)
                    val locations = json.optJSONObject("locations")
                    locations?.optDouble("totalProgression", 0.0)?.toFloat() ?: 0f
                } catch (_: Exception) { 0f }
            } ?: 0f

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
                    ttsManager.initialize(publication, selectedTtsEnginePackageName)
                    refreshTtsCatalog()
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

        ttsNavigator?.let { activeTtsNavigator ->
            ttsHighlightSynchronizer.startSync(activeTtsNavigator, nav)
        }

        // IMPORTANT: Register our custom tap handler FIRST so it takes priority.
        // When TTS is active, ALL taps become play/pause and are consumed (return true),
        // which prevents DirectionalNavigationAdapter from receiving them.
        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (_ttsState.value != TtsPlaybackState.STOPPED) {
                    ttsPlayPause()
                    return true
                }
                val viewWidth = nav.publicationView.width.toFloat()
                val edgeSize = viewWidth * 0.3f
                if (event.point.x in edgeSize..(viewWidth - edgeSize)) {
                    _showBars.value = !_showBars.value
                    return true
                }
                return false
            }
        })

        val overflowableNav = nav as? OverflowableNavigator
        if (overflowableNav != null) {
            nav.addInputListener(DirectionalNavigationAdapter(overflowableNav))
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
        val updated = transform(_preferences.value)
        _preferences.value = updated
        savePreferences(updated)
    }

    fun updateTtsSpeed(value: Float) {
        updateTtsPreferences(_ttsPreferences.value.copy(speed = value.toDouble()))
    }

    fun updateTtsPitch(value: Float) {
        updateTtsPreferences(_ttsPreferences.value.copy(pitch = value.toDouble()))
    }

    fun selectTtsVoice(voiceId: String?) {
        val language = currentTtsLanguage()
        val currentVoices = _ttsPreferences.value.voices.orEmpty().toMutableMap()
        if (voiceId.isNullOrBlank()) {
            currentVoices.remove(language)
        } else {
            currentVoices[language] = AndroidTtsEngine.Voice.Id(voiceId)
        }
        updateTtsPreferences(
            _ttsPreferences.value.copy(voices = currentVoices.takeIf { it.isNotEmpty() })
        )
    }

    fun selectTtsEngine(packageName: String?) {
        selectedTtsEnginePackageName = packageName
        saveTtsPreferences()
        if (_ttsState.value != TtsPlaybackState.STOPPED) {
            stopTts()
        }
        _uiState.value.publication?.let { publication ->
            ttsManager.initialize(publication, selectedTtsEnginePackageName)
        }
        refreshTtsCatalog()
    }

    fun openSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            TtsManager.requestInstallVoice(getApplication())
        }
    }

    private fun updateTtsPreferences(preferences: AndroidTtsPreferences) {
        _ttsPreferences.value = preferences
        _ttsSettings.value = _ttsSettings.value.copy(
            speed = (preferences.speed ?: 1.0).toFloat(),
            pitch = (preferences.pitch ?: 1.0).toFloat(),
            selectedVoiceId = selectedVoiceFor(currentTtsLanguage())
        )
        saveTtsPreferences()
        ttsNavigator?.submitPreferences(preferences)
    }

    private fun refreshTtsCatalog() {
        ttsCatalogJob?.cancel()
        val language = currentTtsLanguage()
        _ttsSettings.value = _ttsSettings.value.copy(
            enginePackageName = selectedTtsEnginePackageName,
            selectedVoiceId = selectedVoiceFor(language),
            languageLabel = language.locale.displayName,
            isLoadingVoices = true
        )
        ttsCatalogJob = viewModelScope.launch {
            val catalog = ttsCatalog.load(selectedTtsEnginePackageName)
            val filteredVoices = catalog.voices
                .filter { voiceMatchesLanguage(it, language) }
                .ifEmpty { catalog.voices }
            _ttsSettings.value = _ttsSettings.value.copy(
                availableEngines = catalog.engines,
                availableVoices = filteredVoices,
                selectedVoiceId = selectedVoiceFor(language),
                languageLabel = language.locale.displayName,
                enginePackageName = selectedTtsEnginePackageName,
                isLoadingVoices = false
            )
        }
    }

    private fun currentTtsLanguage(): Language =
        _uiState.value.publication?.metadata?.language ?: Language(Locale.getDefault())

    private fun selectedVoiceFor(language: Language): String? =
        _ttsPreferences.value.voices?.get(language)?.value

    private fun voiceMatchesLanguage(voice: TtsVoiceOption, language: Language): Boolean {
        val targetTag = language.locale.toLanguageTag()
        return voice.languageTag.equals(targetTag, ignoreCase = true) ||
            voice.languageTag.startsWith("${language.locale.language}-", ignoreCase = true) ||
            voice.languageTag.equals(language.locale.language, ignoreCase = true)
    }

    fun startTts() {
        if (_ttsState.value != TtsPlaybackState.STOPPED) return
        ttsJob = viewModelScope.launch {
            val startLocator = (navigator as? VisualNavigator)?.firstVisibleElementLocator()
                ?: _currentLocator.value
            val nav = ttsManager.start(startLocator, _ttsPreferences.value) ?: return@launch
            ttsNavigator = nav

            val binder = TtsMediaService.bind(getApplication())
            ttsServiceBinder = binder
            binder?.openSession(nav)
            navigator?.let { epubNavigator ->
                ttsHighlightSynchronizer.startSync(nav, epubNavigator)
            }

            nav.play()
            _ttsState.value = TtsPlaybackState.PLAYING

            nav.playback.collect { playback ->
                when (playback.state) {
                    is TtsNavigator.State.Ended -> stopTts()
                    is TtsNavigator.State.Failure -> stopTts()
                    else -> {
                        _ttsState.value = if (playback.playWhenReady) {
                            TtsPlaybackState.PLAYING
                        } else {
                            TtsPlaybackState.PAUSED
                        }
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
        ttsHighlightSynchronizer.stopSync()
        navigator?.let { epubNavigator ->
            viewModelScope.launch {
                ttsHighlightSynchronizer.clearHighlights(epubNavigator)
            }
        }
        ttsNavigator?.close()
        ttsNavigator = null
        ttsManager.stop()
        ttsServiceBinder?.closeSession()
        ttsServiceBinder = null
        _ttsState.value = TtsPlaybackState.STOPPED
    }

    fun ttsSkipPrevious() {
        val nav = navigator as? OverflowableNavigator ?: return
        viewModelScope.launch {
            stopTts()
            nav.goBackward()
            delay(200)
            startTts()
        }
    }

    fun ttsSkipNext() {
        val nav = navigator as? OverflowableNavigator ?: return
        viewModelScope.launch {
            stopTts()
            nav.goForward()
            delay(200)
            startTts()
        }
    }

    fun finalizeSession() {
        val sessionId = currentSessionId ?: return
        val capturedTimestamps = pageTurnTimestamps.toList()
        val capturedStartMs = sessionStartMs
        val capturedStartProgression = sessionStartProgression
        val capturedEndProgression =
            _currentLocator.value?.locations?.totalProgression?.toFloat() ?: capturedStartProgression
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val wordCount = bookRepository.getWordCount(bookId)
            readingRepository.finalizeSession(
                sessionId = sessionId,
                pageTurnTimestampsMs = capturedTimestamps,
                sessionStartMs = capturedStartMs,
                progressionStart = capturedStartProgression,
                progressionEnd = capturedEndProgression,
                bookWordCount = wordCount
            )
        }
        currentSessionId = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTts()
        finalizeSession()
    }
}
