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
 * An inline still is decoded for the BOX that asked for it, not for the screen: a 64dp chip, a 160dp
 * mosaic tile and a full-width lone image each get what they can show. lurker-ios decodes every still
 * at full size, and its 32 MB cache holds about three screenshots; this is where the port spends memory
 * differently on purpose.
 */
object DecodeSize {

    /** A width and height in pixels. */
    data class Pixels(val width: Int, val height: Int)

    /** How the box shows its picture — which decides what the decode has to cover. */
    enum class Mode {
        /** Fills the box and crops (a tile, a lone image, a chip, a poster). */
        Fill,

        /** Fits inside the box, letterboxed (a card's hero band). */
        Fit,
    }

    /** A crop, in the scaled picture's pixels. */
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** What to decode: the picture scaled to [size], then cut to [crop] if there is one. */
    data class Plan(val size: Pixels, val crop: Crop?)

    /**
     * The step a box's size is rounded UP to, so the boxes one screen draws share a handful of decodes
     * rather than one per pixel of difference — and a rotation, which changes every box's width, gets a
     * decode for the new width rather than keeping the old one.
     */
    const val STEP = 128

    /** A box of [width] by [height] pixels as a cache bucket, or null before it has been measured. */
    fun bucket(width: Int, height: Int): Pixels? {
        if (width <= 0 || height <= 0) return null
        return Pixels(roundUp(width), roundUp(height))
    }

    private fun roundUp(px: Int): Int = ((px + STEP - 1) / STEP) * STEP

    /**
     * An inline still of a [width] by [height] picture, for a [box] shown in [mode].
     *
     * ⚠ FILL covers the box on BOTH axes and then crops to it, centred as the box crops: a portrait
     * screenshot in a landscape-clamped box needs its full width to stay sharp, but only a box's worth
     * of its height — decoding the rest would be ten megabytes of pixels nobody sees. FIT only has to
     * fit.
     */
    fun inline(width: Int, height: Int, box: Pixels, mode: Mode): Plan {
        if (width <= 0 || height <= 0) return Plan(Pixels(max(width, 1), max(height, 1)), null)
        val byWidth = box.width.toDouble() / width
        val byHeight = box.height.toDouble() / height
        return when (mode) {
            Mode.Fit -> Plan(scaled(width, height, min(1.0, min(byWidth, byHeight))), null)
            Mode.Fill -> {
                val size = scaled(width, height, min(1.0, max(byWidth, byHeight)))
                val cropWidth = min(box.width, size.width)
                val cropHeight = min(box.height, size.height)
                val crop = if (cropWidth == size.width && cropHeight == size.height) {
                    null
                } else {
                    val left = (size.width - cropWidth) / 2
                    val top = (size.height - cropHeight) / 2
                    Crop(left, top, left + cropWidth, top + cropHeight)
                }
                Plan(size, crop)
            }
        }
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

/**
 * When a still whose fetch failed RETRYABLY is asked for again, while its box stays on screen.
 *
 * ⚠ Only a retryable failure — a 503 from a throttled origin, a dropped connection — comes back here;
 * a verdict (404, bytes that won't decode) is latched by the loader and never retried. Bounded: a few
 * tries on a doubling delay, then the box stays empty until it's composed again (scrolled back to, or
 * the screen reopened), which is a new reader's request rather than a timer hammering the proxy.
 */
object StillRetry {
    /** How many times one box asks again. */
    const val MAX_ATTEMPTS = 4

    /** The wait before retry [attempt] (1-based): 2s, 4s, 8s, 16s; null once the attempts are spent. */
    fun delayMs(attempt: Int): Long? {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) return null
        return 2_000L shl (attempt - 1)
    }
}
