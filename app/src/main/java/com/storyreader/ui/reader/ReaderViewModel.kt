package com.storyreader.ui.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.sync.SyncScheduler
import com.storyreader.reader.epub.EpubRepository
import com.storyreader.reader.tts.TtsCatalog
import com.storyreader.reader.tts.TtsEngineOption
import com.storyreader.reader.tts.TtsHighlightSynchronizer
import com.storyreader.reader.tts.TtsManager
import com.storyreader.reader.tts.TtsMediaService
import com.storyreader.reader.tts.TtsVoiceOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import org.readium.r2.navigator.preferences.TextAlign
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
private const val KEY_SCROLL = "scroll_mode"
private const val KEY_TEXT_ALIGN = "text_align"
private const val KEY_BRIGHTNESS_LEVEL = "brightness_level"
private const val KEY_TTS_PREFS = "tts_prefs_json"
private const val KEY_TTS_ENGINE = "tts_engine"
private const val DEFAULT_TTS_SPEED = 1.5

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

    private val _brightnessLevel = MutableStateFlow(loadSavedBrightnessLevel())
    val brightnessLevel: StateFlow<Float> = _brightnessLevel.asStateFlow()

    private val _ttsPreferences = MutableStateFlow(loadSavedTtsPreferences())
    private var selectedTtsEnginePackageName: String? = prefStore.getString(KEY_TTS_ENGINE, null)
    private val _ttsSettings = MutableStateFlow(
        TtsSettingsUiState(
            speed = (_ttsPreferences.value.speed ?: DEFAULT_TTS_SPEED).toFloat(),
            pitch = (_ttsPreferences.value.pitch ?: 1.0).toFloat(),
            enginePackageName = selectedTtsEnginePackageName,
            selectedVoiceId = selectedVoiceFor(currentTtsLanguage())
        )
    )
    val ttsSettings: StateFlow<TtsSettingsUiState> = _ttsSettings.asStateFlow()

    private fun loadSavedPreferences(): EpubPreferences {
        val fontSize = prefStore.getFloat(KEY_FONT_SIZE, Float.MIN_VALUE)
            .takeIf { it != Float.MIN_VALUE }?.toDouble() ?: 1.5
        val theme = when (prefStore.getString(KEY_THEME, null)) {
            "DARK" -> Theme.DARK
            "SEPIA" -> Theme.SEPIA
            else -> null
        }
        val fontFamily = prefStore.getString(KEY_FONT_FAMILY, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { FontFamily(it) }
        val isNight = prefStore.getBoolean(KEY_IS_NIGHT, false)
        val scroll = if (prefStore.contains(KEY_SCROLL)) prefStore.getBoolean(KEY_SCROLL, false) else null
        val textAlign = when (prefStore.getString(KEY_TEXT_ALIGN, null)) {
            "LEFT" -> TextAlign.LEFT
            "RIGHT" -> TextAlign.RIGHT
            "JUSTIFY" -> TextAlign.JUSTIFY
            else -> null
        }
        return if (isNight) {
            EpubPreferences(
                fontSize = fontSize,
                fontFamily = fontFamily,
                theme = null,
                backgroundColor = org.readium.r2.navigator.preferences.Color(0xFF000000.toInt()),
                textColor = org.readium.r2.navigator.preferences.Color(0xFFFF7722.toInt()),
                publisherStyles = false,
                scroll = scroll,
                textAlign = textAlign
            )
        } else {
            EpubPreferences(
                fontSize = fontSize,
                theme = theme,
                fontFamily = fontFamily,
                scroll = scroll,
                textAlign = textAlign,
                publisherStyles = if (textAlign != null) false else null
            )
        }
    }

    private fun loadSavedTtsPreferences(): AndroidTtsPreferences {
        val serialized = prefStore.getString(KEY_TTS_PREFS, null)
            ?: return AndroidTtsPreferences(speed = DEFAULT_TTS_SPEED)
        return runCatching { ttsPreferencesSerializer.deserialize(serialized) }
            .getOrDefault(AndroidTtsPreferences(speed = DEFAULT_TTS_SPEED))
    }

    private fun loadSavedBrightnessLevel(): Float =
        ReaderBrightness.clamp(
            if (prefStore.contains(KEY_BRIGHTNESS_LEVEL)) {
                prefStore.getFloat(KEY_BRIGHTNESS_LEVEL, 1f)
            } else {
                1f
            }
        )

    private fun savePreferences(prefs: EpubPreferences) {
        val isNight = prefs.backgroundColor?.int == 0xFF000000.toInt()
        prefStore.edit {
            if (prefs.fontSize != null) putFloat(KEY_FONT_SIZE, prefs.fontSize!!.toFloat())
            else remove(KEY_FONT_SIZE)
            putString(KEY_THEME, when (prefs.theme) {
                Theme.DARK -> "DARK"
                Theme.SEPIA -> "SEPIA"
                else -> null
            })
            putString(KEY_FONT_FAMILY, prefs.fontFamily?.name ?: "")
            putBoolean(KEY_IS_NIGHT, isNight)
            if (prefs.scroll != null) putBoolean(KEY_SCROLL, prefs.scroll!!)
            else remove(KEY_SCROLL)
            when (prefs.textAlign) {
                TextAlign.LEFT -> putString(KEY_TEXT_ALIGN, "LEFT")
                TextAlign.RIGHT -> putString(KEY_TEXT_ALIGN, "RIGHT")
                TextAlign.JUSTIFY -> putString(KEY_TEXT_ALIGN, "JUSTIFY")
                else -> remove(KEY_TEXT_ALIGN)
            }
        }
        app.isDarkReadingTheme.value = prefs.theme == Theme.DARK || isNight
    }

    private fun saveTtsPreferences() {
        prefStore.edit {
            putString(KEY_TTS_PREFS, ttsPreferencesSerializer.serialize(_ttsPreferences.value))
            putString(KEY_TTS_ENGINE, selectedTtsEnginePackageName)
        }
    }

    private fun saveBrightnessLevel(level: Float) {
        prefStore.edit {
            putFloat(KEY_BRIGHTNESS_LEVEL, ReaderBrightness.clamp(level))
        }
    }

    private val _ttsState = MutableStateFlow(TtsPlaybackState.STOPPED)
    val ttsState: StateFlow<TtsPlaybackState> = _ttsState.asStateFlow()

    private val ttsManager = TtsManager(application)
    private val ttsHighlightSynchronizer = TtsHighlightSynchronizer(viewModelScope)
    private var ttsNavigator: AndroidTtsNavigator? = null
    private var ttsServiceBinder: TtsMediaService.LocalBinder? = null
    private var ttsJob: Job? = null
    private var ttsResumeLocator: Locator? = null

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _currentChapter = MutableStateFlow<Link?>(null)
    val currentChapter: StateFlow<Link?> = _currentChapter.asStateFlow()

    private val _showBars = MutableStateFlow(true)
    val showBars: StateFlow<Boolean> = _showBars.asStateFlow()

    private var navigator: EpubNavigatorFragment? = null
    private var locatorJob: Job? = null
    private var preferencesJob: Job? = null
    private var savePositionJob: Job? = null
    private var ttsCatalogJob: Job? = null
    private var currentBookId: String? = null
    private var currentCoverArt: ByteArray? = null
    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    private var sessionStartProgression: Float = 0f
    private val pageTurnTimestamps = mutableListOf<Long>()
    private var currentSessionIsTts: Boolean = false
    private var ttsPositionSaveJob: Job? = null
    private var lastTtsSaveMs: Long = 0L
    private var isWebBook: Boolean = false

    init {
        refreshTtsCatalog()
    }

    fun openBook(bookId: String) {
        if (currentBookId == bookId) return
        currentBookId = bookId

        viewModelScope.launch {
            _uiState.value = ReaderUiState(isLoading = true)

            val savedPosition = readingRepository.observeLatestPosition(bookId).firstOrNull()
            val uri = bookId.toUri()
            val bookEntity = app.database.bookDao().getByIdOnce(bookId)
            isWebBook = bookEntity?.sourceType == "web"

            sessionStartProgression = savedPosition?.let { pos ->
                try {
                    val json = org.json.JSONObject(pos.locatorJson)
                    val locations = json.optJSONObject("locations")
                    locations?.optDouble("totalProgression", 0.0)?.toFloat() ?: 0f
                } catch (_: Exception) { 0f }
            } ?: 0f

            epubRepository.openPublication(uri)
                .onSuccess { publication ->
                    // If no saved locator exists but we have a chapter-level fallback
                    // (web stories that were updated), resolve the chapter by title.
                    val initialLocator = savedPosition?.locatorJson
                        ?: resolveChapterFallback(publication, bookEntity)

                    _uiState.value = ReaderUiState(
                        publication = publication,
                        isLoading = false,
                        initialLocatorJson = initialLocator
                    )
                    currentSessionId = readingRepository.startSession(bookId)
                    sessionStartMs = System.currentTimeMillis()
                    currentSessionIsTts = false
                    pageTurnTimestamps.clear()
                    ttsManager.initialize(publication, selectedTtsEnginePackageName)
                    refreshTtsCatalog()
                    currentCoverArt = bookEntity?.coverUri?.let { path ->
                        try {
                            TtsMediaService.downsampleArtwork(java.io.File(path).readBytes())
                        } catch (_: Exception) { null }
                    }
                    bindTtsServiceForAutoRequests(bookId)
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
        if (_ttsState.value == TtsPlaybackState.STOPPED) {
            ttsResumeLocator = null
        }
        pageTurnTimestamps.add(System.currentTimeMillis())

        resolveCurrentChapter(locator)
        // Don't overwrite TTS position when Android Auto is driving playback
        if (ttsServiceBinder?.isStandaloneTtsActive != true) {
            savePositionJob?.cancel()
            savePositionJob = viewModelScope.launch {
                delay(300)
                readingRepository.savePosition(bookId, locator.toJSON().toString())
                bookRepository.updateProgression(
                    bookId,
                    locator.locations.totalProgression?.toFloat() ?: 0f
                )
                // Persist chapter-level fallback for web stories so progress
                // survives content updates that change the file URI / bookId.
                val chapterTitle = _currentChapter.value?.title
                val chapterProgression = locator.locations.progression?.toFloat()
                if (isWebBook && chapterTitle != null) {
                    bookRepository.updateChapterPosition(bookId, chapterTitle, chapterProgression)
                }
            }
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
                val viewHeight = nav.publicationView.height.toFloat()
                // Ignore taps in the top/bottom margins where system bars live.
                // This prevents accidental page turns when pulling down the
                // notification shade or tapping near the bottom status bar.
                val verticalMargin = viewHeight * 0.07f
                if (event.point.y < verticalMargin || event.point.y > viewHeight - verticalMargin) {
                    return true
                }
                val edgeSize = viewWidth * 0.3f
                if (event.point.x in edgeSize..(viewWidth - edgeSize)) {
                    _showBars.value = !_showBars.value
                    return true
                }
                // Edge tap = page turn — hide bars so the reader goes fullscreen
                _showBars.value = false
                return false
            }
        })

        val overflowableNav = nav as? OverflowableNavigator
        if (overflowableNav != null) {
            nav.addInputListener(DirectionalNavigationAdapter(overflowableNav))
        }
    }

    fun navigateToLink(link: Link) {
        // Store the chapter immediately — we know exactly where we're going
        _currentChapter.value = link
        val nav = navigator ?: return
        val pub = _uiState.value.publication ?: return
        val locator = pub.locatorFromLink(link) ?: return
        viewModelScope.launch {
            nav.go(locator)
        }
    }

    private fun resolveCurrentChapter(locator: Locator) {
        val pub = _uiState.value.publication ?: return
        val toc = pub.tableOfContents
        val allLinks = flattenTocLinks(toc)
        val result = matchChapterByHref(locator, allLinks)

        _currentChapter.value = when (result) {
            is ChapterMatch.Single -> result.link
            is ChapterMatch.Multiple -> resolveByProgression(pub, result.candidates, locator)
            is ChapterMatch.NormalizedFallback -> result.link
            ChapterMatch.None -> null
        }
    }

    private fun resolveByProgression(
        pub: Publication,
        candidates: List<Link>,
        locator: Locator
    ): Link? {
        val currentTotal = locator.locations.totalProgression ?: 0.0
        return candidates
            .mapNotNull { link ->
                val entryLocator = pub.locatorFromLink(link) ?: return@mapNotNull null
                val entryTotal = entryLocator.locations.totalProgression ?: 0.0
                link to entryTotal
            }
            .sortedBy { it.second }
            .lastOrNull { it.second <= currentTotal + 0.001 }
            ?.first
            ?: candidates.firstOrNull()
    }

    /**
     * Build a locator JSON from chapter-level fallback data stored on the BookEntity.
     * Used when a web story was updated (new file = new bookId) and the detailed
     * reading position was lost. Matches the saved chapter title against the new TOC
     * and positions at the saved progression within that chapter.
     */
    private fun resolveChapterFallback(publication: Publication, book: BookEntity?): String? {
        val chapterTitle = book?.lastChapterTitle ?: return null
        val chapterProgression = book.lastChapterProgression ?: 0f
        val toc = publication.tableOfContents
        val allLinks = flattenTocLinks(toc)
        val match = allLinks.firstOrNull { it.title.equals(chapterTitle, ignoreCase = true) }
            ?: return null
        val locator = publication.locatorFromLink(match) ?: return null
        val withProgression = locator.copy(
            locations = locator.locations.copy(progression = chapterProgression.toDouble())
        )
        return withProgression.toJSON().toString()
    }

    fun updatePreferences(transform: (EpubPreferences) -> EpubPreferences) {
        val updated = normalizePreferences(transform(_preferences.value))
        _preferences.value = updated
        savePreferences(updated)
    }

    fun setBrightnessLevel(level: Float) {
        val updated = ReaderBrightness.clamp(level)
        _brightnessLevel.value = updated
        saveBrightnessLevel(updated)
    }

    fun adjustBrightnessByDrag(dragFraction: Float) {
        setBrightnessLevel(ReaderBrightness.adjustByDrag(_brightnessLevel.value, dragFraction))
    }

    private fun normalizePreferences(preferences: EpubPreferences): EpubPreferences {
        val publisherStyles = if (preferences.textAlign != null) false else preferences.publisherStyles
        return preferences.copy(publisherStyles = publisherStyles)
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
            speed = (preferences.speed ?: DEFAULT_TTS_SPEED).toFloat(),
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

        // Finalize current manual session and start a TTS session
        finalizeCurrentSession()

        ttsJob = viewModelScope.launch {
            val bookId = currentBookId ?: return@launch
            currentSessionId = readingRepository.startSession(bookId, isTts = true)
            sessionStartMs = System.currentTimeMillis()
            currentSessionIsTts = true
            pageTurnTimestamps.clear()
            sessionStartProgression = _currentLocator.value?.locations?.totalProgression?.toFloat() ?: sessionStartProgression

            val startLocator = ttsResumeLocator
                ?: (navigator as? VisualNavigator)?.firstVisibleElementLocator()
                ?: _currentLocator.value
            val nav = ttsManager.start(startLocator, _ttsPreferences.value)
            if (nav == null) {
                startManualSession(bookId)
                return@launch
            }
            ttsNavigator = nav

            val binder = ttsServiceBinder ?: TtsMediaService.bind(getApplication())
            ttsServiceBinder = binder
            binder?.openSession(nav)
            navigator?.let { epubNavigator ->
                ttsHighlightSynchronizer.startSync(nav, epubNavigator)
            }

            launch {
                nav.location.collect { location ->
                    ttsResumeLocator = location.tokenLocator ?: location.utteranceLocator
                    // Track TTS progression for stats and persist position
                    val utteranceLoc = location.utteranceLocator
                    _currentLocator.value = utteranceLoc
                    pageTurnTimestamps.add(System.currentTimeMillis())
                    // Persist position during TTS so it survives app kill
                    saveTtsPosition(bookId, utteranceLoc)
                    // Push metadata to the media session for Android Auto now-playing
                    pushNowPlayingMetadata(utteranceLoc)
                }
            }

            nav.play()
            _ttsState.value = TtsPlaybackState.PLAYING

            nav.playback.collect { playback ->
                when (playback.state) {
                    is TtsNavigator.State.Ended -> {
                        ttsResumeLocator = null
                        stopTts()
                    }
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
        when (_ttsState.value) {
            TtsPlaybackState.PLAYING -> {
                // Finalize the current TTS session so paused time isn't counted
                finalizeCurrentSession()
                ttsNavigator?.pause()
            }
            TtsPlaybackState.PAUSED -> {
                // Start a new TTS session on resume
                val bookId = currentBookId
                if (bookId != null) {
                    viewModelScope.launch {
                        currentSessionId = readingRepository.startSession(bookId, isTts = true)
                        sessionStartMs = System.currentTimeMillis()
                        currentSessionIsTts = true
                        pageTurnTimestamps.clear()
                        sessionStartProgression = _currentLocator.value?.locations?.totalProgression?.toFloat() ?: sessionStartProgression
                    }
                }
                ttsNavigator?.play()
            }
            TtsPlaybackState.STOPPED -> startTts()
        }
    }

    fun stopTts() {
        stopTts(restartManualSession = true)
    }

    private fun stopTts(restartManualSession: Boolean) {
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
        _ttsState.value = TtsPlaybackState.STOPPED

        // Finalize TTS session and start a new manual session
        finalizeCurrentSession()
        val bookId = currentBookId ?: return
        if (restartManualSession) {
            viewModelScope.launch {
                startManualSession(bookId)
            }
        }
    }

    fun ttsSkipPrevious() {
        val nav = navigator as? OverflowableNavigator ?: return
        viewModelScope.launch {
            stopTts(restartManualSession = false)
            ttsResumeLocator = null
            nav.goBackward()
            delay(200)
            startTts()
        }
    }

    fun ttsSkipNext() {
        val nav = navigator as? OverflowableNavigator ?: return
        viewModelScope.launch {
            stopTts(restartManualSession = false)
            ttsResumeLocator = null
            nav.goForward()
            delay(200)
            startTts()
        }
    }

    /**
     * Bind to TtsMediaService early so we can intercept Android Auto playback requests.
     * When AA starts TTS for the book the phone UI already has open, we handle it through
     * the normal ViewModel flow (with highlight sync) instead of standalone mode.
     */
    private fun bindTtsServiceForAutoRequests(bookId: String) {
        viewModelScope.launch {
            // Re-use existing binder if we already have one (e.g. from TTS start)
            val binder = ttsServiceBinder ?: TtsMediaService.bind(getApplication()) ?: return@launch
            ttsServiceBinder = binder
            binder.ttsPlaybackRequestListener = TtsMediaService.TtsPlaybackRequestListener { requestedBookId ->
                if (requestedBookId == bookId) {
                    if (_ttsState.value == TtsPlaybackState.STOPPED) startTts()
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Push chapter name, progress, and cover art to the media session so Android Auto's
     * now-playing screen shows useful information during ViewModel-driven TTS.
     */
    private fun pushNowPlayingMetadata(locator: Locator) {
        val binder = ttsServiceBinder ?: return
        val publication = _uiState.value.publication ?: return
        val hrefStr = locator.href.toString()
        val toc = publication.tableOfContents
        val chapterTitle = flattenTocLinks(toc)
            .lastOrNull { hrefStr.startsWith(it.href.toString().substringBefore("#")) }
            ?.title
        val chapterPercent = ((locator.locations.progression ?: 0.0) * 100).toInt()
        val bookPercent = ((locator.locations.totalProgression ?: 0.0) * 100).toInt()
        val title = "${chapterTitle ?: "Reading"} · $chapterPercent%"
        val author = publication.metadata.authors.firstOrNull()?.name
        val bookTitle = publication.metadata.title
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .setAlbumTitle("$bookPercent% · $bookTitle")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        currentCoverArt?.let { metaBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        binder.updateSessionMetadata(metaBuilder.build())
    }

    /**
     * Debounced position save during TTS — writes at most every 5 seconds
     * so the position survives app kill without hammering the DB.
     */
    private fun saveTtsPosition(bookId: String, locator: Locator) {
        val now = System.currentTimeMillis()
        if (now - lastTtsSaveMs < 5_000) return
        lastTtsSaveMs = now
        ttsPositionSaveJob?.cancel()
        ttsPositionSaveJob = viewModelScope.launch {
            readingRepository.savePosition(bookId, locator.toJSON().toString())
            bookRepository.updateProgression(
                bookId,
                locator.locations.totalProgression?.toFloat() ?: 0f
            )
            if (isWebBook) {
                val chapterTitle = _currentChapter.value?.title
                val chapterProgression = locator.locations.progression?.toFloat()
                if (chapterTitle != null) {
                    bookRepository.updateChapterPosition(bookId, chapterTitle, chapterProgression)
                }
            }
        }
    }

    private fun finalizeCurrentSession() {
        val sessionId = currentSessionId ?: return
        val capturedTimestamps = pageTurnTimestamps.toList()
        val capturedStartMs = sessionStartMs
        val capturedStartProgression = sessionStartProgression
        val capturedIsTts = currentSessionIsTts
        val capturedLocator = _currentLocator.value
        val capturedEndProgression =
            capturedLocator?.locations?.totalProgression?.toFloat() ?: capturedStartProgression
        val capturedChapterTitle = _currentChapter.value?.title
        val capturedChapterProgression = capturedLocator?.locations?.progression?.toFloat()
        val capturedIsWebBook = isWebBook
        val bookId = currentBookId ?: return
        currentSessionId = null
        viewModelScope.launch {
            withContext(NonCancellable) {
                // Always persist the final position so it survives app kill
                if (capturedLocator != null) {
                    readingRepository.savePosition(bookId, capturedLocator.toJSON().toString())
                    bookRepository.updateProgression(
                        bookId,
                        capturedEndProgression
                    )
                    if (capturedIsWebBook && capturedChapterTitle != null) {
                        bookRepository.updateChapterPosition(
                            bookId, capturedChapterTitle, capturedChapterProgression
                        )
                    }
                }
                val wordCount = bookRepository.getWordCount(bookId)
                readingRepository.finalizeSession(
                    sessionId = sessionId,
                    pageTurnTimestampsMs = capturedTimestamps,
                    sessionStartMs = capturedStartMs,
                    isTts = capturedIsTts,
                    progressionStart = capturedStartProgression,
                    progressionEnd = capturedEndProgression,
                    bookWordCount = wordCount
                )
            }
        }
    }

    private suspend fun startManualSession(bookId: String) {
        currentSessionId = readingRepository.startSession(bookId)
        sessionStartMs = System.currentTimeMillis()
        currentSessionIsTts = false
        pageTurnTimestamps.clear()
        sessionStartProgression =
            _currentLocator.value?.locations?.totalProgression?.toFloat() ?: sessionStartProgression
    }

    fun finalizeSession() {
        finalizeCurrentSession()
        if (app.credentialsManager.hasCredentials) {
            SyncScheduler.scheduleImmediateSync(app)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop TTS without starting a new manual session
        ttsJob?.cancel()
        ttsJob = null
        ttsHighlightSynchronizer.stopSync()
        ttsNavigator?.close()
        ttsNavigator = null
        ttsManager.stop()
        ttsServiceBinder?.ttsPlaybackRequestListener = null
        ttsServiceBinder?.closeSession()
        ttsServiceBinder = null
        _ttsState.value = TtsPlaybackState.STOPPED
        finalizeCurrentSession()
    }
}
