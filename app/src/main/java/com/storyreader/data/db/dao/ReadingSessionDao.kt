package com.storyreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

data class BookLastRead(val bookId: String, val lastReadAt: Long)

data class MonthlyReadingStat(
    val month: Int,
    val totalSeconds: Long,
    val totalWords: Long
)

data class BookSessionStats(
    val bookId: String,
    val firstSessionStart: Long,
    val lastSessionStart: Long,
    val totalDurationSeconds: Int,
    val totalWordsRead: Int,
    val sessionCount: Int,
    val manualDurationSeconds: Int,
    val manualWordsRead: Int,
    val ttsDurationSeconds: Int,
    val ttsWordsRead: Int
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

    @Query(
        """
        SELECT rs.bookId
        FROM reading_sessions rs
        INNER JOIN books b ON b.bookId = rs.bookId
        WHERE b.hidden = 0
        GROUP BY rs.bookId
        ORDER BY MAX(rs.startTime) DESC
        LIMIT 1
        """
    )
    suspend fun getMostRecentlyReadVisibleBookId(): String?

    @Query("""
        SELECT bookId,
               MIN(startTime) as firstSessionStart,
               MAX(startTime) as lastSessionStart,
               SUM(durationSeconds) as totalDurationSeconds,
               SUM(wordsRead) as totalWordsRead,
               COUNT(*) as sessionCount,
               SUM(CASE WHEN isTts = 0 THEN durationSeconds ELSE 0 END) as manualDurationSeconds,
               SUM(CASE WHEN isTts = 0 THEN wordsRead ELSE 0 END) as manualWordsRead,
               SUM(CASE WHEN isTts = 1 THEN durationSeconds ELSE 0 END) as ttsDurationSeconds,
               SUM(CASE WHEN isTts = 1 THEN wordsRead ELSE 0 END) as ttsWordsRead
        FROM reading_sessions
        WHERE durationSeconds >= 10
        GROUP BY bookId
    """)
    fun getBookSessionStats(): Flow<List<BookSessionStats>>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions WHERE durationSeconds >= 10")
    fun getTotalReadingSeconds(): Flow<Long?>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions WHERE durationSeconds >= 10 AND startTime >= :fromMs")
    fun getReadingSecondsSince(fromMs: Long): Flow<Long?>

    @Query("SELECT SUM(wordsRead) FROM reading_sessions WHERE durationSeconds >= 10")
    fun getTotalWordsRead(): Flow<Long?>

    @Query("SELECT SUM(wordsRead) FROM reading_sessions WHERE durationSeconds >= 10 AND startTime >= :fromMs")
    fun getWordsReadSince(fromMs: Long): Flow<Long?>

    @Query("SELECT COUNT(DISTINCT bookId) FROM reading_sessions WHERE durationSeconds >= 10")
    fun getTotalBooksStarted(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT bookId) FROM reading_sessions WHERE durationSeconds >= 10 AND startTime >= :fromMs")
    fun getBooksStartedSince(fromMs: Long): Flow<Int>

    @Query("""
        SELECT CAST(strftime('%m', startTime/1000, 'unixepoch') AS INTEGER) as month,
               SUM(durationSeconds) as totalSeconds,
               SUM(wordsRead) as totalWords
        FROM reading_sessions
        WHERE durationSeconds >= 10 AND startTime >= :yearStartMs AND startTime < :yearEndMs
        GROUP BY month
        ORDER BY month
    """)
    suspend fun getMonthlyStats(yearStartMs: Long, yearEndMs: Long): List<MonthlyReadingStat>

    @Query("""
        SELECT DISTINCT CAST(strftime('%Y', startTime/1000, 'unixepoch') AS INTEGER) as year
        FROM reading_sessions
        WHERE durationSeconds >= 10
        ORDER BY year DESC
    """)
    suspend fun getReadingYears(): List<Int>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions WHERE durationSeconds >= 10 AND startTime >= :fromMs AND startTime < :toMs")
    suspend fun getReadingSecondsBetween(fromMs: Long, toMs: Long): Long?

    @Query("SELECT SUM(wordsRead) FROM reading_sessions WHERE durationSeconds >= 10 AND startTime >= :fromMs AND startTime < :toMs")
    suspend fun getWordsReadBetween(fromMs: Long, toMs: Long): Long?

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId AND startTime = :startTime LIMIT 1")
    suspend fun findByBookIdAndStartTime(bookId: String, startTime: Long): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions")
    suspend fun getAllSessionsOnce(): List<ReadingSessionEntity>

    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Update
    suspend fun updateSession(session: ReadingSessionEntity)

    @Query("DELETE FROM reading_sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: Long)
}
