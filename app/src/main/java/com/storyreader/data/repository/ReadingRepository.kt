package com.storyreader.data.repository

import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

interface ReadingRepository {
    fun observeLatestPosition(bookId: String): Flow<ReadingPositionEntity?>
    suspend fun savePosition(bookId: String, locatorJson: String)
    suspend fun startSession(bookId: String, isTts: Boolean = false): Long
    suspend fun finalizeSession(
        sessionId: Long,
        pageTurnTimestampsMs: List<Long>,
        sessionStartMs: Long,
        isTts: Boolean = false,
        progressionStart: Float = 0f,
        progressionEnd: Float = 0f,
        bookWordCount: Int = 0
    )
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

    override suspend fun startSession(bookId: String, isTts: Boolean): Long =
        sessionDao.insert(ReadingSessionEntity(bookId = bookId, isTts = isTts))

    override suspend fun finalizeSession(
        sessionId: Long,
        pageTurnTimestampsMs: List<Long>,
        sessionStartMs: Long,
        isTts: Boolean,
        progressionStart: Float,
        progressionEnd: Float,
        bookWordCount: Int
    ) {
        val session = sessionDao.getById(sessionId) ?: return
        val nowMs = System.currentTimeMillis()
        val rawDuration = ((nowMs - sessionStartMs) / 1000).toInt()
        // TTS plays at a constant rate — idle detection doesn't apply, use raw duration
        val adjustedDuration = if (isTts) rawDuration
            else calcAdjustedDuration(sessionStartMs, pageTurnTimestampsMs, nowMs)
        val pagesTurned = pageTurnTimestampsMs.size
        val wordsRead = if (bookWordCount > 0 && progressionEnd > progressionStart)
            ((progressionEnd - progressionStart).coerceAtLeast(0f) * bookWordCount).toInt()
        else
            (adjustedDuration / 60.0 * 200).toInt()  // fallback estimate
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
     * Computes reading time by:
     * - Discarding any page where the reader spent more than 3× the per-session average (idle detection).
     * - Discarding any page turned in under 5 seconds (rapid skipping that would inflate WPM stats).
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
        return (intervals
            .filter { it >= MIN_PAGE_INTERVAL_MS && it <= threshold }
            .sum() / 1000
        ).toInt()
    }

    companion object {
        private const val MIN_PAGE_INTERVAL_MS = 5_000L
    }

    override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> =
        sessionDao.getSessionsForBook(bookId)
}
