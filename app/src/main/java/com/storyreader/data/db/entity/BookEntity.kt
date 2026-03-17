package com.storyreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val bookId: String,
    val title: String,
    val author: String,
    val coverUri: String? = null,
    val totalProgression: Float = 0f,
    val wordCount: Int = 0,
    val hidden: Boolean = false,
    val series: String? = null,
    val sourceType: String? = null,
    val serverBookId: Int? = null,
    val contentVersion: Int? = null,
    val contentUpdatedAt: Long? = null,
    val serverWordCount: Int? = null,
    val lastSyncedAt: Long? = null
)
