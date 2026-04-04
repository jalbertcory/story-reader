package com.storyreader.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storyreader.StoryReaderApplication
import com.storyreader.data.catalog.OpdsCredentialsManager
import com.storyreader.data.sync.GoogleDriveAuthorizationOutcome
import com.storyreader.data.sync.SyncEvent
import com.storyreader.data.sync.SyncScheduler
import com.storyreader.data.sync.SyncStatus
import com.storyreader.ui.components.StoryReaderLinearProgressIndicator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AppSettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val appPassword: String = "",
    val savedMessage: String? = null,
    val hasNextcloudCredentials: Boolean = false,
    val isEditingNextcloudCredentials: Boolean = false,
    val isNextcloudSyncEnabled: Boolean = false,
    val isGoogleDriveSyncEnabled: Boolean = false,
    val hasGoogleDriveAccount: Boolean = false,
    val googleDriveAccountLabel: String? = null,
    val isAuthorizingGoogleDrive: Boolean = false,
    val opdsUrl: String = "",
    val opdsUsername: String = "",
    val opdsPassword: String = "",
    val isOpdsStoryManagerBackend: Boolean = false,
    val hasOpdsConfiguration: Boolean = false,
    val isEditingOpdsConfiguration: Boolean = false,
    val opdsStatus: String = "Needs setup",
    val nextcloudStatus: String = "Needs setup",
    val googleDriveStatus: String = "Needs setup",
    val syncSummary: String = "No sync providers enabled",
    val canSyncNow: Boolean = false,
    val isSyncingNow: Boolean = false
)

