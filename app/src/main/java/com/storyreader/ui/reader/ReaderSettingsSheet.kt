package com.storyreader.ui.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor

// Night-theme sentinel colors applied to EpubPreferences
private val NIGHT_BG_INT = 0xFF000000.toInt()
private val NIGHT_TEXT_INT = 0xFFFF7722.toInt()

@OptIn(ExperimentalReadiumApi::class)
private fun EpubPreferences.isNightTheme() =
    theme == null && backgroundColor?.int == NIGHT_BG_INT

// Preview colors per theme (used for the button UI only)
private data class ThemePreview(
    val bg: Color,
    val text: Color,
    val label: String
)

private val themeDefault = ThemePreview(Color.White, Color(0xFF1C1B1F), "Default")
private val themeDark = ThemePreview(Color(0xFF1A1A2E), Color(0xFFE0E0E0), "Dark")
private val themeSepia = ThemePreview(Color(0xFFF5EBD7), Color(0xFF5C4033), "Sepia")
private val themeNight = ThemePreview(Color.Black, Color(0xFFFF7722), "Night")

@OptIn(ExperimentalReadiumApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    preferences: EpubPreferences,
    onPreferencesChange: (EpubPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Reading Settings", style = MaterialTheme.typography.titleLarge)

            // ── Font Size ────────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Font Size", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val fontSize = (preferences.fontSize ?: 1.0).toFloat()
                        Text(
                            "${(fontSize * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { onPreferencesChange(preferences.copy(fontSize = 1.0)) },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val fontSize = (preferences.fontSize ?: 1.0).toFloat()
                Slider(
                    value = fontSize,
                    onValueChange = { onPreferencesChange(preferences.copy(fontSize = it.toDouble())) },
                    valueRange = 0.5f..2.0f,
                    steps = 29  // 0.05× increments
                )
            }

            // ── Theme ────────────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isNight = preferences.isNightTheme()
                    ThemeButton(
                        preview = themeDefault,
                        selected = !isNight && preferences.theme == null,
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(theme = null, backgroundColor = null, textColor = null, publisherStyles = null)
                            )
                        }
                    )
                    ThemeButton(
                        preview = themeDark,
                        selected = preferences.theme == Theme.DARK,
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(theme = Theme.DARK, backgroundColor = null, textColor = null, publisherStyles = null)
                            )
                        }
                    )
                    ThemeButton(
                        preview = themeSepia,
                        selected = preferences.theme == Theme.SEPIA,
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(theme = Theme.SEPIA, backgroundColor = null, textColor = null, publisherStyles = null)
                            )
                        }
                    )
                    ThemeButton(
                        preview = themeNight,
                        selected = isNight,
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(
                                    theme = null,
                                    backgroundColor = ReadiumColor(NIGHT_BG_INT),
                                    textColor = ReadiumColor(NIGHT_TEXT_INT),
                                    publisherStyles = false
                                )
                            )
                        }
                    )
                }
            }

            // ── Font Family ──────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Font", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FontButton(
                        label = "Default",
                        composeFontFamily = FontFamily.Default,
                        selected = preferences.fontFamily == null,
                        onClick = { onPreferencesChange(preferences.copy(fontFamily = null)) }
                    )
                    FontButton(
                        label = "Serif",
                        composeFontFamily = FontFamily.Serif,
                        selected = preferences.fontFamily?.name == "serif",
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(
                                    fontFamily = org.readium.r2.navigator.preferences.FontFamily("serif")
                                )
                            )
                        }
                    )
                    FontButton(
                        label = "Sans",
                        composeFontFamily = FontFamily.SansSerif,
                        selected = preferences.fontFamily?.name == "sans-serif",
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(
                                    fontFamily = org.readium.r2.navigator.preferences.FontFamily("sans-serif")
                                )
                            )
                        }
                    )
                    FontButton(
                        label = "Mono",
                        composeFontFamily = FontFamily.Monospace,
                        selected = preferences.fontFamily?.name == "monospace",
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(
                                    fontFamily = org.readium.r2.navigator.preferences.FontFamily("monospace")
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeButton(
    preview: ThemePreview,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(72.dp)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp)),
        color = preview.bg,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = if (selected) 4.dp else 1.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
        ) {
            Text(
                text = preview.label,
                color = preview.text,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FontButton(
    label: String,
    composeFontFamily: FontFamily,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(72.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            ),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
        ) {
            Text(
                text = label,
                fontFamily = composeFontFamily,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
