package com.storyreader.data.repository

/**
 * DSL for building reading session scenarios to test idle detection.
 *
 * - `page(seconds)` — read for N seconds, then turn the page
 * - `close(seconds)` — read for N seconds, then close the book (no page turn)
 * - `idle(seconds)` — time passes without interaction (phone idle, still on same page)
 * - `pause(seconds)` — app goes to background or TTS pauses for N seconds (known pause)
 *
 * The distinction between `idle` and `pause` matters:
 * - `idle` adds time to the current page interval. The statistical detector may or
 *   may not catch it depending on how much it deviates from normal page times.
 * - `pause` records a known pause interval that is subtracted precisely from the
 *   affected page interval, regardless of statistical detection.
 *
 * Example — reproducing a real user session:
 * ```
 * val scenario = readingScenario {
 *     page(40)              // 40 s reading, then turn
 *     page(31)              // 31 s reading, then turn
 *     page(38)              // 38 s reading, then turn
 *     idle(550); page(40)   // phone idle 550 s, then 40 s reading and turn
 *     page(20)              // 20 s reading, then turn
 *     pause(120); page(42)  // app backgrounded 2 min, then 42 s reading and turn
 *     close(46)             // 46 s reading, then back to library
 * }
 * ```
 */
class ReadingScenario private constructor(
    val sessionStartMs: Long,
    val pageTurnTimestampsMs: List<Long>,
    val sessionEndMs: Long,
    val pauseIntervalsMs: List<Pair<Long, Long>>
) {
    /** Computed idle-adjusted duration using the production algorithm. */
    val adjustedDurationSeconds: Int
        get() = calcAdjustedDuration(
            sessionStartMs,
            pageTurnTimestampsMs,
            sessionEndMs,
            pauseIntervalsMs
        )

    /** Raw wall-clock duration in seconds. */
    val rawDurationSeconds: Int
        get() = ((sessionEndMs - sessionStartMs) / 1000).toInt()

    class Builder {
        private val startMs = BASE_TIME_MS
        private var currentMs = BASE_TIME_MS
        private val pageTurns = mutableListOf<Long>()
        private val pauses = mutableListOf<Pair<Long, Long>>()

        /** Time passes on the current page without a page turn (phone idle, unknown to app). */
        fun idle(seconds: Int): Builder {
            currentMs += seconds * 1000L
            return this
        }

        /** App goes to background or TTS pauses for [seconds] (known pause, precisely tracked). */
        fun pause(seconds: Int): Builder {
            val pauseStart = currentMs
            currentMs += seconds * 1000L
            pauses.add(pauseStart to currentMs)
            return this
        }

        /** Read for [seconds], then turn the page. */
        fun page(seconds: Int): Builder {
            currentMs += seconds * 1000L
            pageTurns.add(currentMs)
            return this
        }

        /** Read for [seconds], then close the book (no page turn — session ends). */
        fun close(seconds: Int): Builder {
            currentMs += seconds * 1000L
            return this
        }

        fun build(): ReadingScenario =
            ReadingScenario(startMs, pageTurns.toList(), currentMs, pauses.toList())

        companion object {
            private const val BASE_TIME_MS = 1_000_000_000L
        }
    }
}

/** Build a [ReadingScenario] using the DSL. */
fun readingScenario(block: ReadingScenario.Builder.() -> Unit): ReadingScenario =
    ReadingScenario.Builder().apply(block).build()
