package com.storyreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val bookId: String,
    val startTime: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,       // Idle-adjusted reading time
    val rawDurationSeconds: Int = 0,    // Total elapsed time including idle
    val pagesTurned: Int = 0,
    val wordsRead: Int = 0,             // Estimated words read
    val isTts: Boolean = false          // Whether this was a TTS session
)
