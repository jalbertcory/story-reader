package com.storyreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.storyreader.data.db.entity.ReadingPositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun getLatestPosition(bookId: String): Flow<ReadingPositionEntity?>

    @Insert
    suspend fun insertPosition(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatestPositionOnce(bookId: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_positions ORDER BY timestamp DESC")
    fun getAllPositions(): Flow<List<ReadingPositionEntity>>
}
