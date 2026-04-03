package com.storyreader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for [calcAdjustedDuration] — the idle-detection algorithm.
 *
 * These tests exercise the function directly (no database, no Robolectric)
 * using [ReadingScenario] to build realistic reading sessions.
 */
class IdleDetectionAlgorithmTest {

    // ── Real-world scenario reported by user ─────────────────────────────────

    @Test
    fun `user-reported scenario - idle phone and app background are detected`() {
        // Exact scenario: read 40s, 31s, 38s pages; left phone 9m50s; read 20s;
        // left app 2m then read 42s; read 46s and closed.
        // Actual reading ≈ 40+31+38+20+42+46 = 217s
        val scenario = readingScenario {
            page(40)
            page(31)
            page(38)
            idle(550); page(40)   // 590s total on page — phone idle
            page(20)
            idle(120); page(42)   // 162s total on page — app backgrounded
            close(46)
        }

        val adjusted = scenario.adjustedDurationSeconds
        // Should be close to actual reading time (~217s), not the old result (~373s)
        assertTrue(
            "Expected ~255s (idle replaced with median), got $adjusted",
            adjusted in 220..280
        )
        // Raw duration should reflect full wall-clock time
        assertEquals(927, scenario.rawDurationSeconds)
    }

    // ── Uniform reading ──────────────────────────────────────────────────────

