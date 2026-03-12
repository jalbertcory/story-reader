package com.storyreader.reader.preferences

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

/**
 * Helper for constructing valid [EpubPreferences] mutations.
 * Callers receive a new immutable copy; the navigator is updated via
 * [EpubNavigatorFragment.submitPreferences].
 */
object PreferencesManager {

    fun adjustFontSize(current: EpubPreferences, delta: Double): EpubPreferences {
        val next = (current.fontSize ?: 1.0) + delta
        return current.copy(fontSize = next.coerceIn(0.5, 3.0))
    }

    fun withTheme(current: EpubPreferences, theme: Theme?): EpubPreferences =
        current.copy(theme = theme)

    fun withFontFamily(current: EpubPreferences, fontFamily: FontFamily?): EpubPreferences =
        current.copy(fontFamily = fontFamily)
}