sealed interface AppSettingsEvent {
    data class LaunchGoogleAuthorization(val request: IntentSenderRequest) : AppSettingsEvent
    data object ReloadApp : AppSettingsEvent
}

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val nextcloudCredentials = app.credentialsManager
    private val googleDriveCredentials = app.googleDriveCredentialsManager
    private val opdsCredentials = app.opdsCredentialsManager
    private val syncSettingsStore = app.syncSettingsStore
    private val googleDriveAuthManager = app.googleDriveAuthManager

    private val _events = MutableSharedFlow<AppSettingsEvent>()
    val events: SharedFlow<AppSettingsEvent> = _events.asSharedFlow()

    val syncStatus: StateFlow<SyncStatus> = app.syncManager.status

    private val _uiState = MutableStateFlow(
        buildUiState(
            savedMessage = null,
            draftServerUrl = nextcloudCredentials.serverUrl.orEmpty(),
            draftUsername = nextcloudCredentials.username.orEmpty(),
            draftAppPassword = nextcloudCredentials.appPassword.orEmpty(),
            draftOpdsUrl = opdsCredentials.url.orEmpty(),
            draftOpdsUsername = if (opdsCredentials.isStoryManagerBackend) {
                OpdsCredentialsManager.STORY_MANAGER_USERNAME
            } else {
                opdsCredentials.username.orEmpty()
            },
            draftOpdsPassword = opdsCredentials.password.orEmpty()
        )
    )
    val uiState: StateFlow<AppSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            app.syncManager.events.collect { event ->
                when (event) {
                    SyncEvent.ReloadAppRequested -> _events.emit(AppSettingsEvent.ReloadApp)
                }
            }
        }
    }

    fun onServerUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value, savedMessage = null)
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, savedMessage = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(appPassword = value, savedMessage = null)
    }

    fun onOpdsUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(opdsUrl = value, savedMessage = null)
    }

    fun onOpdsUsernameChange(value: String) {
        if (_uiState.value.isOpdsStoryManagerBackend) return
        _uiState.value = _uiState.value.copy(opdsUsername = value, savedMessage = null)
    }

    fun onOpdsPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(opdsPassword = value, savedMessage = null)
    }

    fun onOpdsStoryManagerChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            isOpdsStoryManagerBackend = enabled,
            opdsUsername = if (enabled) OpdsCredentialsManager.STORY_MANAGER_USERNAME else _uiState.value.opdsUsername,
            savedMessage = null
        )
    }

    fun saveOpdsSettings() {
        val state = _uiState.value
        opdsCredentials.url = state.opdsUrl.trim()
        opdsCredentials.isStoryManagerBackend = state.isOpdsStoryManagerBackend
        opdsCredentials.username = if (state.isOpdsStoryManagerBackend) {
            OpdsCredentialsManager.STORY_MANAGER_USERNAME
        } else {
            state.opdsUsername.trim()
        }
        opdsCredentials.password = state.opdsPassword
        refreshUi(savedMessage = "OPDS settings saved")
    }

    fun clearOpdsSettings() {
        opdsCredentials.clear()
        _uiState.value = buildUiState(
            savedMessage = "OPDS settings cleared",
            forceEditingNextcloud = _uiState.value.isEditingNextcloudCredentials,
            forceEditingOpds = true,
            draftServerUrl = _uiState.value.serverUrl,
            draftUsername = _uiState.value.username,
            draftAppPassword = _uiState.value.appPassword,
            draftOpdsUrl = "",
            draftOpdsUsername = "",
            draftOpdsPassword = ""
        )
    }

    fun startEditingOpdsConfiguration() {
        _uiState.value = _uiState.value.copy(
            isEditingOpdsConfiguration = true,
            savedMessage = null
        )
    }

    fun saveNextcloudCredentials() {
        val state = _uiState.value
        nextcloudCredentials.serverUrl = state.serverUrl.trim()
        nextcloudCredentials.username = state.username.trim()
        nextcloudCredentials.appPassword = state.appPassword
        syncSettingsStore.isNextcloudEnabled = nextcloudCredentials.hasCredentials
        refreshUi(savedMessage = "Nextcloud settings saved")
        refreshSyncScheduling()
    }

    fun clearNextcloudCredentials() {
        nextcloudCredentials.clear()
        syncSettingsStore.isNextcloudEnabled = false
        refreshUi(savedMessage = "Nextcloud settings cleared", forceEditingNextcloud = true)
        refreshSyncScheduling()
    }

    fun startEditingNextcloudCredentials() {
        _uiState.value = _uiState.value.copy(
            isEditingNextcloudCredentials = true,
            savedMessage = null
        )
    }

    fun onNextcloudEnabledChange(enabled: Boolean) {
        syncSettingsStore.isNextcloudEnabled = enabled && nextcloudCredentials.hasCredentials
        refreshUi(
            savedMessage = if (enabled && nextcloudCredentials.hasCredentials) {
                "Nextcloud sync enabled"
            } else if (enabled) {
                "Add Nextcloud credentials to enable sync"
            } else {
                "Nextcloud sync disabled"
            }
        )
        refreshSyncScheduling()
    }

    fun onGoogleDriveEnabledChange(enabled: Boolean) {
        if (enabled && !googleDriveCredentials.hasAccount) {
            connectGoogleDrive(enableAfterAuthorization = true)
            return
        }
        syncSettingsStore.isGoogleDriveEnabled = enabled && googleDriveCredentials.hasAccount
        refreshUi(
            savedMessage = if (syncSettingsStore.isGoogleDriveEnabled) {
                "Google Drive sync enabled"
            } else {
                "Google Drive sync disabled"
            }
        )
        refreshSyncScheduling()
    }

    fun connectGoogleDrive(enableAfterAuthorization: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isAuthorizingGoogleDrive = true,
                savedMessage = null
            )
            googleDriveAuthManager.authorize()
                .onSuccess { outcome ->
                    when (outcome) {
                        is GoogleDriveAuthorizationOutcome.Authorized -> {
                            if (enableAfterAuthorization) {
                                syncSettingsStore.isGoogleDriveEnabled = true
                            }
                            refreshUi(savedMessage = "Google Drive connected")
                            refreshSyncScheduling()
                        }
                        is GoogleDriveAuthorizationOutcome.NeedsResolution -> {
                            _events.emit(
                                AppSettingsEvent.LaunchGoogleAuthorization(
                                    IntentSenderRequest.Builder(outcome.intentSender).build()
                                )
                            )
                            _uiState.value = _uiState.value.copy(
                                isAuthorizingGoogleDrive = false
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isAuthorizingGoogleDrive = false,
                        savedMessage = error.message ?: "Google Drive connection failed"
                    )
                }
        }
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            googleDriveAuthManager.revokeAccess()
                .onSuccess {
                    syncSettingsStore.isGoogleDriveEnabled = false
                    refreshUi(savedMessage = "Google Drive disconnected")
                    refreshSyncScheduling()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        savedMessage = error.message ?: "Failed to disconnect Google Drive"
                    )
                }
        }
    }

    fun onGoogleDriveAuthorizationResult(data: Intent?) {
        viewModelScope.launch {
            googleDriveAuthManager.consumeAuthorizationResult(data)
                .onSuccess {
                    syncSettingsStore.isGoogleDriveEnabled = true
                    refreshUi(savedMessage = "Google Drive connected")
                    refreshSyncScheduling()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isAuthorizingGoogleDrive = false,
                        savedMessage = error.message ?: "Google Drive sign-in failed"
                    )
                }
        }
    }

    fun syncNow() {
        if (!app.syncManager.hasEnabledConfiguredProviders()) {
            _uiState.value = _uiState.value.copy(savedMessage = "Enable at least one configured sync provider first")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncingNow = true, savedMessage = null)
            app.syncManager.syncEnabledProviders()
                .onSuccess {
                    refreshUi(savedMessage = "Sync completed")
                }
                .onFailure { error ->
                    _uiState.value = buildUiState(
                        savedMessage = error.message ?: "Sync failed",
                        forceEditingNextcloud = _uiState.value.isEditingNextcloudCredentials,
                        forceEditingOpds = _uiState.value.isEditingOpdsConfiguration,
                        draftServerUrl = _uiState.value.serverUrl,
                        draftUsername = _uiState.value.username,
                        draftAppPassword = _uiState.value.appPassword,
                        draftOpdsUrl = _uiState.value.opdsUrl,
                        draftOpdsUsername = _uiState.value.opdsUsername,
                        draftOpdsPassword = _uiState.value.opdsPassword,
                        isSyncingNow = false
                    )
                }
        }
    }

    private fun refreshUi(
        savedMessage: String?,
        forceEditingNextcloud: Boolean? = null,
        forceEditingOpds: Boolean? = null,
        isSyncingNow: Boolean = false
    ) {
        _uiState.value = buildUiState(
            savedMessage = savedMessage,
            forceEditingNextcloud = forceEditingNextcloud,
            forceEditingOpds = forceEditingOpds,
            draftServerUrl = _uiState.value.serverUrl,
            draftUsername = _uiState.value.username,
            draftAppPassword = _uiState.value.appPassword,
            draftOpdsUrl = _uiState.value.opdsUrl,
            draftOpdsUsername = _uiState.value.opdsUsername,
            draftOpdsPassword = _uiState.value.opdsPassword,
            isSyncingNow = isSyncingNow
        )
    }

    private fun buildUiState(
        savedMessage: String?,
        forceEditingNextcloud: Boolean? = null,
        forceEditingOpds: Boolean? = null,
        draftServerUrl: String = "",
        draftUsername: String = "",
        draftAppPassword: String = "",
        draftOpdsUrl: String = "",
        draftOpdsUsername: String = "",
        draftOpdsPassword: String = "",
        isSyncingNow: Boolean = false
    ): AppSettingsUiState {
        val hasNextcloudCredentials = nextcloudCredentials.hasCredentials
        val storedOpdsCredentials = opdsCredentials.currentCredentials()
        val nextcloudEnabled = syncSettingsStore.isNextcloudEnabled && hasNextcloudCredentials
        val googleDriveEnabled = syncSettingsStore.isGoogleDriveEnabled && googleDriveCredentials.hasAccount
        val canSyncNow = app.syncManager.hasEnabledConfiguredProviders()
        return AppSettingsUiState(
            serverUrl = if (hasNextcloudCredentials) nextcloudCredentials.serverUrl.orEmpty() else draftServerUrl,
            username = if (hasNextcloudCredentials) nextcloudCredentials.username.orEmpty() else draftUsername,
            appPassword = if (hasNextcloudCredentials) nextcloudCredentials.appPassword.orEmpty() else draftAppPassword,
            savedMessage = savedMessage,
            hasNextcloudCredentials = hasNextcloudCredentials,
            isEditingNextcloudCredentials = forceEditingNextcloud ?: !hasNextcloudCredentials,
            isNextcloudSyncEnabled = nextcloudEnabled,
            isGoogleDriveSyncEnabled = googleDriveEnabled,
            hasGoogleDriveAccount = googleDriveCredentials.hasAccount,
            googleDriveAccountLabel = googleDriveCredentials.displayLabel(),
            isAuthorizingGoogleDrive = false,
            opdsUrl = storedOpdsCredentials?.baseUrl ?: draftOpdsUrl,
            opdsUsername = when {
                opdsCredentials.isStoryManagerBackend -> OpdsCredentialsManager.STORY_MANAGER_USERNAME
                storedOpdsCredentials != null -> storedOpdsCredentials.username
                else -> draftOpdsUsername
            },
            opdsPassword = storedOpdsCredentials?.password ?: draftOpdsPassword,
            isOpdsStoryManagerBackend = opdsCredentials.isStoryManagerBackend,
            hasOpdsConfiguration = storedOpdsCredentials != null,
            isEditingOpdsConfiguration = forceEditingOpds ?: storedOpdsCredentials == null,
            opdsStatus = when {
                storedOpdsCredentials != null && storedOpdsCredentials.isStoryManagerBackend -> "Saved for Story Manager backend"
                storedOpdsCredentials != null -> "Saved for standard OPDS browsing"
                else -> "Needs setup"
            },
            nextcloudStatus = providerStatusText(hasNextcloudCredentials, nextcloudEnabled),
            googleDriveStatus = providerStatusText(googleDriveCredentials.hasAccount, googleDriveEnabled),
            syncSummary = when {
                nextcloudEnabled && googleDriveEnabled -> "2 providers are active for sync"
                nextcloudEnabled || googleDriveEnabled -> "1 provider is active for sync"
                hasNextcloudCredentials || googleDriveCredentials.hasAccount -> "Providers are connected but sync is turned off"
                else -> "No sync providers enabled"
            },
            canSyncNow = canSyncNow,
            isSyncingNow = isSyncingNow
        )
    }

    private fun providerStatusText(isConfigured: Boolean, isEnabled: Boolean): String {
        return when {
            isConfigured && isEnabled -> "Connected and syncing"
            isConfigured -> "Connected, sync is off"
            else -> "Needs setup"
        }
    }

    private fun refreshSyncScheduling() {
        if (app.syncManager.hasEnabledConfiguredProviders()) {
            SyncScheduler.schedulePeriodicSync(getApplication())
            SyncScheduler.scheduleImmediateSync(getApplication())
        } else {
            SyncScheduler.cancelSync(getApplication())
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    viewModel: AppSettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onGoogleDriveAuthorizationResult(result.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppSettingsEvent.LaunchGoogleAuthorization -> {
                    authorizationLauncher.launch(event.request)
                }
                AppSettingsEvent.ReloadApp -> {
                    (context as? Activity)?.recreate()
                }
            }
        }
    }

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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            uiState.savedMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            CollapsibleSection(title = "Sync Providers", subtitle = uiState.syncSummary) {
            SyncStatusLine(syncStatus)
            Button(
                onClick = viewModel::syncNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.canSyncNow &&
                    !uiState.isSyncingNow &&
                    syncStatus !is SyncStatus.Syncing
            ) {
                Text(
                    when {
                        syncStatus is SyncStatus.Syncing -> syncButtonLabel(syncStatus as SyncStatus.Syncing)
                        uiState.isSyncingNow -> "Syncing..."
                        else -> "Sync Now"
                    }
                )
            }

            SyncProviderCard(
                title = "Nextcloud",
                enabled = uiState.isNextcloudSyncEnabled,
                onEnabledChange = viewModel::onNextcloudEnabledChange
            ) {
                Text(
                    text = uiState.nextcloudStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.hasNextcloudCredentials && !uiState.isEditingNextcloudCredentials) {
                    Text(
                        uiState.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "User: ${uiState.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = viewModel::startEditingNextcloudCredentials) {
                        Text("Edit Credentials")
                    }
                } else {
                    Text(
                        "Connect to a Nextcloud server using an App Password (Settings -> Security -> App Passwords).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KeyboardAwareOutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = viewModel::onServerUrlChange,
                        label = { Text("Server URL") },
                        placeholder = { Text("https://cloud.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    KeyboardAwareOutlinedTextField(
                        value = uiState.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    KeyboardAwareOutlinedTextField(
                        value = uiState.appPassword,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("App Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::saveNextcloudCredentials, modifier = Modifier.weight(1f)) {
                            Text("Save")
                        }
                        OutlinedButton(onClick = viewModel::clearNextcloudCredentials, modifier = Modifier.weight(1f)) {
                            Text("Clear")
                        }
                    }
                }
            }

            SyncProviderCard(
                title = "Google Drive",
                enabled = uiState.isGoogleDriveSyncEnabled,
                onEnabledChange = viewModel::onGoogleDriveEnabledChange
            ) {
                Text(
                    text = uiState.googleDriveStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Use Google Drive for EPUB imports and sync backups. The first connection will ask for Drive access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.hasGoogleDriveAccount) {
                    uiState.googleDriveAccountLabel?.let { account ->
                        Text(
                            account,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.connectGoogleDrive(enableAfterAuthorization = false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reconnect")
                        }
                        OutlinedButton(
                            onClick = viewModel::disconnectGoogleDrive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect")
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.connectGoogleDrive(enableAfterAuthorization = true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uiState.isAuthorizingGoogleDrive) "Connecting..." else "Connect Google Drive")
                    }
                }
            }

            } // end CollapsibleSection "Sync Providers"

            HorizontalDivider()

            CollapsibleSection(title = "Book Sources", subtitle = uiState.opdsStatus) {

            SourceSettingsCard(title = "OPDS Catalog") {
                Text(
                    text = uiState.opdsStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.hasOpdsConfiguration && !uiState.isEditingOpdsConfiguration) {
                    Text(
                        uiState.opdsUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "User: ${uiState.opdsUsername}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = viewModel::startEditingOpdsConfiguration) {
                        Text("Edit Source")
                    }
                } else {
                    Text(
                        text = "Save a default OPDS server for browsing. Story Manager backends lock the username to `reader`.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KeyboardAwareOutlinedTextField(
                        value = uiState.opdsUrl,
                        onValueChange = viewModel::onOpdsUrlChange,
                        label = { Text("Catalog URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Story Manager backend", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = uiState.isOpdsStoryManagerBackend,
                            onCheckedChange = viewModel::onOpdsStoryManagerChange
                        )
                    }
                    KeyboardAwareOutlinedTextField(
                        value = uiState.opdsUsername,
                        onValueChange = viewModel::onOpdsUsernameChange,
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isOpdsStoryManagerBackend,
                        singleLine = true
                    )
                    KeyboardAwareOutlinedTextField(
                        value = uiState.opdsPassword,
                        onValueChange = viewModel::onOpdsPasswordChange,
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::saveOpdsSettings, modifier = Modifier.weight(1f)) {
                            Text("Save")
                        }
                        OutlinedButton(onClick = viewModel::clearOpdsSettings, modifier = Modifier.weight(1f)) {
                            Text("Clear")
                        }
                    }
                }
            }

            } // end CollapsibleSection "Book Sources"
        }
    }
}

@Composable
private fun SyncProviderCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        content()
    }
}

