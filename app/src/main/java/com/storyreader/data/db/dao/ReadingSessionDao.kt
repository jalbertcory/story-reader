package com.storyreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

data class BookLastRead(val bookId: String, val lastReadAt: Long)

data class BookSessionStats(
    val bookId: String,
    val firstSessionStart: Long,
    val lastSessionStart: Long,
    val totalDurationSeconds: Int,
    val totalWordsRead: Int,
    val sessionCount: Int
)

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startTime DESC")
    fun getSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: Long): ReadingSessionEntity?

    @Query("SELECT bookId, MAX(startTime) as lastReadAt FROM reading_sessions GROUP BY bookId")
    fun getLastReadTimes(): Flow<List<BookLastRead>>

    @Query("""
        SELECT bookId,
               MIN(startTime) as firstSessionStart,
               MAX(startTime) as lastSessionStart,
               SUM(durationSeconds) as totalDurationSeconds,
               SUM(wordsRead) as totalWordsRead,
               COUNT(*) as sessionCount
        FROM reading_sessions
        GROUP BY bookId
    """)
    fun getBookSessionStats(): Flow<List<BookSessionStats>>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions")
    fun getTotalReadingSeconds(): Flow<Long?>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions WHERE startTime >= :fromMs")
    fun getReadingSecondsSince(fromMs: Long): Flow<Long?>

    @Query("SELECT SUM(wordsRead) FROM reading_sessions")
    fun getTotalWordsRead(): Flow<Long?>

    @Query("SELECT SUM(wordsRead) FROM reading_sessions WHERE startTime >= :fromMs")
    fun getWordsReadSince(fromMs: Long): Flow<Long?>

    @Query("SELECT COUNT(DISTINCT bookId) FROM reading_sessions")
    fun getTotalBooksStarted(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT bookId) FROM reading_sessions WHERE startTime >= :fromMs")
    fun getBooksStartedSince(fromMs: Long): Flow<Int>

    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Update
    suspend fun updateSession(session: ReadingSessionEntity)
}
