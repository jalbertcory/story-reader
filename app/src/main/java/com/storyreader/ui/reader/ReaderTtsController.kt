package com.storyreader.ui.reader

import android.app.Application
import android.util.Log
import com.storyreader.util.DebugLog
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.MediaMetadata
import com.storyreader.data.sync.KEY_SYNC_TTS_UPDATED_AT
import com.storyreader.data.sync.KEY_TTS_ENGINE
import com.storyreader.data.sync.KEY_TTS_PREFS
import com.storyreader.data.sync.KEY_TTS_TEXT_FILTER
import com.storyreader.reader.tts.TtsTextFilter
import com.storyreader.reader.tts.TtsCatalog
import com.storyreader.reader.tts.TtsHighlightSynchronizer
import com.storyreader.reader.tts.TtsManager
import com.storyreader.reader.tts.TtsMediaService
import com.storyreader.reader.tts.TtsVoiceOption
import com.storyreader.reader.tts.resolveTtsStartLocator
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
import org.json.JSONObject
import java.util.Locale

private const val TAG = "ReaderTtsController"
private const val TRACE_TAG = "TTS_TRACE"
private const val DEFAULT_TTS_SPEED = 1.5
private const val PAGE_START_CANDIDATE_SCRIPT = """
(() => {
  const selectors = [
    'p', 'li', 'blockquote', 'pre', 'figcaption', 'dt', 'dd',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6'
  ];
  const viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
  const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;

  const hasReadableText = (element) => {
    const text = (element.innerText || '').replace(/\s+/g, ' ').trim();
    return text.length > 0 ? text : null;
  };

  const normalizeText = (text) => (text || '').replace(/\s+/g, ' ').trim();

  const normalizeRect = (rect) => ({
    top: rect.top,
    bottom: rect.bottom,
    left: rect.left,
    right: rect.right,
    width: rect.width,
    height: rect.height
  });

  const rectIntersectsViewport = (rect) =>
    rect.bottom > 0 && rect.top < viewportHeight &&
    rect.right > 0 && rect.left < viewportWidth;

  const rectFullyInViewport = (rect) =>
    rect.top >= 0 && rect.bottom <= viewportHeight &&
    rect.left >= 0 && rect.right <= viewportWidth;

  const rectStartsInViewport = (rect) =>
    rect.top >= 0 && rect.top < viewportHeight &&
    rect.left >= 0 && rect.left < viewportWidth;

  const cssPath = (element) => {
    if (!element) return null;
    if (element.id) return '#' + element.id;
    const segments = [];
    let current = element;
    while (current && current.nodeType === Node.ELEMENT_NODE && current.tagName.toLowerCase() !== 'html') {
      const tag = current.tagName.toLowerCase();
      let index = 1;
      let sibling = current.previousElementSibling;
      while (sibling) {
        if (sibling.tagName === current.tagName) index += 1;
        sibling = sibling.previousElementSibling;
      }
      segments.unshift(tag + ':nth-of-type(' + index + ')');
      current = current.parentElement;
    }
    return segments.join(' > ');
  };

  const rangeForPoint = (x, y) => {
    if (document.caretPositionFromPoint) {
      const pos = document.caretPositionFromPoint(x, y);
      if (pos && pos.offsetNode) {
        const range = document.createRange();
        range.setStart(pos.offsetNode, pos.offset);
        range.collapse(true);
        return range;
      }
    }
    if (document.caretRangeFromPoint) {
      const range = document.caretRangeFromPoint(x, y);
      if (range) return range;
    }
    return null;
  };

  const extractSentenceHint = (element, visibleRect) => {
    if (!element || !visibleRect) return null;

    const probeXs = [];
    const leftStart = Math.max(visibleRect.left + 2, 2);
    const leftEnd = Math.min(visibleRect.right - 2, leftStart + 96);
    for (let x = leftStart; x <= leftEnd; x += 12) probeXs.push(x);
    if (probeXs.length === 0) probeXs.push(leftStart);

    const probeYs = [];
    const topStart = Math.max(visibleRect.top + 2, 2);
    const topEnd = Math.min(visibleRect.bottom - 2, topStart + 24);
    for (let y = topStart; y <= topEnd; y += 6) probeYs.push(y);
    if (probeYs.length === 0) probeYs.push(topStart);

    let pointRange = null;
    for (const y of probeYs) {
      for (const x of probeXs) {
        const candidate = rangeForPoint(x, y);
        if (!candidate) continue;
        const container = candidate.startContainer.nodeType === Node.TEXT_NODE
          ? candidate.startContainer.parentElement
          : candidate.startContainer;
        if (container && element.contains(container)) {
          pointRange = candidate;
          break;
        }
      }
      if (pointRange) break;
    }
    if (!pointRange) return null;

    const blockRange = document.createRange();
    blockRange.selectNodeContents(element);

    const remainingRange = blockRange.cloneRange();
    remainingRange.setStart(pointRange.startContainer, pointRange.startOffset);
    const remainingText = normalizeText(remainingRange.toString());
    if (!remainingText) return null;

    const beforeRange = blockRange.cloneRange();
    beforeRange.setEnd(pointRange.startContainer, pointRange.startOffset);
    const beforeText = normalizeText(beforeRange.toString());
    const beforeTail = beforeText.slice(-1);
    const startsMidSentence = !!beforeTail && !/[.!?]/.test(beforeTail);

    const sentences = (remainingText.match(/[^.!?]+[.!?]+["')\]]*|[^.!?]+$/g) || [])
      .map(normalizeText)
      .filter(Boolean);
    if (sentences.length === 0) return null;

    const sentence = startsMidSentence && sentences.length > 1
      ? sentences[1]
      : sentences[0];
    return sentence ? sentence.slice(0, 160) : null;
  };

  const candidates = Array.from(document.querySelectorAll(selectors.join(',')));
  let firstVisible = null;
  let startsOnPage = null;
  let firstCompleteBlock = null;
  let sentenceStart = null;

  for (const element of candidates) {
    const text = hasReadableText(element);
    if (!text) continue;

    const rects = Array.from(element.getClientRects())
      .map(normalizeRect)
      .filter((rect) => rect.width > 0 && rect.height > 0);
    if (rects.length === 0) continue;

    const visibleRects = rects.filter(rectIntersectsViewport);
    if (visibleRects.length === 0) continue;

    const firstRect = rects[0];
    const firstVisibleRect = visibleRects[0];
    const firstRectStartsOnPage = rectStartsInViewport(firstRect);

    const payload = {
      kind: 'visible',
      cssSelector: cssPath(element),
      text: text.slice(0, 160),
      top: firstVisibleRect.top,
      bottom: firstVisibleRect.bottom,
      left: firstVisibleRect.left,
      right: firstVisibleRect.right,
      firstRectTop: firstRect.top,
      firstRectLeft: firstRect.left,
      startedOnPage: rectStartsInViewport(firstRect)
    };

    if (!firstVisible) firstVisible = payload;

    if (!firstRectStartsOnPage && !sentenceStart) {
      const hint = extractSentenceHint(element, firstVisibleRect);
      if (hint) {
        sentenceStart = {
          ...payload,
          kind: 'sentence',
          text: hint,
          utteranceHint: hint
        };
      }
    }
    if (firstRectStartsOnPage && !startsOnPage) {
      payload.kind = 'pageStart';
      startsOnPage = payload;
    }

    const fullyVisibleRect = visibleRects.find(rectFullyInViewport);
    if (firstRectStartsOnPage && fullyVisibleRect && !firstCompleteBlock) {
      payload.kind = 'full';
      payload.top = fullyVisibleRect.top;
      payload.bottom = fullyVisibleRect.bottom;
      payload.left = fullyVisibleRect.left;
      payload.right = fullyVisibleRect.right;
      firstCompleteBlock = payload;
    }
  }

  return sentenceStart || firstCompleteBlock || startsOnPage || firstVisible || null;
})()
"""

