// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.model.UploadItem
import net.amiantos.lurkerkit.model.UploadKind
import net.amiantos.lurkerkit.model.UploadsFilter
import net.amiantos.lurkerkit.model.UploadsPage
import net.amiantos.lurkerkit.model.UploadsRequest
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * The uploads browser's list, as a value: what it holds, what question that answers, and every move
 * the screen makes on it — lurker-ios's `UploadsViewController` state and its `reload`/`loadMore`/
 * `handleFirstPage`/`appendPage`/`toggleStar`/`restore`, pure. `UploadsBrowserState` runs the
 * requests and holds one of these.
 *
 * ⚠⚠ [filter] and [shownFilter] are not the same thing, and the gap between them is the point: while a
 * filter change is in flight the grid still shows the PREVIOUS question's rows, which is right —
 * blanking a readable grid on every keystroke is worse than a moment of staleness — and becomes a lie
 * the instant that request FAILS. [shownFilter] is what tells "these are the matches for what you
 * typed" from "these are what was here before".
 *
 * Every page carries the [generation] it was requested under, and one that lands under a newer one is
 * dropped — the list it was fetched for no longer exists.
 */
data class UploadsGrid(
    val items: List<UploadItem> = emptyList(),
    val filter: UploadsFilter = UploadsFilter(),
    /** The filter the rows on screen answer. */
    val shownFilter: UploadsFilter = UploadsFilter(),
    /** The smallest id seen — the next page's `before`. Null once there's nothing to page from. */
    val cursor: Int? = null,
    val hasMore: Boolean = true,
    /** The starred view came back at the server's ceiling, so there may be more it didn't send. */
    val isTruncated: Boolean = false,
    val isLoading: Boolean = false,
    /** A pull to refresh is out — the pull's own spinner, not the page's. */
    val refreshing: Boolean = false,
    /** The first fetch failed with nothing to show — distinct from empty, so the placeholder offers a retry. */
    val loadFailed: Boolean = false,
    val generation: Int = 0,
) {
    /** One request to make: its generation, its question, its cursor. */
    data class Request(val generation: Int, val filter: UploadsFilter, val before: Int?, val limit: Int)

    /** A star change to undo if the server refuses it — only into the list it came from. */
    data class Revert(val original: UploadItem, val index: Int, val generation: Int)

    /**
     * (Re)fetch from the newest page — first appearance, pull to refresh, and every filter change.
     *
     * ⚠ Does NOT bail while a load is in flight: a filter change must SUPERSEDE the request it replaces,
     * or the grid settles on the answer to the previous keystroke. The truncation note is cleared now,
     * not when the page lands: it describes the list the PREVIOUS filter produced.
     */
    fun reload(filter: UploadsFilter = this.filter, byPull: Boolean = false): Pair<UploadsGrid, Request> {
        val next = copy(
            filter = filter,
            generation = generation + 1,
            isLoading = true,
            refreshing = byPull,
            loadFailed = false,
            isTruncated = false,
        )
        return next to Request(next.generation, filter, before = null, limit = UploadsRequest.limit(filter))
    }

    /** The next page, if there is one and nothing is in flight. */
    fun loadMore(): Pair<UploadsGrid, Request>? {
        val before = cursor
        if (isLoading || !hasMore || before == null) return null
        return copy(isLoading = true) to Request(generation, filter, before = before, limit = UploadsRequest.limit(filter))
    }

    /**
     * A first page landed (null: it failed). Superseded pages change nothing — including `isLoading`,
     * which clearing here would let a scroll page the old filter's cursor into the new filter's list.
     *
     * @return the new grid, and whether the grid should go back to the top: only for a NEW question —
     *   searching from deep in a long grid otherwise lands the reader on the OLDEST matches, which reads
     *   as the search having found the wrong ones.
     */
    fun firstPage(request: Request, page: UploadsPage?): Pair<UploadsGrid, Boolean> {
        if (request.generation != generation) return this to false
        val changedQuestion = request.filter != shownFilter
        val settled = copy(isLoading = false, refreshing = false)
        if (page == null) {
            // ⚠⚠ A failed load of a DIFFERENT question takes the old rows with it. Left up, the whole
            // unfiltered history stands in as "the matches for cat" with nothing said — and the stale
            // cursor then pages the real matches onto the end of it. A failed REFRESH keeps its rows:
            // same question, and there is something to read.
            val failed = settled.copy(loadFailed = true)
            return if (changedQuestion) {
                failed.copy(items = emptyList(), cursor = null, hasMore = false, isTruncated = false, shownFilter = request.filter) to false
            } else {
                failed to false
            }
        }
        return settled.copy(
            items = page.items,
            cursor = page.items.lastOrNull()?.id,
            hasMore = UploadsRequest.hasMore(request.filter, received = page.items.size, limit = request.limit),
            isTruncated = UploadsRequest.isTruncated(request.filter, received = page.items.size),
            shownFilter = request.filter,
        ) to changedQuestion
    }

    /**
     * A later page landed. A failed continuation leaves what's on screen alone — there's a grid to read,
     * and scrolling asks again.
     */
    fun nextPage(request: Request, page: UploadsPage?): UploadsGrid {
        if (request.generation != generation) return this
        val settled = copy(isLoading = false)
        if (page == null) return settled
        val all = items + page.items
        return settled.copy(
            items = all,
            cursor = all.lastOrNull()?.id,
            hasMore = UploadsRequest.hasMore(request.filter, received = page.items.size, limit = request.limit),
        )
    }

    /**
     * Star or unstar, optimistically: the row flips now, and [Revert] puts it back if the server refuses.
     * A state badge that waits on a round trip reads as a missed tap.
     *
     * ⚠ Unstarring inside the starred view removes the row: it no longer belongs to the list shown.
     */
    fun star(item: UploadItem): Pair<UploadsGrid, Revert>? {
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return null
        val original = items[index]
        val wanted = !original.favorite
        val next = if (filter.favoritesOnly && !wanted) removing(index) else copy(items = items.toMutableList().also { it[index] = original.copy(favorite = wanted) })
        return next to Revert(original, index, generation)
    }

    /**
     * Put a row back after a refused star. ⚠⚠ Only into the list it came from: putting a missing row back
     * BY INDEX is what an unstar-in-the-starred-view needs, and against a list since replaced it splices
     * a foreign row in and points the cursor at an id from a list that no longer exists.
     */
    fun revert(revert: Revert): UploadsGrid {
        if (revert.generation != generation) return this
        val existing = items.indexOfFirst { it.id == revert.original.id }
        if (existing >= 0) return copy(items = items.toMutableList().also { it[existing] = revert.original })
        val all = items.toMutableList().also { it.add(minOf(revert.index, it.size), revert.original) }
        return copy(items = all, cursor = all.lastOrNull()?.id)
    }

    /** A delete went through: the row goes. */
    fun deleted(id: Int): UploadsGrid {
        val index = items.indexOfFirst { it.id == id }
        return if (index < 0) this else removing(index)
    }

    private fun removing(index: Int): UploadsGrid {
        val all = items.toMutableList().also { it.removeAt(index) }
        return copy(items = all, cursor = all.lastOrNull()?.id)
    }

    /** Whether a row this far down is close enough to the end to ask for the next page. */
    fun wantsMore(shownIndex: Int): Boolean = shownIndex >= items.size - PREFETCH

    /**
     * What an empty grid says — or null while it has rows. Loading, a failed first fetch, then the three
     * kinds of empty, each naming the filter in WORDS: the filter lives in a menu the reader can't see,
     * and a grid that looks empty for a reason nobody can see is the one failure this owes them.
     */
    val placeholder: UploadsPlaceholder?
        get() = when {
            items.isNotEmpty() -> null
            isLoading -> UploadsPlaceholder("Loading uploads…", isLoading = true)
            loadFailed -> UploadsPlaceholder("Couldn't load uploads", "Pull to try again.")
            filter.query.isNotEmpty() -> UploadsPlaceholder("No matches", filter.noMatchesLine)
            // Names the gesture, as Bookmarks does: an empty starred view is what somebody who has never
            // starred anything sees, and how to put something in it is the useful thing to say.
            filter.favoritesOnly -> UploadsPlaceholder("No ${filter.scope}", "Press and hold an upload, then Star, to keep it here.")
            filter.isNarrowed -> UploadsPlaceholder("No ${filter.scope}", "Nothing you've uploaded is under this filter.")
            else -> UploadsPlaceholder("No uploads yet", "Files you send with the paperclip in a conversation are kept here.")
        }

    /**
     * The starred view's truncation disclosure, at the END of the grid — an answer to having reached the
     * bottom. Said rather than swallowed: unlike every other view the starred one has no cursor, so
     * there is no "load more" to discover the rest with.
     */
    val footer: String? get() = if (isTruncated) "Showing your ${items.size} most recently starred uploads." else null

    companion object {
        /** Fetch the next page once a tile this far from the end is shown, so the grid extends before the bottom. */
        const val PREFETCH = 12

        /**
         * A typed word is one request rather than eight. ⚠ Shorter than message search's 350 ms on
         * purpose: that one throttles an FTS query over everything the account has seen; this is a
         * `LIKE` over one user's own upload rows.
         */
        const val DEBOUNCE_MS = 250L
    }
}

