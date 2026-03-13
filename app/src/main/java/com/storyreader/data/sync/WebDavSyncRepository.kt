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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WebDavSyncRepository(
    private val credentialsManager: SyncCredentialsManager,
    private val positionDao: ReadingPositionDao,
    private val sessionDao: ReadingSessionDao
) {

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

    suspend fun uploadSyncData(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val positions = positionDao.getAllPositions().firstOrNull() ?: emptyList()
            val sessions = sessionDao.getAllSessions().firstOrNull() ?: emptyList()

            val json = JSONObject().apply {
                put("positions", JSONArray().apply {
                    positions.forEach { pos ->
                        put(JSONObject().apply {
                            put("bookId", pos.bookId)
                            put("locatorJson", pos.locatorJson)
                            put("timestamp", pos.timestamp)
                        })
                    }
                })
                put("sessions", JSONArray().apply {
                    sessions.forEach { session ->
                        put(JSONObject().apply {
                            put("bookId", session.bookId)
                            put("startTime", session.startTime)
                            put("durationSeconds", session.durationSeconds)
                            put("pagesTurned", session.pagesTurned)
                        })
                    }
                })
            }

            val client = createClient()
            val url = buildSyncUrl().toHttpUrl()
            val body = json.toString().toRequestBody("application/json".toMediaType())

            val davCollection = DavCollection(client, url)
            davCollection.put(body, "application/json") { response ->
                if (!response.isSuccessful) {
                    throw Exception("Upload failed: ${response.code}")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadSyncData(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val client = createClient()
            val url = buildSyncUrl().toHttpUrl()
            val davCollection = DavCollection(client, url)

            var responseBody = ""
            val headers = okhttp3.Headers.headersOf("Accept-Encoding", "identity")
            davCollection.get("application/json", headers) { response ->
                responseBody = response.body?.string() ?: ""
            }

            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
