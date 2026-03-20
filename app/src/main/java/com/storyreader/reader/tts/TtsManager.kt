package com.storyreader.reader.tts

import android.app.Application
import androidx.media3.common.MediaMetadata
import org.readium.navigator.media.common.MediaMetadataFactory
import org.readium.navigator.media.common.MediaMetadataProvider
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

    fun initialize(publication: Publication, enginePackageName: String?): Boolean {
        return try {
            val engineProvider =
                if (enginePackageName.isNullOrBlank()) {
                    AndroidTtsEngineProvider(application)
                } else {
                    StoryReaderAndroidTtsEngineProvider(application, enginePackageName)
                }
            factory = TtsNavigatorFactory(
                application = application,
                publication = publication,
                ttsEngineProvider = engineProvider,
                tokenizerFactory = { language ->
                    // Keep utterances sentence-sized for smooth playback while still allowing
                    // token-level range callbacks from the engine for visual highlighting.
                    DefaultTextContentTokenizer(TextUnit.Sentence, language)
                },
                metadataProvider = LightweightMetadataProvider()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun start(
        initialLocator: Locator?,
        preferences: AndroidTtsPreferences
    ): TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>? {
        val nav = factory?.createNavigator(
            listener = object : TtsNavigator.Listener {
                override fun onStopRequested() {
                    stop()
                }
            },
            initialLocator = initialLocator,
            initialPreferences = preferences
        )?.getOrNull() ?: return null
        ttsNavigator = nav
        return nav
    }

    fun stop() {
        ttsNavigator = null
    }

    companion object {
        fun requestInstallVoice(context: android.content.Context) {
            AndroidTtsEngine.requestInstallVoice(context)
        }
    }
}

/**
 * A [MediaMetadataProvider] that omits cover art from per-resource metadata.
 *
 * Readium's [DefaultMediaMetadataProvider] embeds cover art bytes into every MediaItem
 * (one per chapter). The full timeline is serialized into Binder transactions to connected
 * controllers (Wear OS, Android Auto), easily exceeding the 1 MB limit for books with many
 * chapters. We supply our own artwork via [TtsMediaService.NowPlayingPlayer] so the
 * per-resource copies are unnecessary.
 */
@ExperimentalReadiumApi
private class LightweightMetadataProvider : MediaMetadataProvider {
    override fun createMetadataFactory(publication: Publication): MediaMetadataFactory {
        val title = publication.metadata.title
        val author = publication.metadata.authors
            .firstOrNull { it.name.isNotBlank() }?.name
        return LightweightMetadataFactory(title, author)
    }
}

@ExperimentalReadiumApi
private class LightweightMetadataFactory(
    private val title: String?,
    private val author: String?,
) : MediaMetadataFactory {

    override suspend fun publicationMetadata(): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .apply { author?.let { setArtist(it) } }
            .build()

    override suspend fun resourceMetadata(index: Int): MediaMetadata =
        MediaMetadata.Builder()
            .setTrackNumber(index)
            .setTitle(title)
            .apply { author?.let { setArtist(it) } }
            .build()
}
