package com.storyreader.data.sync

interface SyncProvider {
    val id: String
    val displayName: String
    val isEnabled: Boolean
    val isConfigured: Boolean

    suspend fun sync(): Result<Unit>
}
