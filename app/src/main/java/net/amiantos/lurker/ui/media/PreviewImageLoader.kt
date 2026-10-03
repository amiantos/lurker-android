// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.model.MediaFetch
import okio.ByteString
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Decoded pictures for preview attachments. lurker-ios's `PreviewImageLoader`.
 *
 * A separate cache from the kit's byte cache rather than a duplicate of it, because they hold
 * different things: the kit's OkHttp cache (`mediaCacheDirectory`) holds *bytes* — the server marks
 * them `immutable` with a long max-age, so it does the heavy lifting across launches — while this
 * holds the *decoded bitmap*. Re-decoding a JPEG every time a row composes during a fast scroll is the
 * expensive part, and it's the part the byte cache can't help with.
 *
 * Keyed by the server's proxy path AND the box that asked (`DecodeSize.bucket`, in its `Mode`): a
 * 64dp chip and a full-width lone image are different decodes of the same bytes, and a rotation's wider
 * boxes get sharper ones rather than inheriting the narrow decode. An `LruCache` sized by decoded
 * bytes: decoration, evicted oldest-first.
 *
 * No image library: bytes through the kit (the proxy is Bearer-gated, so there is no plain URL an
 * image loader could fetch), decoded with `ImageDecoder` off the main thread.
 *
 * Main thread only, except the decode itself.
 */
object PreviewImageLoader {

    /**
     * A decoded still, whether the file it came from moves, and whether it was cut to its box (so the
     * viewer, which shows the whole frame, won't show it even for a moment).
     */
    class Still(val image: ImageBitmap, val animated: Boolean, val cropped: Boolean, internal val bytes: Int)

    /** How a load ended. */
    sealed interface Result {
        data class Loaded(val still: Still) : Result

        /** Worth asking again later — a throttled origin, a dropped connection. Not remembered. */
        data object Retryable : Result

        /** A verdict — gone, refused, or bytes this platform can't draw. Latched for the session. */
        data object Failed : Result
    }

    /** Counted in decoded bytes. iOS's budget, which holds far more here: stills are box-sized. */
    private const val CACHE_BYTES = 32 * 1024 * 1024