/** An empty grid's words. */
data class UploadsPlaceholder(val title: String, val subtitle: String? = null, val isLoading: Boolean = false)

/** Everything a press-and-hold offers on one tile, in iOS's order. */
enum class UploadAction(val title: String) {
    /** Into the media viewer. U8: offered once U8a's viewer can take an upload (`isViewable`). */
    View("View"),

    /** Text, PDFs and whatever the viewer can't show hand off to the browser — named for what happens. */
    OpenInBrowser("Open in Browser"),

    /**
     * Into the composer the browser was opened from. ⚠⚠ Only where one is mounted: the buffer list
     * reaches this screen too, and an Add to Message offered there would land nowhere and report nothing.
     */
    AddToMessage("Add to Message"),
    Star("Star"),
    Unstar("Unstar"),

    /** Kept though a tap does it: the menu is meant to be the complete list of what can be done to a file. */
    CopyLink("Copy Link"),
    Share("Share…"),
    Delete("Delete"),
}

/** The tile's words and menu, pure — lurker-ios's `UploadTileCell` and `actions(for:)`. */
object UploadTiles {
    /**
     * The menu for [item]. [canInsert] is whether a composer is behind the browser; [viewable] whether
     * the media viewer can show it.
     *
     * ⚠⚠ Star is offered on `!removed || favorite`: a takedown doesn't clear the star (the server keeps
     * it, so it survives a restore), so a tombstone can arrive starred — and hiding the control on every
     * removed row would strand that star with no way to clear it.
     *
     * ⚠ Delete only where [UploadItem.canDelete] says the bytes really can be destroyed.
     */
    fun actions(item: UploadItem, canInsert: Boolean, viewable: Boolean = false): List<UploadAction> =
        buildList {
            if (!item.removed) add(if (viewable) UploadAction.View else UploadAction.OpenInBrowser)
            if (canInsert && !item.removed) add(UploadAction.AddToMessage)
            if (!item.removed || item.favorite) add(if (item.favorite) UploadAction.Unstar else UploadAction.Star)
            if (!item.removed) {
                add(UploadAction.CopyLink)
                add(UploadAction.Share)
            }
            if (item.canDelete) add(UploadAction.Delete)
        }

