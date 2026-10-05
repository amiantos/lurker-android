// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.ui.media.MediaSource
import net.amiantos.lurker.ui.media.MediaViewerHost
import net.amiantos.lurker.ui.media.rememberMediaViewer
import net.amiantos.lurker.ui.networks.PagedDialog
import net.amiantos.lurker.ui.networks.PagedFlow
import net.amiantos.lurker.ui.networks.rememberPagedFlow
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.MediaFetch
import net.amiantos.lurkerkit.model.UploadItem
import net.amiantos.lurkerkit.model.UploadsFilter
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.support.trimmingWhitespaces
import java.util.UUID

/**
 * Which uploads browser is up, under which token, and where Add to Message goes — the buffer whose
 * conversation opened it, or null from the buffer list. Saved, so a rotation keeps the dialog up; the
 * list itself lives in its flow (see `PagedFlow`).
 */
internal data class UploadsOpen(val token: String, val insertInto: BufferKey?) {
    companion object {
        /** The CTCP delimiter, which no channel name or nick can contain. */
        private val SEPARATOR = Char(1).toString()

        val Saver: Saver<UploadsOpen?, String> = Saver(
            save = { open ->
                open?.let { listOf(it.token, it.insertInto?.networkId?.toString().orEmpty(), it.insertInto?.target.orEmpty()).joinToString(SEPARATOR) }
                    ?: ""
            },
            restore = { saved ->
                val parts = saved.split(SEPARATOR, limit = 3)
                if (parts.size < 3 || parts[0].isEmpty()) {
                    null
                } else {
                    val key = if (parts[2].isEmpty()) null else BufferKey(networkId = parts[1].toIntOrNull(), target = parts[2])
                    UploadsOpen(parts[0], key)
                }
            },
        )
    }
}

/**
 * The uploads browser's open/closed state — hosted by `MainScaffold` beside the feeds, for their
 * reason: a dialog hosted in the list or the conversation would close under the reader on a phone.
 */
@Stable
class UploadsSheets internal constructor(private val open: MutableState<UploadsOpen?>) {
    /**
     * Open the browser. [insertInto] is the buffer whose composer Add to Message fills — pass it only
     * from a conversation. ⚠⚠ Null is what gates the affordance, and it has to be gated: from the buffer
     * list there's no composer behind the dialog, and an insert would land nowhere.
     */
    fun show(insertInto: BufferKey?) {
        open.value = UploadsOpen(UUID.randomUUID().toString(), insertInto)
    }

    fun dismiss() {
        open.value = null
    }

    internal val current: UploadsOpen? get() = open.value
}

@Composable
fun rememberUploadsSheets(): UploadsSheets {
    val open = rememberSaveable(stateSaver = UploadsOpen.Saver) { mutableStateOf<UploadsOpen?>(null) }
    return remember(open) { UploadsSheets(open) }
}

/** A sentence to say over the browser — a refused star, a failed delete, a tombstone tapped. */
data class UploadsAlert(val title: String, val message: String)

/**
 * The browser, held for the life of its dialog: the list ([grid]), the search field, the debounce, and
 * the row actions. lurker-ios's `UploadsViewController`, minus the views; the decisions are
 * [UploadsGrid]'s.
 *
 * ⚠ Every fetch is a READ (`GET /api/uploads`); a star and a delete are the writes, and they run
 * `NonCancellable` — closing the dialog mid-request must not leave the reader unsure whether it took.
 */
class UploadsBrowserState(private val model: ChatViewModel, private val scope: CoroutineScope) {
    var grid by mutableStateOf(UploadsGrid())
        private set

    /** The search field as typed. The committed query is `grid.filter.query`, which trails it by the debounce. */
    var query by mutableStateOf("")
        private set

    /** Bumped when a new question's answer lands, so the grid goes back to the top. */
    var scrollToTop by mutableIntStateOf(0)
        private set

    /** The last [scrollToTop] the grid went back to the top for — see `UploadsPage`. Not state: nothing draws it. */
    var scrolledToTop = 0

