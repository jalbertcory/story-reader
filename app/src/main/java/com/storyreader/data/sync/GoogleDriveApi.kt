package com.storyreader.data.sync

import android.util.Log
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GoogleDriveSyncFile(
    val id: String
)

class GoogleDriveApi(
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    fun listFolder(
        accessToken: String,
        folderId: String
    ): Result<List<GoogleDriveItem>> = runCatching {
        val url = "${DRIVE_API_BASE}/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", buildFolderQuery(folderId))
            .addQueryParameter("fields", "files(id,name,mimeType,size)")
            .addQueryParameter("orderBy", "folder,name_natural")
            .addQueryParameter("pageSize", "200")
            .build()
        val response = executeJsonRequest(url, accessToken)
        response.getJSONArray("files").toGoogleDriveItems()
    }

    fun downloadFile(
        accessToken: String,
        fileId: String,
        destination: File
    ): Result<Unit> = runCatching {
        val url = "${DRIVE_API_BASE}/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive download failed: ${response.code}")
            }

            response.body?.byteStream()?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Google Drive returned an empty file")
            Unit
        }
    }.onFailure { e ->
        Log.w(TAG, "Google Drive download failed for file $fileId", e)
        destination.delete()
    }

    fun findSyncFile(
        accessToken: String,
        fileName: String
    ): Result<GoogleDriveSyncFile?> = runCatching {
        val url = "${DRIVE_API_BASE}/files".toHttpUrl().newBuilder()
            .addQueryParameter(
                "q",
                "name = '$fileName' and trashed = false and 'appDataFolder' in parents"
            )
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id)")
            .build()
        val response = executeJsonRequest(url, accessToken)
        val files = response.getJSONArray("files")
        if (files.length() == 0) {
            null
        } else {
            GoogleDriveSyncFile(id = files.getJSONObject(0).getString("id"))
        }
    }

    fun downloadSyncJson(
        accessToken: String,
        fileId: String
    ): Result<JSONObject> = runCatching {
        val url = "${DRIVE_API_BASE}/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive sync download failed: ${response.code}")
            }
            JSONObject(response.body?.string().orEmpty())
        }
    }

    fun uploadSyncJson(
        accessToken: String,
        fileId: String?,
        fileName: String,
        payload: JSONObject
    ): Result<Unit> = runCatching {
        if (fileId == null) {
            createSyncFile(accessToken, fileName, payload)
        } else {
            updateSyncFile(accessToken, fileId, payload)
        }
    }

    suspend fun listEpubsRecursively(
        accessToken: String,
        folderId: String
    ): Result<List<GoogleDriveItem>> = runCatching {
        val items = listFolder(accessToken, folderId).getOrThrow()
        val epubs = mutableListOf<GoogleDriveItem>()
        for (item in items) {
            if (item.isFolder) {
                epubs += listEpubsRecursively(accessToken, item.id).getOrThrow()
            } else {
                epubs += item
            }
        }
        epubs
    }

    private fun executeJsonRequest(url: okhttp3.HttpUrl, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive request failed: ${response.code}")
            }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun JSONArray.toGoogleDriveItems(): List<GoogleDriveItem> {
        val items = mutableListOf<GoogleDriveItem>()
        for (i in 0 until length()) {
            val file = getJSONObject(i)
            val mimeType = file.getString("mimeType")
            val name = file.getString("name")
            val isFolder = mimeType == FOLDER_MIME_TYPE
            if (!isFolder && !name.lowercase().endsWith(".epub") && mimeType != EPUB_MIME_TYPE) {
                continue
            }
            items += GoogleDriveItem(
                id = file.getString("id"),
                name = name,
                isFolder = isFolder,
                size = file.optLong("size", 0L)
            )
        }
        return items.sortedWith(compareByDescending<GoogleDriveItem> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    private fun buildFolderQuery(folderId: String): String {
        return "'$folderId' in parents and trashed = false and (" +
            "mimeType = '$FOLDER_MIME_TYPE' or " +
            "mimeType = '$EPUB_MIME_TYPE' or " +
            "name contains '.epub')"
    }

    private fun createSyncFile(accessToken: String, fileName: String, payload: JSONObject) {
        val boundary = "story-reader-boundary"
        val metadata = JSONObject()
            .put("name", fileName)
            .put("parents", JSONArray().put("appDataFolder"))
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(payload)
            append("\r\n--$boundary--")
        }
        val request = Request.Builder()
            .url("${DRIVE_UPLOAD_BASE}/files".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "multipart")
                .build())
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive sync upload failed: ${response.code}")
            }
        }
    }

    private fun updateSyncFile(accessToken: String, fileId: String, payload: JSONObject) {
        val request = Request.Builder()
            .url("${DRIVE_UPLOAD_BASE}/files/$fileId".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "media")
                .build())
            .header("Authorization", "Bearer $accessToken")
            .patch(payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive sync update failed: ${response.code}")
            }
        }
    }

    companion object {
        private const val TAG = "GoogleDriveApi"
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val EPUB_MIME_TYPE = "application/epub+zip"
    }
}