    /**
     * When and how big, in that order — the two things that identify a file you're trying to find again.
     * Deliberately NOT the uploader: which backend a file landed on doesn't help recognise a picture.
     */
    fun metaLine(item: UploadItem, relative: (Instant) -> String, bytes: (Long) -> String): String {
        if (item.removed) return "Removed"
        return listOfNotNull(item.createdAt?.let(relative), item.byteSize?.let(bytes)).joinToString(" · ")
    }

    /** What TalkBack says for a tile: its name, whether it's starred, and its meta line. */
    fun accessibility(item: UploadItem, meta: String): String =
        listOfNotNull(item.displayName, if (item.favorite) "starred" else null, meta).joinToString(", ")

    /** The glyph a tile without a thumbnail draws. A prohibition sign for "taken down"; the caption says the word. */
    enum class Glyph { Removed, Image, Video, Audio, Text, File }

    fun glyph(item: UploadItem): Glyph =
        if (item.removed) {
            Glyph.Removed
        } else {
            when (item.kind) {
                UploadKind.Image -> Glyph.Image
                UploadKind.Video -> Glyph.Video
                UploadKind.Audio -> Glyph.Audio
                UploadKind.Text -> Glyph.Text
                null -> Glyph.File
            }
        }

    /** The narrowest a tile gets before a column is dropped — below it a thumbnail stops being scannable. */
    const val MIN_TILE_DP = 150

    /**
     * As many square tiles across as fit at a readable size, never fewer than two. Sized from the
     * CONTAINER's content width, not the screen: a split window gets the columns its own width deserves.
     */
    fun columns(contentWidthDp: Float): Int = max(2, (contentWidthDp / MIN_TILE_DP).toInt())
}

/**
 * How long ago, in the largest unit that fits — the unit choice of iOS's abbreviated, named
 * `RelativeDateTimeFormatter`. "now" under a second, so something uploaded moments ago never reads as
 * "in 0 sec." when the clocks disagree by a hair.
 */
sealed interface RelativeAge {
    data object Now : RelativeAge

    data class Ago(val amount: Long, val span: Span) : RelativeAge

    enum class Span { Seconds, Minutes, Hours, Days, Weeks, Months, Years }

    companion object {
        fun of(then: Instant, now: Instant): RelativeAge {
            val seconds = Duration.between(then, now).seconds
            return when {
                seconds < 1 -> Now
                seconds < 60 -> Ago(seconds, Span.Seconds)
                seconds < 3_600 -> Ago(seconds / 60, Span.Minutes)
                seconds < 86_400 -> Ago(seconds / 3_600, Span.Hours)
                seconds < 7 * 86_400 -> Ago(seconds / 86_400, Span.Days)
                seconds < 30 * 86_400 -> Ago(seconds / (7 * 86_400), Span.Weeks)
                seconds < 365 * 86_400 -> Ago(seconds / (30 * 86_400), Span.Months)
                else -> Ago(seconds / (365 * 86_400), Span.Years)
            }
        }
    }
}
