package com.storyreader.data.sync

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.ResourceType
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.Dispatchers
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
    private val sessionDao: ReadingSessionDao,
    private val bookDao: com.storyreader.data.db.dao.BookDao? = null
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

    suspend fun uploadSyncData(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val positions = positionDao.getLatestPositionPerBook()
            val sessions = sessionDao.getAllSessionsOnce()
            val json = buildSyncJson(positions, sessions)
            uploadJson(json)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncBidirectional(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Download remote data (may not exist yet)
            val remoteJson = try {
                downloadJson()
            } catch (_: Exception) {
                null
            }

            val localPositions = positionDao.getLatestPositionPerBook()
            val localSessions = sessionDao.getAllSessionsOnce()

            if (remoteJson != null) {
                mergeRemoteData(remoteJson, localPositions, localSessions)
            }

            // Re-read after merge to get the merged state
            val mergedPositions = positionDao.getLatestPositionPerBook()
            val mergedSessions = sessionDao.getAllSessionsOnce()
            val json = buildSyncJson(mergedPositions, mergedSessions)
            uploadJson(json)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun mergeRemoteData(
        remoteJson: JSONObject,
        localPositions: List<ReadingPositionEntity>,
        localSessions: List<ReadingSessionEntity>
    ) {
        val localPositionsByBook = localPositions.associateBy { it.bookId }

        // Merge positions: remote wins if newer
        val remotePositions = remoteJson.optJSONArray("positions")
        if (remotePositions != null) {
            for (i in 0 until remotePositions.length()) {
                val rp = remotePositions.getJSONObject(i)
                val bookId = rp.getString("bookId")
                val remoteTimestamp = rp.getLong("timestamp")
                val locatorJson = rp.getString("locatorJson")

                // Skip if book doesn't exist locally
                if (bookDao?.getByIdOnce(bookId) == null) continue

                val localPos = localPositionsByBook[bookId]
                if (localPos == null || remoteTimestamp > localPos.timestamp) {
                    positionDao.insertPosition(
                        ReadingPositionEntity(
                            bookId = bookId,
                            locatorJson = locatorJson,
                            timestamp = remoteTimestamp
                        )
                    )
                }
            }
        }

        // Merge sessions: insert remote sessions that don't exist locally
        val remoteSessions = remoteJson.optJSONArray("sessions")
        if (remoteSessions != null) {
            for (i in 0 until remoteSessions.length()) {
                val rs = remoteSessions.getJSONObject(i)
                val bookId = rs.getString("bookId")
                val startTime = rs.getLong("startTime")

                // Skip if book doesn't exist locally
                if (bookDao?.getByIdOnce(bookId) == null) continue

                // Skip if session already exists
                if (sessionDao.findByBookIdAndStartTime(bookId, startTime) != null) continue

                sessionDao.insert(
                    ReadingSessionEntity(
                        bookId = bookId,
                        startTime = startTime,
                        durationSeconds = rs.optInt("durationSeconds", 0),
                        rawDurationSeconds = rs.optInt("rawDurationSeconds", 0),
                        pagesTurned = rs.optInt("pagesTurned", 0),
                        wordsRead = rs.optInt("wordsRead", 0),
                        isTts = rs.optBoolean("isTts", false)
                    )
                )
            }
        }
    }

    private fun buildSyncJson(
        positions: List<ReadingPositionEntity>,
        sessions: List<ReadingSessionEntity>
    ): JSONObject = JSONObject().apply {
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
                    put("rawDurationSeconds", session.rawDurationSeconds)
                    put("pagesTurned", session.pagesTurned)
                    put("wordsRead", session.wordsRead)
                    put("isTts", session.isTts)
                })
            }
        })
    }

    private fun uploadJson(json: JSONObject) {
        val client = createClient()
        ensureSyncFolderExists(client)
        val url = buildSyncUrl().toHttpUrl()
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val davCollection = DavCollection(client, url)
        davCollection.put(body, "application/json") { response ->
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

    suspend fun downloadSyncData(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(downloadJson().toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
