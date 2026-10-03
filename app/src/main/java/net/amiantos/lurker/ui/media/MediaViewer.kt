// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import net.amiantos.lurker.ui.shell.SafeUriHandler
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.max

/*
 * Full-screen media — lurker-ios's `MediaViewerController`.
 *
 * The message list can only show a picture small and, in a mosaic, cropped: every attachment's height
 * is fixed by its metadata so an arriving image can't grow a row under the reader's thumb. That is
 * right for a log and wrong for looking at something. This answers three things at once:
 *
 *   - **A cropped tile is recoverable.** Here the whole frame is visible.
 *   - **A GIF plays at its own size.** Inline it would play in a box smaller than itself.
 *   - **A message's other pictures are reachable.** It opens as a GALLERY over every viewable item in
 *     the message, positioned on the one that was tapped.
 *
 * ⚠ Autoplay IS right here, unlike inline: opening the viewer is an explicit request to look at one
 * thing, and there is exactly one animation (or clip) on screen.
 */

/**
 * Which gallery the viewer shows, if any. Hosted by `MainScaffold`, as the other dialogs are — a
 * phone's navigation must not drop it — and opened from rows through `MessageListContext.onOpenMedia`.
 * Saved, so a rotation keeps it up on the same picture.
 */
@Stable
class MediaViewerState internal constructor(private val open: MutableState<Gallery?>) {
    /** Open [previews] full-screen, positioned on [index] (iOS's `onOpenMedia(previews, index)`). */
    fun show(previews: List<LinkPreview>, index: Int) {
        open.value = Gallery.of(previews, index)
    }

    fun dismiss() {
        open.value = null
    }

    internal val current: Gallery? get() = open.value
}

@Composable
fun rememberMediaViewer(): MediaViewerState {
    val open = rememberSaveable(stateSaver = GallerySaver) { mutableStateOf<Gallery?>(null) }
    return remember(open) { MediaViewerState(open) }
}

private val GallerySaver = Saver<Gallery?, String>(save = { Gallery.save(it) }, restore = { Gallery.restore(it) })

/** The viewer, while [state] has a gallery open. Bytes come through [media]. */
@Composable
fun MediaViewerHost(state: MediaViewerState, media: MediaSource) {
    val gallery = state.current ?: return
    // A fresh viewer per gallery: its pages, zoom and player belong to what was opened.
    key(gallery) { MediaViewer(gallery, media, onDismiss = state::dismiss) }
}

/**
 * The viewer itself: a black, immersive full-screen dialog; a pager across the gallery; swipe down
 * (or Back, or the close button, or a tap on a picture) to close.
 *
 * Its own black rather than the theme's ground: a picture is judged against what is behind it, and a
 * viewer that followed light/dark would show the same photograph two different ways.
 *
 * ⚠ Only the settled page plays, and only the pages on screen are composed at all (the pager composes
 * none beyond the viewport). On iOS opening a gallery on a later item built page 0 first, and if page 0
 * was a clip its origin was contacted before the reader had swiped anywhere near it — "a clip's bytes
 * are fetched only on a deliberate tap" is this feature's rule. The pager opens on [Gallery.start]
 * directly, and a clip half-dragged into view shows its poster until the swipe settles on it.
 */
@Composable
private fun MediaViewer(gallery: Gallery, media: MediaSource, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Immersive()
        // The platform's opener throws when nothing on the device takes an address; this dialog is
        // outside the conversation's provider, so it brings its own.
        val platform = LocalUriHandler.current
        val uriHandler = remember(platform) { SafeUriHandler(platform) }
        CompositionLocalProvider(LocalUriHandler provides uriHandler) { ViewerPages(gallery, media, onDismiss) }
    }
}

