package com.storyreader.data.sync

data class NextcloudItem(
    val url: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long
)
