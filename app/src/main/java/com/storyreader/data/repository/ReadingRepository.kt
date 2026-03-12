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
    suspend fun finalizeSession(sessionId: Long, durationSeconds: Int, pagesTurned: Int)
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

    override suspend fun finalizeSession(sessionId: Long, durationSeconds: Int, pagesTurned: Int) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.updateSession(session.copy(durationSeconds = durationSeconds, pagesTurned = pagesTurned))
    }

    override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> =
        sessionDao.getSessionsForBook(bookId)
}
