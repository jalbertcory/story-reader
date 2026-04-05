package com.storyreader.data.repository

import android.net.Uri
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.sync.SyncSourceKinds
import java.io.File

fun BookEntity.isStoryManagerRecoverable(): Boolean =
    (syncSourceKind == SyncSourceKinds.STORY_MANAGER || sourceType == "web") && serverBookId != null

fun BookEntity.localBookFile(): File? {
    val uri = Uri.parse(bookId)
    val path = when {
        uri.scheme == "file" -> uri.path
        uri.scheme.isNullOrBlank() -> bookId
        else -> null
    }
    return path?.takeIf { it.isNotBlank() }?.let(::File)
}

fun BookEntity.hasLocalBookFile(): Boolean = localBookFile()?.exists() == true

fun BookEntity.shouldDeleteCompletedLocalFile(): Boolean =
    totalProgression >= 1f && isStoryManagerRecoverable()

fun BookEntity.requiresRestartToOpen(): Boolean =
    shouldDeleteCompletedLocalFile() && !hasLocalBookFile()

fun deleteLocalBookFile(book: BookEntity): Boolean =
    book.localBookFile()?.takeIf(File::exists)?.delete() == true
