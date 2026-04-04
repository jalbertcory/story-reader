package com.storyreader.data.sync

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SyncManager"

class SyncManager(
    private val providers: List<SyncProvider>,
    private val syncSettingsStore: SyncSettingsStore
) {

    private val _status = MutableStateFlow<SyncStatus>(restoreLastStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val inFlightMutex = Mutex()
    private var inFlightSync: CompletableDeferred<Result<Unit>>? = null

    fun hasEnabledConfiguredProviders(): Boolean {
        return providers.any { it.isEnabled && it.isConfigured }
    }

    suspend fun syncEnabledProviders(): Result<Unit> {
        val existingSync = inFlightMutex.withLock {
            inFlightSync?.takeIf { it.isActive }?.also {
                Log.d(TAG, "Joining in-flight sync")
            }
        }
        if (existingSync != null) {
            return existingSync.await()
        }

        val deferred = CompletableDeferred<Result<Unit>>()
        val shouldRun = inFlightMutex.withLock {
            val active = inFlightSync
            if (active != null && active.isActive) {
                false
            } else {
                inFlightSync = deferred
                true
            }
        }
        if (!shouldRun) {
            return inFlightMutex.withLock { inFlightSync }?.await() ?: Result.success(Unit)
        }

        val result = try {
            runSyncInternal()
        } catch (t: Throwable) {
            val now = System.currentTimeMillis()
            val errorMessage = t.message ?: "Unknown error"
            val failed = SyncStatus.Failed(errorMessage, now)
            _status.value = failed
            syncSettingsStore.lastSyncTimestampMs = now
            syncSettingsStore.lastSyncError = errorMessage
            Result.failure(t)
        } finally {
            inFlightMutex.withLock {
                if (inFlightSync === deferred) {
                    inFlightSync = null
                }
            }
        }
        deferred.complete(result)
        return result
    }

    private suspend fun runSyncInternal(): Result<Unit> {
        val enabledProviders = providers.filter { it.isEnabled && it.isConfigured }
        if (enabledProviders.isEmpty()) {
            return Result.success(Unit)
        }

        val failures = mutableListOf<String>()
        enabledProviders.forEach { provider ->
            _status.value = SyncStatus.Syncing(provider.displayName)
            provider.sync { progress ->
                _status.value = SyncStatus.Syncing(provider.displayName, progress)
            }.onFailure { error ->
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
