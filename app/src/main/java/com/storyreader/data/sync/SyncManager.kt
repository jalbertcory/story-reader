package com.storyreader.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SyncManager(
    private val providers: List<SyncProvider>,
    private val syncSettingsStore: SyncSettingsStore
) {

    private val _status = MutableStateFlow<SyncStatus>(restoreLastStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun hasEnabledConfiguredProviders(): Boolean {
        return providers.any { it.isEnabled && it.isConfigured }
    }

    suspend fun syncEnabledProviders(): Result<Unit> {
        val enabledProviders = providers.filter { it.isEnabled && it.isConfigured }
        if (enabledProviders.isEmpty()) {
            return Result.success(Unit)
        }

        val failures = mutableListOf<String>()
        enabledProviders.forEach { provider ->
            _status.value = SyncStatus.Syncing(provider.displayName)
            provider.sync().onFailure { error ->
                failures += "${provider.displayName}: ${error.message ?: "Unknown error"}"
            }
        }

        val now = System.currentTimeMillis()
        return if (failures.isEmpty()) {
            val completed = SyncStatus.Completed(now)
            _status.value = completed
            syncSettingsStore.lastSyncTimestampMs = now
            syncSettingsStore.lastSyncError = null
            Result.success(Unit)
        } else {
            val errorMessage = failures.joinToString(separator = "\n")
            val failed = SyncStatus.Failed(errorMessage, now)
            _status.value = failed
            syncSettingsStore.lastSyncTimestampMs = now
            syncSettingsStore.lastSyncError = errorMessage
            Result.failure(IllegalStateException(errorMessage))
        }
    }

    private fun restoreLastStatus(): SyncStatus {
        val ts = syncSettingsStore.lastSyncTimestampMs
        if (ts == 0L) return SyncStatus.Idle
        val error = syncSettingsStore.lastSyncError
        return if (error != null) {
            SyncStatus.Failed(error, ts)
        } else {
            SyncStatus.Completed(ts)
        }
    }
}
