package com.storyreader.reader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

class TtsHighlightSynchronizer(
    private val scope: CoroutineScope
) {

    private var syncJob: Job? = null

    fun startSync(
        locationFlow: StateFlow<Locator?>,
        epubNavigator: EpubNavigatorFragment
    ) {
        stopSync()
        syncJob = scope.launch {
            locationFlow
                .filterNotNull()
                .distinctUntilChanged()
                .collect { locator ->
                    val decoration = Decoration(
                        id = "tts-utterance",
                        locator = locator,
                        style = Decoration.Style.Highlight(tint = android.graphics.Color.YELLOW)
                    )
                    (epubNavigator as DecorableNavigator).applyDecorations(
                        listOf(decoration),
                        group = "tts"
                    )
                    epubNavigator.go(locator)
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
