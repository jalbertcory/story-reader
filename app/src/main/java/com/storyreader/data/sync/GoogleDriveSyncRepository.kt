package com.storyreader.data.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleDriveSyncRepository(
    private val authManager: GoogleDriveAuthManager,
    private val payloadStore: SyncPayloadStore,
    private val googleDriveApi: GoogleDriveApi,
    private val recoveryManager: RemoteBookRecoveryManager
) {

    suspend fun syncBidirectional(onProgress: (SyncProgress) -> Unit = {}): Result<Unit> =
        runCatching {
            val accessToken = when (val authorization = authManager.authorize().getOrThrow()) {
                is GoogleDriveAuthorizationOutcome.Authorized -> authorization.accessToken
                is GoogleDriveAuthorizationOutcome.NeedsResolution -> {
                    throw IllegalStateException("Google Drive needs to be reconnected in Settings before sync can continue")
                }
            }

            withContext(Dispatchers.IO) {
                onProgress(SyncProgress(message = "Checking remote backup"))
                val remoteFile = googleDriveApi.findSyncFile(accessToken, SYNC_FILE_NAME).getOrThrow()
                if (remoteFile != null) {
                    onProgress(SyncProgress(message = "Downloading sync data"))
                    val remoteJson = googleDriveApi.downloadSyncJson(accessToken, remoteFile.id).getOrThrow()
                    onProgress(SyncProgress(message = "Merging remote progress"))
                    payloadStore.mergeRemoteData(remoteJson)
                    onProgress(SyncProgress(message = "Restoring missing books"))
                    val recoverySummary = recoveryManager.recoverMissingBooks(remoteJson) { progress ->
                        onProgress(SyncProgress(
                            message = "Restoring books",
                            completed = progress.completed,
                            total = progress.total
                        ))
                    }
                    Log.i(
                        TAG,
                        "Recovery summary attempted=${recoverySummary.attempted} imported=${recoverySummary.imported} failed=${recoverySummary.failed} skipped=${recoverySummary.skipped}"
                    )
                    if (recoverySummary.imported > 0) {
                        onProgress(SyncProgress(message = "Applying restored progress"))
                        payloadStore.mergeRemoteData(remoteJson)
                    }
                    onProgress(SyncProgress(message = "Uploading merged backup"))
                    val mergedJson = payloadStore.buildLatestJson(remoteJson)
                    googleDriveApi.uploadSyncJson(
                        accessToken = accessToken,
                        fileId = remoteFile.id,
                        fileName = SYNC_FILE_NAME,
                        payload = mergedJson
                    ).getOrThrow()
                    return@withContext
                }

                onProgress(SyncProgress(message = "Creating initial backup"))
                val mergedJson = payloadStore.buildLatestJson()
                onProgress(SyncProgress(message = "Uploading merged backup"))
                googleDriveApi.uploadSyncJson(
                    accessToken = accessToken,
                    fileId = remoteFile?.id,
                    fileName = SYNC_FILE_NAME,
                    payload = mergedJson
                ).getOrThrow()
            }
        }.onFailure { error ->
            Log.w(TAG, "Google Drive sync failed", error)
        }

    companion object {
        private const val TAG = "GoogleDriveSyncRepo"
        private const val SYNC_FILE_NAME = "story_reader_sync_data.json"
    }
}
