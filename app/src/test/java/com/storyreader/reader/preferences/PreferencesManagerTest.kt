package com.storyreader.reader.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import com.storyreader.ui.reader.ReaderBrightness
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesManagerTest {

    @Test
    fun `adjustFontSize increases font size by delta`() {
        val prefs = EpubPreferences(fontSize = 1.0)
        val result = PreferencesManager.adjustFontSize(prefs, 0.25)
        assertEquals(1.25, result.fontSize ?: 0.0, 0.001)
    }

    @Test
    fun `adjustFontSize clamps at minimum`() {
        val prefs = EpubPreferences(fontSize = 0.55)
        val result = PreferencesManager.adjustFontSize(prefs, -0.5)
        assertEquals(0.5, result.fontSize ?: 0.0, 0.001)
    }

    @Test
    fun `adjustFontSize clamps at maximum`() {
        val prefs = EpubPreferences(fontSize = 2.9)
        val result = PreferencesManager.adjustFontSize(prefs, 0.5)
        assertEquals(3.0, result.fontSize ?: 0.0, 0.001)
    }

    @Test
    fun `adjustFontSize defaults to 1_5 when fontSize is null`() {
        val prefs = EpubPreferences(fontSize = null)
        val result = PreferencesManager.adjustFontSize(prefs, 0.0)
        assertEquals(1.5, result.fontSize ?: 0.0, 0.001)
    }

    @Test
    fun `withTheme sets dark theme`() {
        val prefs = EpubPreferences()
        val result = PreferencesManager.withTheme(prefs, Theme.DARK)
        assertEquals(Theme.DARK, result.theme)
    }

    @Test
    fun `withTheme clears theme when null passed`() {
        val prefs = EpubPreferences(theme = Theme.SEPIA)
        val result = PreferencesManager.withTheme(prefs, null)
        assertNull(result.theme)
    }

    @Test
    fun `withFontFamily sets font family`() {
        val prefs = EpubPreferences()
        val result = PreferencesManager.withFontFamily(prefs, FontFamily("serif"))
        assertEquals("serif", result.fontFamily?.name)
    }

    @Test
    fun `withFontFamily clears font family when null passed`() {
        val prefs = EpubPreferences(fontFamily = FontFamily("sans-serif"))
        val result = PreferencesManager.withFontFamily(prefs, null)
        assertNull(result.fontFamily)
    }

    @Test
    fun `reader brightness drag can enter extra dim`() {
        assertEquals(-0.25f, ReaderBrightness.adjustByDrag(0.1f, 0.35f), 0.001f)
    }

    @Test
    fun `reader brightness overlay only appears below zero`() {
        assertEquals(1f, ReaderBrightness.windowBrightnessFor(1f), 0.001f)
        assertEquals(0.63f, ReaderBrightness.windowBrightnessFor(0.4f), 0.01f)
        assertEquals(0.01f, ReaderBrightness.windowBrightnessFor(-0.2f), 0.001f)
        assertEquals(0f, ReaderBrightness.overlayAlphaFor(1f), 0.001f)
        assertEquals(0.128f, ReaderBrightness.overlayAlphaFor(0.6f), 0.001f)
        assertEquals(0.55f, ReaderBrightness.overlayAlphaFor(-0.5f), 0.001f)
    }
}
