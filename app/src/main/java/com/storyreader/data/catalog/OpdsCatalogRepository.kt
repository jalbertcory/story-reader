package com.storyreader.data.catalog

import java.io.File
import java.net.URLEncoder
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

class OpdsCatalogRepository(
    private val parser: OpdsParser = OpdsParser(),
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    suspend fun fetchCatalog(
        credentials: OpdsCredentials,
        url: String = credentials.baseUrl
    ): Result<OpdsCatalogPage> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT_HEADER)
            .applyAuth(credentials)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("OPDS request failed: ${response.code}")
            }
            parser.parse(
                body = response.body?.string().orEmpty(),
                requestUrl = response.request.url.toString(),
                contentType = response.header("Content-Type")
            )
        }
    }

    suspend fun downloadPublication(
        credentials: OpdsCredentials,
        entry: OpdsCatalogEntry,
        destinationDir: File
    ): Result<File> = runCatching {
        val acquisitionUrl = entry.acquisitionUrl
            ?: throw IllegalStateException("This OPDS entry does not have an EPUB download link")
        destinationDir.mkdirs()
        val destination = File(destinationDir, buildFileName(entry))
        val request = Request.Builder()
            .url(acquisitionUrl)
            .applyAuth(credentials)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("OPDS download failed: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("OPDS returned an empty publication")
        }
        destination
    }.onFailure {
        val destination = File(destinationDir, buildFileName(entry))
        destination.delete()
    }

    private fun Request.Builder.applyAuth(credentials: OpdsCredentials): Request.Builder {
        if (credentials.username.isBlank()) return this
        return header("Authorization", Credentials.basic(credentials.username, credentials.password))
    }

    private fun buildFileName(entry: OpdsCatalogEntry): String {
        val slug = entry.title
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { URLEncoder.encode(it, Charsets.UTF_8.name()) }
            ?: "book"
        return "$slug.epub"
    }

    companion object {
        private const val ACCEPT_HEADER =
            "application/opds+json, application/atom+xml;profile=opds-catalog, application/atom+xml, application/json;q=0.9, */*;q=0.8"
    }
}
