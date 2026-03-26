package com.storyreader.ui.reader

import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator

/**
 * Tracks reading sessions (manual and TTS) for a single book, recording page-turn
 * timestamps and progression to compute reading statistics.
 */
class ReadingSessionTracker(
    private val readingRepository: ReadingRepository,
    private val bookRepository: BookRepository,
    private val scope: CoroutineScope
) {

    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    var sessionStartProgression: Float = 0f
    private var currentSessionIsTts: Boolean = false
    private val pageTurnTimestamps = mutableListOf<Long>()

    fun startSession(
        bookId: String,
        isTts: Boolean = false,
        currentProgression: Float? = null
    ) {
        scope.launch {
            currentSessionId = readingRepository.startSession(bookId, isTts = isTts)
            sessionStartMs = System.currentTimeMillis()
            currentSessionIsTts = isTts
            pageTurnTimestamps.clear()
            if (currentProgression != null) {
                sessionStartProgression = currentProgression
            }
        }
    }

    fun startSessionSync(
        bookId: String,
        isTts: Boolean = false,
        currentProgression: Float? = null
    ): suspend () -> Unit = {
        currentSessionId = readingRepository.startSession(bookId, isTts = isTts)
        sessionStartMs = System.currentTimeMillis()
        currentSessionIsTts = isTts
        pageTurnTimestamps.clear()
        if (currentProgression != null) {
            sessionStartProgression = currentProgression
        }
    }

    fun recordPageTurn() {
        pageTurnTimestamps.add(System.currentTimeMillis())
    }

    fun finalize(
        bookId: String,
        currentLocator: Locator?,
        currentChapterTitle: String?,
        isWebBook: Boolean,
        onPositionSave: (suspend (Locator) -> Unit)? = null
    ) {
        val sessionId = currentSessionId ?: return
        val capturedTimestamps = pageTurnTimestamps.toList()
        val capturedStartMs = sessionStartMs
        val capturedStartProgression = sessionStartProgression
        val capturedIsTts = currentSessionIsTts
        val capturedEndProgression =
            currentLocator?.locations?.totalProgression?.toFloat() ?: capturedStartProgression
        val capturedChapterProgression = currentLocator?.locations?.progression?.toFloat()
        currentSessionId = null

        scope.launch {
            withContext(NonCancellable) {
                if (currentLocator != null) {
                    readingRepository.savePosition(bookId, currentLocator.toJSON().toString())
                    bookRepository.updateProgression(bookId, capturedEndProgression)
                    if (isWebBook && currentChapterTitle != null) {
                        bookRepository.updateChapterPosition(
                            bookId, currentChapterTitle, capturedChapterProgression
                        )
                    }
                    onPositionSave?.invoke(currentLocator)
                }
                val wordCount = bookRepository.getWordCount(bookId)
                readingRepository.finalizeSession(
                    sessionId = sessionId,
                    pageTurnTimestampsMs = capturedTimestamps,
                    sessionStartMs = capturedStartMs,
                    isTts = capturedIsTts,
                    progressionStart = capturedStartProgression,
                    progressionEnd = capturedEndProgression,
                    bookWordCount = wordCount
                )
            }
        }
    }
}
