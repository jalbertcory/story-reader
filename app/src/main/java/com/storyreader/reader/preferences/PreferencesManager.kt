package com.storyreader.reader.preferences

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

class PreferencesManager {

    fun updateFontSize(current: EpubPreferences, delta: Double): EpubPreferences {
        val currentSize = current.fontSize ?: 1.0
        val newSize = (currentSize + delta).coerceIn(0.5, 3.0)
        return current.copy(fontSize = newSize)
    }

    fun updateTheme(current: EpubPreferences, theme: Theme?): EpubPreferences {
        return current.copy(theme = theme)
    }

    fun updateFontFamily(current: EpubPreferences, fontFamily: FontFamily?): EpubPreferences {
        return current.copy(fontFamily = fontFamily)
    }
}
