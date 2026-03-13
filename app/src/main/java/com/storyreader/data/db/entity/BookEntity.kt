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
    val wordCount: Int = 0
)
