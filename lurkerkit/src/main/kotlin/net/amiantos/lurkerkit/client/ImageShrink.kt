// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

/**
 * Whether to redraw an image on the device before uploading it, and how (lurker-ios#155).
 *
 * The server downscales a static image to its longest-edge limit and re-encodes it, so a 48 MP
 * photo is ~100 MB on the wire to produce a ~300 KB file. Once the server advertises that limit
 * (`maxStaticImageDimension`, lurker#872) we can drop the pixels it would have thrown away before
 * spending the user's data on them.
 *
 * This is only the decision. Measuring the source and doing the redraw is the app's job
 * (`ImageConverter` on iOS, because that is ImageIO; the uploads slice on Android, over
 * `ImageDecoder`/`BitmapFactory`); keeping the rule here keeps it testable and keeps it portable
 * to a platform with a different decoder.
 *
 * ⚠⚠ **We shrink; the server re-encodes.** The output is a still-ordinary JPEG or PNG at high
 * quality, never the server's final format: the format policy, the quality setting and the
 * metadata scrub belong to the server and its operator, and doing that job here with worse
 * tools would silently override their configuration.
 */
object ImageShrink {

    /** What the decoder measured about the picked file (ImageIO, on iOS). */
    data class Source(
        /**
         * As stored — before EXIF orientation. Only the longest edge matters, and rotation
         * doesn't change which edge that is.
         */
        val pixelWidth: Int,
        val pixelHeight: Int,
        /**
         * Images in the container (`CGImageSourceGetCount`, on iOS). More than one is an
         * animation (GIF, APNG, animated WebP) — except in a JPEG, see `plan`.
         */
        val frameCount: Int,
        /**
         * The decoder's type for the bytes, e.g. `image/jpeg`. Read from the file itself, never
         * from its extension or the picker's claim.
         *
         * Port note: a MIME type, where LurkerKit holds ImageIO's UTI (`CGImageSourceGetType`) —
         * PORTING.md maps `UniformTypeIdentifiers` to MIME strings, and the name is kept so it
         * greps across both repos. On Android the app fills it from what the decoder reports
         * (`BitmapFactory.Options.outMimeType`, or `ImageDecoder`'s `ImageInfo.mimeType`). The UTIs
         * LurkerKit and its tests use, and what stands for each here:
         *
         * | LurkerKit | Here |
         * |---|---|
         * | `public.jpeg` | `image/jpeg` |
         * | `public.png` | `image/png` |
         * | `public.heic` | `image/heic` |
         * | `public.heif` | `image/heif` |
         * | `com.compuserve.gif` | `image/gif` |
         * | `org.webmproject.webp` | `image/webp` |
         * | `public.tiff` | `image/tiff` |
         * | `com.adobe.raw-image` | `image/x-adobe-dng` — the UTI is an abstract RAW type with no single MIME; this is a concrete RAW type Android's decoders report |
         *
         * Compared exactly, as LurkerKit compares the UTI; the MIME types Android's decoders
         * report are lower-case.
         *
         * ⚠⚠ Two obligations on the app that fills this, both because a MIME string carries less
         * than a UTI:
         * - **A HEIF image sequence is `image/heif-sequence`** (or `image/heic-sequence`), never
         *   `image/heif`. LurkerKit relies on ImageIO naming an animated HEIF `public.heics`, a
         *   type `isHEIC` doesn't match, so the HEIC conversion — which runs before the animation
         *   check — never flattens one to its first frame. Android's decoders may report a
         *   sequence as plain `image/heif`; the app decides from the frame count and writes the
         *   sequence type, the IANA names that stand for `public.heics`.
         * - **The decoder's name, never `ContentResolver.getType` or the picker's**, which can say
         *   `image/jpg` or `image/HEIC` and would take the wrong branch here without a word.
         */
        val typeIdentifier: String,
        val hasAlpha: Boolean,
    )

    /**
     * The two formats a redraw writes. Both are ones the server's decoder reads without
     * complaint, which is the whole requirement.
     */
    enum class Format {
        Jpeg,
        Png,
        ;

        /**
         * Port note: a MIME type, like `Source.typeIdentifier` (see its Port note), so it is the
         * same string as `mime`. Both names are kept.
         */
        val typeIdentifier: String
            get() = mime

        val mime: String
            get() = when (this) {
                Jpeg -> "image/jpeg"
                Png -> "image/png"
            }

        val fileExtension: String
            get() = when (this) {
                Jpeg -> "jpg"
                Png -> "png"
            }
    }

