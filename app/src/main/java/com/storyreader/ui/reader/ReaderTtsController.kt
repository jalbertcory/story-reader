package com.storyreader.ui.reader

import android.app.Application
import android.util.Log
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.MediaMetadata
import com.storyreader.reader.tts.TtsCatalog
import com.storyreader.reader.tts.TtsHighlightSynchronizer
import com.storyreader.reader.tts.TtsManager
import com.storyreader.reader.tts.TtsMediaService
import com.storyreader.reader.tts.TtsVoiceOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesSerializer
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import java.util.Locale

private const val TAG = "ReaderTtsController"
private const val KEY_TTS_PREFS = "tts_prefs_json"
private const val KEY_TTS_ENGINE = "tts_engine"
private const val DEFAULT_TTS_SPEED = 1.5

/**
 * Callback interface for events the TTS controller needs from the reader.
 */
interface ReaderTtsDelegate {
    val currentLocator: Locator?
    val navigator: EpubNavigatorFragment?
    val publication: Publication?
    val currentChapterTitle: String?
    val coverArt: ByteArray?
    val bookId: String?
    val isWebBook: Boolean

    fun onTtsSessionStarting()
    fun onTtsSessionEnding(restartManualSession: Boolean)
    fun onTtsLocatorUpdate(locator: Locator)
    fun onTtsPageTurn()
    fun savePosition(bookId: String, locator: Locator)
}

