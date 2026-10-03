// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.client.ImageShrink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Redraws a picked image before it's uploaded, when the kit's `ImageShrink.plan` says to
 * (lurker-ios#14, #155) — lurker-ios's `ImageConverter`, over Android's `ImageDecoder`.
 *
 * Two reasons, one redraw:
 *
 * - **Shrink.** The server keeps a static image only up to its advertised longest edge
 *   (`maxStaticImageDimension`, lurker#872) and throws the rest away, so a 48 MP photo would be
 *   ~100 MB on the wire for a ~300 KB result. Those pixels are dropped first.
 * - **Convert.** Some HEICs carry more `iref` references than the server's libheif allows and come
 *   back 415 (lurker#626), so every HEIC goes up as a JPEG the platform decoder drew.
 *
 * The server re-encodes regardless, so the extra hop costs ~nothing in quality. The redraw also drops
 * the original's metadata (GPS included) and bakes its EXIF orientation into the pixels —
 * `ImageDecoder` applies the orientation as it decodes — so the result is upright without it.
 *
 * ⚠ `ImageDecoder` refuses what isn't an image — a PDF, a video, text from the Files picker — which is
 * the answer wanted: rasterizing a document would be a silent, lossy surprise. Refused, or failed
 * anywhere, the original goes up untouched: better to try it than to block the upload.
 */
class ImageConverter(private val cacheDirectory: File) {

    /** Thrown from the header callback to stop a decode once the header has said what's needed. */
    private class Measured : RuntimeException(null, null, false, false)

    /**
     * The redrawn image, or null for "upload the original". Off the main thread: decoding a photo is
     * real work. A cancel is honoured between the decode and the encode — the decode itself can't be
     * interrupted, but a RAW's encode needn't follow a tap on ✕.
     */
    suspend fun prepare(file: File, maxStaticImageDimension: Int?): PreparedImage? =
        withContext(Dispatchers.Default) {
            val header = measure(file) ?: return@withContext null
            val source = ImagePlanning.source(header)
            val plan = ImageShrink.plan(source, maxStaticImageDimension)
            val maxPixelSize = ImagePlanning.maxPixelSize(plan) ?: return@withContext null

            val bitmap = decode(file, maxPixelSize) ?: return@withContext null
            try {
                ensureActive()
                val format = ImagePlanning.format(source, bitmap.hasAlpha(), maxStaticImageDimension) ?: return@withContext null
                val out = File(cacheDirectory, "lurker-img-${UUID.randomUUID()}.${format.fileExtension}")
                val wrote = try {
                    FileOutputStream(out).use { stream ->
                        val compression = if (format == ImageShrink.Format.Png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        bitmap.compress(compression, ImagePlanning.QUALITY, stream)
                    }
                } catch (_: IOException) {
                    false
                }
                if (!wrote || !ImagePlanning.keeps(ImagePlanning.onlyIfSmaller(plan), before = file.length(), after = out.length())) {
                    out.delete()
                    return@withContext null
                }
                PreparedImage(out, format)
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * The decoder's header — size (as oriented), type and whether it animates — without decoding a
     * pixel: the header callback stops the decode once it has run.
     */
    private fun measure(file: File): ImagePlanning.Header? {
        var header: ImagePlanning.Header? = null
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { _, info, _ ->
                header = ImagePlanning.Header(info.size.width, info.size.height, info.mimeType, info.isAnimated)
                throw Measured()
            }
        } catch (_: Measured) {
            // The header is in.
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        return header
    }

    /**
     * Decode straight to the target size rather than rasterizing the full image and scaling it: the
     * decoder subsamples while it decodes, so a 48 MP original never exists as a bitmap. Software, so
     * the pixels can be compressed.
     */
    private fun decode(file: File, maxPixelSize: Int): Bitmap? =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (width, height) = ImagePlanning.targetSize(info.size.width, info.size.height, maxPixelSize)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // An `OutOfMemoryError` is not caught: it is not an image problem.
            null
        }
}
