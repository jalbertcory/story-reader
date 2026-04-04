package com.storyreader.data.sync

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.ResourceType
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.util.Log
import java.io.File

private const val TAG = "WebDavSyncRepo"

class WebDavSyncRepository(
    private val credentialsManager: SyncCredentialsManager,
    positionDao: ReadingPositionDao,
    sessionDao: ReadingSessionDao,
    bookDao: com.storyreader.data.db.dao.BookDao? = null,
    private val recoveryManager: RemoteBookRecoveryManager? = null
) {
    private val payloadStore = SyncPayloadStore(positionDao, sessionDao, bookDao)

    private fun createClient(): OkHttpClient {
        val authHandler = BasicDigestAuthHandler(
            domain = null,
            username = credentialsManager.username ?: "",
            password = credentialsManager.appPassword ?: ""
        )
        return OkHttpClient.Builder()
            .followRedirects(false)
            .authenticator(authHandler)
            .addNetworkInterceptor(authHandler)
            .build()
    }

    private fun buildSyncUrl(): String {
        val server = credentialsManager.serverUrl?.trimEnd('/') ?: ""
        val user = credentialsManager.username ?: ""
        return "$server/remote.php/dav/files/$user/EreaderSync/sync_data.json"
    }

    private fun buildSyncFolderUrl(): String {
        val server = credentialsManager.serverUrl?.trimEnd('/') ?: ""
        val user = credentialsManager.username ?: ""
        return "$server/remote.php/dav/files/$user/EreaderSync/"
    }

    fun buildUserRootUrl(): String {
        val server = credentialsManager.serverUrl?.trimEnd('/') ?: ""
        val user = credentialsManager.username ?: ""
        return "$server/remote.php/dav/files/$user/"
    }

    suspend fun listFolder(folderUrl: String): Result<List<NextcloudItem>> = withContext(Dispatchers.IO) {
        try {
            val client = createClient()
            val url = folderUrl.toHttpUrl()
            val davCollection = DavCollection(client, url)
            val items = mutableListOf<NextcloudItem>()

            davCollection.propfind(1, ResourceType.NAME, DisplayName.NAME, GetContentLength.NAME) { response, relation ->
                if (relation != Response.HrefRelation.MEMBER) return@propfind

                val resourceType = response[ResourceType::class.java]
                val isFolder = resourceType?.types?.contains(ResourceType.COLLECTION) == true

                val name = response[DisplayName::class.java]?.displayName
                    ?: response.href.pathSegments.lastOrNull { it.isNotEmpty() }
                    ?: return@propfind

                val size = response[GetContentLength::class.java]?.contentLength ?: 0L

                if (isFolder || name.lowercase().endsWith(".epub")) {
                    items.add(
                        NextcloudItem(
                            url = response.href.toString(),
                            name = name,
                            isFolder = isFolder,
                            size = size
                        )
                    )
                }
            }

            Result.success(
                items.sortedWith(
                    compareByDescending<NextcloudItem> { it.isFolder }.thenBy { it.name.lowercase() }
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listEpubsInFolder(folderUrl: String): Result<List<NextcloudItem>> = withContext(Dispatchers.IO) {
        try {
            val result = mutableListOf<NextcloudItem>()
            collectEpubs(folderUrl, result)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun collectEpubs(folderUrl: String, result: MutableList<NextcloudItem>) {
        val items = listFolder(folderUrl).getOrThrow()
        for (item in items) {
            if (item.isFolder) {
                collectEpubs(item.url, result)
            } else {
                result.add(item)
            }
        }
    }

    suspend fun downloadEpub(fileUrl: String, destFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = createClient()
            val request = Request.Builder().url(fileUrl).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Empty response body")
            Result.success(Unit)
        } catch (e: Exception) {
            destFile.delete()
            Result.failure(e)
        }
    }

    suspend fun syncBidirectional(onProgress: (SyncProgress) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Download remote data (may not exist yet)
            onProgress(SyncProgress(message = "Checking remote backup"))
            val remoteJson = try {
                onProgress(SyncProgress(message = "Downloading sync data"))
                downloadJson()
            } catch (e: Exception) {
                Log.w(TAG, "No remote sync data found (may not exist yet)", e)
                null
            }

            if (remoteJson != null) {
                onProgress(SyncProgress(message = "Merging remote progress"))
                payloadStore.mergeRemoteData(remoteJson)
                onProgress(SyncProgress(message = "Restoring missing books"))
                val recoverySummary = recoveryManager?.recoverMissingBooks(remoteJson) { progress ->
                    onProgress(SyncProgress(
                        message = "Restoring books",
                        completed = progress.completed,
                        total = progress.total
                    ))
                }
                if (recoverySummary != null) {
                    Log.i(
                        TAG,
                        "Recovery summary attempted=${recoverySummary.attempted} imported=${recoverySummary.imported} failed=${recoverySummary.failed} skipped=${recoverySummary.skipped}"
                    )
                }
                if ((recoverySummary?.imported ?: 0) > 0) {
                    onProgress(SyncProgress(message = "Applying restored progress"))
                    payloadStore.mergeRemoteData(remoteJson)
                }
            }

            onProgress(SyncProgress(message = "Uploading merged backup"))
            val json = payloadStore.buildLatestJson(remoteJson)
            uploadJson(json)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun uploadJson(json: JSONObject) {
        val client = createClient()
        ensureSyncFolderExists(client)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(buildSyncUrl())
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Upload failed: ${response.code}")
            }
        }
    }

    private fun ensureSyncFolderExists(client: OkHttpClient) {
        val request = Request.Builder()
            .url(buildSyncFolderUrl())
            .method("MKCOL", null)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 405) {
                return
            }
            throw Exception("Failed to create sync folder: ${response.code}")
        }
    }

    private fun downloadJson(): JSONObject {
        val client = createClient()
        val url = buildSyncUrl().toHttpUrl()
        val davCollection = DavCollection(client, url)
        var responseBody = ""
        val headers = okhttp3.Headers.headersOf("Accept-Encoding", "identity")
        davCollection.get("application/json", headers) { response ->
            responseBody = response.body?.string() ?: ""
        }
        return JSONObject(responseBody)
    }

}
