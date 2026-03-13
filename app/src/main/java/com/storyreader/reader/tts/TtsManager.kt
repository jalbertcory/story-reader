package com.storyreader.reader.tts

import android.app.Application
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

@OptIn(ExperimentalReadiumApi::class)
class TtsManager(private val application: Application) {

    private var ttsNavigator: TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>? = null
    private var factory: TtsNavigatorFactory<AndroidTtsSettings, AndroidTtsPreferences, *, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>? = null

    fun initialize(publication: Publication): Boolean {
        return try {
            val engineProvider = AndroidTtsEngineProvider(application)
            factory = TtsNavigatorFactory(
                application = application,
                publication = publication,
                ttsEngineProvider = engineProvider,
                tokenizerFactory = { language ->
                    // Keep utterances sentence-sized for smooth playback while still allowing
                    // token-level range callbacks from the engine for visual highlighting.
                    DefaultTextContentTokenizer(TextUnit.Sentence, language)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun start(initialLocator: Locator?): TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>? {
        val nav = factory?.createNavigator(
            listener = object : TtsNavigator.Listener {
                override fun onStopRequested() {
                    stop()
                }
            },
            initialLocator = initialLocator
        )?.getOrNull() ?: return null
        ttsNavigator = nav
        return nav
    }

    fun stop() {
        ttsNavigator = null
    }

    fun getNavigator() = ttsNavigator

    companion object {
        fun requestInstallVoice(context: android.content.Context) {
            AndroidTtsEngine.requestInstallVoice(context)
        }
    }
}
