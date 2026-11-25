// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurkerkit.model.FeedAnswer
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
     * The last fetch failed — [FeedPaging.failure] (lurker#1308) — or is the failure being retried
     * ([retrying]). With no rows the Error placeholder is up and this says why ([errorWords]). With
     * rows — a failed pull or page-in, which keeps them — a notice beside them says so, with a Try
     * Again: above them for a failed pull ([failureAbove]), below for a failed page-in
     * ([failureBelow]). A pull that silently did nothing reads as nothing new, and paging is driven by
     * rows coming on screen, which at the bottom of a list none ever will again.
     */
    val failure: FeedPaging.Failure? = null,
    /**
     * Try Again was tapped and its answer hasn't landed: the notice stays up, busy, until it does — a
     * notice that vanished on the tap and came back a few seconds later read as a glitch.
     */
    val retrying: Boolean = false,
    /**
     * Counts the first pages that have landed. A new first page is a fresh read of every row (a rule
     * made since, a relay bot marked since), so what was rendered for the last one is thrown away.
     */
    val epoch: Int = 0,
) {
    /**
     * A failed pull, said ABOVE the rows: that is where the reader who pulled is looking. Only with
     * rows up — with none, the placeholder says it.
     */
    val failureAbove: FeedPaging.Failure? get() = failure?.takeIf { it.firstPage && placeholder == null }

    /** A failed page-in, said BELOW the rows: that is where the reader scrolled to. */
    val failureBelow: FeedPaging.Failure? get() = failure?.takeIf { !it.firstPage && placeholder == null }

    /**
     * The Error placeholder's words: the feed's own ([said]), with the server's reason ahead of its
     * advice when it gave one, not in place of it — "Search took too long. Pull to try again, or edit
     * your search." (iOS's `renderPlaceholder`).
     */
    fun errorWords(said: StateModel): StateModel {
        val failure = failure?.takeIf { it.reason != null } ?: return said
        return said.copy(subtitle = listOfNotNull(failure.sentence(fallback = ""), said.subtitle).joinToString(" "))
    }
}

/**
 * A cross-buffer feed's paging, as this app draws it: LurkerKit's [FeedPaging] — the cursor, the
 * generation, the hop budget, the rows and which placeholder stands in for them, every rule shared with
 * lurker-ios and tested there — plus the few rules that belong to this screen alone. Synchronous and
 * pure like the kit's half, so they are tested too; `FeedController` runs the fetches it hands out.
 *
 * What is only here, and why:
 * - [FeedSnapshot.refreshing], the pull's own spinner (iOS's refresh control, which ends itself).
 * - Where a [FeedSnapshot.failure] is said — above the rows, below them, or in the placeholder's
 *   words — and that it stays up, busy, while it is retried ([FeedSnapshot.retrying]).
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

    private var epoch = 0

    /** The failure being retried, kept so its notice stays up until the answer lands ([retry]). */
    private var retrying: FeedPaging.Failure? = null

    /** The generation of the latest reload — what a removal is stamped with (see [removed]). */
    private var reloadGeneration = 0

    /**
     * Rows the reader removed, by message id, with the reload generation they were removed under. A
     * reload already in flight was answered from before the removal, so its page still holds the row —
     * filtered out here until a reload issued AFTER the removal lands, whose answer is the server's word
     * on it.
     */
    private val removed = HashMap<Long, Int>()

    fun snapshot(): FeedSnapshot {
        val standing = paging.failure
        return FeedSnapshot(
            items = items,
            placeholder = placeholder,
            refreshing = refreshing,
            failure = standing ?: retrying,
            retrying = standing == null && retrying != null,
            epoch = epoch,
        )
    }

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
        // A pull or a new question replaces a retry that was out: its notice is no longer busy
        // with anything.
        retrying = null
        return fetch
    }

    /** The next older page, if there is one and nothing is already loading — [FeedPaging.loadMore]. */
    fun loadMore(): FeedPaging.Fetch? = paging.loadMore()

    /**
     * The fetch that failed, again — [FeedPaging.retry]: the newest page for a failed open or pull, the
     * same older page for a failed page-in. Null when nothing failed — or when the retry is already
     * out. The rows stay up while it asks and so does the notice, busy ([FeedSnapshot.retrying]); no
     * pull spinner goes up — the reader tapped a button, they didn't pull.
     */
    fun retry(): FeedPaging.Fetch? {
        val failed = paging.failure
        val fetch = paging.retry() ?: return null
        retrying = failed
        if (fetch.isFirstPage) {
            reloadGeneration = fetch.generation
            refreshing = false
        }
        return fetch
    }

    /**
     * Whether a scroll that has drawn row [index] should ask for more: within [PREFETCH] rows of the
     * true end, however the channel+day runs happen to be sized — so the list extends before the reader
     * hits the end rather than stalling on it.
     */
    fun wantsMore(index: Int): Boolean = index >= items.size - PREFETCH

    /** [land] for a page-or-null: null is a failure with nothing said about why. */
    fun land(fetch: FeedPaging.Fetch, page: HighlightsPage?): FeedPaging.Fetch? = land(fetch, FeedAnswer(page))

    /**
     * An answer landed — [FeedPaging.land]. Returns the follow-up fetch when the page filtered to
     * nothing and a cursor is still live. Null, having touched nothing, for an answer to a question
     * since replaced — ⚠ which is what keeps the server's 503 for a search this client displaced
     * ("search superseded") from ever being shown as the new question's failure (lurker#1308).
     *
     * A failure of either kind keeps the rows already up and is said under them, or in the placeholder
     * when there are none ([FeedSnapshot.failure]). A first page ends a pull's spinner, failed or not,
     * and one that answers is a new [FeedSnapshot.epoch].
     */
    fun land(fetch: FeedPaging.Fetch, answer: FeedAnswer): FeedPaging.Fetch? {
        val landing = paging.land(answer, fetch) { rows -> shown(rows, fetch) } ?: return null
        // Whatever was being retried has its answer, or was replaced by the fetch that just landed.
        retrying = null
        if (fetch.isFirstPage) {
            refreshing = false
            if (answer.page != null) {
                // This answer was asked for after these removals, so it is the server's word on them.
                removed.values.removeAll { it < fetch.generation }
                epoch += 1
            }
        }
        return landing.next
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
        return landing.next
    }

    companion object {
        /** Fetch the next page once a scroll comes within this many rows of the end — the kit's window. */
        const val PREFETCH = FeedPaging.prefetchWindow
    }
}
