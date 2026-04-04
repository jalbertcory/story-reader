package com.storyreader.data.sync

import android.util.Log

class GoogleDriveSyncRepository(
    private val authManager: GoogleDriveAuthManager,
    private val payloadStore: SyncPayloadStore,
    private val googleDriveApi: GoogleDriveApi,
    private val recoveryManager: RemoteBookRecoveryManager
) {

    suspend fun syncBidirectional(): Result<Unit> = runCatching {
        val accessToken = when (val authorization = authManager.authorize().getOrThrow()) {
            is GoogleDriveAuthorizationOutcome.Authorized -> authorization.accessToken
            is GoogleDriveAuthorizationOutcome.NeedsResolution -> {
                throw IllegalStateException("Google Drive needs to be reconnected in Settings before sync can continue")
            }
        }

        val remoteFile = googleDriveApi.findSyncFile(accessToken, SYNC_FILE_NAME).getOrThrow()
        if (remoteFile != null) {
            val remoteJson = googleDriveApi.downloadSyncJson(accessToken, remoteFile.id).getOrThrow()
            payloadStore.mergeRemoteData(remoteJson)
            val recoverySummary = recoveryManager.recoverMissingBooks(remoteJson)
            Log.i(
                TAG,
                "Recovery summary attempted=${recoverySummary.attempted} imported=${recoverySummary.imported} failed=${recoverySummary.failed} skipped=${recoverySummary.skipped}"
            )
            if (recoverySummary.imported > 0) {
                payloadStore.mergeRemoteData(remoteJson)
            }
            val mergedJson = payloadStore.buildLatestJson(remoteJson)
            googleDriveApi.uploadSyncJson(
                accessToken = accessToken,
                fileId = remoteFile.id,
                fileName = SYNC_FILE_NAME,
                payload = mergedJson
            ).getOrThrow()
            return@runCatching
        }

        val mergedJson = payloadStore.buildLatestJson()
        googleDriveApi.uploadSyncJson(
            accessToken = accessToken,
            fileId = remoteFile?.id,
            fileName = SYNC_FILE_NAME,
            payload = mergedJson
        ).getOrThrow()
    }

    companion object {
        private const val TAG = "GoogleDriveSyncRepo"
        private const val SYNC_FILE_NAME = "story_reader_sync_data.json"
    }
}
