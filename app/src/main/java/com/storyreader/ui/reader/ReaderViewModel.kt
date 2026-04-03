package com.storyreader.ui.reader

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.sync.SyncScheduler
import com.storyreader.reader.epub.EpubRepository
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
import org.readium.r2.navigator.OverflowableNavigator
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

enum class TtsPlaybackState { STOPPED, PLAYING, PAUSED }

data class TtsSettingsUiState(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val enginePackageName: String? = null,
    val availableEngines: List<com.storyreader.reader.tts.TtsEngineOption> = emptyList(),
    val selectedVoiceId: String? = null,
    val availableVoices: List<com.storyreader.reader.tts.TtsVoiceOption> = emptyList(),
    val languageLabel: String = java.util.Locale.getDefault().displayName,
    val isLoadingVoices: Boolean = false
)

data class NextBookInfo(
    val bookId: String,
    val title: String,
    val seriesName: String
)

data class ReaderUiState(
    val publication: Publication? = null,
    val book: BookEntity? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialLocatorJson: String? = null
)

private const val TAG = "ReaderViewModel"
private const val END_OF_BOOK_THRESHOLD = 0.99
private const val PREFS_NAME = "reader_preferences"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_THEME = "theme"
private const val KEY_FONT_FAMILY = "font_family"
private const val KEY_IS_NIGHT = "is_night_theme"
private const val KEY_SCROLL = "scroll_mode"
private const val KEY_TEXT_ALIGN = "text_align"
private const val KEY_BRIGHTNESS_LEVEL = "brightness_level"

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

    private val _brightnessLevel = MutableStateFlow(loadSavedBrightnessLevel())
    val brightnessLevel: StateFlow<Float> = _brightnessLevel.asStateFlow()

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _currentChapter = MutableStateFlow<Link?>(null)
    val currentChapter: StateFlow<Link?> = _currentChapter.asStateFlow()

    private val _showBars = MutableStateFlow(true)
    val showBars: StateFlow<Boolean> = _showBars.asStateFlow()

    private val _showNextBookPrompt = MutableStateFlow<NextBookInfo?>(null)
    val showNextBookPrompt: StateFlow<NextBookInfo?> = _showNextBookPrompt.asStateFlow()
    private var nextBookChecked = false

    private var navigator: EpubNavigatorFragment? = null
    private var locatorJob: Job? = null
    private var preferencesJob: Job? = null
    private var savePositionJob: Job? = null
    private var currentBookId: String? = null
    private var currentCoverArt: ByteArray? = null
    private var isWebBook: Boolean = false

    // --- Delegate for TTS controller ---
    private val ttsDelegate = object : ReaderTtsDelegate {
        override val currentLocator: Locator? get() = _currentLocator.value
        override val navigator: EpubNavigatorFragment? get() = this@ReaderViewModel.navigator
        override val publication: Publication? get() = _uiState.value.publication
        override val currentChapterTitle: String? get() = _currentChapter.value?.title
        override val coverArt: ByteArray? get() = currentCoverArt
        override val bookId: String? get() = currentBookId
        override val isWebBook: Boolean get() = this@ReaderViewModel.isWebBook

        override fun onTtsSessionStarting() {
            sessionTracker.finalize(
                bookId = currentBookId ?: return,
                currentLocator = _currentLocator.value,
                currentChapterTitle = _currentChapter.value?.title,
                isWebBook = isWebBook
            )
            sessionTracker.startSession(
                bookId = currentBookId ?: return,
                isTts = true,
                currentProgression = _currentLocator.value?.locations?.totalProgression?.toFloat()
            )
        }

        override fun onTtsSessionEnding(restartManualSession: Boolean) {
            sessionTracker.finalize(
                bookId = currentBookId ?: return,
                currentLocator = _currentLocator.value,
                currentChapterTitle = _currentChapter.value?.title,
                isWebBook = isWebBook
            )
            if (restartManualSession) {
                sessionTracker.startSession(
                    bookId = currentBookId ?: return,
                    isTts = false,
                    currentProgression = _currentLocator.value?.locations?.totalProgression?.toFloat()
                )
            }
        }

        override fun onTtsPaused() {
            sessionTracker.onPause()
        }

        override fun onTtsResumed() {
            if (sessionTracker.onResume()) {
                // Pause was too long — split into a new session
                sessionTracker.finalize(
                    bookId = currentBookId ?: return,
                    currentLocator = _currentLocator.value,
                    currentChapterTitle = _currentChapter.value?.title,
                    isWebBook = isWebBook
                )
                sessionTracker.startSession(
                    bookId = currentBookId ?: return,
                    isTts = true,
                    currentProgression = _currentLocator.value?.locations?.totalProgression?.toFloat()
                )
            }
        }

        override fun onTtsLocatorUpdate(locator: Locator) {
            _currentLocator.value = locator
        }

        override fun onTtsPageTurn() {
            sessionTracker.recordPageTurn()
        }

        override fun savePosition(bookId: String, locator: Locator) {
            savePositionForLocator(bookId, locator)
        }
    }

    private val sessionTracker = ReadingSessionTracker(readingRepository, bookRepository, viewModelScope)

    private val ttsController = ReaderTtsController(application, viewModelScope, prefStore, ttsDelegate)
    val ttsState: StateFlow<TtsPlaybackState> = ttsController.ttsState
    val ttsSettings: StateFlow<TtsSettingsUiState> = ttsController.ttsSettings

    init {
        ttsController.refreshTtsCatalog()
    }

    fun openBook(bookId: String) {
        if (currentBookId == bookId) return
        currentBookId = bookId
        nextBookChecked = false
        _showNextBookPrompt.value = null

        viewModelScope.launch {
            _uiState.value = ReaderUiState(isLoading = true)

            val savedPosition = readingRepository.observeLatestPosition(bookId).firstOrNull()
            val uri = bookId.toUri()
            val bookEntity = app.database.bookDao().getByIdOnce(bookId)
            isWebBook = bookEntity?.sourceType == "web"

            sessionTracker.sessionStartProgression = savedPosition?.let { pos ->
                try {
                    val json = org.json.JSONObject(pos.locatorJson)
                    val locations = json.optJSONObject("locations")
                    locations?.optDouble("totalProgression", 0.0)?.toFloat() ?: 0f
                } catch (e: Exception) { Log.w(TAG, "Failed to parse saved locator JSON", e); 0f }
            } ?: 0f

            epubRepository.openPublication(uri)
                .onSuccess { publication ->
                    val initialLocator = savedPosition?.locatorJson
                        ?: resolveChapterFallback(publication, bookEntity)

                    _uiState.value = ReaderUiState(
                        publication = publication,
                        isLoading = false,
                        initialLocatorJson = initialLocator
                    )
                    sessionTracker.startSession(bookId)
                    currentCoverArt = bookEntity?.coverUri?.let { path ->
                        try {
                            TtsMediaService.downsampleArtwork(java.io.File(path).readBytes())
                        } catch (e: Exception) { Log.w(TAG, "Failed to load cover art", e); null }
                    }
                    ttsController.initialize(publication)
                    ttsController.bindServiceForAutoRequests(bookId)
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
        if (ttsController.ttsState.value == TtsPlaybackState.STOPPED) {
            ttsController.clearResumeLocator()
        }
        sessionTracker.recordPageTurn()

        resolveCurrentChapter(locator)
        if (!ttsController.isStandaloneTtsActive) {
            savePositionJob?.cancel()
            savePositionJob = viewModelScope.launch {
                delay(300)
                savePositionForLocator(bookId, locator)
            }
        }

        checkEndOfBook(bookId, locator)
    }

    private fun checkEndOfBook(bookId: String, locator: Locator) {
        val totalProgression = locator.locations.totalProgression ?: return
        if (totalProgression < END_OF_BOOK_THRESHOLD || nextBookChecked) return
        nextBookChecked = true

        viewModelScope.launch {
            val book = app.database.bookDao().getByIdOnce(bookId) ?: return@launch
            val series = book.series ?: return@launch
            val index = book.seriesIndex ?: return@launch
            val nextBook = app.database.bookDao().getNextBookInSeries(series, index) ?: return@launch
            _showNextBookPrompt.value = NextBookInfo(
                bookId = nextBook.bookId,
                title = nextBook.title,
                seriesName = series
            )
        }
    }

    fun dismissNextBookPrompt() {
        _showNextBookPrompt.value = null
    }

    private fun savePositionForLocator(bookId: String, locator: Locator) {
        viewModelScope.launch {
            readingRepository.savePosition(bookId, locator.toJSON().toString())
            bookRepository.updateProgression(
                bookId,
                locator.locations.totalProgression?.toFloat() ?: 0f
            )
            val chapterTitle = _currentChapter.value?.title
            val chapterProgression = locator.locations.progression?.toFloat()
            if (isWebBook && chapterTitle != null) {
                bookRepository.updateChapterPosition(bookId, chapterTitle, chapterProgression)
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

        ttsController.onNavigatorReady(nav)

        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (ttsController.handleTapDuringTts()) return true
                val viewWidth = nav.publicationView.width.toFloat()
                val viewHeight = nav.publicationView.height.toFloat()
                val verticalMargin = viewHeight * 0.07f
                if (event.point.y < verticalMargin || event.point.y > viewHeight - verticalMargin) {
                    return true
                }
                val edgeSize = viewWidth * 0.3f
                if (event.point.x in edgeSize..(viewWidth - edgeSize)) {
                    _showBars.value = !_showBars.value
                    return true
                }
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
        _currentChapter.value = link
        val nav = navigator ?: return
        val pub = _uiState.value.publication ?: return
        val locator = pub.locatorFromLink(link) ?: return
        viewModelScope.launch { nav.go(locator) }
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

    // --- Visual preferences ---

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

    private fun loadSavedBrightnessLevel(): Float =
        ReaderBrightness.clamp(
            if (prefStore.contains(KEY_BRIGHTNESS_LEVEL)) {
                prefStore.getFloat(KEY_BRIGHTNESS_LEVEL, 1f)
            } else {
                1f
            }
        )

    private fun saveBrightnessLevel(level: Float) {
        prefStore.edit {
            putFloat(KEY_BRIGHTNESS_LEVEL, ReaderBrightness.clamp(level))
        }
    }

    // --- TTS delegation ---

    fun startTts() = ttsController.startTts()
    fun ttsPlayPause() = ttsController.ttsPlayPause()
    fun stopTts() = ttsController.stopTts()
    fun ttsSkipPrevious() = ttsController.ttsSkipPrevious()
    fun ttsSkipNext() = ttsController.ttsSkipNext()
    fun updateTtsSpeed(value: Float) = ttsController.updateTtsSpeed(value)
    fun updateTtsPitch(value: Float) = ttsController.updateTtsPitch(value)
    fun selectTtsVoice(voiceId: String?) = ttsController.selectTtsVoice(voiceId)
    fun selectTtsEngine(packageName: String?) = ttsController.selectTtsEngine(packageName)
    fun openSystemTtsSettings() = ttsController.openSystemTtsSettings()

    // --- Session lifecycle ---

    fun onAppPaused() {
        if (ttsController.ttsState.value != TtsPlaybackState.PLAYING) {
            sessionTracker.onPause()
        }
    }

    fun onAppResumed() {
        if (ttsController.ttsState.value != TtsPlaybackState.PLAYING) {
            if (sessionTracker.onResume()) {
                // Pause was too long — split into a new session
                finalizeSession()
                val bookId = currentBookId ?: return
                val isTts = ttsController.ttsState.value == TtsPlaybackState.PAUSED
                sessionTracker.startSession(
                    bookId = bookId,
                    isTts = isTts,
                    currentProgression = _currentLocator.value?.locations?.totalProgression?.toFloat()
                )
            }
        }
    }

    fun finalizeSession() {
        sessionTracker.finalize(
            bookId = currentBookId ?: return,
            currentLocator = _currentLocator.value,
            currentChapterTitle = _currentChapter.value?.title,
            isWebBook = isWebBook
        )
        if (app.credentialsManager.hasCredentials) {
            SyncScheduler.scheduleImmediateSync(app)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsController.onCleared()
        sessionTracker.finalize(
            bookId = currentBookId ?: return,
            currentLocator = _currentLocator.value,
            currentChapterTitle = _currentChapter.value?.title,
            isWebBook = isWebBook
        )
    }
}