    sealed interface Plan {
        /** Upload the original bytes. */
        data object Leave : Plan

        /**
         * Redraw to fit `maxPixelSize` on the longest edge because it saves bytes. If the
         * result comes out no smaller than the original, upload the original instead — the
         * server would have shrunk it anyway, so nothing is lost but the attempt.
         */
        data class Shrink(val maxPixelSize: Int, val format: Format) : Plan

        /** Redraw whatever the size: the server can't decode the original at all (lurker#626). */
        data class Convert(val maxPixelSize: Int, val format: Format) : Plan
    }

    /**
     * The bound on a HEIC conversion when the server hasn't asked for less. It caps the
     * decode: a 48 MP HEIC as a bitmap is ~190 MB, a jetsam risk on a pressured device, while
     * 4096 is ~64 MB and still past what the server keeps by default.
     */
    const val heicDecodeCeiling: Int = 4096

    /**
     * The most pixels a shrink will decode to — the same ~64 MB of bitmap the HEIC ceiling
     * allows. Past it the original goes up and the server shrinks it: that costs the user
     * bandwidth, where decoding a 12000×9000 image to an 8192 edge (~200 MB) could cost them
     * the app.
     */
    const val shrinkPixelBudget: Int = heicDecodeCeiling * heicDecodeCeiling

    fun plan(source: Source, maxStaticImageDimension: Int?): Plan {
        val longestEdge = maxOf(source.pixelWidth, source.pixelHeight)
        if (longestEdge <= 0) return Plan.Leave

        // Some iPhone HEICs carry more `iref` references than the server's libheif allows and
        // come back 415 (lurker#626), so every HEIC is converted to JPEG, as it always has
        // been — the advertised dimension only lets the conversion go smaller. Always JPEG,
        // even with alpha: the conversion has no size check to fall back on, and a 4096px
        // photo as PNG is tens of MB. Ahead of the frame check because that's what the
        // conversion always did; Photos doesn't produce animated HEIC (that's `public.heics`).
        if (isHEIC(source.typeIdentifier)) {
            val bound = minOf(maxStaticImageDimension ?: heicDecodeCeiling, heicDecodeCeiling)
            return Plan.Convert(maxPixelSize = bound, format = Format.Jpeg)
        }

        // ⚠⚠ An animation goes up verbatim. The server skips the resize for it, so it keeps
        // every frame — and a redraw here would flatten it to the first one, with no error.
        //
        // ⚠ But a JPEG with more than one image is not an animation. An MPO (stereo cameras)
        // or a JPEG carrying an HDR gain map stores its extra images in an MPF segment, which
        // ImageIO counts (on iOS) and the server's decoder doesn't — to sharp it is one page,
        // resized like any photo. Skipping it would quietly upload the full original.
        val animated = source.frameCount > 1 && source.typeIdentifier != Format.Jpeg.typeIdentifier
        if (animated) return Plan.Leave

        // ⚠⚠ No dimension, no shrink. The server didn't say what it keeps, so nothing tells us
        // which pixels are waste; a guessed 2048 would cost a user on a 4096 instance half
        // their resolution without a word.
        if (maxStaticImageDimension == null || longestEdge <= maxStaticImageDimension) {
            return Plan.Leave
        }

        // Past the budget, let the server do it (see `shrinkPixelBudget`).
        val scale = maxStaticImageDimension.toDouble() / longestEdge.toDouble()
        val pixels = source.pixelWidth.toDouble() * scale * source.pixelHeight.toDouble() * scale
        if (!(pixels <= shrinkPixelBudget.toDouble())) return Plan.Leave

        return Plan.Shrink(maxPixelSize = maxStaticImageDimension, format = output(source))
    }

    /**
     * JPEG and PNG keep their own format. Anything else — RAW/DNG, TIFF, a static GIF or
     * WebP — becomes PNG when it can be transparent, so transparency survives, and JPEG
     * otherwise, because a photo as PNG would be several times the bytes this exists to save.
     */
    internal fun output(source: Source): Format =
        when (source.typeIdentifier) {
            Format.Jpeg.typeIdentifier -> Format.Jpeg
            Format.Png.typeIdentifier -> Format.Png
            else -> if (source.hasAlpha) Format.Png else Format.Jpeg
        }

    /** Port note: the MIME types for LurkerKit's `public.heic` and `public.heif`. */
    internal fun isHEIC(typeIdentifier: String): Boolean =
        typeIdentifier == "image/heic" || typeIdentifier == "image/heif"
}
