package com.storyreader.data.sync

import com.storyreader.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SyncManager"

sealed interface SyncEvent {
    data object ReloadAppRequested : SyncEvent
}

class SyncManager(
    private val providers: List<SyncProvider>,
    private val syncSettingsStore: SyncSettingsStore,
    private val countLocalBooks: suspend () -> Int = { 0 },
    private val onBooksAdded: (Int) -> Unit = {}
) {

    private val _status = MutableStateFlow<SyncStatus>(restoreLastStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()
    private val inFlightMutex = Mutex()
    private var inFlightSync: CompletableDeferred<Result<Unit>>? = null

    fun hasEnabledConfiguredProviders(): Boolean {
        return providers.any { it.isEnabled && it.isConfigured }
    }

    suspend fun syncEnabledProviders(): Result<Unit> {
        val existingSync = inFlightMutex.withLock {
            inFlightSync?.takeIf { it.isActive }?.also {
                DebugLog.d(TAG) { "Joining in-flight sync" }
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

        val startingBookCount = countLocalBooks()
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
            val endingBookCount = countLocalBooks()
            val addedBooks = (endingBookCount - startingBookCount).coerceAtLeast(0)
            if (addedBooks > 0) {
                onBooksAdded(addedBooks)
                if (startingBookCount == 0) {
                    _events.tryEmit(SyncEvent.ReloadAppRequested)
                }
            }
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