@Composable
private fun SourceSettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!expanded && subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            content()
        }
    }
}

@Composable
private fun SyncStatusLine(syncStatus: SyncStatus) {
    when (syncStatus) {
        is SyncStatus.Idle -> Unit
        is SyncStatus.Syncing -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = syncStatusText(syncStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val progress = syncStatus.progress?.fraction
                if (progress != null) {
                    StoryReaderLinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is SyncStatus.Completed -> {
            Text(
                text = "Last synced ${
                    DateUtils.getRelativeTimeSpanString(
                        syncStatus.timestampMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is SyncStatus.Failed -> {
            Text(
                text = "Last sync failed: ${syncStatus.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun syncButtonLabel(syncStatus: SyncStatus.Syncing): String {
    val progress = syncStatus.progress
    val detail = progress?.message
    val completed = progress?.completed
    val total = progress?.total
    return when {
        detail != null && completed != null && total != null && total > 0 ->
            "$detail $completed/$total"
        detail != null -> detail
        else -> "Syncing ${syncStatus.providerName}..."
    }
}

private fun syncStatusText(syncStatus: SyncStatus.Syncing): String {
    val progress = syncStatus.progress
    val detail = progress?.message
    val completed = progress?.completed
    val total = progress?.total
    return when {
        detail != null && completed != null && total != null && total > 0 ->
            "Syncing ${syncStatus.providerName}: $detail $completed/$total"
        detail != null -> "Syncing ${syncStatus.providerName}: $detail"
        else -> "Syncing ${syncStatus.providerName}..."
    }
}

@Composable
private fun KeyboardAwareOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        delay(250)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    )
}
