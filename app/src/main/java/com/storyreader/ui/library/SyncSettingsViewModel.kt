package com.storyreader.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.storyreader.data.sync.SyncCredentialsManager
import com.storyreader.data.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncSettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val appPassword: String = "",
    val savedMessage: String? = null
)

class SyncSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val credentialsManager = SyncCredentialsManager(application)

    private val _uiState = MutableStateFlow(
        SyncSettingsUiState(
            serverUrl = credentialsManager.serverUrl ?: "",
            username = credentialsManager.username ?: "",
            appPassword = credentialsManager.appPassword ?: ""
        )
    )
    val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value, savedMessage = null)
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, savedMessage = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(appPassword = value, savedMessage = null)
    }

    fun saveCredentials() {
        val state = _uiState.value
        credentialsManager.serverUrl = state.serverUrl.trim()
        credentialsManager.username = state.username.trim()
        credentialsManager.appPassword = state.appPassword
        _uiState.value = state.copy(savedMessage = "Credentials saved")

        if (credentialsManager.hasCredentials) {
            SyncScheduler.schedulePeriodicSync(getApplication())
        }
    }

    fun clearCredentials() {
        credentialsManager.clear()
        _uiState.value = SyncSettingsUiState(savedMessage = "Credentials cleared")
        SyncScheduler.cancelSync(getApplication())
    }
}
