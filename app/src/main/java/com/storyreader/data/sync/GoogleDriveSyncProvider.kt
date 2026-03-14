package com.storyreader.data.sync

class GoogleDriveSyncProvider(
    private val settingsStore: SyncSettingsStore,
    private val credentialsManager: GoogleDriveCredentialsManager,
    private val repository: GoogleDriveSyncRepository
) : SyncProvider {

    override val id: String = "google_drive"
    override val displayName: String = "Google Drive"
    override val isEnabled: Boolean
        get() = settingsStore.isGoogleDriveEnabled
    override val isConfigured: Boolean
        get() = credentialsManager.hasAccount

    override suspend fun sync(): Result<Unit> = repository.syncBidirectional()
}
