package com.storyreader.data.sync

import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import org.json.JSONArray
import org.json.JSONObject

private const val SYNC_SCHEMA_VERSION = 2
private const val MIN_SYNC_SESSION_DURATION_SECONDS = 10

class SyncPayloadStore(
    private val positionDao: ReadingPositionDao,
    private val sessionDao: ReadingSessionDao,
    private val bookDao: BookDao? = null,
    private val appStateStore: SyncAppStateStore? = null
) {

    suspend fun buildLatestJson(remoteJson: JSONObject? = null): JSONObject {
        val localBooks = normalizeLocalBooks(bookDao?.getAllIncludingHiddenOnce().orEmpty())
        val latestPositionsByBookId = positionDao.getLatestPositionPerBook().associateBy { it.bookId }
        val sessionsByBookId = sessionDao.getAllSessionsOnce()
            .filter { it.durationSeconds >= MIN_SYNC_SESSION_DURATION_SECONDS }
            .groupBy { it.bookId }

        val localBooksBySyncId = localBooks.associateBy(
            keySelector = { BookSyncMetadata.syncIdFor(it) },
            valueTransform = { book ->
                buildLocalBookPayload(
                    book = book,
                    latestPosition = latestPositionsByBookId[book.bookId],
                    sessions = sessionsByBookId[book.bookId].orEmpty()
                )
            }
        )
        val remoteBooksBySyncId = parseRemoteBooks(remoteJson).associateBy { it.syncId }

        val mergedBooks = buildList {
            localBooksBySyncId.values
                .sortedWith(compareBy<SyncBookPayload> { it.title.lowercase() }.thenBy { it.author.lowercase() })
                .forEach { localBook ->
                    add(mergeBookPayload(localBook, remoteBooksBySyncId[localBook.syncId]))
                }

            remoteBooksBySyncId.values
                .filter { it.syncId !in localBooksBySyncId }
                .sortedWith(compareBy<SyncBookPayload> { it.title.lowercase() }.thenBy { it.author.lowercase() })
                .forEach(::add)
        }
        val mergedAppState = appStateStore
            ?.buildLocalPayload()
            ?.mergeWith(appStateStore.parseRemotePayload(remoteJson))

        return JSONObject().apply {
            put("schemaVersion", SYNC_SCHEMA_VERSION)
            if (mergedAppState != null) {
                put("app", mergedAppState.toJson())
            }
            put("books", JSONArray().apply {
                mergedBooks.forEach { put(it.toJson()) }
            })
        }
    }

    suspend fun mergeRemoteData(remoteJson: JSONObject) {
        if (!isSchemaV2(remoteJson)) {
            mergeLegacyRemoteData(remoteJson)
            return
        }

        val dao = bookDao ?: return
        appStateStore?.mergeRemoteData(remoteJson)
        val localBooks = normalizeLocalBooks(dao.getAllIncludingHiddenOnce())
        val localBooksBySyncId = localBooks.associateBy { BookSyncMetadata.syncIdFor(it) }
        val localPositionsByBookId = positionDao.getLatestPositionPerBook().associateBy { it.bookId }
        val localSessionsByBookId = sessionDao.getAllSessionsOnce()
            .filter { it.durationSeconds >= MIN_SYNC_SESSION_DURATION_SECONDS }
            .groupBy { it.bookId }

        parseRemoteBooks(remoteJson).forEach { remoteBook ->
            val localBook = localBooksBySyncId[remoteBook.syncId] ?: return@forEach
            val mergedBook = mergeRemoteMetadataIntoLocal(localBook, remoteBook)
            if (mergedBook != localBook) {
                dao.update(mergedBook)
            }

            val remoteLatestPosition = remoteBook.latestPosition
            val localLatestPosition = localPositionsByBookId[localBook.bookId]
            if (remoteLatestPosition != null &&
                (localLatestPosition == null || remoteLatestPosition.timestamp > localLatestPosition.timestamp)
            ) {
                positionDao.insertPosition(
                    ReadingPositionEntity(
                        bookId = localBook.bookId,
                        locatorJson = remoteLatestPosition.locatorJson,
                        timestamp = remoteLatestPosition.timestamp
                    )
                )
            }

            val remoteProgress = remoteBook.effectiveProgress()
            if (remoteProgress > mergedBook.totalProgression) {
                dao.updateProgression(localBook.bookId, remoteProgress)
            }

            val localSessionIds = localSessionsByBookId[localBook.bookId].orEmpty()
                .mapTo(mutableSetOf()) {
                    BookSyncMetadata.sessionIdFor(
                        bookSyncId = BookSyncMetadata.syncIdFor(mergedBook),
                        startTime = it.startTime,
                        isTts = it.isTts
                    )
                }

            remoteBook.sessions
                .filter { it.durationSeconds >= MIN_SYNC_SESSION_DURATION_SECONDS }
                .forEach { remoteSession ->
                    if (remoteSession.id in localSessionIds) return@forEach
                    if (sessionDao.findByBookIdAndStartTime(localBook.bookId, remoteSession.startTime) != null) return@forEach

                    sessionDao.insert(
                        ReadingSessionEntity(
                            bookId = localBook.bookId,
                            startTime = remoteSession.startTime,
                            durationSeconds = remoteSession.durationSeconds,
                            rawDurationSeconds = remoteSession.rawDurationSeconds,
                            pagesTurned = remoteSession.pagesTurned,
                            wordsRead = remoteSession.wordsRead,
                            isTts = remoteSession.isTts
                        )
                    )
                    localSessionIds += remoteSession.id
                }
        }
    }

    private suspend fun mergeLegacyRemoteData(remoteJson: JSONObject) {
        val dao = bookDao ?: return
        val localBooksById = normalizeLocalBooks(dao.getAllIncludingHiddenOnce()).associateBy { it.bookId }
        val localPositionsByBook = positionDao.getLatestPositionPerBook().associateBy { it.bookId }

        val remotePositions = remoteJson.optJSONArray("positions")
        if (remotePositions != null) {
            for (i in 0 until remotePositions.length()) {
                val remotePosition = remotePositions.getJSONObject(i)
                val bookId = remotePosition.getString("bookId")
                val remoteTimestamp = remotePosition.getLong("timestamp")
                val locatorJson = remotePosition.getString("locatorJson")

                val localBook = localBooksById[bookId] ?: continue
                val localPosition = localPositionsByBook[bookId]
                if (localPosition == null || remoteTimestamp > localPosition.timestamp) {
                    positionDao.insertPosition(
                        ReadingPositionEntity(
                            bookId = localBook.bookId,
                            locatorJson = locatorJson,
                            timestamp = remoteTimestamp
                        )
                    )
                }

                val extractedProgress = extractProgression(locatorJson)
                if (extractedProgress > localBook.totalProgression) {
                    dao.updateProgression(localBook.bookId, extractedProgress)
                }
            }
        }

        val remoteSessions = remoteJson.optJSONArray("sessions")
        if (remoteSessions != null) {
            for (i in 0 until remoteSessions.length()) {
                val remoteSession = remoteSessions.getJSONObject(i)
                val bookId = remoteSession.getString("bookId")
                val startTime = remoteSession.getLong("startTime")
                val durationSeconds = remoteSession.optInt("durationSeconds", 0)

                if (durationSeconds < MIN_SYNC_SESSION_DURATION_SECONDS) continue
                val localBook = localBooksById[bookId] ?: continue
                if (sessionDao.findByBookIdAndStartTime(localBook.bookId, startTime) != null) continue

                sessionDao.insert(
                    ReadingSessionEntity(
                        bookId = localBook.bookId,
                        startTime = startTime,
                        durationSeconds = durationSeconds,
                        rawDurationSeconds = remoteSession.optInt("rawDurationSeconds", 0),
                        pagesTurned = remoteSession.optInt("pagesTurned", 0),
                        wordsRead = remoteSession.optInt("wordsRead", 0),
                        isTts = remoteSession.optBoolean("isTts", false)
                    )
                )
            }
        }
    }

    private suspend fun normalizeLocalBooks(books: List<BookEntity>): List<BookEntity> {
        val dao = bookDao ?: return books
        return books.map { book ->
            val normalized = book.copy(
                syncId = book.syncId ?: BookSyncMetadata.syncIdFor(book),
                originalFileName = book.originalFileName ?: BookSyncMetadata.extractOriginalFileName(book.bookId)
            )
            if (normalized != book) {
                dao.update(normalized)
            }
            normalized
        }
    }

    private fun buildLocalBookPayload(
        book: BookEntity,
        latestPosition: ReadingPositionEntity?,
        sessions: List<ReadingSessionEntity>
    ): SyncBookPayload {
        val inferredSourceKind = when {
            book.syncSourceKind != null -> book.syncSourceKind
            book.sourceType == "web" && book.serverBookId != null -> SyncSourceKinds.STORY_MANAGER
            else -> null
        }
        val inferredSourceUrl = when {
            book.syncSourceUrl != null -> BookSyncMetadata.normalizeRemoteUrl(book.syncSourceUrl)
            book.sourceType == "web" && book.serverBookId != null -> "/reader/books/${book.serverBookId}/download"
            else -> null
        }
        val latestPayload = latestPosition?.let {
            LatestPositionPayload(locatorJson = it.locatorJson, timestamp = it.timestamp)
        }
        val furthestProgress = maxOf(
            book.totalProgression,
            latestPayload?.progression ?: 0f
        )
        val bookSyncId = BookSyncMetadata.syncIdFor(book)

        return SyncBookPayload(
            syncId = bookSyncId,
            title = book.title,
            author = book.author,
            sourceType = book.sourceType,
            series = book.series,
            seriesIndex = book.seriesIndex,
            sourceKind = inferredSourceKind,
            sourceUrl = inferredSourceUrl,
            serverBookId = book.serverBookId,
            originalFileName = book.originalFileName,
            latestPosition = latestPayload,
            furthestProgress = furthestProgress,
            isCompleted = book.totalProgression >= 1f,
            sessions = sessions.map { session ->
                SyncSessionPayload(
                    id = BookSyncMetadata.sessionIdFor(bookSyncId, session.startTime, session.isTts),
                    startTime = session.startTime,
                    durationSeconds = session.durationSeconds,
                    rawDurationSeconds = session.rawDurationSeconds,
                    pagesTurned = session.pagesTurned,
                    wordsRead = session.wordsRead,
                    isTts = session.isTts
                )
            }
        )
    }

    private fun mergeBookPayload(local: SyncBookPayload, remote: SyncBookPayload?): SyncBookPayload {
        if (remote == null) return local

        val latestPosition = listOfNotNull(local.latestPosition, remote.latestPosition)
            .maxByOrNull { it.timestamp }
        val furthestProgress = maxOf(
            local.furthestProgress,
            remote.furthestProgress,
            latestPosition?.progression ?: 0f
        )
        val sessionsById = linkedMapOf<String, SyncSessionPayload>()
        remote.sessions.forEach { sessionsById[it.id] = it }
        local.sessions.forEach { sessionsById[it.id] = it }

        return local.copy(
            title = local.title.ifBlank { remote.title },
            author = local.author.ifBlank { remote.author },
            sourceType = local.sourceType ?: remote.sourceType,
            series = local.series ?: remote.series,
            seriesIndex = local.seriesIndex ?: remote.seriesIndex,
            sourceKind = local.sourceKind ?: remote.sourceKind,
            sourceUrl = local.sourceUrl ?: remote.sourceUrl,
            serverBookId = local.serverBookId ?: remote.serverBookId,
            originalFileName = local.originalFileName ?: remote.originalFileName,
            latestPosition = latestPosition,
            furthestProgress = furthestProgress,
            isCompleted = local.isCompleted || remote.isCompleted || furthestProgress >= 1f,
            sessions = sessionsById.values.sortedByDescending { it.startTime }
        )
    }

    private fun mergeRemoteMetadataIntoLocal(local: BookEntity, remote: SyncBookPayload): BookEntity {
        val shouldPreferRemoteFileName = local.syncSourceKind == null && local.syncSourceUrl == null
        return local.copy(
            syncId = local.syncId ?: remote.syncId,
            syncSourceKind = local.syncSourceKind ?: remote.sourceKind,
            syncSourceUrl = local.syncSourceUrl ?: remote.sourceUrl,
            originalFileName = when {
                local.originalFileName == null -> remote.originalFileName
                shouldPreferRemoteFileName && remote.originalFileName != null -> remote.originalFileName
                else -> local.originalFileName
            },
            sourceType = local.sourceType ?: remote.sourceType,
            series = local.series ?: remote.series,
            seriesIndex = local.seriesIndex ?: remote.seriesIndex,
            serverBookId = local.serverBookId ?: remote.serverBookId
        )
    }

    private fun parseRemoteBooks(remoteJson: JSONObject?): List<SyncBookPayload> {
        remoteJson ?: return emptyList()
        val books = remoteJson.optJSONArray("books") ?: return emptyList()

        return buildList {
            for (i in 0 until books.length()) {
                val obj = books.getJSONObject(i)
                val syncId = obj.optString("syncId").takeIf { it.isNotBlank() } ?: continue
                val progress = obj.optJSONObject("progress")
                val latestPosition = progress?.optJSONObject("latestPosition")?.let { latest ->
                    val locatorJson = latest.optString("locatorJson").takeIf { it.isNotBlank() } ?: return@let null
                    LatestPositionPayload(
                        locatorJson = locatorJson,
                        timestamp = latest.optLong("timestamp", 0L)
                    )
                }
                val source = obj.optJSONObject("source")
                val sessions = obj.optJSONArray("sessions")

                add(
                    SyncBookPayload(
                        syncId = syncId,
                        title = obj.optString("title", ""),
                        author = obj.optString("author", ""),
                        sourceType = obj.optStringOrNull("sourceType"),
                        series = obj.optStringOrNull("series"),
                        seriesIndex = obj.optFloatOrNull("seriesIndex"),
                        sourceKind = source?.optStringOrNull("kind"),
                        sourceUrl = source?.optStringOrNull("url"),
                        serverBookId = source?.optIntOrNull("serverBookId"),
                        originalFileName = source?.optStringOrNull("originalFileName"),
                        latestPosition = latestPosition,
                        furthestProgress = progress?.optDouble("furthestProgress", 0.0)?.toFloat() ?: 0f,
                        isCompleted = progress?.optBoolean("isCompleted", false) == true,
                        sessions = buildList {
                            if (sessions != null) {
                                for (j in 0 until sessions.length()) {
                                    val session = sessions.getJSONObject(j)
                                    val sessionId = session.optString("id").takeIf { it.isNotBlank() }
                                        ?: BookSyncMetadata.sessionIdFor(
                                            bookSyncId = syncId,
                                            startTime = session.optLong("startTime", 0L),
                                            isTts = session.optBoolean("isTts", false)
                                        )
                                    add(
                                        SyncSessionPayload(
                                            id = sessionId,
                                            startTime = session.optLong("startTime", 0L),
                                            durationSeconds = session.optInt("durationSeconds", 0),
                                            rawDurationSeconds = session.optInt("rawDurationSeconds", 0),
                                            pagesTurned = session.optInt("pagesTurned", 0),
                                            wordsRead = session.optInt("wordsRead", 0),
                                            isTts = session.optBoolean("isTts", false)
                                        )
                                    )
                                }
                            }
                        }
                    )
                )
            }
        }
    }

    private fun isSchemaV2(remoteJson: JSONObject): Boolean =
        remoteJson.optInt("schemaVersion", 0) >= SYNC_SCHEMA_VERSION || remoteJson.has("books")

    private fun extractProgression(locatorJson: String): Float = try {
        JSONObject(locatorJson)
            .optJSONObject("locations")
            ?.optDouble("totalProgression", 0.0)
            ?.toFloat()
            ?: 0f
    } catch (_: Exception) {
        0f
    }

    private data class LatestPositionPayload(
        val locatorJson: String,
        val timestamp: Long
    ) {
        val progression: Float = try {
            JSONObject(locatorJson)
                .optJSONObject("locations")
                ?.optDouble("totalProgression", 0.0)
                ?.toFloat()
                ?: 0f
        } catch (_: Exception) {
            0f
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("locatorJson", locatorJson)
            put("timestamp", timestamp)
        }
    }

    private data class SyncSessionPayload(
        val id: String,
        val startTime: Long,
        val durationSeconds: Int,
        val rawDurationSeconds: Int,
        val pagesTurned: Int,
        val wordsRead: Int,
        val isTts: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("startTime", startTime)
            put("durationSeconds", durationSeconds)
            put("rawDurationSeconds", rawDurationSeconds)
            put("pagesTurned", pagesTurned)
            put("wordsRead", wordsRead)
            put("isTts", isTts)
        }
    }

    private data class SyncBookPayload(
        val syncId: String,
        val title: String,
        val author: String,
        val sourceType: String?,
        val series: String?,
        val seriesIndex: Float?,
        val sourceKind: String?,
        val sourceUrl: String?,
        val serverBookId: Int?,
        val originalFileName: String?,
        val latestPosition: LatestPositionPayload?,
        val furthestProgress: Float,
        val isCompleted: Boolean,
        val sessions: List<SyncSessionPayload>
    ) {
        fun effectiveProgress(): Float = if (isCompleted) {
            1f
        } else {
            maxOf(furthestProgress, latestPosition?.progression ?: 0f)
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("syncId", syncId)
            put("title", title)
            put("author", author)
            if (sourceType != null) put("sourceType", sourceType)
            if (series != null) put("series", series)
            if (seriesIndex != null) put("seriesIndex", seriesIndex.toDouble())
            put(
                "source",
                JSONObject().apply {
                    if (sourceKind != null) put("kind", sourceKind)
                    if (sourceUrl != null) put("url", sourceUrl)
                    if (serverBookId != null) put("serverBookId", serverBookId)
                    if (originalFileName != null) put("originalFileName", originalFileName)
                }
            )
            put(
                "progress",
                JSONObject().apply {
                    if (latestPosition != null) put("latestPosition", latestPosition.toJson())
                    put("furthestProgress", furthestProgress.toDouble())
                    put("isCompleted", isCompleted)
                }
            )
            put("sessions", JSONArray().apply {
                sessions.forEach { put(it.toJson()) }
            })
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (has(key) && !isNull(key)) optDouble(key).toFloat() else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
