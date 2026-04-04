package com.storyreader.data.sync

data class SyncProgress(
    val message: String? = null,
    val completed: Int? = null,
    val total: Int? = null
) {
    val fraction: Float?
        get() = if (completed != null && total != null && total > 0) {
            completed.coerceIn(0, total).toFloat() / total.toFloat()
        } else {
            null
        }
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Syncing(
        val providerName: String,
        val progress: SyncProgress? = null
    ) : SyncStatus
    data class Completed(val timestampMs: Long) : SyncStatus
    data class Failed(val message: String, val timestampMs: Long) : SyncStatus
}