private data class FreshTtsStart(
    val locator: Locator?,
    val kind: String,
    val snippet: String?,
    val utteranceHint: String?,
)

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
    fun onTtsPaused()
    fun onTtsResumed()
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
    private var ttsServiceConnection: android.content.ServiceConnection? = null
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
    private var _textFilter = MutableStateFlow(loadSavedTextFilter())

    private val _ttsSettings = MutableStateFlow(
        TtsSettingsUiState(
            speed = (_ttsPreferences.value.speed ?: DEFAULT_TTS_SPEED).toFloat(),
            pitch = (_ttsPreferences.value.pitch ?: 1.0).toFloat(),
            enginePackageName = selectedTtsEnginePackageName,
            selectedVoiceId = selectedVoiceFor(currentTtsLanguage()),
            textFilter = _textFilter.value
        )
    )
    val ttsSettings: StateFlow<TtsSettingsUiState> = _ttsSettings.asStateFlow()

    fun initialize(publication: Publication) {
        ttsManager.initialize(publication, selectedTtsEnginePackageName, _textFilter.value)
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
        DebugLog.d(TRACE_TAG) { "startTts: state=${_ttsState.value}, resume=${ttsResumeLocator.debugSummary()}" }
        if (_ttsState.value != TtsPlaybackState.STOPPED) return

        delegate.onTtsSessionStarting()

        ttsJob = scope.launch {
            val bookId = delegate.bookId ?: return@launch

            val freshStart = if (ttsResumeLocator != null) {
                null
            } else {
                val pageLocator = delegate.currentLocator
                    ?: (delegate.navigator as? VisualNavigator)?.firstVisibleElementLocator()
                resolveFreshStart(pageLocator)
            }

            val startLocator = if (ttsResumeLocator != null) {
                DebugLog.d(TRACE_TAG) { "startTts: resuming from saved locator ${ttsResumeLocator.debugSummary()}" }
                ttsResumeLocator
            } else {
                freshStart?.locator
            }
            val nav = ttsManager.start(startLocator, _ttsPreferences.value)
            if (nav == null) {
                delegate.onTtsSessionEnding(restartManualSession = true)
                return@launch
            }
            ttsNavigator = nav
            alignNavigatorToFreshStart(nav, freshStart)

            if (ttsServiceBinder == null) {
                val result = TtsMediaService.bind(application)
                ttsServiceBinder = result?.binder
                ttsServiceConnection = result?.connection
            }
            ttsServiceBinder?.openSession(nav)
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
                delegate.onTtsPaused()
                ttsNavigator?.pause()
            }
            TtsPlaybackState.PAUSED -> {
                delegate.onTtsResumed()
                ttsNavigator?.play()
            }
            TtsPlaybackState.STOPPED -> startTts()
        }
    }

    fun stopTts() {
        DebugLog.d(TRACE_TAG) { "stopTts: clearing resume ${ttsResumeLocator.debugSummary()}" }
        ttsResumeLocator = null
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
            ttsManager.initialize(publication, selectedTtsEnginePackageName, _textFilter.value)
        }
        refreshTtsCatalog()
    }

    fun updateTextFilter(filter: TtsTextFilter) {
        _textFilter.value = filter
        _ttsSettings.value = _ttsSettings.value.copy(textFilter = filter)
        saveTextFilter()
        // Reinitialize the engine so the new filter takes effect on next playback.
        delegate.publication?.let { publication ->
            ttsManager.initialize(publication, selectedTtsEnginePackageName, filter)
        }
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
            if (ttsServiceBinder == null) {
                val result = TtsMediaService.bind(application) ?: return@launch
                ttsServiceBinder = result.binder
                ttsServiceConnection = result.connection
            }
            val binder = ttsServiceBinder ?: return@launch
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
        ttsServiceConnection?.let { conn ->
            runCatching { application.unbindService(conn) }
        }
        ttsServiceConnection = null
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
            putLong(KEY_SYNC_TTS_UPDATED_AT, System.currentTimeMillis())
        }
    }

    private fun loadSavedTextFilter(): TtsTextFilter =
        TtsTextFilter.fromJsonString(prefStore.getString(KEY_TTS_TEXT_FILTER, null))

    private fun saveTextFilter() {
        prefStore.edit {
            putString(KEY_TTS_TEXT_FILTER, _textFilter.value.toJson().toString())
            putLong(KEY_SYNC_TTS_UPDATED_AT, System.currentTimeMillis())
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

    private suspend fun resolveFreshStart(pageLocator: Locator?): FreshTtsStart {
        val navigator = delegate.navigator
        val domStart = if (navigator != null) {
            findCurrentPageStartLocator(navigator, pageLocator)
        } else {
            null
        }
        val resolvedLocator = domStart?.locator
            ?: delegate.publication?.resolveTtsStartLocator(pageLocator)
            ?: pageLocator
        val resolved = FreshTtsStart(
            locator = resolvedLocator,
            kind = domStart?.kind ?: "fallback",
            snippet = domStart?.snippet,
            utteranceHint = domStart?.utteranceHint
        )
        DebugLog.d(TRACE_TAG) { "resolveFreshStart: kind=${resolved.kind}, page=${pageLocator.debugSummary()}, dom=${domStart.debugSummary()}, resolved=${resolved.locator.debugSummary()}" }
        return resolved
    }

    private suspend fun findCurrentPageStartLocator(
        navigator: EpubNavigatorFragment,
        pageLocator: Locator?
    ): FreshTtsStart? {
        pageLocator ?: return null
        val rawResult = navigator.evaluateJavascript(PAGE_START_CANDIDATE_SCRIPT) ?: return null
        val candidate = parseJavascriptObject(rawResult) ?: return null
        val cssSelector = candidate.optString("cssSelector").takeIf { it.isNotBlank() } ?: return null
        val kind = candidate.optString("kind").takeIf { it.isNotBlank() } ?: "unknown"
        val snippet = candidate.optString("text").takeIf { it.isNotBlank() }
        val utteranceHint = candidate.optString("utteranceHint").takeIf { it.isNotBlank() }

        val locator = pageLocator.copy(
            locations = pageLocator.locations.copy(
                otherLocations = pageLocator.locations.otherLocations + ("cssSelector" to cssSelector)
            )
        )
        DebugLog.d(TRACE_TAG) { "findCurrentPageStartLocator: kind=$kind, cssSelector=$cssSelector" }
        return FreshTtsStart(
            locator = locator,
            kind = kind,
            snippet = snippet,
            utteranceHint = utteranceHint
        )
    }

    private suspend fun alignNavigatorToFreshStart(
        navigator: AndroidTtsNavigator,
        freshStart: FreshTtsStart?
    ) {
        val targetHint = freshStart?.utteranceHint?.normalizedUtteranceHint() ?: return
        repeat(8) { step ->
            val currentUtterance = navigator.location.value.utterance
            val normalizedCurrent = currentUtterance.normalizedUtteranceHint()
            DebugLog.d(TRACE_TAG) { "alignNavigatorToFreshStart: step=$step, kind=${freshStart.kind}" }
            if (normalizedCurrent.matchesUtteranceHint(targetHint)) {
                return
            }
            if (!navigator.hasNextUtterance()) {
                DebugLog.d(TRACE_TAG) { "alignNavigatorToFreshStart: no next utterance while seeking target" }
                return
            }
            val previousUtterance = currentUtterance
            navigator.skipToNextUtterance()
            waitForUtteranceAdvance(navigator, previousUtterance)
        }
        DebugLog.d(TRACE_TAG) { "alignNavigatorToFreshStart: exhausted alignment attempts" }
    }

    private suspend fun waitForUtteranceAdvance(
        navigator: AndroidTtsNavigator,
        previousUtterance: String
    ) {
        repeat(10) {
            delay(30)
            if (navigator.location.value.utterance != previousUtterance) {
                return
            }
        }
    }

    private fun parseJavascriptObject(rawResult: String): JSONObject? {
        val trimmed = rawResult.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
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

    private fun Locator?.debugSummary(): String {
        if (this == null) return "<null>"
        val cssSelector = locations.otherLocations["cssSelector"]
        return "href=${href}, progression=${locations.progression}, total=${locations.totalProgression}, css=$cssSelector"
    }

    private fun FreshTtsStart?.debugSummary(): String {
        if (this == null) return "<null>"
        return "kind=$kind, locator=${locator.debugSummary()}, snippet=${snippet ?: "<none>"}, hint=${utteranceHint ?: "<none>"}"
    }

    private fun String.normalizedUtteranceHint(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.matchesUtteranceHint(targetHint: String): Boolean {
        if (isBlank() || targetHint.isBlank()) return false
        val minPrefixLength = minOf(24, length, targetHint.length)
        if (minPrefixLength <= 0) return false
        val currentPrefix = take(minPrefixLength)
        val targetPrefix = targetHint.take(minPrefixLength)
        return startsWith(targetPrefix) || targetHint.startsWith(currentPrefix)
    }
}