/** The viewer's ground, pager and chrome — inside its dialog. */
@Composable
private fun ViewerPages(gallery: Gallery, media: MediaSource, onDismiss: () -> Unit) {
    val previews = gallery.previews
    val pager = rememberPagerState(initialPage = gallery.start) { previews.size }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // How far a swipe down has carried the pictures, in px.
    var drag by remember { mutableFloatStateOf(0f) }
    var zoomed by remember { mutableStateOf(false) }
    // A new page starts at fit.
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val page = pager.currentPage.coerceIn(0, previews.size - 1)
    val onPlayer = MediaViewerModel.isPlayer(previews[page])

    Box(
        Modifier
            .fillMaxSize()
            // Read while drawing, so a swipe's every frame repaints the ground without recomposing.
            .drawBehind { drawRect(Color.Black.copy(alpha = MediaViewerModel.groundAlpha(drag / density.density))) }
            // Swipe down to dismiss, the pictures following the finger — the gesture every other
            // full-screen viewer uses, so it's the one a reader tries first. ⚠ It stands down while
            // zoomed in (panning a magnified picture must move the picture), and over a player,
            // whose scrubber is a horizontal drag inside a vertically-dismissing view.
            .draggable(
                orientation = Orientation.Vertical,
                enabled = !zoomed && !onPlayer,
                state = rememberDraggableState { delta -> drag = max(0f, drag + delta) },
                onDragStopped = { velocity ->
                    if (MediaViewerModel.dismisses(drag / density.density, velocity / density.density)) {
                        onDismiss()
                    } else {
                        scope.launch { animate(drag, 0f) { value, _ -> drag = value } }
                    }
                },
            ),
    ) {
        HorizontalPager(
            state = pager,
            // Paging stands down while zoomed in, so a pan reaches the picture.
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = drag },
        ) { index ->
            val preview = previews[index]
            if (MediaViewerModel.isPlayer(preview)) {
                PlayerPage(preview, media, active = pager.settledPage == index, onClose = onDismiss)
            } else {
                ImagePage(
                    preview,
                    media,
                    onTap = onDismiss,
                    onZoomChange = { if (index == pager.currentPage) zoomed = it },
                )
            }
        }
        ViewerChrome(
            counter = MediaViewerModel.counter(page, previews.size),
            shareUrl = previews[page].url,
            onClose = onDismiss,
        )
    }
}

/**
 * The dialog's window, immersive: system bars hidden (a swipe from the edge brings them back for a
 * moment), and no dim of its own — the black ground is ours, so a swipe down can fade it.
 */
@Composable
private fun Immersive() {
    val view = LocalView.current
    // Once the window is up, not on every recomposition: a swipe recomposes nothing, but a page turn does.
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0f)
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }
}

/**
 * Close, the counter, and Share, each its own element in a corner.
 *
 * ⚠⚠ Three separate pieces, NOT a bar: on iOS a full-width bar was a touch target for its whole
 * rectangle, an invisible band swallowing every tap across the top of the screen — the player's own
 * controls under it included.
 *
 * ⚠ Both buttons carry their own scrim. A white glyph over an unknown picture is invisible against a
 * white one — and this viewer exists precisely to show pictures we know nothing about.
 *
 * Unlike iOS, the chrome stays up over a player page: `PlayerView` draws no dismiss of its own and
 * keeps its controls at the bottom and centre, so nothing here covers them — and without our close
 * button a lone clip would leave only Back as the way out.
 */
@Composable
private fun ViewerChrome(counter: String?, shareUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
        ScrimButton(LurkerIcons.Close, "Close", onClose, Modifier.align(Alignment.TopStart))
        if (counter != null) {
            Text(
                counter,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                // Not read out, as on iOS: the pager announces its own pages.
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clearAndSetSemantics {},
            )
        }
        // ⚠ The ORIGIN address, not our proxy path. A proxy URL is authenticated and signed for this
        // session, so sharing one hands over something nobody else can open — and leaks a bearer-gated
        // path. What a person means by "share this picture" is the address it came from.
        ScrimButton(LurkerIcons.Share, "Share link", { share(context, shareUrl) }, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ScrimButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
        shape = CircleShape,
    ) { Icon(icon, contentDescription = label) }
}

/** Share Link: the system share sheet, which offers Copy among its targets. */
private fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: ActivityNotFoundException) {
        Log.w("Lurker", "nothing shares $url", e)
    }
}

// MARK: - A picture

/** Where a picture page's own decode is. */
private sealed interface FullPicture {
    data object Loading : FullPicture

    data class Loaded(val drawable: Drawable) : FullPicture

    data object Failed : FullPicture
}

/**
 * One picture, zoomable: pinch, double-tap to 2.5x at the finger (and back), pan while zoomed; a
 * single tap closes the viewer, as on iOS. A whole-frame still from the list's cache is on screen the
 * instant the viewer opens, when there is one (never a cropped tile); the viewer's own decode (or the
 * animation) replaces it when it lands. If that fails with nothing to show, the page says so, with
 * the way out and the origin in the browser — the player pages' fallback — rather than staying black.
 */
