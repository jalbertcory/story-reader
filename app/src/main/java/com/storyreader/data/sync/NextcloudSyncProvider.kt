package com.storyreader.data.sync

class NextcloudSyncProvider(
    private val settingsStore: SyncSettingsStore,
    private val credentialsManager: SyncCredentialsManager,
    private val repository: WebDavSyncRepository
) : SyncProvider {

    override val id: String = "nextcloud"
    override val displayName: String = "Nextcloud"
    override val isEnabled: Boolean
        get() = settingsStore.isNextcloudEnabled
    override val isConfigured: Boolean
        get() = credentialsManager.hasCredentials

    override suspend fun sync(): Result<Unit> = repository.syncBidirectional()
}
