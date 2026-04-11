package com.storyreader.reader.tts

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

/**
 * A [TtsEngine] decorator that applies text substitutions to each utterance
 * before forwarding to the [delegate] engine.
 *
 * Note: because the spoken text may differ in length from the original, word-level
 * `onRange` highlight callbacks may be offset. Sentence-level highlighting
 * (based on locator positions) is unaffected.
 */
@OptIn(ExperimentalReadiumApi::class)
class SubstitutingTtsEngine(
    private val delegate: AndroidTtsEngine,
    private val replacements: List<Pair<Regex, String>>
) : TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> {

    override val voices: Set<AndroidTtsEngine.Voice>
        get() = delegate.voices

    override val settings: StateFlow<AndroidTtsSettings>
        get() = delegate.settings

    override fun submitPreferences(preferences: AndroidTtsPreferences) {
        delegate.submitPreferences(preferences)
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        val transformed = replacements.fold(text) { acc, (pattern, replacement) ->
            pattern.replace(acc, replacement)
        }.trim()
        val spoken = transformed.ifBlank { text }
        if (spoken != text) {
            Log.d(TAG, "speak: substituted \"${text.take(120)}\" -> \"${spoken.take(120)}\"")
        } else {
            Log.d(TAG, "speak: no change \"${text.take(120)}\"")
        }
        delegate.speak(requestId, spoken, language)
    }

    companion object {
        private const val TAG = "SubstitutingTtsEngine"
    }

    override fun stop() {
        delegate.stop()
    }

    override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) {
        delegate.setListener(listener)
    }

    override fun close() {
        delegate.close()
    }
}
