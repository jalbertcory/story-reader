package com.storyreader.data.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.storyreader.util.DebugLog
import com.storyreader.data.catalog.OpdsCredentialsManager
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File

private const val TAG = "RemoteBookRecovery"

data class RemoteRecoverySummary(
    val attempted: Int,
    val imported: Int,
    val failed: Int,
    val skipped: Int
)

data class RemoteRecoveryProgress(
    val completed: Int,
    val total: Int
)

class RemoteBookRecoveryManager(
    context: Context,
    private val bookDao: BookDao,
    private val bookRepository: BookRepository,
    private val syncCredentialsManager: SyncCredentialsManager,
    private val googleDriveAuthManager: GoogleDriveAuthManager,
    private val googleDriveApi: GoogleDriveApi,
    private val opdsCredentialsManager: OpdsCredentialsManager,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val booksDir = File(context.filesDir, "books")

    suspend fun recoverMissingBooks(
        remoteJson: JSONObject,
        onProgress: (RemoteRecoveryProgress) -> Unit = {}
    ): RemoteRecoverySummary = withContext(Dispatchers.IO) {
        val localSyncIds = bookDao.getAllIncludingHiddenOnce()
            .mapTo(mutableSetOf()) { BookSyncMetadata.syncIdFor(it) }

        var attempted = 0
        var imported = 0
        var failed = 0
        var skipped = 0

        val remoteBooks = parseRecoverableBooks(remoteJson)
        val recoverableBooks = remoteBooks.filter { remoteBook ->
            remoteBook.syncId !in localSyncIds &&
                remoteBook.sourceKind != null &&
                remoteBook.sourceUrl != null
        }
        onProgress(RemoteRecoveryProgress(completed = 0, total = recoverableBooks.size))

        remoteBooks.forEach { remoteBook ->
            if (remoteBook.syncId in localSyncIds) {
                skipped++
                return@forEach
            }

            if (remoteBook.sourceKind == null || remoteBook.sourceUrl == null) {
                skipped++
                DebugLog.d(TAG) { "Skipping recovery for ${remoteBook.syncId}: missing source metadata" }
                return@forEach
            }

            attempted++
            runCatching {
                if (remoteBook.shouldRecoverAsStub()) {
                    bookDao.insert(remoteBook.toStubBook())
                } else {
                    val recoveredFile = recoverToFile(remoteBook)
                    val importMetadata = BookImportMetadata(
                        syncId = remoteBook.syncId,
                        sourceKind = remoteBook.sourceKind,
                        sourceUrl = remoteBook.sourceUrl,
                        originalFileName = remoteBook.originalFileName ?: recoveredFile.name
                    )
                    bookRepository.importFromUri(Uri.fromFile(recoveredFile), importMetadata).getOrThrow()
                }
                localSyncIds += remoteBook.syncId
                imported++
                Log.i(TAG, "Recovered missing book ${remoteBook.syncId} from ${remoteBook.sourceKind}")
            }.onFailure { error ->
                failed++
                Log.w(
                    TAG,
                    "Failed to recover ${remoteBook.syncId} from ${remoteBook.sourceKind}: ${error.message}",
                    error
                )
            }
            onProgress(RemoteRecoveryProgress(completed = attempted, total = recoverableBooks.size))
        }

        Log.i(TAG, "Recovery summary attempted=$attempted imported=$imported failed=$failed skipped=$skipped")
        RemoteRecoverySummary(attempted = attempted, imported = imported, failed = failed, skipped = skipped)
    }

    private suspend fun recoverToFile(book: RecoverableRemoteBook): File {
        booksDir.mkdirs()
        val destination = File(booksDir, stableLocalFileName(book))

        return when (book.sourceKind) {
            SyncSourceKinds.NEXTCLOUD -> {
                downloadNextcloudSource(book.sourceUrl!!, destination)
                destination
            }

            SyncSourceKinds.GOOGLE_DRIVE -> {
                val accessToken = when (val authorization = googleDriveAuthManager.authorize().getOrThrow()) {
                    is GoogleDriveAuthorizationOutcome.Authorized -> authorization.accessToken
                    is GoogleDriveAuthorizationOutcome.NeedsResolution ->
                        throw IllegalStateException("Google Drive needs reconnecting before recovery can continue")
                }
                val fileId = book.sourceUrl!!.removePrefix("gdrive://")
                googleDriveApi.downloadFile(accessToken, fileId, destination).getOrThrow()
                destination
            }

            SyncSourceKinds.OPDS, SyncSourceKinds.STORY_MANAGER -> {
                downloadHttpSource(book.sourceUrl!!, destination)
                destination
            }

            SyncSourceKinds.DEVICE ->
                throw IllegalStateException("Device-local books cannot be re-downloaded automatically")

            else ->
                throw IllegalStateException("Unsupported recovery source: ${book.sourceKind}")
        }
    }

    private fun downloadHttpSource(sourceUrl: String, destination: File) {
        val credentials = opdsCredentialsManager.currentCredentials()
            ?: throw IllegalStateException("OPDS credentials are not configured for recovery")
        val resolvedUrl = resolveRecoveryUrl(
            configuredBaseUrl = credentials.baseUrl,
            sourceUrl = sourceUrl,
            providerName = "OPDS"
        )
        val request = Request.Builder()
            .url(resolvedUrl)
            .get()
            .apply {
                if (credentials.username.isNotBlank()) {
                    header("Authorization", Credentials.basic(credentials.username, credentials.password))
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                destination.delete()
                throw IllegalStateException("Recovery download failed: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Recovery download returned an empty body")
        }
    }

    private fun downloadNextcloudSource(sourceUrl: String, destination: File) {
        val username = syncCredentialsManager.username
            ?: throw IllegalStateException("Nextcloud username is not configured")
        val password = syncCredentialsManager.appPassword
            ?: throw IllegalStateException("Nextcloud app password is not configured")
        val configuredServerUrl = syncCredentialsManager.serverUrl
            ?: throw IllegalStateException("Nextcloud server URL is not configured")
        val normalizedUrl = resolveRecoveryUrl(
            configuredBaseUrl = configuredServerUrl,
            sourceUrl = sourceUrl,
            providerName = "Nextcloud"
        )
        val request = Request.Builder()
            .url(normalizedUrl)
            .header("Authorization", Credentials.basic(username, password))
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                destination.delete()
                throw IllegalStateException("Nextcloud recovery download failed: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Nextcloud recovery returned an empty body")
        }
    }

    private fun stableLocalFileName(book: RecoverableRemoteBook): String {
        val original = book.originalFileName
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "book.epub"
        return "${book.syncId}_$original"
    }

    private fun resolveRecoveryUrl(
        configuredBaseUrl: String,
        sourceUrl: String,
        providerName: String
    ): String {
        val configuredUrl = BookSyncMetadata.normalizeRemoteUrl(configuredBaseUrl)
            ?.toHttpUrlOrNull()
            ?: throw IllegalStateException("$providerName server URL is invalid")
        val resolvedUrl = when {
            sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://") ->
                BookSyncMetadata.normalizeRemoteUrl(sourceUrl)?.toHttpUrlOrNull()

            sourceUrl.startsWith("/") ->
                configuredUrl.resolve(sourceUrl)

            else ->
                "${configuredUrl.toString().trimEnd('/')}/${sourceUrl.trimStart('/')}".toHttpUrlOrNull()
        } ?: throw IllegalStateException("$providerName source URL could not be normalized")

        if (!sameOrigin(configuredUrl, resolvedUrl)) {
            throw IllegalStateException("$providerName recovery URL must match the configured server")
        }

        return resolvedUrl.toString()
    }

    private fun sameOrigin(configuredUrl: HttpUrl, candidateUrl: HttpUrl): Boolean =
        configuredUrl.scheme == candidateUrl.scheme &&
            configuredUrl.host.equals(candidateUrl.host, ignoreCase = true) &&
            configuredUrl.port == candidateUrl.port

    private fun parseRecoverableBooks(remoteJson: JSONObject): List<RecoverableRemoteBook> {
        val books = remoteJson.optJSONArray("books") ?: return emptyList()
        return buildList {
            for (i in 0 until books.length()) {
                val obj = books.getJSONObject(i)
                val syncId = obj.optString("syncId").takeIf { it.isNotBlank() } ?: continue
                val source = obj.optJSONObject("source")
                val progress = obj.optJSONObject("progress")
                add(
                    RecoverableRemoteBook(
                        syncId = syncId,
                        title = obj.optString("title", ""),
                        author = obj.optString("author", ""),
                        sourceType = obj.optString("sourceType").takeIf { it.isNotBlank() },
                        series = obj.optString("series").takeIf { it.isNotBlank() },
                        seriesIndex = if (obj.has("seriesIndex") && !obj.isNull("seriesIndex")) {
                            obj.optDouble("seriesIndex").toFloat()
                        } else {
                            null
                        },
                        sourceKind = source?.optString("kind")?.takeIf { it.isNotBlank() },
                        sourceUrl = source?.optString("url")?.takeIf { it.isNotBlank() },
                        originalFileName = source?.optString("originalFileName")?.takeIf { it.isNotBlank() },
                        serverBookId = if (source?.has("serverBookId") == true && !source.isNull("serverBookId")) {
                            source.optInt("serverBookId")
                        } else {
                            null
                        },
                        isCompleted = progress?.optBoolean("isCompleted", false) == true ||
                            (progress?.optDouble("furthestProgress", 0.0) ?: 0.0) >= 1.0
                    )
                )
            }
        }
    }

    private data class RecoverableRemoteBook(
        val syncId: String,
        val title: String,
        val author: String,
        val sourceType: String?,
        val series: String?,
        val seriesIndex: Float?,
        val sourceKind: String?,
        val sourceUrl: String?,
        val originalFileName: String?,
        val serverBookId: Int?,
        val isCompleted: Boolean
    ) {
        fun shouldRecoverAsStub(): Boolean =
            sourceKind == SyncSourceKinds.STORY_MANAGER && serverBookId != null && isCompleted

        fun toStubBook(): BookEntity = BookEntity(
            bookId = "recoverable://$syncId",
            title = title.ifBlank { originalFileName ?: "Untitled" },
            author = author,
            syncId = syncId,
            syncSourceKind = sourceKind,
            syncSourceUrl = sourceUrl,
            originalFileName = originalFileName,
            totalProgression = 1f,
            hidden = false,
            series = series,
            sourceType = sourceType ?: "web",
            serverBookId = serverBookId,
            seriesIndex = seriesIndex
        )
    }
}
