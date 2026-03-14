package com.storyreader.data.sync

data class GoogleDriveItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long
)
