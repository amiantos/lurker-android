// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import androidx.media3.common.PlaybackException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import kotlin.math.max
import kotlin.math.min

/**
 * What the viewer shows: a message's viewable media, and the one it opened on. lurker-ios's
 * `MediaViewerController(previews:startAt:)`.
 */
data class Gallery(val previews: List<LinkPreview>, val start: Int) {
    companion object {
        /** A gallery positioned on [index], clamped into range — iOS clamps the same way. */
        fun of(previews: List<LinkPreview>, index: Int): Gallery? {
            if (previews.isEmpty()) return null
            return Gallery(previews, index.coerceIn(0, previews.size - 1))
        }

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(LinkPreview.serializer())

        /** Saved as text, so a rotation keeps the viewer up on the same picture. */
        fun save(gallery: Gallery?): String =
            gallery?.let { "${it.start}\n${json.encodeToString(serializer, it.previews)}" } ?: ""

        fun restore(saved: String): Gallery? {
            val newline = saved.indexOf('\n')
            if (newline < 0) return null
            val start = saved.substring(0, newline).toIntOrNull() ?: return null
            val previews = try {
                json.decodeFromString(serializer, saved.substring(newline + 1))
            } catch (e: IllegalArgumentException) {
                return null
            }
            return of(previews, start)
        }
    }
}

/**
 * The viewer's decisions, drawn by `MediaViewer` — the half of lurker-ios's `MediaViewerController`
 * that isn't views, so it can be tested.
 */
object MediaViewerModel {

    /** "2 of 5" over a gallery of more than one, nothing over a single picture. */
    fun counter(page: Int, count: Int): String? = if (count > 1) "${page + 1} of $count" else null

    /** Whether a page is a player (video or audio) rather than a zoomable picture. */
    fun isPlayer(preview: LinkPreview): Boolean = preview.kind != PreviewKind.Image

    /** The zoom a double-tap goes to from fit, and the ceiling a pinch stops at. iOS's numbers. */
    const val DOUBLE_TAP_SCALE = 2.5f
    const val MAX_SCALE = 4f

    /** Below this a picture counts as at fit — a pinch that ends a hair over 1 is not "zoomed in". */
    fun isZoomed(scale: Float): Boolean = scale > 1.01f

    /**
     * The picture's size on a [pageWidth] by [pageHeight] page as the viewer draws it at fit
     * (`ContentScale.Fit`): the whole of it, as large as fits, letterboxed on one axis. Before its
     * size is known, the page.
     */
    fun fitted(imageWidth: Float, imageHeight: Float, pageWidth: Float, pageHeight: Float): Pair<Float, Float> {
        if (!(imageWidth > 0f && imageHeight > 0f && pageWidth > 0f && pageHeight > 0f)) return pageWidth to pageHeight
        val fit = min(pageWidth / imageWidth, pageHeight / imageHeight)
        return imageWidth * fit to imageHeight * fit
    }

    /**
     * The offset that keeps a zoomed picture on its page: on each axis, the FITTED picture
     * ([contentWidth] by [contentHeight], see [fitted]) at [scale] may travel half of whatever it
     * overhangs the [pageWidth] by [pageHeight] page — and not at all on an axis where it still fits,
     * where it stays centred. Measured from the page alone, a panorama could be dragged off into its
     * own letterbox. Back to centre at fit.
     */
    fun clampOffset(
        x: Float,
        y: Float,
        scale: Float,
        pageWidth: Float,
        pageHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Pair<Float, Float> {
        if (!isZoomed(scale)) return 0f to 0f
        return travel(x, (contentWidth * scale - pageWidth) / 2f) to travel(y, (contentHeight * scale - pageHeight) / 2f)
    }

    /** [offset] within ±[overhang]; exactly centred (never -0) on an axis that doesn't overhang. */
    private fun travel(offset: Float, overhang: Float): Float {
        if (overhang <= 0f) return 0f
        return offset.coerceIn(-overhang, overhang)
    }

    /**
     * Where a double-tap at ([tapX], [tapY]) on the page puts the picture when it zooms to [scale]
     * about the page's centre: the point under the finger stays under it, as far as the fitted
     * picture's edges allow (see [clampOffset]).
     */
    fun zoomOffset(
        tapX: Float,
        tapY: Float,
        scale: Float,
        pageWidth: Float,
        pageHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Pair<Float, Float> {
        val x = (pageWidth / 2f - tapX) * (scale - 1f)
        val y = (pageHeight / 2f - tapY) * (scale - 1f)
        return clampOffset(x, y, scale, pageWidth, pageHeight, contentWidth, contentHeight)
    }

    /**
     * Whether a swipe down that let go at [distanceDp] travelled with [velocityDp] per second throws the
     * viewer away. iOS's thresholds, point for dp: past 120, or flung faster than 900.
     */
    fun dismisses(distanceDp: Float, velocityDp: Float): Boolean = distanceDp > 120f || velocityDp > 900f

    /**
     * The ground's opacity as a swipe down travels: fading as it goes is what makes it read as
     * dismissal rather than as the picture having come loose. Never below 0.4.
     */
    fun groundAlpha(distanceDp: Float): Float = 1f - min(0.6f, max(0f, distanceDp) / 400f)

    /** Why a player page has nothing to play. */
    enum class PlayerFailure(val message: String) {
        /** `playableMediaURL` had no address to give. */
        Nothing("There's nothing to play here."),

        /** The origin couldn't be reached — a 404, DNS, TLS, a host that refuses a hotlink. */
        Unreachable("This couldn't be loaded."),

        /** The bytes arrived, and no decoder here takes them. */
        Format("This format can't be played on Android."),
    }

    /**
     * What a player error means to the reader, from media3's `PlaybackException.errorCode`.
     *
     * ⚠⚠ Reaching the file and decoding it are different failures with different sentences. Since the
     * bytes come from the origin rather than this instance, reaching them is the part that fails most:
     * a 404, a DNS or TLS failure, a host that 403s a hotlink. Telling the reader their phone can't
     * decode the file for any of those sends them to check a setting that isn't the problem.
     *
     * Codes by media3's ranges: 2xxx input/output, 3xxx parsing, 4xxx decoding. A container no
     * extractor recognises (3003) and every decoder failure (4001–4005) are the FORMAT; a malformed
     * file, a network failure, and anything unknown are "couldn't be loaded".
     */
    fun failure(errorCode: Int): PlayerFailure =
        when (errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> PlayerFailure.Format
            else -> PlayerFailure.Unreachable
        }
}
