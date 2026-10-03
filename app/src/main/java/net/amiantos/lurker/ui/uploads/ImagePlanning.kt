// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.client.ImageShrink
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The app's half of the kit's `ImageShrink` contract, pure: filling its `Source` from what Android's
 * decoder reports, and turning its plan into a redraw. `ImageConverter` does the decoding and encoding.
 */
object ImagePlanning {
    /** What the decoder's header says about a file (`ImageDecoder.ImageInfo`). */
    data class Header(val width: Int, val height: Int, val mimeType: String, val isAnimated: Boolean)

    /**
     * The shrink's `Source` for a decoded header.
     *
     * ⚠⚠ The type is the DECODER's, never `ContentResolver.getType` or the picker's claim, which can say
     * `image/jpg` or `image/HEIC` and would take the wrong branch of the plan without a word.
     *
     * ⚠⚠ A multi-frame HEIF is reported as a SEQUENCE (`image/heif-sequence`, `image/heic-sequence`).
     * Android's decoder names an image sequence plain `image/heif`, and the plan's HEIC conversion runs
     * before its animation check — so left alone, an animated HEIF would be flattened to its first frame.
     * The sequence types are the IANA names that stand for iOS's `public.heics`, which the plan leaves.
     *
     * `hasAlpha` starts false: Android's header doesn't carry it, and nothing the plan decides from it
     * changes whether or how big to redraw — only the format of a redraw, which [format] settles from the
     * decoded bitmap.
     *
     * Frame count: the header says only animated or not, so an animation is 2 — the plan asks "more
     * than one". A JPEG is never animated to Android's decoder (an MPO's extra images aren't frames to
     * it), which is the case the plan's JPEG exemption exists for on iOS.
     */
    fun source(header: Header): ImageShrink.Source =
        ImageShrink.Source(
            pixelWidth = header.width,
            pixelHeight = header.height,
            frameCount = if (header.isAnimated) 2 else 1,
            typeIdentifier = typeIdentifier(header.mimeType, header.isAnimated),
            hasAlpha = false,
        )

    /** The decoder's type as the plan reads it — see [source]. Lower-cased: the plan compares exactly. */
    fun typeIdentifier(mimeType: String, isAnimated: Boolean): String {
        val type = mimeType.lowercase()
        if (!isAnimated) return type
        return when (type) {
            "image/heif" -> "image/heif-sequence"
            "image/heic" -> "image/heic-sequence"
            else -> type
        }
    }

    /**
     * The format a redraw writes, now the decoded bitmap has said whether it has alpha. Re-planned with
     * the real answer rather than re-deciding here, so the rule stays the kit's: JPEG and PNG keep their
     * own, anything else is PNG only when it can be transparent, and a HEIC conversion is always JPEG.
     */
    fun format(source: ImageShrink.Source, hasAlpha: Boolean, maxStaticImageDimension: Int?): ImageShrink.Format? =
        when (val plan = ImageShrink.plan(source.copy(hasAlpha = hasAlpha), maxStaticImageDimension)) {
            ImageShrink.Plan.Leave -> null
            is ImageShrink.Plan.Shrink -> plan.format
            is ImageShrink.Plan.Convert -> plan.format
        }

    /** The bound on the longest edge a plan asks for, or null to leave the file alone. */
    fun maxPixelSize(plan: ImageShrink.Plan): Int? =
        when (plan) {
            ImageShrink.Plan.Leave -> null
            is ImageShrink.Plan.Shrink -> plan.maxPixelSize
            is ImageShrink.Plan.Convert -> plan.maxPixelSize
        }

    /** Whether a redraw is kept only when it came out smaller — a shrink's rule, not a conversion's. */
    fun onlyIfSmaller(plan: ImageShrink.Plan): Boolean = plan is ImageShrink.Plan.Shrink

    /**
     * The size to decode to: the oriented size scaled so its longest edge fits [maxPixelSize], never
     * up. Decoding straight to it (`ImageDecoder.setTargetSize`) means a 48 MP original never exists as
     * a bitmap.
     */
    fun targetSize(width: Int, height: Int, maxPixelSize: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxPixelSize || longest <= 0) return width to height
        val scale = maxPixelSize.toDouble() / longest.toDouble()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * Whether to keep a redraw. A shrink exists to save bytes; one that didn't (a tiny, heavily
     * compressed JPEG redrawn at high quality can grow) is dropped, and the server does the shrinking.
     */
    fun keeps(onlyIfSmaller: Boolean, before: Long, after: Long): Boolean = !onlyIfSmaller || after < before

    /**
     * The encode quality. High, not the server's: this is an intermediate the server decodes and
     * re-encodes at the quality its operator configured. Ignored for PNG.
     */
    const val QUALITY = 90
}
