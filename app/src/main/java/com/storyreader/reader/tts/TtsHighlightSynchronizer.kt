package com.storyreader.reader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalReadiumApi::class)
class TtsHighlightSynchronizer(
    private val scope: CoroutineScope
) {

    private var syncJob: Job? = null

    fun startSync(
        ttsNavigator: AndroidTtsNavigator,
        epubNavigator: EpubNavigatorFragment
    ) {
        stopSync()
        syncJob = scope.launch {
            var lastFollowLocator: Locator? = null
            var lastTokenLocator: Locator? = null
            ttsNavigator.location
                .collect { location ->
                    val highlightLocator = when {
                        location.tokenLocator != null -> {
                            lastTokenLocator = location.tokenLocator
                            location.tokenLocator
                        }
                        // Avoid flashing the whole sentence between token callbacks.
                        location.range == null && lastTokenLocator != null -> lastTokenLocator
                        else -> {
                            lastTokenLocator = null
                            location.utteranceLocator
                        }
                    } ?: location.utteranceLocator
                    val decoration = Decoration(
                        id = "tts-utterance",
                        locator = highlightLocator,
                        style = Decoration.Style.Highlight(tint = android.graphics.Color.YELLOW)
                    )
                    (epubNavigator as DecorableNavigator).applyDecorations(
                        listOf(decoration),
                        group = "tts"
                    )

                    // Follow the highlight — navigate when it moves to a different page.
                    // Using the resolved highlight locator (token when available) so the
                    // page turns exactly when the highlighted word crosses a page boundary.
                    val followLocator = highlightLocator
                    val hrefChanged = followLocator.href != lastFollowLocator?.href
                    if (hrefChanged || followLocator != lastFollowLocator) {
                        lastFollowLocator = followLocator
                        epubNavigator.go(followLocator)
                    }
                }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    suspend fun clearHighlights(epubNavigator: EpubNavigatorFragment) {
        (epubNavigator as DecorableNavigator).applyDecorations(emptyList(), group = "tts")
    }
}
