package com.storyreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

@OptIn(ExperimentalMaterial3Api::class)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Reading Settings",
                style = MaterialTheme.typography.titleLarge
            )

            // Font Size
            Column {
                Text("Font Size", style = MaterialTheme.typography.labelLarge)
                val fontSize = (preferences.fontSize ?: 1.0).toFloat()
                Slider(
                    value = fontSize,
                    onValueChange = { onPreferencesChange(preferences.copy(fontSize = it.toDouble())) },
                    valueRange = 0.5f..3.0f,
                    steps = 9
                )
                Text(
                    "${(fontSize * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Theme
            Column {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onPreferencesChange(preferences.copy(theme = null)) }) {
                        Text("Default")
                    }
                    TextButton(onClick = { onPreferencesChange(preferences.copy(theme = Theme.DARK)) }) {
                        Text("Dark")
                    }
                    TextButton(onClick = { onPreferencesChange(preferences.copy(theme = Theme.SEPIA)) }) {
                        Text("Sepia")
                    }
                }
            }

            // Font Family
            Column {
                Text("Font Family", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onPreferencesChange(preferences.copy(fontFamily = null)) }) {
                        Text("Default")
                    }
                    TextButton(onClick = { onPreferencesChange(preferences.copy(fontFamily = FontFamily("serif"))) }) {
                        Text("Serif")
                    }
                    TextButton(onClick = { onPreferencesChange(preferences.copy(fontFamily = FontFamily("sans-serif"))) }) {
                        Text("Sans-Serif")
                    }
                }
            }
        }
    }
}
