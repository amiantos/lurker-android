// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurker.ui.media.DecodeSize.Pixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How large a preview picture is decoded — never upscaled, aspect kept. */
class DecodeSizeTest {

    @Test
    fun anInlineStillCoversItsBoxOnBothAxes() {
        // A 4000x3000 photo in a 1080x840 box: 0.28 of it covers the height and overfills the width.
        assertEquals(Pixels(1120, 840), DecodeSize.inline(4000, 3000, boxWidth = 1080, boxHeight = 840))
    }

    @Test
    fun aPortraitScreenshotKeepsItsFullWidth() {
        // Filling a landscape-clamped box with a portrait picture needs its whole width.
        assertEquals(Pixels(1080, 2400), DecodeSize.inline(1080, 2400, boxWidth = 1080, boxHeight = 840))
    }

    @Test
    fun aSmallPictureIsNeverUpscaled() {
        assertEquals(Pixels(64, 64), DecodeSize.inline(64, 64, boxWidth = 1080, boxHeight = 840))
        assertEquals(Pixels(64, 64), DecodeSize.viewer(64, 64, maxEdge = 4800, maxPixels = 8_000_000))
    }

    @Test
    fun theViewerBoundsTheEdgeAndTheArea() {
        // By edge: 10000x1000 within 4800.
        assertEquals(Pixels(4800, 480), DecodeSize.viewer(10_000, 1_000, maxEdge = 4800, maxPixels = 8_000_000))
        // By area: a 12MP photo comes down to 8MP.
        val photo = DecodeSize.viewer(4000, 3000, maxEdge = 4800, maxPixels = 8_000_000)
        assertTrue(photo.width.toLong() * photo.height <= 8_000_000L + 4000)
        assertEquals(4f / 3f, photo.width.toFloat() / photo.height, 0.01f)
    }

    @Test
    fun aDegenerateSizeDecodesToSomething() {
        assertEquals(Pixels(1, 1), DecodeSize.inline(0, 0, boxWidth = 1080, boxHeight = 840))
    }
}
