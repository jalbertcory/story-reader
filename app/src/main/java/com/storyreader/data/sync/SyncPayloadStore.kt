package com.storyreader.data.sync

import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import org.json.JSONArray
import org.json.JSONObject

class SyncPayloadStore(
    private val positionDao: ReadingPositionDao,
    private val sessionDao: ReadingSessionDao,
    private val bookDao: BookDao? = null
) {

    suspend fun buildLatestJson(): JSONObject {
        val positions = positionDao.getLatestPositionPerBook()
        val sessions = sessionDao.getAllSessionsOnce()
        return buildSyncJson(positions, sessions)
    }

    suspend fun mergeRemoteData(remoteJson: JSONObject) {
        val localPositions = positionDao.getLatestPositionPerBook()
        val localSessions = sessionDao.getAllSessionsOnce()
        mergeRemoteData(remoteJson, localPositions, localSessions)
    }

    private suspend fun mergeRemoteData(
        remoteJson: JSONObject,
        localPositions: List<ReadingPositionEntity>,
        localSessions: List<ReadingSessionEntity>
    ) {
        val localPositionsByBook = localPositions.associateBy { it.bookId }

        val remotePositions = remoteJson.optJSONArray("positions")
        if (remotePositions != null) {
            for (i in 0 until remotePositions.length()) {
                val remotePosition = remotePositions.getJSONObject(i)
                val bookId = remotePosition.getString("bookId")
                val remoteTimestamp = remotePosition.getLong("timestamp")
                val locatorJson = remotePosition.getString("locatorJson")

                if (bookDao?.getByIdOnce(bookId) == null) continue

                val localPosition = localPositionsByBook[bookId]
                if (localPosition == null || remoteTimestamp > localPosition.timestamp) {
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

        val remoteSessions = remoteJson.optJSONArray("sessions")
        if (remoteSessions != null) {
            for (i in 0 until remoteSessions.length()) {
                val remoteSession = remoteSessions.getJSONObject(i)
                val bookId = remoteSession.getString("bookId")
                val startTime = remoteSession.getLong("startTime")

                if (bookDao?.getByIdOnce(bookId) == null) continue
                if (sessionDao.findByBookIdAndStartTime(bookId, startTime) != null) continue

                sessionDao.insert(
                    ReadingSessionEntity(
                        bookId = bookId,
                        startTime = startTime,
                        durationSeconds = remoteSession.optInt("durationSeconds", 0),
                        rawDurationSeconds = remoteSession.optInt("rawDurationSeconds", 0),
                        pagesTurned = remoteSession.optInt("pagesTurned", 0),
                        wordsRead = remoteSession.optInt("wordsRead", 0),
                        isTts = remoteSession.optBoolean("isTts", false)
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
            positions.forEach { position ->
                put(JSONObject().apply {
                    put("bookId", position.bookId)
                    put("locatorJson", position.locatorJson)
                    put("timestamp", position.timestamp)
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
}
