// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How large to decode a picture — the arithmetic behind `PreviewImageLoader`, kept apart so it can be
 * tested. Never upscaled: a decode is the source's size or smaller, aspect kept.
 *
 * lurker-ios decodes every inline still at full size, and its 32 MB cache holds three screenshots.
 * Here an inline still is decoded to what its box can show, which is the only place this port spends
 * memory differently on purpose.
 */
object DecodeSize {

    /** A width and height in pixels. */
    data class Pixels(val width: Int, val height: Int)

    /**
     * An inline still: enough to fill a box [boxWidth] by [boxHeight] pixels — the largest an inline
     * attachment ever is (the column's width, and the 280dp tallest lone box).
     *
     * ⚠ FILL, not fit: inline boxes crop (a mosaic tile, a lone image clamped to its bounds), so the
     * decode must cover the box on BOTH axes. A portrait screenshot in a landscape-clamped box needs its
     * full width, and keeps it; a 4000x3000 photo needs a quarter of it.
     */
    fun inline(width: Int, height: Int, boxWidth: Int, boxHeight: Int): Pixels {
        if (width <= 0 || height <= 0) return Pixels(max(width, 1), max(height, 1))
        val scale = min(1.0, max(boxWidth.toDouble() / width, boxHeight.toDouble() / height))
        return scaled(width, height, scale)
    }

    /**
     * A picture in the viewer, where it can be zoomed: within [maxEdge] on its longest side and
     * [maxPixels] in all — sharp at a couple of times the screen, without a twelve-megapixel photo
     * becoming a 48 MB bitmap.
     */
    fun viewer(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Pixels {
        if (width <= 0 || height <= 0) return Pixels(max(width, 1), max(height, 1))
        val byEdge = maxEdge.toDouble() / max(width, height)
        val byArea = sqrt(maxPixels.toDouble() / (width.toLong() * height))
        return scaled(width, height, min(1.0, min(byEdge, byArea)))
    }

    private fun scaled(width: Int, height: Int, scale: Double): Pixels =
        Pixels(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
}
