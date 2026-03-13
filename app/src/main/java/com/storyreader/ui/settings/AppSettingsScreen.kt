package com.storyreader.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storyreader.StoryReaderApplication
import com.storyreader.data.sync.SyncCredentialsManager
import com.storyreader.data.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor

private const val PREFS_NAME = "reader_preferences"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_THEME = "theme"
private const val KEY_FONT_FAMILY = "font_family"
private const val KEY_IS_NIGHT = "is_night_theme"

// Sentinel values for Night theme
private val NIGHT_BG_INT = 0xFF000000.toInt()
private val NIGHT_TEXT_INT = 0xFFFF7722.toInt()

@OptIn(ExperimentalReadiumApi::class)
private fun EpubPreferences.isNightTheme() =
    theme == null && backgroundColor?.int == NIGHT_BG_INT

private data class ThemePreview(val bg: Color, val text: Color, val label: String)
private val themeDefault = ThemePreview(Color.White, Color(0xFF1C1B1F), "Default")
private val themeDark = ThemePreview(Color(0xFF1A1A2E), Color(0xFFE0E0E0), "Dark")
private val themeSepia = ThemePreview(Color(0xFFF5EBD7), Color(0xFF5C4033), "Sepia")
private val themeNight = ThemePreview(Color.Black, Color(0xFFFF7722), "Night")

data class AppSettingsUiState(
    val preferences: EpubPreferences,
    val serverUrl: String = "",
    val username: String = "",
    val appPassword: String = "",
    val savedMessage: String? = null
)

