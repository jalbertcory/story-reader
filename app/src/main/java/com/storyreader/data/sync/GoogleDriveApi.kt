package com.storyreader.data.sync

import android.util.Log
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class GoogleDriveSyncFile(
    val id: String
)

class GoogleDriveApi(
    private val httpClient: OkHttpClient = OkHttpClient()
) {

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
                throw response.toGoogleDriveException("Google Drive download failed")
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
                throw response.toGoogleDriveException("Google Drive sync download failed")
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

    private fun executeJsonRequest(url: okhttp3.HttpUrl, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toGoogleDriveException("Google Drive request failed")
            }
            return JSONObject(response.body?.string().orEmpty())
        }
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
                throw response.toGoogleDriveException("Google Drive sync upload failed")
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
                throw response.toGoogleDriveException("Google Drive sync update failed")
            }
        }
    }

    private fun Response.toGoogleDriveException(prefix: String): IllegalStateException {
        val rawBody = body?.string().orEmpty()
        val apiMessage = runCatching {
            JSONObject(rawBody)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val message = buildString {
            append(prefix)
            append(": ")
            append(code)
            this@toGoogleDriveException.message.takeIf { it.isNotBlank() }?.let {
                append(" ")
                append(it)
            }
            apiMessage?.let {
                append(" - ")
                append(it)
            }
        }
        Log.w(TAG, message)
        return IllegalStateException(message)
    }

    companion object {
        private const val TAG = "GoogleDriveApi"
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
    }
}
