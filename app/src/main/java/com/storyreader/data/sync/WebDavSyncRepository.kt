package com.storyreader.data.sync

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
            davCollection.get("application/json") { response ->
                responseBody = response.body?.string() ?: ""
            }

            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