@OptIn(ExperimentalReadiumApi::class)
class ReaderTtsController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val prefStore: SharedPreferences,
    private val delegate: ReaderTtsDelegate
) {

    private val ttsManager = TtsManager(application)
    private val ttsHighlightSynchronizer = TtsHighlightSynchronizer(scope)
    private val ttsCatalog = TtsCatalog(application)
    private val ttsPreferencesSerializer = AndroidTtsPreferencesSerializer()

    private var ttsNavigator: AndroidTtsNavigator? = null
    private var ttsServiceBinder: TtsMediaService.LocalBinder? = null
    private var ttsJob: Job? = null
    var ttsResumeLocator: Locator? = null
        private set
    private var ttsCatalogJob: Job? = null
    private var ttsPositionSaveJob: Job? = null
    private var lastTtsSaveMs: Long = 0L

    private val _ttsState = MutableStateFlow(TtsPlaybackState.STOPPED)
    val ttsState: StateFlow<TtsPlaybackState> = _ttsState.asStateFlow()

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

    fun initialize(publication: Publication) {
        ttsManager.initialize(publication, selectedTtsEnginePackageName)
        refreshTtsCatalog()
    }

    fun refreshTtsCatalog() {
        ttsCatalogJob?.cancel()
        val language = currentTtsLanguage()
        _ttsSettings.value = _ttsSettings.value.copy(
            enginePackageName = selectedTtsEnginePackageName,
            selectedVoiceId = selectedVoiceFor(language),
            languageLabel = language.locale.displayName,
            isLoadingVoices = true
        )
        ttsCatalogJob = scope.launch {
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

    fun startTts() {
        if (_ttsState.value != TtsPlaybackState.STOPPED) return

        delegate.onTtsSessionStarting()

        ttsJob = scope.launch {
            val bookId = delegate.bookId ?: return@launch

            val startLocator = ttsResumeLocator
                ?: (delegate.navigator as? VisualNavigator)?.firstVisibleElementLocator()
                ?: delegate.currentLocator
            val nav = ttsManager.start(startLocator, _ttsPreferences.value)
            if (nav == null) {
                delegate.onTtsSessionEnding(restartManualSession = true)
                return@launch
            }
            ttsNavigator = nav

            val binder = ttsServiceBinder ?: TtsMediaService.bind(application)
            ttsServiceBinder = binder
            binder?.openSession(nav)
            delegate.navigator?.let { epubNavigator ->
                ttsHighlightSynchronizer.startSync(nav, epubNavigator)
            }

            launch {
                nav.location.collect { location ->
                    ttsResumeLocator = location.tokenLocator ?: location.utteranceLocator
                    val utteranceLoc = location.utteranceLocator
                    delegate.onTtsLocatorUpdate(utteranceLoc)
                    delegate.onTtsPageTurn()
                    saveTtsPosition(bookId, utteranceLoc)
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
                delegate.onTtsSessionEnding(restartManualSession = false)
                ttsNavigator?.pause()
            }
            TtsPlaybackState.PAUSED -> {
                delegate.onTtsSessionStarting()
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
        delegate.navigator?.let { epubNavigator ->
            scope.launch {
                ttsHighlightSynchronizer.clearHighlights(epubNavigator)
            }
        }
        ttsNavigator?.close()
        ttsNavigator = null
        ttsManager.stop()
        ttsServiceBinder?.closeSession()
        _ttsState.value = TtsPlaybackState.STOPPED

        delegate.onTtsSessionEnding(restartManualSession)
    }

    fun ttsSkipPrevious() {
        val nav = delegate.navigator as? OverflowableNavigator ?: return
        scope.launch {
            stopTts(restartManualSession = false)
            ttsResumeLocator = null
            nav.goBackward()
            delay(200)
            startTts()
        }
    }

    fun ttsSkipNext() {
        val nav = delegate.navigator as? OverflowableNavigator ?: return
        scope.launch {
            stopTts(restartManualSession = false)
            ttsResumeLocator = null
            nav.goForward()
            delay(200)
            startTts()
        }
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
        delegate.publication?.let { publication ->
            ttsManager.initialize(publication, selectedTtsEnginePackageName)
        }
        refreshTtsCatalog()
    }

    fun openSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            application.startActivity(intent)
        }.onFailure {
            TtsManager.requestInstallVoice(application)
        }
    }

    fun onNavigatorReady(epubNavigator: EpubNavigatorFragment) {
        ttsNavigator?.let { activeTtsNavigator ->
            ttsHighlightSynchronizer.startSync(activeTtsNavigator, epubNavigator)
        }
    }

    fun handleTapDuringTts(): Boolean {
        if (_ttsState.value != TtsPlaybackState.STOPPED) {
            ttsPlayPause()
            return true
        }
        return false
    }

    fun bindServiceForAutoRequests(bookId: String) {
        scope.launch {
            val binder = ttsServiceBinder ?: TtsMediaService.bind(application) ?: return@launch
            ttsServiceBinder = binder
            binder.ttsPlaybackRequestListener =
                TtsMediaService.TtsPlaybackRequestListener { requestedBookId ->
                    if (requestedBookId == bookId) {
                        if (_ttsState.value == TtsPlaybackState.STOPPED) startTts()
                        true
                    } else {
                        false
                    }
                }
        }
    }

    /** Whether Android Auto is driving TTS independently. */
    val isStandaloneTtsActive: Boolean
        get() = ttsServiceBinder?.isStandaloneTtsActive == true

    fun clearResumeLocator() {
        ttsResumeLocator = null
    }

    fun onCleared() {
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
    }

    // --- Private helpers ---

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

    private fun saveTtsPreferences() {
        prefStore.edit {
            putString(KEY_TTS_PREFS, ttsPreferencesSerializer.serialize(_ttsPreferences.value))
            putString(KEY_TTS_ENGINE, selectedTtsEnginePackageName)
        }
    }

    private fun loadSavedTtsPreferences(): AndroidTtsPreferences {
        val serialized = prefStore.getString(KEY_TTS_PREFS, null)
            ?: return AndroidTtsPreferences(speed = DEFAULT_TTS_SPEED)
        return runCatching { ttsPreferencesSerializer.deserialize(serialized) }
            .onFailure { e -> Log.w(TAG, "Failed to deserialize TTS preferences", e) }
            .getOrDefault(AndroidTtsPreferences(speed = DEFAULT_TTS_SPEED))
    }

    private fun currentTtsLanguage(): Language =
        delegate.publication?.metadata?.language ?: Language(Locale.getDefault())

    private fun selectedVoiceFor(language: Language): String? =
        _ttsPreferences.value.voices?.get(language)?.value

    private fun voiceMatchesLanguage(voice: TtsVoiceOption, language: Language): Boolean {
        val targetTag = language.locale.toLanguageTag()
        return voice.languageTag.equals(targetTag, ignoreCase = true) ||
            voice.languageTag.startsWith("${language.locale.language}-", ignoreCase = true) ||
            voice.languageTag.equals(language.locale.language, ignoreCase = true)
    }

    private fun saveTtsPosition(bookId: String, locator: Locator) {
        val now = System.currentTimeMillis()
        if (now - lastTtsSaveMs < 5_000) return
        lastTtsSaveMs = now
        ttsPositionSaveJob?.cancel()
        ttsPositionSaveJob = scope.launch {
            delegate.savePosition(bookId, locator)
        }
    }

    private fun pushNowPlayingMetadata(locator: Locator) {
        val binder = ttsServiceBinder ?: return
        val publication = delegate.publication ?: return
        val toc = publication.tableOfContents
        val allLinks = flattenTocLinks(toc)
        val chapterMatch = matchChapterByHref(locator, allLinks)
        val chapterTitle = when (chapterMatch) {
            is ChapterMatch.Single -> chapterMatch.link.title
            is ChapterMatch.Multiple -> resolveByProgression(publication, chapterMatch.candidates, locator)?.title
            is ChapterMatch.NormalizedFallback -> chapterMatch.link.title
            ChapterMatch.None -> null
        }
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
        delegate.coverArt?.let { metaBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        binder.updateSessionMetadata(metaBuilder.build())
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
}