    @Test
    fun `uniform page times are all counted`() {
        val scenario = readingScenario {
            page(30); page(30); page(30); page(30); page(30)
            close(30)
        }
        // 6 intervals × 30s = 180s — all within 3× median, none excluded
        assertEquals(180, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `moderate variance is preserved`() {
        // Pages between 20–50s — all within 3× median
        val scenario = readingScenario {
            page(20); page(35); page(50); page(25); page(45)
            close(30)
        }
        // Sum = 205s, median ≈ 32s, threshold ≈ 97s — all pass
        assertEquals(205, scenario.adjustedDurationSeconds)
    }

    // ── Single idle period ───────────────────────────────────────────────────

    @Test
    fun `one long idle among consistent pages is replaced with median`() {
        val scenario = readingScenario {
            page(30); page(30); page(30)
            idle(3570); page(30)  // 3600s total = 1 hour idle
            page(30); page(30)
            close(30)
        }
        // 7 intervals: six of 30s + one of 3600s
        // Median = 30s, threshold = 90s
        // 3600s → replaced with 30s
        // Total: 7 × 30s = 210s
        assertEquals(210, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `multiple idle periods are each replaced`() {
        val scenario = readingScenario {
            page(30); page(30)
            idle(270); page(30)   // 300s idle
            page(30); page(30)
            idle(570); page(30)   // 600s idle
            page(30)
            close(30)
        }
        // Median of valid intervals: 30s (most are 30s)
        // Threshold: 90s
        // Two idle pages (300s, 600s) → each replaced with 30s
        // 8 intervals × 30s = 240s
        assertEquals(240, scenario.adjustedDurationSeconds)
    }

    // ── Rapid page flips ─────────────────────────────────────────────────────

    @Test
    fun `all rapid flips yield zero duration`() {
        val scenario = readingScenario {
            page(1); page(2); page(1); page(3); page(2)
            close(1)
        }
        // All intervals < 5s → all excluded
        assertEquals(0, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `rapid flips mixed with normal reading - rapid excluded, normal kept`() {
        val scenario = readingScenario {
            page(2)    // rapid
            page(30)   // normal
            page(1)    // rapid
            page(25)   // normal
            page(3)    // rapid
            close(35)  // normal
        }
        // Valid intervals (≥5s): [30, 25, 35]
        // Median = 30, threshold = 90
        // All valid intervals pass → sum = 90s
        assertEquals(90, scenario.adjustedDurationSeconds)
    }

    // ── Single page / no page turns ──────────────────────────────────────────

    @Test
    fun `no page turns - single interval counted`() {
        val scenario = readingScenario {
            close(300)  // 5 minutes of reading, no page turns
        }
        // Single interval of 300s. Median = 300s, threshold = 900s.
        // 300s < 900s → kept
        assertEquals(300, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `one page turn creates two intervals`() {
        val scenario = readingScenario {
            page(40)
            close(35)
        }
        // Intervals: [40, 35]. Both >= 5s.
        // Median = 37, threshold = 112
        // Both pass → 75s
        assertEquals(75, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `short session under minimum interval is zero`() {
        val scenario = readingScenario {
            close(3)  // 3 seconds, under MIN_PAGE_INTERVAL_MS
        }
        assertEquals(0, scenario.adjustedDurationSeconds)
    }

    // ── Edge cases with few data points ──────────────────────────────────────

    @Test
    fun `two intervals - one normal one idle`() {
        val scenario = readingScenario {
            page(30)
            idle(270); close(30)  // 300s on last page
        }
        // Intervals: [30s, 300s]
        // Median = (30+300)/2 = 165s
        // Threshold = 495s
        // Both pass. Sum = 330s
        // With few data points, the median is skewed, but at least not as bad as mean
        assertEquals(330, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `three intervals - clear outlier even with small sample`() {
        val scenario = readingScenario {
            page(30)
            page(30)
            idle(3570); close(30) // 3600s idle on last page
        }
        // Intervals: [30, 30, 3600]
        // Median = 30, threshold = 90
        // 3600 → replaced with 30
        // Total: 90s
        assertEquals(90, scenario.adjustedDurationSeconds)
    }

    // ── Gradually changing pace ──────────────────────────────────────────────

    @Test
    fun `gradually increasing page times are preserved when within 3x median`() {
        val scenario = readingScenario {
            page(20); page(30); page(40); page(50); page(60)
            close(70)
        }
        // Sorted: [20, 30, 40, 50, 60, 70]. Median = (40+50)/2 = 45
        // Threshold = 135. Max interval is 70 < 135. All kept.
        assertEquals(270, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `dense book with long pages is not penalized`() {
        // Reader spends 2-4 minutes per page — legitimate, not idle
        val scenario = readingScenario {
            page(120); page(180); page(150); page(240); page(130)
            close(200)
        }
        // Sorted: [120, 130, 150, 180, 200, 240]. Median = (150+180)/2 = 165
        // Threshold = 495. Max = 240 < 495. All kept.
        assertEquals(1020, scenario.adjustedDurationSeconds)
    }

    // ── Identical intervals ──────────────────────────────────────────────────

    @Test
    fun `all identical intervals are kept`() {
        val scenario = readingScenario {
            page(45); page(45); page(45); page(45)
            close(45)
        }
        assertEquals(225, scenario.adjustedDurationSeconds)
    }

    // ── Outlier at session boundary ──────────────────────────────────────────

    @Test
    fun `idle at session start is detected`() {
        // User opens book, puts phone down, comes back 10 min later, reads normally
        val scenario = readingScenario {
            idle(570); page(30)  // 600s on first page
            page(30); page(30); page(30)
            close(30)
        }
        // Intervals: [600, 30, 30, 30, 30]
        // Median = 30, threshold = 90. 600 → 30.
        // Total: 5 × 30 = 150s
        assertEquals(150, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `idle at session end is detected`() {
        val scenario = readingScenario {
            page(30); page(30); page(30); page(30)
            idle(570); close(30)  // 600s on last page before closing
        }
        // Intervals: [30, 30, 30, 30, 600]
        // Median = 30, threshold = 90. 600 → 30.
        assertEquals(150, scenario.adjustedDurationSeconds)
    }

    // ── Zero and negative edge cases ─────────────────────────────────────────

    @Test
    fun `empty scenario yields zero`() {
        val scenario = readingScenario { close(0) }
        assertEquals(0, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `scenario with only page turns and no close time`() {
        val scenario = readingScenario {
            page(30); page(30); page(30)
            close(0)
        }
        // Last interval is 0s (< 5s) — excluded
        // Remaining: [30, 30, 30]. Median = 30, threshold = 90.
        assertEquals(90, scenario.adjustedDurationSeconds)
    }

    // ── Algorithm property: median robustness ────────────────────────────────

    @Test
    fun `median is not skewed by extreme outlier`() {
        // 10 normal pages + 1 absurd outlier (24 hours)
        val scenario = readingScenario {
            page(30); page(30); page(30); page(30); page(30)
            idle(86370); page(30) // 86400s = 24 hours idle
            page(30); page(30); page(30); page(30)
            close(30)
        }
        // 11 intervals: ten of 30s, one of 86400s
        // Median = 30, threshold = 90. 86400 → 30.
        // Total: 11 × 30 = 330s
        assertEquals(330, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `old mean-based algorithm would fail here but median catches it`() {
        // The key scenario: a moderate outlier that inflates the mean
        // enough to make the threshold useless.
        // 5 pages at 30s, then one at 200s (lunch break).
        val scenario = readingScenario {
            page(30); page(30); page(30); page(30); page(30)
            idle(170); page(30) // 200s total — user went to get coffee
            close(30)
        }
        // Intervals: [30,30,30,30,30,200,30]
        // Old mean: avg ≈ 54, threshold ≈ 163 → 200 > 163 so excluded, sum = 180s
        // New median: median = 30, threshold = 90 → 200 > 90, replaced with 30
        // Total: 7 × 30 = 210s (more accurate — includes the actual reading on that page)
        //
        // But old algorithm would EXCLUDE the 200s entirely (losing the ~30s of reading),
        // while new algorithm REPLACES it with median (30s estimate for the reading time).
        // In this case the outcomes are similar, but the replacement approach is more
        // principled — see the user-reported scenario test above for a case where
        // the old algorithm badly fails.
        assertEquals(210, scenario.adjustedDurationSeconds)
    }

    // ── Borderline intervals ─────────────────────────────────────────────────

    @Test
    fun `interval exactly at 3x median is kept`() {
        val scenario = readingScenario {
            page(30); page(30); page(30)
            page(90) // exactly 3× median
            close(30)
        }
        // Intervals: [30,30,30,90,30]. Median = 30, threshold = 90.
        // 90 <= 90 → kept (not strictly greater)
        assertEquals(210, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `interval just over 3x median is replaced`() {
        val scenario = readingScenario {
            page(30); page(30); page(30)
            page(91) // just over 3× median
            close(30)
        }
        // 91 > 90 → replaced with 30
        assertEquals(150, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `interval exactly at minimum page interval is kept`() {
        val scenario = readingScenario {
            page(5)   // exactly 5s — passes MIN_PAGE_INTERVAL_MS
            page(30)
            close(30)
        }
        // Intervals: [5s, 30s, 30s]. Median of valid = (5+30)/... wait
        // Valid intervals (≥5s): [5000, 30000, 30000]. Median = 30000.
        // Threshold = 90000. All pass.
        assertEquals(65, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `interval just under minimum page interval is excluded`() {
        val scenario = readingScenario {
            page(4)   // 4s — just under 5s threshold
            page(30)
            close(30)
        }
        // Intervals: [4s, 30s, 30s].
        // 4s < 5s → excluded. Valid: [30, 30]. Sum = 60s.
        assertEquals(60, scenario.adjustedDurationSeconds)
    }

    // ── Known pause handling ─────────────────────────────────────────────────

    @Test
    fun `known pause is subtracted from the affected page interval`() {
        val scenario = readingScenario {
            page(30); page(30); page(30)
            pause(120); page(30)   // 150s total on page, 120s is known pause
            page(30)
            close(30)
        }
        // Without pause: intervals = [30, 30, 30, 150, 30, 30]
        // After subtracting 120s pause from the 150s interval: [30, 30, 30, 30, 30, 30]
        // All within threshold → 180s
        assertEquals(180, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `user-reported scenario with known pause gives precise result`() {
        // Same as user-reported scenario, but the 2-min app background is now
        // a known pause instead of an unknown idle.
        val scenario = readingScenario {
            page(40)
            page(31)
            page(38)
            idle(550); page(40)    // phone idle — still caught by statistical detection
            page(20)
            pause(120); page(42)   // app background is now a known pause
            close(46)
        }
        // Intervals after pause subtraction: [40, 31, 38, 590, 20, 42, 46]
        // Median of valid intervals: sorted = [20, 31, 38, 40, 42, 46, 590]
        //   median = 40
        // Threshold = 120. 590 > 120 → replaced with 40.
        // Sum = 40 + 31 + 38 + 40 + 20 + 42 + 46 = 257s
        val adjusted = scenario.adjustedDurationSeconds
        assertTrue(
            "Expected ~257s with known pause, got $adjusted",
            adjusted in 240..270
        )
    }

    @Test
    fun `multiple known pauses on different pages`() {
        val scenario = readingScenario {
            page(30)
            pause(60); page(30)    // 90s total, 60s paused → 30s reading
            page(30)
            pause(300); page(30)   // 330s total, 300s paused → 30s reading
            close(30)
        }
        // After pause subtraction: [30, 30, 30, 30, 30] → 150s
        assertEquals(150, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `pause covering entire page interval reduces it to zero`() {
        val scenario = readingScenario {
            page(30); page(30)
            pause(60); close(0)    // pause then immediately close
        }
        // Intervals: [30, 30, 60]. After subtracting 60s pause from last: [30, 30, 0]
        // 0 < 5s → excluded. Valid: [30, 30]. Sum = 60s.
        assertEquals(60, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `pause does not affect non-overlapping intervals`() {
        val scenario = readingScenario {
            page(30)
            page(30)
            pause(60); page(30)   // pause only on this page
            page(30)
            close(30)
        }
        // Intervals: [30, 30, 90, 30, 30]. Pause covers 60s of the 90s interval.
        // After subtraction: [30, 30, 30, 30, 30] = 150s
        assertEquals(150, scenario.adjustedDurationSeconds)
    }

    @Test
    fun `combined idle and pause - idle caught by statistics, pause subtracted`() {
        // Page with both unknown idle AND a known pause.
        val scenario = readingScenario {
            page(30); page(30); page(30); page(30)
            idle(300); pause(120); page(30)  // 450s total: 300s idle + 120s pause + 30s reading
            page(30)
            close(30)
        }
        // Intervals before pause: [30, 30, 30, 30, 450, 30, 30]
        // After subtracting 120s pause from 450s: [30, 30, 30, 30, 330, 30, 30]
        // Median = 30, threshold = 90. 330 > 90 → replaced with 30.
        // Total: 7 × 30 = 210s
        assertEquals(210, scenario.adjustedDurationSeconds)
    }

    // ── TTS-style scenarios ──────────────────────────────────────────────────

    @Test
    fun `tts pause time can be computed from scenario`() {
        // For TTS sessions, adjusted = raw - paused (no statistical detection).
        // Verify the scenario tracks pause time correctly.
        val scenario = readingScenario {
            page(10); page(10); page(10)
            pause(120)  // TTS paused for 2 min
            page(10); page(10)
            pause(60)   // TTS paused for 1 min
            page(10)
            close(10)
        }
        val totalPausedMs = scenario.pauseIntervalsMs.sumOf { it.second - it.first }
        val ttsAdjusted = scenario.rawDurationSeconds - (totalPausedMs / 1000).toInt()
        // Raw = 70s reading + 180s paused = 250s
        // TTS adjusted = 250 - 180 = 70s
        assertEquals(250, scenario.rawDurationSeconds)
        assertEquals(70, ttsAdjusted)
    }
}
