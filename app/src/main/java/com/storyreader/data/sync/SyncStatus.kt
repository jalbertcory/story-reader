package com.storyreader.data.sync

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Syncing(val providerName: String) : SyncStatus
    data class Completed(val timestampMs: Long) : SyncStatus
    data class Failed(val message: String, val timestampMs: Long) : SyncStatus
}
