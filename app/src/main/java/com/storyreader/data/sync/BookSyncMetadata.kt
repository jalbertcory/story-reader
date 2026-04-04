package com.storyreader.data.sync

import android.net.Uri
import com.storyreader.data.db.entity.BookEntity
import java.security.MessageDigest
import java.util.Locale

data class BookImportMetadata(
    val syncId: String? = null,
    val sourceKind: String? = null,
    val sourceUrl: String? = null,
    val originalFileName: String? = null
)

object SyncSourceKinds {
    const val DEVICE = "device"
    const val GOOGLE_DRIVE = "google_drive"
    const val NEXTCLOUD = "nextcloud"
    const val OPDS = "opds"
    const val STORY_MANAGER = "story_manager"
}

object BookSyncMetadata {
    fun syncIdFor(title: String, author: String): String =
        "book_${sha256("${normalize(title)}|${normalize(author)}").take(24)}"

    fun syncIdFor(book: BookEntity): String = book.syncId ?: syncIdFor(book.title, book.author)

    fun sessionIdFor(bookSyncId: String, startTime: Long, isTts: Boolean): String =
        "session_${sha256("$bookSyncId|$startTime|$isTts").take(24)}"

    fun extractOriginalFileName(rawValue: String?): String? {
        rawValue ?: return null
        val parsed = Uri.parse(rawValue)
        val candidate = parsed.lastPathSegment ?: rawValue.substringAfterLast('/')
        return candidate
            .substringAfterLast(':')
            .takeIf { it.isNotBlank() }
    }

    fun normalizeRemoteUrl(rawValue: String?): String? =
        rawValue?.let {
            if (it.startsWith("http://")) {
                "https://${it.removePrefix("http://")}"
            } else {
                it
            }
        }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
