// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import net.amiantos.lurker.ui.theme.LurkerIcons
import kotlin.math.roundToInt

/**
 * The decoded still at [path] for a box measured at [box] pixels, shown in [mode]; null until it lands
 * (and for good, if it can't). Until the box has been measured — and while its own decode is on the
 * way — it shows the latest decode of the same picture for any box, so a row composing again over a
 * picture it already showed draws it on its first frame.
 *
 * ⚠ The value is RESET when the path changes, not just reloaded: `produceState` keeps its state
 * across key changes, so a box re-planned onto a different preview would otherwise go on showing the
 * previous picture — one message's image in another's place.
 *
 * A retryable failure is asked again on a short, bounded backoff while the box stays composed
 * (`StillRetry`); a verdict is latched by the loader and isn't.
 */
@Composable
internal fun rememberPreviewStill(
    path: String?,
    media: MediaSource,
    box: IntSize,
    mode: DecodeSize.Mode,
): State<PreviewImageLoader.Still?> {
    val bucket = DecodeSize.bucket(box.width, box.height)
    return produceState(initialValue = path?.let { stillNow(it, mode, bucket) }, path, media, mode, bucket) {
        value = path?.let { stillNow(it, mode, bucket) }
        if (path == null || bucket == null || PreviewImageLoader.cached(path, mode, bucket) != null) return@produceState
        var attempt = 0
        while (true) {
            when (val result = PreviewImageLoader.load(path, media, mode, bucket)) {
                is PreviewImageLoader.Result.Loaded -> {
                    value = result.still
                    return@produceState
                }
                PreviewImageLoader.Result.Failed -> return@produceState
                PreviewImageLoader.Result.Retryable -> {
                    attempt += 1
                    delay(StillRetry.delayMs(attempt) ?: return@produceState)
                }
            }
        }
    }
}

/** The exact decode for this box, or the latest for any box until there is one. */
private fun stillNow(path: String, mode: DecodeSize.Mode, bucket: DecodeSize.Pixels?): PreviewImageLoader.Still? =
    bucket?.let { PreviewImageLoader.cached(path, mode, it) } ?: PreviewImageLoader.anyCached(path, mode)

/**
 * A still drawn into whatever box holds it, or nothing until it arrives — the box is already sized
 * from the descriptor, so the picture landing is paint, never layout.
 */
@Composable
internal fun PreviewStillImage(still: PreviewImageLoader.Still?, contentScale: ContentScale, modifier: Modifier = Modifier) {
    if (still == null) return
    Image(still.image, contentDescription = null, modifier = modifier.fillMaxSize(), contentScale = contentScale)
}

/** The Material glyph for an attachment's kind. */
internal fun AttachmentLayout.Glyph.vector(): ImageVector =
    when (this) {
        AttachmentLayout.Glyph.Play -> LurkerIcons.PlayCircle
        AttachmentLayout.Glyph.Waveform -> LurkerIcons.GraphicEq
        AttachmentLayout.Glyph.Photo -> LurkerIcons.Image
    }

/**
 * A glyph meant to sit ON a picture rather than in an empty box: white, over a soft dark halo, so it
 * stays legible over whatever frame is underneath — a snow scene and a night shot both. Never a
 * control (the whole tile is), so it says nothing to TalkBack.
 */
@Composable
internal fun OverlayGlyph(vector: ImageVector, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)),
                    radius = this.size.minDimension * 0.75f,
                )
            },
    ) {
        Icon(vector, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A `Drawable` as a Compose painter, animating if it animates — what the viewer draws an
 * `AnimatedImageDrawable` (or a decoded still) with. No image library here, so no `DrawablePainter`;
 * this is the part of one that matters.
 *
 * The drawable invalidates itself frame by frame through its callback; each invalidation bumps a
 * state the draw reads, so Compose redraws exactly as often as the animation asks. Started when it
 * enters composition, stopped when it leaves — a page swiped away stops its animation and lets its
 * decoder go with it.
 */
internal class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {
    private var invalidations by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidations++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            main.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            main.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        } else {
            Size.Unspecified
        }

    override fun DrawScope.onDraw() {
        // Read, so a frame's invalidation redraws.
        invalidations.hashCode()
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
        drawable.setVisible(true, true)
        (drawable as? Animatable)?.start()
    }

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    private fun release() {
        (drawable as? Animatable)?.stop()
        drawable.setVisible(false, false)
        drawable.callback = null
        main.removeCallbacksAndMessages(null)
    }
}