    var alert by mutableStateOf<UploadsAlert?>(null)

    /** The row whose delete is being confirmed. */
    var confirmingDelete by mutableStateOf<UploadItem?>(null)

    val thumbnails = UploadThumbnails(model, scope)

    private var load: Job? = null
    private var debounce: Job? = null

    init {
        reload()
    }

    /**
     * From the newest page — on open, by a pull, and every filter change. Supersedes the request in
     * flight rather than waiting on it (`UploadsGrid.reload`); the generation is the correctness, the
     * cancel only the saving.
     */
    fun reload(filter: UploadsFilter = grid.filter, byPull: Boolean = false) {
        load?.cancel()
        val (next, request) = grid.reload(filter, byPull)
        grid = next
        load = scope.launch {
            val page = model.fetchUploads(filter = request.filter, before = request.before, limit = request.limit)
            val (settled, toTop) = grid.firstPage(request, page)
            grid = settled
            if (toTop) scrollToTop += 1
        }
    }

    /** A tile near the end came on screen. */
    fun shown(index: Int) {
        if (grid.wantsMore(index) && !grid.pageInFailed) loadMore()
    }

    /** The foot's Try Again, after a page failed to come in. */
    fun retryMore() = loadMore()

    private fun loadMore() {
        val (next, request) = grid.loadMore() ?: return
        grid = next
        load = scope.launch {
            val page = model.fetchUploads(filter = request.filter, before = request.before, limit = request.limit)
            grid = grid.nextPage(request, page)
        }
    }

    fun setFilter(change: (UploadsFilter) -> UploadsFilter) {
        val next = change(grid.filter)
        if (next != grid.filter) reload(next)
    }

    /**
     * The field changed. Leading and trailing spaces are an accident of typing — a search for " " is no
     * search. The pending commit is cancelled BEFORE the up-to-date check: editing back to the committed
     * text inside the window (type `cat`, backspace to `ca`) would otherwise leave `cat` armed.
     */
    fun edit(text: String) {
        query = text
        debounce?.cancel()
        val trimmed = text.trimmingWhitespaces()
        if (trimmed == grid.filter.query) return
        debounce = scope.launch {
            delay(UploadsGrid.DEBOUNCE_MS)
            setFilter { it.copy(query = trimmed) }
        }
    }

    /**
     * Star or unstar, optimistically; a refusal puts the row back (into the list it came from only) and
     * says why — a star that silently un-flips a second later is worse than one that never moved.
     *
     * ⚠ A WRITE: the star is server-side, on every device.
     */
    fun toggleStar(item: UploadItem) {
        val (next, revert) = grid.star(item) ?: return
        grid = next
        val wanted = !revert.original.favorite
        scope.launch {
            val refusal = withContext(NonCancellable) { model.setUploadFavorite(id = item.id, favorite = wanted) }
            if (refusal == null) {
                grid = grid.starred(revert)
                return@launch
            }
            // Put back only if this toggle is still the row's latest and the row still exists
            // (`UploadsGrid.revert`); the refusal is said either way — the server didn't change.
            grid = grid.revert(revert)
            alert = UploadsAlert(if (wanted) "Couldn't Star" else "Couldn't Unstar", refusal)
        }
    }

    /** Ask first: unlike everything else here this isn't a change to a list — the file goes. */
    fun askToDelete(item: UploadItem) {
        confirmingDelete = item
    }

    /**
     * ⚠ A WRITE that destroys the stored bytes. Only ever offered where `canDelete` says it can. A
     * refusal leaves the row and says why.
     */
    fun delete(item: UploadItem) {
        confirmingDelete = null
        scope.launch {
            val refusal = withContext(NonCancellable) { model.deleteUpload(id = item.id) }
            if (refusal != null) {
                alert = UploadsAlert("Couldn't Delete", refusal)
            } else {
                grid = grid.deleted(item.id)
            }
        }
    }