@Composable
private fun ImagePage(preview: LinkPreview, media: MediaSource, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    val path = preview.src
    val still = remember(path) { path?.let(PreviewImageLoader::wholeFrame) }
    val full by produceState<FullPicture>(FullPicture.Loading, path, media) {
        value = FullPicture.Loading
        value = path?.let { PreviewImageLoader.loadFull(it, media) }?.let(FullPicture::Loaded) ?: FullPicture.Failed
    }
    // Remembered against the drawable, so it starts and stops with the page — frames released as the
    // page leaves, rather than a gallery accumulating every animation paged past.
    val painter = remember(full) { (full as? FullPicture.Loaded)?.drawable?.let(::DrawablePainter) }
    if (full == FullPicture.Failed && still == null) {
        ViewerFallback(MediaViewerModel.PlayerFailure.Unreachable.message, preview.url, onClose = onTap)
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The picture's own size, for the pan bounds — whichever of the two is drawn.
    val drawn = painter?.intrinsicSize?.takeIf { it.isSpecified }
        ?: still?.let { Size(it.image.width.toFloat(), it.image.height.toFloat()) }
        ?: Size.Zero
    val imageSize by rememberUpdatedState(drawn)
    // The picture as fitted on the page, which is what the bounds are measured against.
    fun content(): Pair<Float, Float> =
        MediaViewerModel.fitted(imageSize.width, imageSize.height, size.width.toFloat(), size.height.toFloat())
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoom by rememberUpdatedState(onZoomChange)

    fun zoomTo(target: Float, x: Float, y: Float) {
        val fromScale = scale
        val fromX = offsetX
        val fromY = offsetY
        scope.launch {
            animate(0f, 1f) { t, _ ->
                scale = fromScale + (target - fromScale) * t
                offsetX = fromX + (x - fromX) * t
                offsetY = fromY + (y - fromY) * t
            }
        }
        currentOnZoom(MediaViewerModel.isZoomed(target))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .clearAndSetSemantics { contentDescription = preview.title ?: "Image" }
            .pointerInput(Unit) {
                detectTapGestures(
                    // ⚠ Waits out a possible second tap, or zooming in would always close on its first.
                    onTap = { currentOnTap() },
                    onDoubleTap = { at ->
                        if (MediaViewerModel.isZoomed(scale)) {
                            zoomTo(1f, 0f, 0f)
                        } else {
                            val (contentWidth, contentHeight) = content()
                            val (x, y) = MediaViewerModel.zoomOffset(
                                at.x, at.y, MediaViewerModel.DOUBLE_TAP_SCALE,
                                size.width.toFloat(), size.height.toFloat(), contentWidth, contentHeight,
                            )
                            zoomTo(MediaViewerModel.DOUBLE_TAP_SCALE, x, y)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Pinch at any time; pan only while zoomed — at fit, one finger belongs to the pager
                // (sideways) and the dismissal (down), so it's left unconsumed for them.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || MediaViewerModel.isZoomed(scale)) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, MediaViewerModel.MAX_SCALE)
                            val pan = event.calculatePan()
                            val (contentWidth, contentHeight) = content()
                            val (x, y) = MediaViewerModel.clampOffset(
                                offsetX + pan.x, offsetY + pan.y, next,
                                size.width.toFloat(), size.height.toFloat(), contentWidth, contentHeight,
                            )
                            val wasZoomed = MediaViewerModel.isZoomed(scale)
                            scale = next
                            offsetX = x
                            offsetY = y
                            if (wasZoomed != MediaViewerModel.isZoomed(next)) currentOnZoom(MediaViewerModel.isZoomed(next))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!MediaViewerModel.isZoomed(scale)) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                        currentOnZoom(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val transformed = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offsetX
            translationY = offsetY
        }
        val sharp = painter
        val first = still
        if (sharp != null) {
            Image(sharp, contentDescription = null, modifier = transformed, contentScale = ContentScale.Fit)
        } else if (first != null) {
            Image(first.image, contentDescription = null, modifier = transformed, contentScale = ContentScale.Fit)
        } else if (full == FullPicture.Loading) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

// MARK: - A clip

/** Where a player page is. */
private sealed interface PlayerPhase {
    /** Asking for the address — the spinner, and the way out. */
    data object Loading : PlayerPhase

    data class Ready(val url: String) : PlayerPhase

    data class Failed(val failure: MediaViewerModel.PlayerFailure) : PlayerPhase
}

/**
 * A video or audio page: media3's `PlayerView` (in an `AndroidView` — media3's Compose UI has no
 * transport controls of its own yet), with an honest failure state behind it.
 *
 * ⚠⚠ THE ORIGIN URL, never `preview.src`, even when one is present. The server stopped minting a byte
 * URL for video and audio: a card that renders by itself must not report the reader to a stranger's
 * host, but pressing play is a deliberate act. And a descriptor minted before that change may still be
 * in memory with a token that now answers 404 — so "prefer `src`" is the branch that reliably fails.
 *
 * ⚠ STREAMED: `playableMediaURL` hands an absolute http(s) address straight back, ExoPlayer does its own
 * ranged reads, and no Authorization header goes anywhere near it — it's a third-party address.
 *
 * Until [active] (the swipe has settled here), the page shows the poster and contacts nothing.
 */
@Composable
private fun PlayerPage(preview: LinkPreview, media: MediaSource, active: Boolean, onClose: () -> Unit) {
    if (!active) {
        PlayerPoster(preview, media)
        return
    }
    var phase by remember(preview.url) { mutableStateOf<PlayerPhase>(PlayerPhase.Loading) }
    LaunchedEffect(preview.url) {
        val url = media.playable(preview.url, preview.mime)
        phase = if (url == null) PlayerPhase.Failed(MediaViewerModel.PlayerFailure.Nothing) else PlayerPhase.Ready(url)
    }
    when (val current = phase) {
        PlayerPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        is PlayerPhase.Ready -> ClipPlayer(current.url) { code -> phase = PlayerPhase.Failed(MediaViewerModel.failure(code)) }
        is PlayerPhase.Failed -> ViewerFallback(current.failure.message, preview.url, onClose)
    }
}

/** A clip not yet played: its poster, if the server decoded one, and the glyph for its kind. */
@Composable
private fun PlayerPoster(preview: LinkPreview, media: MediaSource) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val still by rememberPreviewStill(preview.inlinePicture, media, box, DecodeSize.Mode.Fit)
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        PreviewStillImage(still, ContentScale.Fit)
        OverlayGlyph(AttachmentLayout.glyph(preview.kind).vector(), 56.dp)
    }
}

/**
 * The player. Audio focus is requested for playback and respected — another app taking it pauses
 * this — and a headset unplugged pauses it too (iOS's `.playback` session, Android's way: without
 * focus a clip would play over the reader's music, or silently lose to it). Released when the page
 * leaves, which also gives the focus back, so whatever was playing before can resume.
 *
 * ⚠ A page leaving stops playing, and that is not politeness: without it, swiping on leaves the clip's
 * audio running under the next picture, and closing the viewer leaves it playing over the message list
 * with no control anywhere to stop it. The app going to the background pauses it, for the same reason.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ClipPlayer(url: String, onError: (Int) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                // handleAudioFocus
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }
    val currentOnError by rememberUpdatedState(onError)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentOnError(error.errorCode)
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * A page with nothing to show: a sentence, the origin in the browser (when its address parses), and
 * the way out — one column, so nothing can draw over anything else whichever of them is showing.
 * Opened through the viewer's `SafeUriHandler`, so a device with nothing to take the address shrugs.
 */
@Composable
private fun ViewerFallback(message: String, url: String, onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    // The viewer's black is the same in both themes, so its buttons are too.
    val onBlack = ButtonDefaults.textButtonColors(contentColor = Color.White)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (url.toHttpUrlOrNull() != null) {
            TextButton(
                onClick = {
                    onClose()
                    uriHandler.openUri(url)
                },
                colors = onBlack,
            ) { Text("Open in Browser") }
        }
        TextButton(onClick = onClose, colors = onBlack) { Text("Close") }
    }
}

// MARK: - Previews

@Composable
private fun FallbackPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(Color.Black)) {
            ViewerFallback(MediaViewerModel.PlayerFailure.Format.message, "https://example.com/clip.webm", onClose = {})
            ViewerChrome(counter = "2 of 3", shareUrl = "https://example.com/clip.webm", onClose = {})
        }
    }
}

@Preview(name = "Viewer fallback — light", widthDp = 360, heightDp = 640)
@Composable
private fun FallbackPreviewLight() = FallbackPreview(dark = false)

@Preview(name = "Viewer fallback — dark", widthDp = 360, heightDp = 640)
@Composable
private fun FallbackPreviewDark() = FallbackPreview(dark = true)

@Composable
private fun PosterPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(Color.Black).size(360.dp, 640.dp)) {
            PlayerPoster(
                LinkPreview(url = "https://example.com/clip.mp4", status = LinkPreview.Status.Ok, kind = PreviewKind.Video),
                MediaSource.None,
            )
            ViewerChrome(counter = null, shareUrl = "https://example.com/clip.mp4", onClose = {})
        }
    }
}

@Preview(name = "Viewer poster — light", widthDp = 360, heightDp = 640)
@Composable
private fun PosterPreviewLight() = PosterPreview(dark = false)

@Preview(name = "Viewer poster — dark", widthDp = 360, heightDp = 640)
@Composable
private fun PosterPreviewDark() = PosterPreview(dark = true)
