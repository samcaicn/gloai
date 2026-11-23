package com.wangzan101.mika.chat.ui

import kotlin.math.min

/** Preview window size only; the source display and captured image are never resized or cropped. */
object PipSizing {
    const val MIN_SCALE = 0.65f
    const val MAX_SCALE = 1.8f
    fun scale(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else 1f

    data class Size(val width: Float, val height: Float)
    fun fit(baseWidth: Float, aspect: Float, availableWidth: Float, availableHeight: Float, inset: Float): Size {
        val ratio = aspect.takeIf { it.isFinite() && it > 0 } ?: 2.2f
        val width = availableWidth.coerceAtLeast(1f)
        val height = availableHeight.coerceAtLeast(1f)
        val padding = min(inset.coerceAtLeast(0f), min(width / 2, height / 2))
        val content = min((baseWidth - padding).coerceAtLeast(0.1f), min(width - padding, (height - padding) / ratio))
        return Size(content + padding, content * ratio + padding)
    }
}
