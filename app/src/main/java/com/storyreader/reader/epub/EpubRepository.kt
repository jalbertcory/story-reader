package com.storyreader.reader.epub

import android.content.Context
import android.net.Uri
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class EpubRepository(private val context: Context) {

    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationParser = DefaultPublicationParser(context, httpClient, assetRetriever, null)
    private val publicationOpener = PublicationOpener(publicationParser)

    suspend fun openPublication(uri: Uri): Result<Publication> {
        return try {
            val url = AbsoluteUrl(uri.toString())
                ?: throw IllegalArgumentException("Invalid URI: $uri")
            val asset = assetRetriever.retrieve(url)
                .getOrNull() ?: throw Exception("Failed to retrieve asset")
            val publication = publicationOpener.open(asset, allowUserInteraction = false)
                .getOrNull() ?: throw Exception("Failed to open publication")
            Result.success(publication)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
