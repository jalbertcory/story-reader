package com.storyreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storyreader.reader.tts.TtsRegexRule
import com.storyreader.reader.tts.TtsTextFilter
import com.storyreader.ui.components.SelectableSettingTile
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import java.util.Locale
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign

private const val NIGHT_BG_INT = 0xFF000000.toInt()
private const val NIGHT_TEXT_INT = 0xFFFF7722.toInt()

@OptIn(ExperimentalReadiumApi::class)
private fun EpubPreferences.isNightTheme() =
    theme == null && backgroundColor?.int == NIGHT_BG_INT

private data class ThemePreview(
    val bg: Color,
    val text: Color,
    val label: String
)

private val themeDefault = ThemePreview(Color.White, Color(0xFF1C1B1F), "Default")
private val themeDark = ThemePreview(Color(0xFF000000), Color(0xFFFEFEFE), "Dark")
private val themeSepia = ThemePreview(Color(0xFFFAF4E8), Color(0xFF121212), "Sepia")
private val themeNight = ThemePreview(Color.Black, Color(0xFFFF7722), "Night")

object ReaderSettingsTestTags {
    const val BRIGHTNESS_SLIDER = "reader_settings_brightness_slider"
    const val FONT_SIZE_SLIDER = "reader_settings_font_size_slider"
    const val THEME_DEFAULT = "reader_settings_theme_default"
    const val THEME_DARK = "reader_settings_theme_dark"
    const val THEME_SEPIA = "reader_settings_theme_sepia"
    const val THEME_NIGHT = "reader_settings_theme_night"
    const val FONT_DEFAULT = "reader_settings_font_default"
    const val FONT_SERIF = "reader_settings_font_serif"
    const val FONT_SANS = "reader_settings_font_sans"
    const val FONT_MONO = "reader_settings_font_mono"
    const val ALIGN_LEFT = "reader_settings_align_left"
    const val ALIGN_RIGHT = "reader_settings_align_right"
    const val ALIGN_JUSTIFY = "reader_settings_align_justify"
    const val SCROLL_MODE_SWITCH = "reader_settings_scroll_mode_switch"
}

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
                    valueRange = -1f..1f,
                    modifier = Modifier.testTag(ReaderSettingsTestTags.BRIGHTNESS_SLIDER)
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
                    valueRange = 0.5f..2.5f,
                    steps = 39,
                    modifier = Modifier.testTag(ReaderSettingsTestTags.FONT_SIZE_SLIDER)
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
                        modifier = Modifier.testTag(ReaderSettingsTestTags.THEME_DEFAULT),
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(theme = null, backgroundColor = null, textColor = null, publisherStyles = null)
                            )
                        }
                    )
                    ThemeButton(
                        preview = themeDark,
                        selected = preferences.theme == Theme.DARK,
                        modifier = Modifier.testTag(ReaderSettingsTestTags.THEME_DARK),
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(theme = Theme.DARK, backgroundColor = null, textColor = null, publisherStyles = null)
                            )
                        }
                    )
                    ThemeButton(
                        preview = themeSepia,
                        selected = preferences.theme == Theme.SEPIA,
                        modifier = Modifier.testTag(ReaderSettingsTestTags.THEME_SEPIA),
                        onClick = {
                            onPreferencesChange(
                                preferences.copy(theme = Theme.SEPIA, backgroundColor = null, textColor = null, publisherStyles = null)
                            )
                        }
                    )
                    ThemeButton(
                        preview = themeNight,
                        selected = isNight,
                        modifier = Modifier.testTag(ReaderSettingsTestTags.THEME_NIGHT),
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
                        modifier = Modifier.testTag(ReaderSettingsTestTags.FONT_DEFAULT),
                        onClick = { onPreferencesChange(preferences.copy(fontFamily = null)) }
                    )
                    FontButton(
                        label = "Serif",
                        composeFontFamily = FontFamily.Serif,
                        selected = preferences.fontFamily?.name == "serif",
                        modifier = Modifier.testTag(ReaderSettingsTestTags.FONT_SERIF),
                        onClick = {
                            onPreferencesChange(preferences.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("serif")))
                        }
                    )
                    FontButton(
                        label = "Sans",
                        composeFontFamily = FontFamily.SansSerif,
                        selected = preferences.fontFamily?.name == "sans-serif",
                        modifier = Modifier.testTag(ReaderSettingsTestTags.FONT_SANS),
                        onClick = {
                            onPreferencesChange(preferences.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("sans-serif")))
                        }
                    )
                    FontButton(
                        label = "Mono",
                        composeFontFamily = FontFamily.Monospace,
                        selected = preferences.fontFamily?.name == "monospace",
                        modifier = Modifier.testTag(ReaderSettingsTestTags.FONT_MONO),
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
                        modifier = Modifier.testTag(ReaderSettingsTestTags.ALIGN_LEFT),
                        onClick = { onPreferencesChange(preferences.copy(textAlign = ReadiumTextAlign.LEFT, publisherStyles = false)) }
                    )
                    AlignButton(
                        icon = Icons.AutoMirrored.Filled.FormatAlignRight,
                        contentDescription = "Right",
                        selected = preferences.textAlign == ReadiumTextAlign.RIGHT,
                        modifier = Modifier.testTag(ReaderSettingsTestTags.ALIGN_RIGHT),
                        onClick = { onPreferencesChange(preferences.copy(textAlign = ReadiumTextAlign.RIGHT, publisherStyles = false)) }
                    )
                    AlignButton(
                        icon = Icons.Default.FormatAlignJustify,
                        contentDescription = "Justify",
                        selected = preferences.textAlign == ReadiumTextAlign.JUSTIFY,
                        modifier = Modifier.testTag(ReaderSettingsTestTags.ALIGN_JUSTIFY),
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
                    onCheckedChange = { onPreferencesChange(preferences.copy(scroll = it)) },
                    modifier = Modifier.testTag(ReaderSettingsTestTags.SCROLL_MODE_SWITCH)
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
    onTextFilterChange: (TtsTextFilter) -> Unit,
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
                display = "${String.format(Locale.getDefault(), "%.2f", ttsSettings.speed)}x",
                onReset = { onTtsSpeedChange(1f) },
                onValueChange = onTtsSpeedChange
            )

            SliderSetting(
                label = "Pitch",
                value = ttsSettings.pitch,
                display = "${String.format(Locale.getDefault(), "%.2f", ttsSettings.pitch)}x",
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

            HorizontalDivider()

            TtsTextFilterSection(
                filter = ttsSettings.textFilter,
                onFilterChange = onTextFilterChange
            )

            TextButton(onClick = onOpenSystemTtsSettings) {
                Text("Open Android TTS settings")
            }
        }
    }
}

