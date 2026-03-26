package com.storyreader.reader.tts

import android.util.Log
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.annotation.VisibleForTesting
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Constructor
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsDefaults
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

private const val TAG = "TtsPreferencesSupport"

data class TtsEngineOption(
    val packageName: String,
    val label: String,
    val isSystemDefault: Boolean
)

data class TtsVoiceOption(
    val id: String,
    val label: String,
    val languageTag: String,
    val requiresNetwork: Boolean,
    val qualityRank: Int
)

data class TtsCatalogResult(
    val engines: List<TtsEngineOption>,
    val voices: List<TtsVoiceOption>
)

@OptIn(ExperimentalReadiumApi::class)
class TtsCatalog(private val context: Context) {

    suspend fun load(enginePackageName: String?): TtsCatalogResult =
        withContext(Dispatchers.Main.immediate) {
            val engine = createTextToSpeech(enginePackageName) ?: return@withContext TtsCatalogResult(
                engines = emptyList(),
                voices = emptyList()
            )

            try {
                val systemDefault = engine.defaultEngine
                val engines = engine.engines
                    .orEmpty()
                    .map { info ->
                        TtsEngineOption(
                            packageName = info.name,
                            label = info.label ?: info.name,
                            isSystemDefault = info.name == systemDefault
                        )
                    }
                    .sortedWith(compareByDescending<TtsEngineOption> { it.isSystemDefault }.thenBy { it.label.lowercase() })

                val voices = try {
                    engine.voices.orEmpty()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to get TTS voices from engine", e)
                    emptySet()
                }
                    .map { voice ->
                        TtsVoiceOption(
                            id = voice.name,
                            label = buildVoiceLabel(voice),
                            languageTag = voice.locale?.toLanguageTag().orEmpty(),
                            requiresNetwork = voice.isNetworkConnectionRequired,
                            qualityRank = voice.quality
                        )
                    }
                    .sortedWith(
                        compareBy<TtsVoiceOption> { it.requiresNetwork }
                            .thenByDescending { it.qualityRank }
                            .thenBy { it.languageTag }
                            .thenBy { it.label.lowercase() }
                    )

                TtsCatalogResult(engines = engines, voices = voices)
            } finally {
                engine.shutdown()
            }
        }

    private suspend fun createTextToSpeech(enginePackageName: String?): TextToSpeech? {
        val deferred = CompletableDeferred<TextToSpeech?>()
        var textToSpeech: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (!deferred.isCompleted) {
                deferred.complete(if (status == TextToSpeech.SUCCESS) textToSpeech else null)
            }
        }
        textToSpeech = if (enginePackageName.isNullOrBlank()) {
            TextToSpeech(context, listener)
        } else {
            TextToSpeech(context, listener, enginePackageName)
        }
        return deferred.await()
    }

    private fun buildVoiceLabel(voice: android.speech.tts.Voice): String {
        val localeLabel = voice.locale?.displayName?.takeIf { it.isNotBlank() } ?: "Unknown language"
        val qualityLabel = when (voice.quality) {
            android.speech.tts.Voice.QUALITY_VERY_HIGH -> "Highest quality"
            android.speech.tts.Voice.QUALITY_HIGH -> "High quality"
            android.speech.tts.Voice.QUALITY_NORMAL -> "Normal quality"
            android.speech.tts.Voice.QUALITY_LOW -> "Low quality"
            android.speech.tts.Voice.QUALITY_VERY_LOW -> "Lowest quality"
            else -> null
        }
        val networkLabel = if (voice.isNetworkConnectionRequired) "Network" else "On-device"
        val pieces = listOfNotNull(localeLabel, qualityLabel, networkLabel)
        return pieces.joinToString(" - ")
    }
}

