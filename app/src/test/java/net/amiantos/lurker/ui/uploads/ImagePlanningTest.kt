// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.client.ImageShrink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filling the kit's `ImageShrink.Source` from Android's decoder, and turning its plan into a redraw. */
class ImagePlanningTest {
    private fun plan(header: ImagePlanning.Header, max: Int?) = ImageShrink.plan(ImagePlanning.source(header), max)

    @Test
    fun anAnimatedHeifIsReportedAsASequenceSoItIsNeverFlattened() {
        // ⚠⚠ The plan's HEIC conversion runs before its animation check.
        assertEquals("image/heif-sequence", ImagePlanning.typeIdentifier("image/heif", isAnimated = true))
        assertEquals("image/heic-sequence", ImagePlanning.typeIdentifier("image/heic", isAnimated = true))
        assertEquals(ImageShrink.Plan.Leave, plan(ImagePlanning.Header(4000, 3000, "image/heif", isAnimated = true), 2048))
    }

    @Test
    fun aStillHeicIsConvertedToJpeg() {
        val convert = plan(ImagePlanning.Header(4032, 3024, "image/heif", isAnimated = false), null)
        assertEquals(ImageShrink.Plan.Convert(ImageShrink.heicDecodeCeiling, ImageShrink.Format.Jpeg), convert)
    }

    @Test
    fun theDecodersTypeIsLowerCasedForTheExactCompare() {
        assertEquals("image/jpeg", ImagePlanning.typeIdentifier("IMAGE/JPEG", isAnimated = false))
    }

    @Test
    fun anAnimationGoesUpVerbatim() {
        assertEquals(ImageShrink.Plan.Leave, plan(ImagePlanning.Header(4000, 4000, "image/gif", isAnimated = true), 1024))
        assertEquals(ImageShrink.Plan.Leave, plan(ImagePlanning.Header(4000, 4000, "image/webp", isAnimated = true), 1024))
    }

    @Test
    fun aBigJpegShrinksToTheAdvertisedEdge() {
        val shrink = plan(ImagePlanning.Header(8000, 6000, "image/jpeg", isAnimated = false), 2048)
        assertEquals(ImageShrink.Plan.Shrink(2048, ImageShrink.Format.Jpeg), shrink)
        assertTrue(ImagePlanning.onlyIfSmaller(shrink))
        assertEquals(2048, ImagePlanning.maxPixelSize(shrink))
    }

    @Test
    fun noAdvertisedEdgeMeansNoShrink() {
        assertEquals(ImageShrink.Plan.Leave, plan(ImagePlanning.Header(8000, 6000, "image/jpeg", isAnimated = false), null))
        assertNull(ImagePlanning.maxPixelSize(ImageShrink.Plan.Leave))
    }

    @Test
    fun theFormatWaitsForTheBitmapsAlpha() {
        // A static WebP: PNG only if the decoded pixels can be transparent.
        val source = ImagePlanning.source(ImagePlanning.Header(5000, 5000, "image/webp", isAnimated = false))
        assertEquals(ImageShrink.Format.Png, ImagePlanning.format(source, hasAlpha = true, maxStaticImageDimension = 2048))
        assertEquals(ImageShrink.Format.Jpeg, ImagePlanning.format(source, hasAlpha = false, maxStaticImageDimension = 2048))
        // A PNG stays PNG, and a HEIC conversion is JPEG even with alpha.
        val png = ImagePlanning.source(ImagePlanning.Header(5000, 5000, "image/png", isAnimated = false))
        assertEquals(ImageShrink.Format.Png, ImagePlanning.format(png, hasAlpha = false, maxStaticImageDimension = 2048))
        val heic = ImagePlanning.source(ImagePlanning.Header(5000, 5000, "image/heic", isAnimated = false))
        assertEquals(ImageShrink.Format.Jpeg, ImagePlanning.format(heic, hasAlpha = true, maxStaticImageDimension = null))
    }

    @Test
    fun theTargetFitsTheLongestEdgeAndNeverGrows() {
        assertEquals(2048 to 1536, ImagePlanning.targetSize(8000, 6000, 2048))
        assertEquals(1536 to 2048, ImagePlanning.targetSize(6000, 8000, 2048))
        assertEquals(800 to 600, ImagePlanning.targetSize(800, 600, 2048))
        assertEquals(4096 to 1, ImagePlanning.targetSize(40000, 2, 4096))
    }

    @Test
    fun aShrinkThatDidntShrinkIsDropped() {
        assertTrue(ImagePlanning.keeps(onlyIfSmaller = true, before = 1_000, after = 999))
        assertFalse(ImagePlanning.keeps(onlyIfSmaller = true, before = 1_000, after = 1_000))
        // A conversion is kept whatever the size: the server can't decode the original at all.
        assertTrue(ImagePlanning.keeps(onlyIfSmaller = false, before = 1_000, after = 5_000))
    }
}
