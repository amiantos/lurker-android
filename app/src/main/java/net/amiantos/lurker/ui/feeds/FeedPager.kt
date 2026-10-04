// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurkerkit.model.FeedPaging
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage

/** What a feed draws, as of the last change — one value, so the screen recomposes once per change. */
data class FeedSnapshot(
    val items: List<HighlightItem> = emptyList(),
    val placeholder: FeedPaging.Placeholder? = FeedPaging.Placeholder.Loading,
    /** A pull's own spinner is up — the page keeps its spinner away while it is. */
    val refreshing: Boolean = false,
    /**
     * The last page-in failed under rows already shown: the list ends in a retry row. Paging is driven
     * by rows coming on screen, and at the bottom of a list nothing new ever does — so without an
     * explicit retry, a failed page-in there could never be asked again short of a pull.
     */
    val pageInFailed: Boolean = false,
    /**
     * Counts the first pages that have landed. A new first page is a fresh read of every row (a rule
     * made since, a relay bot marked since), so what was rendered for the last one is thrown away.
     */
    val epoch: Int = 0,
)

/**
 * A cross-buffer feed's paging, as this app draws it: LurkerKit's [FeedPaging] — the cursor, the
 * generation, the hop budget, the rows and which placeholder stands in for them, every rule shared with
 * lurker-ios and tested there — plus the few rules that belong to this screen alone. Synchronous and
 * pure like the kit's half, so they are tested too; `FeedController` runs the fetches it hands out.
 *
 * What is only here, and why:
 * - [FeedSnapshot.refreshing], the pull's own spinner (iOS's refresh control, which ends itself).
 * - [FeedSnapshot.pageInFailed], the retry row under a failed page-in.
 * - [FeedSnapshot.epoch], which throws away what was rendered for the last first page.
 * - [wantsMore], the prefetch window (iOS's `willDisplay` check).
 * - A removed row stays removed across a reload already in flight ([remove]). LurkerKit lacks this.
 *
 * @param supersedes whether a reload replaces a FIRST page already in flight — [FeedPaging.supersedes].
 * @param visible the rows an ignore rule doesn't hide (lurker#301), judged as pages land.
 */
class FeedPager(
    supersedes: Boolean,
    private val visible: (List<HighlightItem>) -> List<HighlightItem> = { it },
) {
    private val paging = FeedPaging(supersedes)

    val items: List<HighlightItem> get() = paging.items
    /**
     * [FeedPaging.placeholder] — except that an empty list with a page on its way says Loading, as the
     * kit's own rule has it, after a removal asked for that page past the kit ([remove]): the kit had
     * already settled the list as empty when it declined to page.
     */
    val placeholder: FeedPaging.Placeholder?
        get() = if (items.isEmpty() && paging.isLoading) FeedPaging.Placeholder.Loading else paging.placeholder

    val isLoading: Boolean get() = paging.isLoading

    var refreshing: Boolean = false
        private set

    private var pageInFailed = false
    private var epoch = 0

    /** The generation of the latest reload — what a removal is stamped with (see [removed]). */
    private var reloadGeneration = 0

    /**
     * Rows the reader removed, by message id, with the reload generation they were removed under. A
     * reload already in flight was answered from before the removal, so its page still holds the row —
     * filtered out here until a reload issued AFTER the removal lands, whose answer is the server's word
     * on it.
     */
    private val removed = HashMap<Long, Int>()

    fun snapshot(): FeedSnapshot = FeedSnapshot(items, placeholder, refreshing, pageInFailed, epoch)

    /** Whether [fetch] still answers the list on screen — re-checked when its request actually starts. */
    fun isCurrent(fetch: FeedPaging.Fetch): Boolean = paging.isCurrent(fetch)

    /**
     * (Re)fetch from the newest page — [FeedPaging.reload]. A pull ([byPull]) puts its own spinner up;
     * a dropped one (a repeat pull of an idempotent feed while its first page is still loading) ends it,
     * or it would spin forever.
     */
    fun reload(byPull: Boolean = false, newQuestion: Boolean = false): FeedPaging.Fetch? {
        val fetch = paging.reload(newQuestion)
        if (fetch == null) {
            refreshing = false
            return null
        }
        reloadGeneration = fetch.generation
        refreshing = byPull
        pageInFailed = false
        return fetch
    }

    /** The next older page, if there is one and nothing is already loading — [FeedPaging.loadMore]. */
    fun loadMore(): FeedPaging.Fetch? = issued(paging.loadMore())

    /**
     * Whether a scroll that has drawn row [index] should ask for more: within [PREFETCH] rows of the
     * true end, however the channel+day runs happen to be sized — so the list extends before the reader
     * hits the end rather than stalling on it.
     */
    fun wantsMore(index: Int): Boolean = index >= items.size - PREFETCH

    /**
     * A page landed (null: the fetch failed) — [FeedPaging.land]. Returns the follow-up fetch when the
     * page filtered to nothing and a cursor is still live.
     *
     * A failed page-in under rows ends the list in a retry row ([FeedSnapshot.pageInFailed]). A first
     * page ends a pull's spinner, failed or not, and one that answers is a new [FeedSnapshot.epoch].
     */
    fun land(fetch: FeedPaging.Fetch, page: HighlightsPage?): FeedPaging.Fetch? {
        val landing = paging.land(page, fetch) { rows -> shown(rows, fetch) } ?: return null
        if (fetch.isFirstPage) {
            refreshing = false
            if (page != null) {
                // This answer was asked for after these removals, so it is the server's word on them.
                removed.values.removeAll { it < fetch.generation }
                epoch += 1
            }
        } else if (page == null && items.isNotEmpty()) {
            pageInFailed = true
        }
        return issued(landing.next)
    }

    /**
     * A page's rows as shown: the ignore filter, less what the reader removed meanwhile. A first page
     * keeps only the removals made under its own reload — one asked for after a removal is the server's
     * word on that row ([land] then forgets it).
     */
    private fun shown(rows: List<HighlightItem>, fetch: FeedPaging.Fetch): List<HighlightItem> {
        val seen = visible(rows)
        if (removed.isEmpty()) return seen
        return seen.filter { item ->
            val removedUnder = removed[item.message.id] ?: return@filter true
            fetch.isFirstPage && removedUnder < fetch.generation
        }
    }

    /**
     * Drop one row — a bookmark swiped away — [FeedPaging.remove], by message id. Remembered against a
     * reload in flight ([removed]). Pages in only when the removal leaves the list inside the prefetch
     * window, and spends no skip-ahead hop: LurkerKit's rule.
     */
    fun remove(messageId: Long): FeedPaging.Fetch? {
        removed[messageId] = reloadGeneration
        val landing = paging.remove(messageId) ?: return null
        return issued(landing.next)
    }

    /** Every fetch handed out takes a failed page-in's retry row down: the page is being asked again. */
    private fun issued(fetch: FeedPaging.Fetch?): FeedPaging.Fetch? {
        if (fetch != null) pageInFailed = false
        return fetch
    }

    companion object {
        /** Fetch the next page once a scroll comes within this many rows of the end — the kit's window. */
        const val PREFETCH = FeedPaging.prefetchWindow
    }
}
