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
        pauseIntervalsMs: List<Pair<Long, Long>> = emptyList(),
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
        pauseIntervalsMs: List<Pair<Long, Long>>,
        progressionStart: Float,
        progressionEnd: Float,
        bookWordCount: Int
    ) {
        val session = sessionDao.getById(sessionId) ?: return
        val nowMs = System.currentTimeMillis()
        val rawDuration = ((nowMs - sessionStartMs) / 1000).toInt()
        val totalPausedSeconds = (pauseIntervalsMs.sumOf { it.second - it.first } / 1000).toInt()
        val adjustedDuration = if (isTts) {
            // TTS plays at a constant rate — subtract known pauses only
            (rawDuration - totalPausedSeconds).coerceAtLeast(0)
        } else {
            // Manual reading: statistical idle detection + known pause subtraction
            calcAdjustedDuration(sessionStartMs, pageTurnTimestampsMs, nowMs, pauseIntervalsMs)
        }
        val pagesTurned = pageTurnTimestampsMs.size
        val wordsRead = if (bookWordCount > 0 && progressionEnd > progressionStart)
            ((progressionEnd - progressionStart).coerceAtLeast(0f) * bookWordCount).toInt()
        else
            (adjustedDuration / 60.0 * 200).toInt()  // fallback estimate
        // Discard trivial sessions: no page turns and either too few words or too little time
        if (pagesTurned == 0 && (wordsRead < MIN_WORDS_THRESHOLD || adjustedDuration < MIN_DURATION_THRESHOLD)) {
            sessionDao.deleteById(sessionId)
            return
        }
        sessionDao.updateSession(
            session.copy(
                durationSeconds = adjustedDuration,
                rawDurationSeconds = rawDuration,
                pagesTurned = pagesTurned,
                wordsRead = wordsRead
            )
        )
    }

    companion object {
        private const val MIN_WORDS_THRESHOLD = 50
        private const val MIN_DURATION_THRESHOLD = 30  // seconds
    }

    override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> =
        sessionDao.getSessionsForBook(bookId)
}