    private val cache = object : LruCache<String, Still>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Still): Int = value.bytes
    }

    /**
     * The most recent decode per path and mode — what a box draws in the moment before it has been
     * measured (a row composing again over a picture it already showed), and what the viewer starts
     * from. Points into [cache]; a key it evicted is simply a miss.
     */
    private val latest = HashMap<String, String>()

    /**
     * Everyone waiting on a decode shares one load, not just whoever asked first.
     *
     * ⚠ On iOS this was once a set of in-flight paths, and a cell arriving mid-flight registered
     * nothing — it drew empty, and whether an image appeared came down to which cell asked first.
     * Here every caller awaits the same `Deferred`, launched in the loader's own [scope], so a row
     * leaving composition (its own coroutine cancelled) never cancels the load for the rows still
     * waiting on it.
     */
    private val inFlight = mutableMapOf<String, Deferred<Result>>()

    /**
     * Paths that failed with a VERDICT, so they aren't refetched every time their row composes — by
     * path alone, since the verdict is about the bytes, whatever box asked.
     *
     * ⚠⚠ Only a verdict is latched. The proxy answers a throttled origin with 503 — every GitHub
     * link's og:image comes from a host with a budget of 100, which a run of GitHub links spends in one
     * burst from the instance's one IP. Latching those blanked the images for the rest of the session;
     * a retryable failure isn't remembered, and the box asks again (`StillRetry`).
     */
    private val failed = mutableSetOf<String>()

    /**
     * Bumped by [reset], so answers already in the air can tell they belong to the account that just
     * signed out. ⚠⚠ A sign-out landing mid-fetch must not be undone by the answer: without this the
     * in-flight decode resumed afterwards and wrote the departing account's picture straight back in.
     */
    private var generation = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun key(path: String, mode: DecodeSize.Mode, box: DecodeSize.Pixels) = "$path|$mode|${box.width}x${box.height}"

    private fun family(path: String, mode: DecodeSize.Mode) = "$path|$mode"

    /** The decode for exactly this box, or null. Synchronous: a row draws whatever is there. */
    fun cached(path: String, mode: DecodeSize.Mode, box: DecodeSize.Pixels): Still? = cache.get(key(path, mode, box))

    /** The latest decode of [path] for any box in [mode], or null — a stand-in until the right one lands. */
    fun anyCached(path: String, mode: DecodeSize.Mode): Still? = latest[family(path, mode)]?.let(cache::get)

    /**
     * Something the viewer can show of [path] at once, before its own decode: a whole-frame still — a
     * fitted one, or a filled one that wasn't cut. Never a cropped tile, which would show the reader a
     * different picture from the one they opened.
     */
    fun wholeFrame(path: String): Still? =
        anyCached(path, DecodeSize.Mode.Fit) ?: anyCached(path, DecodeSize.Mode.Fill)?.takeUnless { it.cropped }

    /**
     * The still at [path] decoded for [box] in [mode], fetching and decoding it if it isn't yet. The
     * loader says nothing about why a load failed beyond whether it's worth asking again — a box that
     * fails stays the box it was, which is already sized.
     */
    suspend fun load(path: String, media: MediaSource, mode: DecodeSize.Mode, box: DecodeSize.Pixels): Result {
        val key = key(path, mode, box)
        cache.get(key)?.let { return Result.Loaded(it) }
        if (path in failed) return Result.Failed
        // ⚠ Recorded only if still running: on `Main.immediate` the load runs inline up to its first
        // suspension, so an answer that needed none has already finished (and cleaned up after itself)
        // by the time `start` returns — recording it then would leave a finished load in the map for
        // good, and a retryable failure would never be retried.
        val pending = inFlight[key] ?: start(path, key, media, mode, box).also { if (!it.isCompleted) inFlight[key] = it }
        return pending.await()
    }

    private fun start(path: String, key: String, media: MediaSource, mode: DecodeSize.Mode, box: DecodeSize.Pixels): Deferred<Result> {
        val started = generation
        return scope.async {
            var result: Result = Result.Failed
            try {
                result = when (val fetched = media.fetch(path)) {
                    // Bytes we cannot decode are a verdict too: asking again gets the same bytes.
                    is MediaFetch.Success -> decodeStill(fetched.data, mode, box)?.let(Result::Loaded) ?: Result.Failed
                    MediaFetch.Retryable -> Result.Retryable
                    MediaFetch.Permanent -> Result.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing the kit documents, so nothing to latch: the box asks again.
                Log.w(TAG, "preview picture fetch failed", e)
                result = Result.Retryable
            } finally {
                // Whatever happened, this load is over — or every later caller would await it forever.
                if (started == generation) inFlight.remove(key)
            }
            if (started != generation) return@async Result.Retryable
            when (val done = result) {
                is Result.Loaded -> {
                    cache.put(key, done.still)
                    latest[family(path, mode)] = key
                }
                Result.Failed -> failed += path
                Result.Retryable -> Unit
            }
            result
        }
    }

    /**
     * The picture at [path] for the viewer: an `AnimatedImageDrawable` when it moves, else a still
     * decoded large enough to zoom into (`DecodeSize.viewer`); null when it can't be had, which the
     * viewer says in words. Not cached — the page holds it, and lets it go with the page, so a gallery
     * of animations doesn't accumulate every frame set paged past.
     *
     * ⚠ The bytes are fetched again rather than held. The kit's byte cache keeps them (the proxy marks
     * them `immutable`), so this is a local read — and holding every picture's bytes against the
     * chance somebody opens the viewer is the memory problem this design avoids.
     *
     * ⚠ Autoplay is right here, unlike inline: one picture on screen, and the reader asked for it.
     * `AnimatedImageDrawable` decodes frame by frame rather than holding the set, so the frame budget
     * iOS needs (and its averaged frame timing) has no counterpart: per-frame delays play as written.
     */
    suspend fun loadFull(path: String, media: MediaSource): Drawable? {
        val started = generation
        val fetched = media.fetch(path) as? MediaFetch.Success ?: return null
        val drawable = withContext(Dispatchers.Default) {
            // The copy out of the response is a picture's worth of bytes: off Main, with the decode.
            decode(fetched.data.toByteArray()) { w, h ->
                val screen = Resources.getSystem().displayMetrics
                DecodeSize.Plan(
                    DecodeSize.viewer(w, h, maxEdge = 2 * max(screen.widthPixels, screen.heightPixels), maxPixels = VIEWER_MAX_PIXELS),
                    crop = null,
                )
            }
        }
        return drawable.takeIf { started == generation }
    }

    /**
     * Drop everything. On sign-out — decoded pictures from the previous account must not survive into
     * the next one's session, and a different instance's signed proxy tokens wouldn't verify anyway.
     */
    fun reset() {
        cache.evictAll()
        latest.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        failed.clear()
        generation += 1
    }

    /** A viewer still's ceiling: 8 megapixels, 32 MB as ARGB. */
    private const val VIEWER_MAX_PIXELS = 8_000_000L

    /**
     * The first frame, decoded for its box, plus whether there are more.
     *
     * ⚠ Only the first frame is drawn here, even for an animation — the reason inline playback is
     * opt-in (a tap opens the viewer): a page of scrollback with a dozen animations running is a dozen
     * decoders and timers whether or not anybody is watching, and the list becomes noisy in a way a log
     * should not be. Whether a file moves is known only once it has decoded — `image/webp` says nothing
     * either way — which is why the play badge appears when the still lands, at no layout cost.
     */
    private suspend fun decodeStill(data: ByteString, mode: DecodeSize.Mode, box: DecodeSize.Pixels): Still? =
        withContext(Dispatchers.Default) {
            var cropped = false
            // The copy out of the response is a picture's worth of bytes: off Main, with the decode — the
            // loader's own scope is `Main.immediate`.
            val drawable = decode(data.toByteArray()) { w, h ->
                DecodeSize.inline(w, h, box, mode).also { cropped = it.crop != null }
            }
            when (drawable) {
                is AnimatedImageDrawable -> {
                    val w = max(1, drawable.intrinsicWidth)
                    val h = max(1, drawable.intrinsicHeight)
                    val frame = createBitmap(w, h)
                    drawable.setBounds(0, 0, w, h)
                    // Not started, so this draws frame one.
                    drawable.draw(Canvas(frame))
                    Still(frame.asImageBitmap(), animated = true, cropped = cropped, bytes = w * h * 4)
                }
                is BitmapDrawable -> {
                    val bitmap = drawable.bitmap ?: return@withContext null
                    Still(bitmap.asImageBitmap(), animated = false, cropped = cropped, bytes = bitmap.width * bitmap.height * 4)
                }
                else -> null
            }
        }

    /** Decode on the calling thread, scaled (and cropped) as [plan] says for the source's size. */
    private fun decode(bytes: ByteArray, plan: (Int, Int) -> DecodeSize.Plan): Drawable? =
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val target = plan(info.size.width, info.size.height)
                decoder.setTargetSize(target.size.width, target.size.height)
                target.crop?.let { decoder.crop = Rect(it.left, it.top, it.right, it.bottom) }
            }
        } catch (e: IOException) {
            // Includes `ImageDecoder.DecodeException`: bytes this platform can't read (an SVG, a
            // truncated file). A verdict — the same bytes come back next time.
            Log.d(TAG, "can't decode a preview picture", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "can't decode a preview picture", e)
            null
        } catch (e: OutOfMemoryError) {
            // Decoration: a picture too big to decode is a box that stays empty, not a crash.
            Log.w(TAG, "out of memory decoding a preview picture", e)
            null
        }

    private const val TAG = "Lurker"
}