    /**
     * The bytes are gone, so there's nothing to copy, view or share — said, rather than left as a
     * gesture that visibly did nothing (a tap that silently does nothing reads as a missed touch).
     */
    fun reportTombstone() {
        alert = UploadsAlert(
            "Upload Removed",
            "This file was removed by the server's operator. The record is kept, but the file itself is gone.",
        )
    }
}

/**
 * Thumbnails for the grid, held for the life of the dialog — dropped with it, so the previous
 * session's pictures never outlive a sign-out. Fetched through the kit's media request builder (an
 * instance path carries the bearer token, a CDN address never does) and decoded off the main thread,
 * subsampled to about the tile's size.
 */
class UploadThumbnails(private val model: ChatViewModel, private val scope: CoroutineScope) {
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val inFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()

    /** Paths that answered for good with nothing drawable — not asked again this dialog. */
    private val failed = mutableSetOf<String>()

    fun cached(path: String): ImageBitmap? = cache.get(path)

    /** The picture for [path], or null when there isn't one to draw. Main-thread. */
    suspend fun load(path: String, targetPx: Int): ImageBitmap? {
        cache.get(path)?.let { return it }
        if (path in failed) return null
        val running = inFlight[path] ?: scope.async {
            try {
                when (val fetch = model.proxiedMedia(path)) {
                    is MediaFetch.Success -> withContext(Dispatchers.Default) { decode(fetch.data.toByteArray(), targetPx) }
                        .also { bitmap -> if (bitmap == null) failed.add(path) else cache.put(path, bitmap) }
                    MediaFetch.Permanent -> null.also { failed.add(path) }
                    // Worth another go — the next time the tile is drawn.
                    MediaFetch.Retryable -> null
                }
            } finally {
                inFlight.remove(path)
            }
        }.also { inFlight[path] = it }
        return running.await()
    }

    private fun decode(bytes: ByteArray, targetPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        /** About a few screens of tiles. */
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}

/** The browser's flow — one page, kept across a configuration change (see `PagedFlow`). */
internal class UploadsFlow(model: ChatViewModel) : PagedFlow<UploadsBrowserState>() {
    init {
        pages.add(UploadsBrowserState(model, scope))
    }
}

/**
 * Draws the uploads browser when [sheets] has it open.
 *
 * @param onAddToMessage put a file's address in [UploadsOpen.insertInto]'s composer. The dialog has
 *   closed by the time this runs — you asked for this file, so the browse is over.
 */
@Composable
fun UploadsSheetsHost(sheets: UploadsSheets, model: ChatViewModel, onAddToMessage: (BufferKey, String) -> Unit) {
    val open = sheets.current ?: return
    val flow = rememberPagedFlow(open.token, isOpen = { sheets.current?.token == open.token }) { UploadsFlow(model) }
    // The app's media viewer (U8a's), over this dialog: View opens a gallery of the grid's viewable rows.
    // Its own state and source rather than the scaffold's, for two reasons. The source: the scaffold's
    // (`MediaSource.of`) answers nothing while the instance has link previews off — right for previews,
    // whose bytes come through the preview proxy, and wrong here, where a picture is the account's own
    // upload fetched from its own address. And the window: hosted inside this dialog, the viewer is drawn
    // over it, and closing it comes back to the grid.
    val viewer = rememberMediaViewer()
    val media = remember(model) {
        MediaSource(fetch = { path -> model.proxiedMedia(path) }, playable = { path, mime -> model.playableMediaURL(path, mime) })
    }
    PagedDialog(flow = flow, onDismiss = sheets::dismiss, label = "uploads page") { _, state ->
        val target = open.insertInto
        UploadsPage(
            state = state,
            onClose = sheets::dismiss,
            onAddToMessage = target?.let { key ->
                { url: String ->
                    sheets.dismiss()
                    onAddToMessage(key, url)
                }
            },
            onView = viewer::show,
        )
        MediaViewerHost(viewer, media)
    }
}