@OptIn(ExperimentalReadiumApi::class)
class StoryReaderAndroidTtsEngineProvider(
    private val context: Context,
    private val enginePackageName: String?,
    private val defaults: AndroidTtsDefaults = AndroidTtsDefaults(),
    private val voiceSelector: AndroidTtsEngine.VoiceSelector = AndroidTtsEngine.VoiceSelector { _, _ -> null }
) : TtsEngineProvider<
    AndroidTtsSettings,
    AndroidTtsPreferences,
    AndroidTtsPreferencesEditor,
    AndroidTtsEngine.Error,
    AndroidTtsEngine.Voice
    > {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences
    ): Try<AndroidTtsEngine, org.readium.r2.shared.util.Error> {
        val settingsResolver = AndroidTtsEngine.SettingsResolver { preferences ->
            val language = preferences.language
                ?: publication.metadata.language
                ?: defaults.language
                ?: Language(Locale.getDefault())

            AndroidTtsSettings(
                language = language,
                voices = preferences.voices ?: emptyMap(),
                pitch = preferences.pitch ?: defaults.pitch ?: 1.0,
                speed = preferences.speed ?: defaults.speed ?: 1.0,
                overrideContentLanguage = preferences.language != null
            )
        }

        val textToSpeech = createTextToSpeech() ?: return Try.failure(
            DebugError("Initialization of Android Tts service failed.")
        )

        val voices = try {
            textToSpeech.voices.orEmpty()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get TTS voices from custom engine", e)
            emptySet()
        }.map { it.toTtsEngineVoice() }
            .toSet()

        val engine = try {
            createAndroidTtsEngine(
                context = context,
                textToSpeech = textToSpeech,
                settingsResolver = settingsResolver,
                voiceSelector = voiceSelector,
                voices = voices,
                initialPreferences = initialPreferences
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create AndroidTtsEngine instance", e)
            textToSpeech.shutdown()
            null
        } ?: return Try.failure(DebugError("Failed to create Android Tts engine."))

        return Try.success(engine)
    }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences
    ): AndroidTtsPreferencesEditor =
        AndroidTtsPreferencesEditor(initialPreferences, publication.metadata, defaults)

    override fun createEmptyPreferences(): AndroidTtsPreferences =
        AndroidTtsPreferences()

    override fun getPlaybackParameters(settings: AndroidTtsSettings): PlaybackParameters =
        PlaybackParameters(settings.speed.toFloat(), settings.pitch.toFloat())

    override fun updatePlaybackParameters(
        previousPreferences: AndroidTtsPreferences,
        playbackParameters: PlaybackParameters
    ): AndroidTtsPreferences =
        previousPreferences.copy(
            speed = playbackParameters.speed.toDouble(),
            pitch = playbackParameters.pitch.toDouble()
        )

    @UnstableApi
    override fun mapEngineError(error: AndroidTtsEngine.Error): PlaybackException {
        val errorCode = when (error) {
            AndroidTtsEngine.Error.Unknown -> PlaybackException.ERROR_CODE_UNSPECIFIED
            AndroidTtsEngine.Error.InvalidRequest -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            AndroidTtsEngine.Error.Network -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            AndroidTtsEngine.Error.NetworkTimeout -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            AndroidTtsEngine.Error.Output,
            AndroidTtsEngine.Error.Service,
            AndroidTtsEngine.Error.Synthesis,
            is AndroidTtsEngine.Error.LanguageMissingData,
            AndroidTtsEngine.Error.NotInstalledYet -> PlaybackException.ERROR_CODE_UNSPECIFIED
        }

        return PlaybackException(
            "Android TTS engine error: ${error.javaClass.simpleName}",
            null,
            errorCode
        )
    }

    private suspend fun createTextToSpeech(): TextToSpeech? =
        withContext(Dispatchers.Main.immediate) {
            val deferred = CompletableDeferred<TextToSpeech?>()
            var textToSpeech: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                if (!deferred.isCompleted) {
                    deferred.complete(if (status == TextToSpeech.SUCCESS) textToSpeech else null)
                }
            }
            textToSpeech = if (enginePackageName.isNullOrBlank()) {
                TextToSpeech(context, listener)
            } else {
                TextToSpeech(context, listener, enginePackageName)
            }
            deferred.await()
        }

    @VisibleForTesting
    internal fun createAndroidTtsEngine(
        context: Context,
        textToSpeech: TextToSpeech,
        settingsResolver: AndroidTtsEngine.SettingsResolver,
        voiceSelector: AndroidTtsEngine.VoiceSelector,
        voices: Set<AndroidTtsEngine.Voice>,
        initialPreferences: AndroidTtsPreferences
    ): AndroidTtsEngine? {
        val constructor = androidTtsEngineConstructor() ?: return null
        constructor.isAccessible = true
        return constructor.newInstance(
            context,
            textToSpeech,
            settingsResolver,
            voiceSelector,
            voices,
            initialPreferences
        ) as? AndroidTtsEngine
    }

    @Suppress("UNCHECKED_CAST")
    private fun androidTtsEngineConstructor():
        Constructor<AndroidTtsEngine>? =
        AndroidTtsEngine::class.java.declaredConstructors
            .firstOrNull { it.parameterTypes.size == 6 }
            as? Constructor<AndroidTtsEngine>

    private fun android.speech.tts.Voice.toTtsEngineVoice(): AndroidTtsEngine.Voice =
        AndroidTtsEngine.Voice(
            id = AndroidTtsEngine.Voice.Id(name),
            language = Language(locale),
            quality = when (quality) {
                android.speech.tts.Voice.QUALITY_VERY_HIGH -> AndroidTtsEngine.Voice.Quality.Highest
                android.speech.tts.Voice.QUALITY_HIGH -> AndroidTtsEngine.Voice.Quality.High
                android.speech.tts.Voice.QUALITY_NORMAL -> AndroidTtsEngine.Voice.Quality.Normal
                android.speech.tts.Voice.QUALITY_LOW -> AndroidTtsEngine.Voice.Quality.Low
                android.speech.tts.Voice.QUALITY_VERY_LOW -> AndroidTtsEngine.Voice.Quality.Lowest
                else -> AndroidTtsEngine.Voice.Quality.Normal
            },
            requiresNetwork = isNetworkConnectionRequired
        )
}
