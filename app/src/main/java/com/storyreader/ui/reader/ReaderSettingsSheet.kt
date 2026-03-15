package com.storyreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storyreader.ui.components.SelectableSettingTile
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign

private val NIGHT_BG_INT = 0xFF000000.toInt()
private val NIGHT_TEXT_INT = 0xFFFF7722.toInt()

@OptIn(ExperimentalReadiumApi::class)
private fun EpubPreferences.isNightTheme() =
    theme == null && backgroundColor?.int == NIGHT_BG_INT

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
    brightnessLevel: Float,
    onPreferencesChange: (EpubPreferences) -> Unit,
    onBrightnessLevelChange: (Float) -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Reading Settings", style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Brightness", style = MaterialTheme.typography.labelLarge)
                    Text(
                        brightnessLabel(brightnessLevel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = brightnessLevel,
                    onValueChange = onBrightnessLevelChange,
                    valueRange = -1f..1f
                )
                Text(
                    "Swipe vertically along the left edge while reading to adjust. Values below 0 use extra dim.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                    steps = 29
                )
            }

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
                            onPreferencesChange(preferences.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("serif")))
                        }
                    )
                    FontButton(
                        label = "Sans",
                        composeFontFamily = FontFamily.SansSerif,
                        selected = preferences.fontFamily?.name == "sans-serif",
                        onClick = {
                            onPreferencesChange(preferences.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("sans-serif")))
                        }
                    )
                    FontButton(
                        label = "Mono",
                        composeFontFamily = FontFamily.Monospace,
                        selected = preferences.fontFamily?.name == "monospace",
                        onClick = {
                            onPreferencesChange(preferences.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("monospace")))
                        }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text Alignment", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlignButton(
                        icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
                        contentDescription = "Left",
                        selected = preferences.textAlign == ReadiumTextAlign.LEFT || preferences.textAlign == null,
                        onClick = { onPreferencesChange(preferences.copy(textAlign = ReadiumTextAlign.LEFT, publisherStyles = false)) }
                    )
                    AlignButton(
                        icon = Icons.Default.FormatAlignCenter,
                        contentDescription = "Start",
                        selected = preferences.textAlign == ReadiumTextAlign.START,
                        onClick = { onPreferencesChange(preferences.copy(textAlign = ReadiumTextAlign.START, publisherStyles = false)) }
                    )
                    AlignButton(
                        icon = Icons.AutoMirrored.Filled.FormatAlignRight,
                        contentDescription = "Right",
                        selected = preferences.textAlign == ReadiumTextAlign.RIGHT,
                        onClick = { onPreferencesChange(preferences.copy(textAlign = ReadiumTextAlign.RIGHT, publisherStyles = false)) }
                    )
                    AlignButton(
                        icon = Icons.Default.FormatAlignJustify,
                        contentDescription = "Justify",
                        selected = preferences.textAlign == ReadiumTextAlign.JUSTIFY,
                        onClick = { onPreferencesChange(preferences.copy(textAlign = ReadiumTextAlign.JUSTIFY, publisherStyles = false)) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Scroll Mode", style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = preferences.scroll == true,
                    onCheckedChange = { onPreferencesChange(preferences.copy(scroll = it)) }
                )
            }
        }
    }
}

private fun brightnessLabel(level: Float): String {
    val percent = (kotlin.math.abs(level) * 100).toInt()
    return if (level >= 0f) "$percent%" else "Extra dim $percent%"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsSheet(
    ttsSettings: TtsSettingsUiState,
    onTtsSpeedChange: (Float) -> Unit,
    onTtsPitchChange: (Float) -> Unit,
    onTtsVoiceSelected: (String?) -> Unit,
    onTtsEngineSelected: (String?) -> Unit,
    onOpenSystemTtsSettings: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Text-to-Speech Settings", style = MaterialTheme.typography.titleLarge)

            SliderSetting(
                label = "Speed",
                value = ttsSettings.speed,
                display = "${String.format("%.2f", ttsSettings.speed)}x",
                onReset = { onTtsSpeedChange(1f) },
                onValueChange = onTtsSpeedChange
            )

            SliderSetting(
                label = "Pitch",
                value = ttsSettings.pitch,
                display = "${String.format("%.2f", ttsSettings.pitch)}x",
                onReset = { onTtsPitchChange(1f) },
                onValueChange = onTtsPitchChange
            )

            SelectionField(
                label = "Engine",
                value = ttsSettings.availableEngines
                    .firstOrNull { it.packageName == ttsSettings.enginePackageName }
                    ?.label
                    ?: ttsSettings.availableEngines.firstOrNull { it.isSystemDefault }?.label
                    ?: "System default",
                entries = listOf("System default" to null) +
                    ttsSettings.availableEngines.map { engine ->
                        val suffix = if (engine.isSystemDefault) " (system default)" else ""
                        "${engine.label}$suffix" to engine.packageName
                    },
                onSelected = onTtsEngineSelected
            )

            SelectionField(
                label = "Voice (${ttsSettings.languageLabel})",
                value = ttsSettings.availableVoices
                    .firstOrNull { it.id == ttsSettings.selectedVoiceId }
                    ?.label
                    ?: if (ttsSettings.isLoadingVoices) "Loading voices..." else "System default",
                entries = listOf("System default" to null) +
                    ttsSettings.availableVoices.map { voice -> voice.label to voice.id },
                enabled = !ttsSettings.isLoadingVoices,
                onSelected = onTtsVoiceSelected
            )

            Text(
                "Choose System default here to let Android handle the active engine voice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onOpenSystemTtsSettings) {
                Text("Open Android TTS settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionField(
    label: String,
    value: String,
    entries: List<Pair<String, String?>>,
    enabled: Boolean = true,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                entries.forEach { (title, id) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            onSelected(id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    display: String,
    onReset: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    display,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onReset) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.5f..2.0f,
            steps = 29
        )
    }
}

@Composable
private fun ThemeButton(
    preview: ThemePreview,
    selected: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingTile(
        selected = selected,
        onClick = onClick,
        selectedContainerColor = preview.bg,
        unselectedContainerColor = preview.bg,
        selectedBorderColor = MaterialTheme.colorScheme.primary,
        unselectedBorderColor = Color.Transparent,
        unselectedBorderWidth = 2.dp,
        shadowElevation = if (selected) 4.dp else 1.dp
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

@Composable
private fun AlignButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingTile(
        selected = selected,
        onClick = onClick,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FontButton(
    label: String,
    composeFontFamily: FontFamily,
    selected: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingTile(
        selected = selected,
        onClick = onClick,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedContainerColor = MaterialTheme.colorScheme.surface
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