@Composable
private fun TtsTextFilterSection(
    filter: TtsTextFilter,
    onFilterChange: (TtsTextFilter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Text Filters", style = MaterialTheme.typography.labelLarge)
        Text(
            "Strip characters or patterns from text before it is spoken. Word-level highlighting may be slightly offset when filters are active.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FilterCheckbox("Angle brackets < >", filter.stripAngleBrackets) {
            onFilterChange(filter.copy(stripAngleBrackets = it))
        }
        FilterCheckbox("Square brackets [ ]", filter.stripSquareBrackets) {
            onFilterChange(filter.copy(stripSquareBrackets = it))
        }
        FilterCheckbox("Curly braces { }", filter.stripCurlyBraces) {
            onFilterChange(filter.copy(stripCurlyBraces = it))
        }
        FilterCheckbox("Parentheses ( )", filter.stripParentheses) {
            onFilterChange(filter.copy(stripParentheses = it))
        }
        FilterCheckbox("Asterisks *", filter.stripAsterisks) {
            onFilterChange(filter.copy(stripAsterisks = it))
        }
        FilterCheckbox("Underscores _", filter.stripUnderscores) {
            onFilterChange(filter.copy(stripUnderscores = it))
        }
        FilterCheckbox("Hashes #", filter.stripHashes) {
            onFilterChange(filter.copy(stripHashes = it))
        }
        FilterCheckbox("URLs", filter.stripUrls) {
            onFilterChange(filter.copy(stripUrls = it))
        }

        // Custom regex rules
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Custom Replacements", style = MaterialTheme.typography.labelMedium)
                IconButton(
                    onClick = {
                        onFilterChange(
                            filter.copy(
                                customRules = filter.customRules + TtsRegexRule("", "")
                            )
                        )
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add rule",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            filter.customRules.forEachIndexed { index, rule ->
                CustomRuleRow(
                    rule = rule,
                    onUpdate = { updated ->
                        onFilterChange(
                            filter.copy(
                                customRules = filter.customRules.toMutableList().apply {
                                    set(index, updated)
                                }
                            )
                        )
                    },
                    onRemove = {
                        onFilterChange(
                            filter.copy(
                                customRules = filter.customRules.toMutableList().apply {
                                    removeAt(index)
                                }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun FilterCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun CustomRuleRow(
    rule: TtsRegexRule,
    onUpdate: (TtsRegexRule) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = rule.enabled,
            onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
            modifier = Modifier.size(32.dp)
        )
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { onUpdate(rule.copy(pattern = it)) },
            label = { Text("Pattern") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = rule.replacement,
            onValueChange = { onUpdate(rule.copy(replacement = it)) },
            label = { Text("Replace") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove rule",
                modifier = Modifier.size(18.dp)
            )
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SelectableSettingTile(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SelectableSettingTile(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SelectableSettingTile(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
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
