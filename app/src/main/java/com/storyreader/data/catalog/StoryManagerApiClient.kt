package com.storyreader.data.catalog

import android.util.Log
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "StoryManagerApi"

data class SeriesSummary(
    val name: String,
    val bookCount: Int,
    val totalWords: Int,
    val latestUpdate: String?,
    val coverUrl: String?,
    val genreTags: List<String> = emptyList()
)

data class ServerBook(
    val id: Int,
    val title: String,
    val author: String,
    val series: String?,
    val seriesIndex: Float?,
    val sourceType: String,
    val contentUpdatedAt: String,
    val contentVersion: Int,
    val currentWordCount: Int?,
    val downloadUrl: String,
    val coverUrl: String?
)

class StoryManagerApiClient(
    private val credentialsManager: OpdsCredentialsManager,
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    private fun baseUrl(): String =
        credentialsManager.url?.trimEnd('/') ?: throw IllegalStateException("No Story Manager URL configured")

    private fun Request.Builder.applyAuth(): Request.Builder {
        val creds = credentialsManager.currentCredentials() ?: return this
        if (creds.username.isBlank()) return this
        return header("Authorization", Credentials.basic(creds.username, creds.password))
    }

    fun fetchSeries(): Result<List<SeriesSummary>> = runCatching {
        val request = Request.Builder()
            .url("${baseUrl()}/reader/series")
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Failed to fetch series: ${response.code}")
            val arr = JSONArray(response.body?.string().orEmpty())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val cover = obj.optStringOrNull("cover_url")
                Log.d(TAG, "series=${obj.getString("name")} coverUrl=$cover")
                val genreTagsArr = obj.optJSONArray("genre_tags")
                val genreTags = if (genreTagsArr != null) {
                    (0 until genreTagsArr.length()).map { j -> genreTagsArr.getString(j) }
                } else {
                    emptyList()
                }
                SeriesSummary(
                    name = obj.getString("name"),
                    bookCount = obj.getInt("book_count"),
                    totalWords = obj.getInt("total_words"),
                    latestUpdate = obj.optStringOrNull("latest_update"),
                    coverUrl = cover,
                    genreTags = genreTags
                )
            }
        }
    }

    fun fetchSeriesBooks(name: String): Result<List<ServerBook>> = runCatching {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val url = "${baseUrl()}/reader/series/$encoded/books"
        Log.d(TAG, "fetchSeriesBooks url=$url")
        val request = Request.Builder()
            .url(url)
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            Log.d(TAG, "fetchSeriesBooks response=${response.code}")
            if (!response.isSuccessful) throw IllegalStateException("Failed to fetch series books: ${response.code}")
            val body = response.body?.string().orEmpty()
            Log.d(TAG, "fetchSeriesBooks body length=${body.length}")
            parseServerBooks(body)
        }
    }

    fun fetchStandaloneBooks(): Result<List<ServerBook>> = runCatching {
        val request = Request.Builder()
            .url("${baseUrl()}/reader/books/standalone")
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Failed to fetch standalone books: ${response.code}")
            parseServerBooks(response.body?.string().orEmpty())
        }
    }

    fun fetchAllBooks(): Result<List<ServerBook>> = runCatching {
        val request = Request.Builder()
            .url("${baseUrl()}/reader/books/all")
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Failed to fetch all books: ${response.code}")
            parseServerBooks(response.body?.string().orEmpty())
        }
    }

    fun fetchUpdates(since: Long?): Result<List<ServerBook>> = runCatching {
        val url = if (since != null) {
            val isoDate = java.time.Instant.ofEpochMilli(since).toString()
            "${baseUrl()}/reader/updates?since=$isoDate"
        } else {
            "${baseUrl()}/reader/updates"
        }
        Log.d(TAG, "fetchUpdates url=$url")
        val request = Request.Builder()
            .url(url)
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            Log.d(TAG, "fetchUpdates response=${response.code}")
            if (!response.isSuccessful) throw IllegalStateException("Failed to fetch updates: ${response.code}")
            val body = response.body?.string().orEmpty()
            Log.d(TAG, "fetchUpdates body=$body")
            parseServerBooks(body)
        }
    }

    fun downloadBook(bookId: Int, destDir: File): Result<File> = runCatching {
        destDir.mkdirs()
        val request = Request.Builder()
            .url("${baseUrl()}/reader/books/$bookId/download")
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Download failed: ${response.code}")
            val disposition = response.header("Content-Disposition")
            val filename = disposition
                ?.substringAfter("filename=", "")
                ?.trim('"')
                ?.takeIf { it.isNotBlank() }
                ?: "book_$bookId.epub"
            val dest = File(destDir, filename)
            response.body?.byteStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Empty response body")
            dest
        }
    }

    fun downloadCoverImage(coverUrl: String): Result<ByteArray> = runCatching {
        val resolvedUrl = if (coverUrl.startsWith("http")) coverUrl else "${baseUrl()}$coverUrl"
        val url = resolvedUrl.replace("http://", "https://")
        val request = Request.Builder()
            .url(url)
            .applyAuth()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Cover download failed: ${response.code}")
            response.body?.bytes() ?: throw IllegalStateException("Empty cover response")
        }
    }

    private fun parseServerBooks(json: String): List<ServerBook> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            parseServerBook(obj)
        }
    }

    private fun parseServerBook(obj: JSONObject): ServerBook = ServerBook(
        id = obj.getInt("id"),
        title = obj.getString("title"),
        author = obj.getString("author"),
        series = obj.optStringOrNull("series"),
        seriesIndex = if (obj.isNull("series_index")) null else obj.getDouble("series_index").toFloat(),
        sourceType = obj.getString("source_type"),
        contentUpdatedAt = obj.getString("content_updated_at"),
        contentVersion = obj.getInt("content_version"),
        currentWordCount = if (obj.isNull("current_word_count")) null else obj.getInt("current_word_count"),
        downloadUrl = obj.getString("download_url"),
        coverUrl = obj.optStringOrNull("cover_url")
    )
}

/**
 * Safe alternative to [JSONObject.optString] that returns actual null
 * instead of the string "null" for JSON null values.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
