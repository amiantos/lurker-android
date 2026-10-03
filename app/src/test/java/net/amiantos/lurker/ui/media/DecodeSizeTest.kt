// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurker.ui.media.DecodeSize.Crop
import net.amiantos.lurker.ui.media.DecodeSize.Mode
import net.amiantos.lurker.ui.media.DecodeSize.Pixels
import net.amiantos.lurker.ui.media.DecodeSize.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How large a preview picture is decoded — for the box that asked, never upscaled, aspect kept. */
class DecodeSizeTest {

    @Test
    fun boxesShareBucketsAndAnUnmeasuredBoxHasNone() {
        assertEquals(Pixels(1152, 896), DecodeSize.bucket(1080, 840))
        assertEquals(Pixels(1152, 896), DecodeSize.bucket(1100, 841))
        assertEquals(Pixels(256, 256), DecodeSize.bucket(192, 192))
        assertNull(DecodeSize.bucket(0, 840))
    }

    @Test
    fun aRotationsWiderBoxIsADifferentBucket() {
        val portrait = DecodeSize.bucket(1080, 840)
        val landscape = DecodeSize.bucket(2340, 840)
        assertTrue(portrait != landscape)
    }

    @Test
    fun aFilledPhotoCoversItsBoxAndIsCutToIt() {
        // A 4000x3000 photo for a 1152x896 box: scaled to cover the height, then cut to the box.
        val plan = DecodeSize.inline(4000, 3000, Pixels(1152, 896), Mode.Fill)
        assertEquals(Pixels(1195, 896), plan.size)
        assertEquals(Crop(21, 0, 1173, 896), plan.crop)
    }

    @Test
    fun aPortraitScreenshotKeepsItsWidthButOnlyABoxOfItsHeight() {
        // Full width to stay sharp; only the middle 896 rows are kept — not ten megabytes of them.
        val plan = DecodeSize.inline(1080, 2400, Pixels(1152, 896), Mode.Fill)
        assertEquals(Pixels(1080, 2400), plan.size)
        assertEquals(Crop(0, 752, 1080, 1648), plan.crop)
    }

    @Test
    fun aChipDecodesAtChipSize() {
        val plan = DecodeSize.inline(512, 512, Pixels(256, 256), Mode.Fill)
        assertEquals(Plan(Pixels(256, 256), null), plan)
    }

    @Test
    fun aFittedPictureOnlyHasToFit() {
        assertEquals(Plan(Pixels(1152, 576), null), DecodeSize.inline(1200, 600, Pixels(1152, 640), Mode.Fit))
    }

    @Test
    fun aSmallPictureIsNeverUpscaled() {
        assertEquals(Plan(Pixels(64, 64), null), DecodeSize.inline(64, 64, Pixels(1152, 896), Mode.Fill))
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
        assertEquals(Pixels(1, 1), DecodeSize.inline(0, 0, Pixels(1152, 896), Mode.Fill).size)
    }

    @Test
    fun aRetryableStillIsAskedAgainAFewTimesOnADoublingDelay() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), (1..StillRetry.MAX_ATTEMPTS).map { StillRetry.delayMs(it) })
        assertNull(StillRetry.delayMs(StillRetry.MAX_ATTEMPTS + 1))
        assertNull(StillRetry.delayMs(0))
    }
}