@OptIn(ExperimentalReadiumApi::class)
class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val prefStore = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val credManager = SyncCredentialsManager.create(application)

    private val _uiState = MutableStateFlow(
        AppSettingsUiState(
            preferences = loadPrefs(),
            serverUrl = credManager.serverUrl ?: "",
            username = credManager.username ?: "",
            appPassword = credManager.appPassword ?: ""
        )
    )
    val uiState: StateFlow<AppSettingsUiState> = _uiState.asStateFlow()

    private fun loadPrefs(): EpubPreferences {
        val fontSize = prefStore.getFloat(KEY_FONT_SIZE, Float.MIN_VALUE)
            .takeIf { it != Float.MIN_VALUE }?.toDouble()
        val theme = when (prefStore.getString(KEY_THEME, null)) {
            "DARK" -> Theme.DARK
            "SEPIA" -> Theme.SEPIA
            else -> null
        }
        val fontFamily = prefStore.getString(KEY_FONT_FAMILY, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { org.readium.r2.navigator.preferences.FontFamily(it) }
        val isNight = prefStore.getBoolean(KEY_IS_NIGHT, false)
        return if (isNight) {
            EpubPreferences(
                fontSize = fontSize, fontFamily = fontFamily, theme = null,
                backgroundColor = ReadiumColor(NIGHT_BG_INT),
                textColor = ReadiumColor(NIGHT_TEXT_INT),
                publisherStyles = false
            )
        } else {
            EpubPreferences(fontSize = fontSize, theme = theme, fontFamily = fontFamily)
        }
    }

    fun updatePreferences(transform: (EpubPreferences) -> EpubPreferences) {
        val updated = transform(_uiState.value.preferences)
        _uiState.value = _uiState.value.copy(preferences = updated)
        savePrefs(updated)
    }

    private fun savePrefs(prefs: EpubPreferences) {
        val isNight = prefs.backgroundColor?.int == NIGHT_BG_INT
        prefStore.edit().apply {
            if (prefs.fontSize != null) putFloat(KEY_FONT_SIZE, prefs.fontSize!!.toFloat())
            else remove(KEY_FONT_SIZE)
            putString(KEY_THEME, when (prefs.theme) {
                Theme.DARK -> "DARK"
                Theme.SEPIA -> "SEPIA"
                else -> null
            })
            putString(KEY_FONT_FAMILY, prefs.fontFamily?.name ?: "")
            putBoolean(KEY_IS_NIGHT, isNight)
        }.apply()
        app.isDarkReadingTheme.value = prefs.theme == Theme.DARK || isNight
    }

    fun onServerUrlChange(v: String) { _uiState.value = _uiState.value.copy(serverUrl = v, savedMessage = null) }
    fun onUsernameChange(v: String) { _uiState.value = _uiState.value.copy(username = v, savedMessage = null) }
    fun onPasswordChange(v: String) { _uiState.value = _uiState.value.copy(appPassword = v, savedMessage = null) }

    fun saveCredentials() {
        val s = _uiState.value
        credManager.serverUrl = s.serverUrl.trim()
        credManager.username = s.username.trim()
        credManager.appPassword = s.appPassword
        _uiState.value = s.copy(savedMessage = "Credentials saved")
        if (credManager.hasCredentials) SyncScheduler.schedulePeriodicSync(getApplication())
    }

    fun clearCredentials() {
        credManager.clear()
        _uiState.value = _uiState.value.copy(serverUrl = "", username = "", appPassword = "", savedMessage = "Credentials cleared")
        SyncScheduler.cancelSync(getApplication())
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    viewModel: AppSettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val prefs = uiState.preferences

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Reading Preferences ──────────────────────────────────────────────
            Text("Reading Preferences", style = MaterialTheme.typography.titleMedium)

            // Font Size
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Font Size", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val fontSize = (prefs.fontSize ?: 1.0).toFloat()
                        Text(
                            "${(fontSize * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { viewModel.updatePreferences { it.copy(fontSize = 1.0) } }) {
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Slider(
                    value = (prefs.fontSize ?: 1.0).toFloat(),
                    onValueChange = { viewModel.updatePreferences { p -> p.copy(fontSize = it.toDouble()) } },
                    valueRange = 0.5f..2.0f,
                    steps = 29
                )
            }

            // Theme
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isNight = prefs.isNightTheme()
                    ThemeBtn(themeDefault, !isNight && prefs.theme == null) {
                        viewModel.updatePreferences {
                            it.copy(theme = null, backgroundColor = null, textColor = null, publisherStyles = null)
                        }
                    }
                    ThemeBtn(themeDark, prefs.theme == Theme.DARK) {
                        viewModel.updatePreferences {
                            it.copy(theme = Theme.DARK, backgroundColor = null, textColor = null, publisherStyles = null)
                        }
                    }
                    ThemeBtn(themeSepia, prefs.theme == Theme.SEPIA) {
                        viewModel.updatePreferences {
                            it.copy(theme = Theme.SEPIA, backgroundColor = null, textColor = null, publisherStyles = null)
                        }
                    }
                    ThemeBtn(themeNight, isNight) {
                        viewModel.updatePreferences {
                            it.copy(
                                theme = null,
                                backgroundColor = ReadiumColor(NIGHT_BG_INT),
                                textColor = ReadiumColor(NIGHT_TEXT_INT),
                                publisherStyles = false
                            )
                        }
                    }
                }
            }

            // Font Family
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Font", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontBtn("Default", FontFamily.Default, prefs.fontFamily == null) {
                        viewModel.updatePreferences { it.copy(fontFamily = null) }
                    }
                    FontBtn("Serif", FontFamily.Serif, prefs.fontFamily?.name == "serif") {
                        viewModel.updatePreferences {
                            it.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("serif"))
                        }
                    }
                    FontBtn("Sans", FontFamily.SansSerif, prefs.fontFamily?.name == "sans-serif") {
                        viewModel.updatePreferences {
                            it.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("sans-serif"))
                        }
                    }
                    FontBtn("Mono", FontFamily.Monospace, prefs.fontFamily?.name == "monospace") {
                        viewModel.updatePreferences {
                            it.copy(fontFamily = org.readium.r2.navigator.preferences.FontFamily("monospace"))
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Nextcloud / Sync ─────────────────────────────────────────────────
            Text("Nextcloud Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "Connect to a Nextcloud server using an App Password (Settings → Security → App Passwords).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://cloud.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = uiState.appPassword,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("App Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::saveCredentials, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(onClick = viewModel::clearCredentials, modifier = Modifier.weight(1f)) { Text("Clear") }
            }
            uiState.savedMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ThemeBtn(preview: ThemePreview, selected: Boolean, onClick: () -> Unit) {
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
            Text(preview.label, color = preview.text, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun FontBtn(label: String, composeFontFamily: FontFamily, selected: Boolean, onClick: () -> Unit) {
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
            Text(label, fontFamily = composeFontFamily, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
