package com.storyreader.ui.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyreader.ui.theme.StoryReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign

@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderSettingsSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nightTheme_appliesNightColorsAndDisablesPublisherStyles() {
        var latestPreferences = EpubPreferences(
            theme = Theme.DARK,
            publisherStyles = true
        )

        composeRule.setContent {
            var preferences by remember { mutableStateOf(latestPreferences) }
            StoryReaderTheme {
                ReaderSettingsSheet(
                    preferences = preferences,
                    brightnessLevel = 1f,
                    onPreferencesChange = {
                        preferences = it
                        latestPreferences = it
                    },
                    onBrightnessLevelChange = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(ReaderSettingsTestTags.themeNight).performClick()

        composeRule.runOnIdle {
            assertEquals(null, latestPreferences.theme)
            assertEquals(ReadiumColor(0xFF000000.toInt()), latestPreferences.backgroundColor)
            assertEquals(ReadiumColor(0xFFFF7722.toInt()), latestPreferences.textColor)
            assertEquals(false, latestPreferences.publisherStyles)
        }
    }

    @Test
    fun justifyAlignment_disablesPublisherStyles() {
        var latestPreferences = EpubPreferences(
            textAlign = ReadiumTextAlign.START,
            publisherStyles = true
        )

        composeRule.setContent {
            var preferences by remember { mutableStateOf(latestPreferences) }
            StoryReaderTheme {
                ReaderSettingsSheet(
                    preferences = preferences,
                    brightnessLevel = 1f,
                    onPreferencesChange = {
                        preferences = it
                        latestPreferences = it
                    },
                    onBrightnessLevelChange = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(ReaderSettingsTestTags.alignJustify).performClick()

        composeRule.runOnIdle {
            assertEquals(ReadiumTextAlign.JUSTIFY, latestPreferences.textAlign)
            assertFalse(latestPreferences.publisherStyles ?: true)
        }
    }

    @Test
    fun scrollMode_switchTogglesPreference() {
        var latestPreferences = EpubPreferences(scroll = false)

        composeRule.setContent {
            var preferences by remember { mutableStateOf(latestPreferences) }
            StoryReaderTheme {
                ReaderSettingsSheet(
                    preferences = preferences,
                    brightnessLevel = 1f,
                    onPreferencesChange = {
                        preferences = it
                        latestPreferences = it
                    },
                    onBrightnessLevelChange = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(ReaderSettingsTestTags.scrollModeSwitch).assertIsOff()
        composeRule.onNodeWithTag(ReaderSettingsTestTags.scrollModeSwitch).performClick()
        composeRule.onNodeWithTag(ReaderSettingsTestTags.scrollModeSwitch).assertIsOn()

        composeRule.runOnIdle {
            assertEquals(true, latestPreferences.scroll)
        }
    }

    @Test
    fun brightnessSlider_updatesDisplayedLabel() {
        composeRule.setContent {
            var brightnessLevel by remember { mutableFloatStateOf(1f) }
            var preferences by remember { mutableStateOf(EpubPreferences()) }
            StoryReaderTheme {
                ReaderSettingsSheet(
                    preferences = preferences,
                    brightnessLevel = brightnessLevel,
                    onPreferencesChange = { preferences = it },
                    onBrightnessLevelChange = { brightnessLevel = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(ReaderSettingsTestTags.brightnessSlider)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(-0.4f)
            }

        composeRule.onNodeWithText("Extra dim 40%").assertExists()
    }
}
