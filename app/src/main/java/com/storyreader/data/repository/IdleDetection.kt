package com.storyreader.data.repository

import kotlin.math.max
import kotlin.math.min

/**
 * Computes adjusted (idle-filtered) reading duration from page-turn timestamps.
 *
 * Algorithm:
 * 1. Build time-on-page intervals: start → first turn, turn_i → turn_i+1, last turn → end
 * 2. Subtract any known pause time (app backgrounded, TTS paused) from each interval
 * 3. Discard rapid flips (< [MIN_PAGE_INTERVAL_MS]) — navigation, not reading
 * 4. Compute the **median** of remaining intervals
 * 5. Any interval exceeding [OUTLIER_MULTIPLIER] × median is replaced with the median —
 *    the page was read, but idle time inflated its interval
 * 6. Sum the adjusted intervals
 *
 * Two layers of idle detection:
 * - **Known pauses** (app background, TTS pause): subtracted precisely via [pauseIntervalsMs]
 * - **Unknown idle** (phone sitting open): caught by median-based statistical detection
 */
internal fun calcAdjustedDuration(
    sessionStartMs: Long,
    pageTurnsMs: List<Long>,
    endMs: Long,
    pauseIntervalsMs: List<Pair<Long, Long>> = emptyList()
): Int {
    val allPoints = listOf(sessionStartMs) + pageTurnsMs + listOf(endMs)
    val intervals = allPoints.zipWithNext { a, b ->
        val rawMs = (b - a).coerceAtLeast(0L)
        // Subtract any pause time that overlaps this interval
        val pauseMs = pauseIntervalsMs.sumOf { (pStart, pEnd) ->
            val overlapStart = max(a, pStart)
            val overlapEnd = min(b, pEnd)
            (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
        (rawMs - pauseMs).coerceAtLeast(0L)
    }
    if (intervals.isEmpty()) return 0

    val validIntervals = intervals.filter { it >= MIN_PAGE_INTERVAL_MS }
    if (validIntervals.isEmpty()) return 0

    val medianMs = validIntervals.median()
    val thresholdMs = (medianMs * OUTLIER_MULTIPLIER).toLong()

    return (intervals.sumOf { interval ->
        when {
            interval < MIN_PAGE_INTERVAL_MS -> 0L  // rapid flip — skip
            interval > thresholdMs -> medianMs      // idle — replace with median
            else -> interval                         // normal reading
        }
    } / 1000).toInt()
}

private fun List<Long>.median(): Long {
    require(isNotEmpty())
    val sorted = sorted()
    val mid = size / 2
    return if (size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2
    else sorted[mid]
}

internal const val MIN_PAGE_INTERVAL_MS = 5_000L
internal const val OUTLIER_MULTIPLIER = 3.0
