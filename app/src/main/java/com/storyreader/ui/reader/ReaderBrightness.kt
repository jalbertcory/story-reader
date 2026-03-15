package com.storyreader.ui.reader

import kotlin.math.abs
import kotlin.math.sqrt

internal const val MIN_WINDOW_BRIGHTNESS = 0.01f
internal const val MAX_NORMAL_DIM_ALPHA = 0.32f
internal const val MAX_EXTRA_DIM_ALPHA = 0.78f

internal object ReaderBrightness {
    fun clamp(level: Float): Float = level.coerceIn(-1f, 1f)

    fun adjustByDrag(current: Float, dragFraction: Float): Float =
        clamp(current - dragFraction)

    fun windowBrightnessFor(level: Float): Float = when {
        level <= 0f -> MIN_WINDOW_BRIGHTNESS
        level >= 1f -> 1f
        else -> sqrt(level)
    }

    fun overlayAlphaFor(level: Float): Float = when {
        level >= 1f -> 0f
        level >= 0f -> (1f - level) * MAX_NORMAL_DIM_ALPHA
        else -> MAX_NORMAL_DIM_ALPHA + abs(level).coerceIn(0f, 1f) * (MAX_EXTRA_DIM_ALPHA - MAX_NORMAL_DIM_ALPHA)
    }
}
