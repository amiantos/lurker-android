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
import net.amiantos.lurker.ui.theme.LurkerIcons
import kotlin.math.roundToInt

/**
 * The decoded still at [path], or null until it lands (and for good, if it can't). Starts from the
 * loader's cache, so a row recomposing over a picture already decoded draws it on its first frame.
 *
 * Keyed by path, which is the reuse guard: a row recycled onto a different message starts a fresh
 * producer, and the old one's answer is delivered to nothing — never one row's image painted into
 * another.
 */
@Composable
internal fun rememberPreviewStill(path: String?, media: MediaSource): State<PreviewImageLoader.Still?> =
    produceState(initialValue = path?.let(PreviewImageLoader::cached), path, media) {
        if (path == null || value != null) return@produceState
        value = PreviewImageLoader.load(path, media)
    }

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
