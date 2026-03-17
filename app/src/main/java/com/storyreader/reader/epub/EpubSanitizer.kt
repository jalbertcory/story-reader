package com.storyreader.reader.epub

import android.net.Uri
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val TAG = "EpubSanitizer"

/**
 * Fixes malformed XHTML in EPUB files so Readium can process them.
 *
 * Readium's CSS injection requires well-formed `<html>`, `<head>`, `</head>`,
 * and `<body>` tags. Some EPUB generators (e.g., FanFicFare) produce XHTML
 * that is missing these elements, causing Readium to throw
 * "No </head> closing tag found in this resource".
 *
 * This sanitizer rewrites the EPUB in-place for file:// URIs when any
 * XHTML resource needs fixing.
 */
object EpubSanitizer {

    fun sanitizeIfNeeded(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val file = File(path)
        if (!file.exists() || !file.name.endsWith(".epub", ignoreCase = true)) return

        try {
            sanitizeEpub(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sanitize EPUB: ${e.message}")
        }
    }

    private fun sanitizeEpub(file: File) {
        val fixes = mutableMapOf<String, ByteArray>()

        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (!isXhtml(entry.name)) continue
                val original = zip.getInputStream(entry).use { it.readBytes() }
                val content = original.toString(Charsets.UTF_8)
                val fixed = sanitizeXhtml(content)
                if (fixed != null) {
                    Log.d(TAG, "Fixing XHTML: ${entry.name}")
                    fixes[entry.name] = fixed.toByteArray(Charsets.UTF_8)
                }
            }
        }

        if (fixes.isEmpty()) return

        Log.d(TAG, "Rewriting EPUB with ${fixes.size} fixed XHTML files")
        rewriteEpub(file, fixes)
    }

    /**
     * Returns sanitized XHTML if fixes were needed, or null if the content is fine.
     */
    internal fun sanitizeXhtml(content: String): String? {
        var result = content.trim()
        var modified = result != content

        // Strip XML declaration for easier processing, re-add later
        val xmlDecl = XML_DECL_REGEX.find(result)?.value
        if (xmlDecl != null) {
            result = result.removePrefix(xmlDecl).trimStart()
        }

        // Ensure <html> wrapper exists
        if (!result.contains("<html", ignoreCase = true)) {
            result = "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n$result\n</html>"
            modified = true
        }

        // Ensure <head> exists
        if (!result.contains("<head", ignoreCase = true)) {
            val insertIdx = HEAD_INSERT_REGEX.find(result)
            if (insertIdx != null) {
                val pos = insertIdx.range.last + 1
                result = result.substring(0, pos) + "\n<head><title></title></head>" + result.substring(pos)
                modified = true
            }
        }

        // Ensure </head> exists (opening <head> present but no closing tag)
        if (result.contains("<head", ignoreCase = true) && !result.contains("</head>", ignoreCase = true)) {
            // Find the end of head content: either <body or the first block-level element
            val bodyIdx = result.indexOf("<body", ignoreCase = true)
            if (bodyIdx != -1) {
                result = result.substring(0, bodyIdx) + "</head>\n" + result.substring(bodyIdx)
                modified = true
            } else {
                // No <body> either — insert </head><body> before content after last head-level element
                val headOpenEnd = OPEN_HEAD_REGEX.find(result)
                if (headOpenEnd != null) {
                    val searchFrom = headOpenEnd.range.last + 1
                    val firstContent = FIRST_CONTENT_REGEX.find(result, searchFrom)
                    val insertPos = firstContent?.range?.first ?: searchFrom
                    result = result.substring(0, insertPos) + "</head>\n<body>\n" + result.substring(insertPos) + "\n</body>"
                    modified = true
                }
            }
        }

        // Ensure <body> exists
        if (!result.contains("<body", ignoreCase = true)) {
            val headCloseIdx = result.indexOf("</head>", ignoreCase = true)
            if (headCloseIdx != -1) {
                val afterHead = headCloseIdx + "</head>".length
                val closingHtml = result.lastIndexOf("</html>", ignoreCase = true)
                if (closingHtml != -1 && closingHtml > afterHead) {
                    result = result.substring(0, afterHead) + "\n<body>" +
                        result.substring(afterHead, closingHtml) + "</body>\n" +
                        result.substring(closingHtml)
                    modified = true
                }
            }
        }

        // Re-add XML declaration
        if (xmlDecl != null) {
            result = "$xmlDecl\n$result"
        } else if (!result.startsWith("<?xml", ignoreCase = true)) {
            result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$result"
            modified = true
        }

        return if (modified) result else null
    }

    private fun rewriteEpub(file: File, fixes: Map<String, ByteArray>) {
        val tempFile = File(file.parent, file.name + ".tmp")
        ZipFile(file).use { zip ->
            ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
                for (entry in zip.entries()) {
                    writeEntry(zip, zos, entry, fixes[entry.name])
                }
            }
        }
        tempFile.renameTo(file)
    }

    private fun writeEntry(zip: ZipFile, zos: ZipOutputStream, entry: ZipEntry, fixedContent: ByteArray?) {
        if (fixedContent != null) {
            zos.putNextEntry(ZipEntry(entry.name))
            zos.write(fixedContent)
        } else if (entry.name == "mimetype") {
            // mimetype must be stored uncompressed per EPUB spec
            val data = zip.getInputStream(entry).use { it.readBytes() }
            val storedEntry = ZipEntry(entry.name).apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = java.util.zip.CRC32().apply { update(data) }.value
            }
            zos.putNextEntry(storedEntry)
            zos.write(data)
        } else {
            zos.putNextEntry(ZipEntry(entry.name))
            zip.getInputStream(entry).use { it.copyTo(zos) }
        }
        zos.closeEntry()
    }

    private fun isXhtml(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
    }

    private val XML_DECL_REGEX = Regex("""<\?xml[^?]*\?>""", RegexOption.IGNORE_CASE)
    private val HEAD_INSERT_REGEX = Regex("""<html[^>]*>""", RegexOption.IGNORE_CASE)
    private val OPEN_HEAD_REGEX = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE)
    private val FIRST_CONTENT_REGEX = Regex("""<(?!meta|link|title|style|script)[a-z]""", RegexOption.IGNORE_CASE)
}
