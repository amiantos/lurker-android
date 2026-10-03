// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ImageShrink
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Pre-shrinking static images to the server's advertised longest edge (lurker-ios#155, the iOS
 * half of lurker#872): the rule that decides, and how the number reaches the store.
 *
 * As with the upload cap, "the server didn't say" and "the server said a number" are
 * different states — and here the silence has no fallback at all.
 *
 * The type identifiers are MIME types, mapped from LurkerKit's UTIs by the table on
 * `ImageShrink.Source.typeIdentifier`.
 */
class ImageShrinkTests {

    private fun source(
        width: Int,
        height: Int,
        frames: Int = 1,
        type: String = "image/jpeg",
        alpha: Boolean = false,
    ): ImageShrink.Source =
        ImageShrink.Source(
            pixelWidth = width, pixelHeight = height, frameCount = frames, typeIdentifier = type,
            hasAlpha = alpha,
        )

    // MARK: - The rule

    /** a photo past the advertised edge is shrunk to it, in its own format */
    @Test
    fun aBigPhotoIsShrunk() {
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 2048, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(8064, 6048), maxStaticImageDimension = 2048),
        )
        // Portrait: it's the LONGEST edge, whichever way round.
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 2048, format = ImageShrink.Format.Png),
            ImageShrink.plan(source(3024, 4032, type = "image/png"), maxStaticImageDimension = 2048),
        )
    }

    /** an image already within the edge is left alone */
    @Test
    fun aSmallImageIsLeftAlone() {
        assertEquals(ImageShrink.Plan.Leave, ImageShrink.plan(source(2048, 1536), maxStaticImageDimension = 2048))
        assertEquals(ImageShrink.Plan.Leave, ImageShrink.plan(source(640, 480), maxStaticImageDimension = 2048))
    }

    /** ⚠⚠ no advertised dimension means no shrink — never a guessed 2048 */
    @Test
    fun silenceShrinksNothing() {
        // An instance older than lurker#872, or the window before the snapshot. A guess would
        // halve a photo on an instance that keeps 4096, and the user would never know.
        assertEquals(ImageShrink.Plan.Leave, ImageShrink.plan(source(8064, 6048), maxStaticImageDimension = null))
    }

    /** ⚠⚠ an animation goes up verbatim, however big */
    @Test
    fun animationsPassThrough() {
        // The server skips the resize for these. A redraw would keep frame one and drop the
        // rest without an error — the trap the frame count exists for.
        for (type in listOf("image/gif", "image/png", "image/webp")) {
            assertEquals(
                ImageShrink.Plan.Leave,
                ImageShrink.plan(source(4000, 4000, frames = 24, type = type), maxStaticImageDimension = 2048),
                type,
            )
        }
    }

    /** ⚠ a JPEG with extra images is still a photo, not an animation */
    @Test
    fun multiPictureJPEGIsShrunk() {
        // An MPO, or a JPEG carrying an HDR gain map: ImageIO counts the MPF images, the
        // server's decoder sees one page and resizes it. Leaving it alone would upload the
        // full original for nothing — silently, which is why it's worth a test.
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 2048, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(8064, 6048, frames = 2), maxStaticImageDimension = 2048),
        )
    }

    /** a shrink past the decode budget leaves the shrinking to the server */
    @Test
    fun aHugeDecodeIsLeftToTheServer() {
        // 12000×9000 to an 8192 edge is 8192×6144 — ~200 MB of bitmap. The server can shrink it;
        // a jetsam can't be undone.
        assertEquals(ImageShrink.Plan.Leave, ImageShrink.plan(source(12000, 9000), maxStaticImageDimension = 8192))
        // The same photo to a 4096 edge fits, as does a long panorama to 8192.
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 4096, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(12000, 9000), maxStaticImageDimension = 4096),
        )
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 8192, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(20000, 4000), maxStaticImageDimension = 8192),
        )
    }

    /** a static GIF, by contrast, is just an image */
    @Test
    fun aStaticGIFIsShrunk() {
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 2048, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(4000, 3000, type = "image/gif"), maxStaticImageDimension = 2048),
        )
    }

    /** a format we don't write becomes JPEG, or PNG when it can be transparent */
    @Test
    fun otherFormatsBecomeOrdinary() {
        // ProRAW / DNG — the headline case: ~100 MB of RAW for a 2048px result.
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 2048, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(8064, 6048, type = "image/x-adobe-dng"), maxStaticImageDimension = 2048),
        )
        // Transparency survives: a TIFF or WebP with alpha is not flattened onto black.
        assertEquals(
            ImageShrink.Plan.Shrink(maxPixelSize = 2048, format = ImageShrink.Format.Png),
            ImageShrink.plan(source(5000, 5000, type = "image/tiff", alpha = true), maxStaticImageDimension = 2048),
        )
    }

    /** a JPEG stays a JPEG and a PNG a PNG, alpha or not */
    @Test
    fun jpegAndPNGKeepTheirFormat() {
        assertEquals(ImageShrink.Format.Png, ImageShrink.output(source(1, 1, type = "image/png", alpha = false)))
        assertEquals(ImageShrink.Format.Jpeg, ImageShrink.output(source(1, 1, type = "image/jpeg", alpha = true)))
    }

    /** every HEIC is converted, as before — the dimension only lets it go smaller */
    @Test
    fun heicIsAlwaysConverted() {
        // lurker#626: some iPhone HEICs 415 in the server's libheif, so they never go up as-is.
        val heic = source(4032, 3024, type = "image/heic")
        assertEquals(
            ImageShrink.Plan.Convert(maxPixelSize = ImageShrink.heicDecodeCeiling, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(heic, maxStaticImageDimension = null),
        )
        assertEquals(
            ImageShrink.Plan.Convert(maxPixelSize = 2048, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(heic, maxStaticImageDimension = 2048),
        )
        // A server that keeps more than the decode ceiling doesn't lift it: the ceiling is
        // what keeps a 48 MP decode from becoming a jetsam.
        assertEquals(
            ImageShrink.Plan.Convert(maxPixelSize = ImageShrink.heicDecodeCeiling, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(heic, maxStaticImageDimension = 8192),
        )
        // Even when it already fits: the conversion is about decodability, not size.
        assertEquals(
            ImageShrink.Plan.Convert(maxPixelSize = 2048, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(640, 480, type = "image/heif"), maxStaticImageDimension = 2048),
        )
        // JPEG even with alpha: a conversion has no size check to fall back on, and a 4096px
        // photo as PNG could run into the upload cap where the JPEG never did.
        assertEquals(
            ImageShrink.Plan.Convert(maxPixelSize = ImageShrink.heicDecodeCeiling, format = ImageShrink.Format.Jpeg),
            ImageShrink.plan(source(4032, 3024, type = "image/heic", alpha = true), maxStaticImageDimension = null),
        )
    }

    /** an image ImageIO couldn't measure is left alone */
    @Test
    fun unmeasuredIsLeftAlone() {
        assertEquals(ImageShrink.Plan.Leave, ImageShrink.plan(source(0, 0), maxStaticImageDimension = 2048))
    }

    // MARK: - Reading it off the wire

    /** the snapshot's dimension is parsed, beside the cap */
    @Test
    fun snapshotCarriesTheDimension() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[],"globalIgnores":[],"maxUploadBytes":26214400,"maxStaticImageDimension":2048}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot, got $frame")
        val limits = frame.uploadLimits
        assertEquals(UploadLimits(maxUploadBytes = 26_214_400, maxStaticImageDimension = 2048), limits)
    }

    /** a snapshot from a server too old to advertise it says nothing */
    @Test
    fun anOldSnapshotSaysNothing() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[],"globalIgnores":[],"maxUploadBytes":26214400}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot, got $frame")
        val limits = frame.uploadLimits
        assertNull(limits.maxStaticImageDimension)
        assertEquals(26_214_400L, limits.maxUploadBytes, "the cap is independent of it")
    }

    /** a non-positive dimension is read as no answer */
    @Test
    fun nonsenseIsNotAnAnswer() {
        for (value in listOf("0", "-1")) {
            val frame = FrameParser.parseWs(
                """{"kind":"snapshot","networks":[],"globalIgnores":[],"maxStaticImageDimension":""" + value + "}",
            )
            if (frame !is ServerFrame.Snapshot) fail("expected a snapshot")
            val limits = frame.uploadLimits
            assertNull(limits.maxStaticImageDimension, "$value pixels is not an image")
        }
    }

    /** the settings frame carries the dimension when the user changed it */
    @Test
    fun settingsFrameCarriesTheDimension() {
        val frame = FrameParser.parseWs(
            """{"kind":"settings","changes":{"uploads.image.max_dimension":1024},"maxUploadBytes":12582912,"maxStaticImageDimension":1024}""",
        )
        if (frame !is ServerFrame.SettingsChanged) fail("expected a settings frame, got $frame")
        val limits = frame.uploadLimits
        assertEquals(1024, limits.maxStaticImageDimension)
    }

    // MARK: - Reaching the store

    /** the snapshot seeds the dimension, and a snapshot without one clears it */
    @Test
    fun snapshotSeedsAndClears() {
        var state = LurkerStore.reduce(
            ChatState(),
            ServerFrame.Snapshot(
                emptyList(), globalIgnores = emptyList(), uploadLimits = UploadLimits(maxStaticImageDimension = 2048),
            ),
        )
        assertEquals(2048, state.maxStaticImageDimension)
        // The refresh point: a reconnect to an instance that no longer says must not keep
        // shrinking to the last server's number.
        state = LurkerStore.reduce(
            state, ServerFrame.Snapshot(emptyList(), globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated),
        )
        assertNull(state.maxStaticImageDimension)
    }

    /** a settings frame that carries a dimension updates it */
    @Test
    fun aSettingsFrameUpdatesIt() {
        var state = LurkerStore.reduce(
            ChatState(),
            ServerFrame.Snapshot(
                emptyList(), globalIgnores = emptyList(), uploadLimits = UploadLimits(maxStaticImageDimension = 2048),
            ),
        )
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(
                mapOf("uploads.image.max_dimension" to SettingValue.Int(4096)),
                uploadLimits = UploadLimits(maxUploadBytes = 52_428_800, maxStaticImageDimension = 4096),
            ),
        )
        assertEquals(4096, state.maxStaticImageDimension)
        assertEquals(52_428_800L, state.maxUploadBytes)
    }

    /** ⚠⚠ a settings frame about anything else must not clear the dimension */
    @Test
    fun anUnrelatedSettingsFrameLeavesItAlone() {
        var state = LurkerStore.reduce(
            ChatState(),
            ServerFrame.Snapshot(
                emptyList(), globalIgnores = emptyList(),
                uploadLimits = UploadLimits(maxUploadBytes = 209_715_200, maxStaticImageDimension = 2048),
            ),
        )
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(
                mapOf("chat.consolidate_joins" to SettingValue.Bool(true)), uploadLimits = UploadLimits.unstated,
            ),
        )
        assertEquals(2048, state.maxStaticImageDimension)
        assertEquals(209_715_200L, state.maxUploadBytes)
    }

    /** a fresh state has no dimension */
    @Test
    fun aFreshStateHasNone() {
        assertNull(ChatState().maxStaticImageDimension)
    }
}
