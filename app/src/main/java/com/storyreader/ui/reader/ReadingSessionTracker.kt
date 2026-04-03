package com.storyreader.ui.reader

import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator

/**
 * Tracks reading sessions (manual and TTS) for a single book, recording page-turn
 * timestamps, pause intervals, and progression to compute reading statistics.
 *
 * Pause tracking (app backgrounded, TTS paused) enables precise idle-time
 * subtraction in addition to the statistical idle detection.
 *
 * If a pause exceeds [SESSION_SPLIT_THRESHOLD_MS], the session is split:
 * [onResume] returns `true` and the caller should finalize the old session
 * and start a new one.
 */
class ReadingSessionTracker(
    private val readingRepository: ReadingRepository,
    private val bookRepository: BookRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.SYSTEM
) {

    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    var sessionStartProgression: Float = 0f
    private var currentSessionIsTts: Boolean = false
    private val pageTurnTimestamps = mutableListOf<Long>()
    private val pauseIntervals = mutableListOf<Pair<Long, Long>>()
    private var pauseStartMs: Long? = null

    fun startSession(
        bookId: String,
        isTts: Boolean = false,
        currentProgression: Float? = null
    ) {
        scope.launch {
            currentSessionId = readingRepository.startSession(bookId, isTts = isTts)
            sessionStartMs = clock.currentTimeMillis()
            currentSessionIsTts = isTts
            pageTurnTimestamps.clear()
            pauseIntervals.clear()
            pauseStartMs = null
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
        sessionStartMs = clock.currentTimeMillis()
        currentSessionIsTts = isTts
        pageTurnTimestamps.clear()
        pauseIntervals.clear()
        pauseStartMs = null
        if (currentProgression != null) {
            sessionStartProgression = currentProgression
        }
    }

    fun recordPageTurn() {
        pageTurnTimestamps.add(clock.currentTimeMillis())
    }

    /**
     * Record the start of a pause (app backgrounded or TTS paused).
     * Idempotent — does nothing if already paused.
     */
    fun onPause() {
        if (currentSessionId == null || pauseStartMs != null) return
        pauseStartMs = clock.currentTimeMillis()
    }

    /**
     * Record the end of a pause (app foregrounded or TTS resumed).
     *
     * @return `true` if the pause exceeded [SESSION_SPLIT_THRESHOLD_MS] and the
     *         caller should finalize the current session and start a new one.
     *         Returns `false` if the pause was recorded normally, or if there was
     *         no active pause.
     */
    fun onResume(): Boolean {
        val start = pauseStartMs ?: return false
        pauseStartMs = null
        if (currentSessionId == null) return false
        val now = clock.currentTimeMillis()
        val duration = now - start
        if (duration >= SESSION_SPLIT_THRESHOLD_MS) {
            return true
        }
        pauseIntervals.add(start to now)
        return false
    }

    fun finalize(
        bookId: String,
        currentLocator: Locator?,
        currentChapterTitle: String?,
        isWebBook: Boolean,
        onPositionSave: (suspend (Locator) -> Unit)? = null
    ) {
        val sessionId = currentSessionId ?: return
        // Close any active pause
        val activePauseStart = pauseStartMs
        if (activePauseStart != null) {
            pauseIntervals.add(activePauseStart to clock.currentTimeMillis())
            pauseStartMs = null
        }
        val capturedTimestamps = pageTurnTimestamps.toList()
        val capturedStartMs = sessionStartMs
        val capturedStartProgression = sessionStartProgression
        val capturedIsTts = currentSessionIsTts
        val capturedPauseIntervals = pauseIntervals.toList()
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
                    pauseIntervalsMs = capturedPauseIntervals,
                    progressionStart = capturedStartProgression,
                    progressionEnd = capturedEndProgression,
                    bookWordCount = wordCount
                )
            }
        }
    }

    companion object {
        internal const val SESSION_SPLIT_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
    }
}
