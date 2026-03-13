package com.storyreader.data.repository

import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

interface ReadingRepository {
    fun observeLatestPosition(bookId: String): Flow<ReadingPositionEntity?>
    suspend fun savePosition(bookId: String, locatorJson: String)
    suspend fun startSession(bookId: String): Long
    suspend fun finalizeSession(sessionId: Long, pageTurnTimestampsMs: List<Long>, sessionStartMs: Long)
    fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>>
}

class ReadingRepositoryImpl(
    private val positionDao: ReadingPositionDao,
    private val sessionDao: ReadingSessionDao
) : ReadingRepository {

    override fun observeLatestPosition(bookId: String): Flow<ReadingPositionEntity?> =
        positionDao.getLatestPosition(bookId)

    override suspend fun savePosition(bookId: String, locatorJson: String) {
        positionDao.insertPosition(
            ReadingPositionEntity(bookId = bookId, locatorJson = locatorJson)
        )
    }

    override suspend fun startSession(bookId: String): Long =
        sessionDao.insert(ReadingSessionEntity(bookId = bookId))

    override suspend fun finalizeSession(
        sessionId: Long,
        pageTurnTimestampsMs: List<Long>,
        sessionStartMs: Long
    ) {
        val session = sessionDao.getById(sessionId) ?: return
        val nowMs = System.currentTimeMillis()
        val rawDuration = ((nowMs - sessionStartMs) / 1000).toInt()
        val adjustedDuration = calcAdjustedDuration(sessionStartMs, pageTurnTimestampsMs, nowMs)
        val pagesTurned = pageTurnTimestampsMs.size
        val wordsRead = (adjustedDuration / 60.0 * 200).toInt()
        sessionDao.updateSession(
            session.copy(
                durationSeconds = adjustedDuration,
                rawDurationSeconds = rawDuration,
                pagesTurned = pagesTurned,
                wordsRead = wordsRead
            )
        )
    }

    /**
     * Computes reading time by discarding any page where the reader spent more than 3× the
     * per-session average (idle detection).
     */
    private fun calcAdjustedDuration(
        sessionStartMs: Long,
        pageTurnsMs: List<Long>,
        endMs: Long
    ): Int {
        // Build list of time-on-page intervals: start → first turn, turn_i → turn_i+1, last → end
        val allPoints = listOf(sessionStartMs) + pageTurnsMs + listOf(endMs)
        val intervals = allPoints.zipWithNext { a, b -> (b - a).coerceAtLeast(0L) }
        if (intervals.isEmpty()) return 0
        val avgMs = intervals.average()
        val threshold = avgMs * 3.0
        return (intervals.filter { it <= threshold }.sum() / 1000).toInt()
    }

    override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> =
        sessionDao.getSessionsForBook(bookId)
}
