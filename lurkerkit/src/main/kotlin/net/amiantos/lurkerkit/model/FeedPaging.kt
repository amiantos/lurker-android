// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * The paging rules of a cross-buffer feed (Activity, Bookmarks, Search): which page to ask for
 * next, which answer may land, and which placeholder stands in for the rows.
 *
 * The half of the iOS app's `HistoryFeedViewController` that isn't table plumbing, kept synchronous
 * and pure so the rules can be tested. The screen runs the fetches this hands it and reports
 * each answer back through `land`; what comes back says whether the rows changed and whether
 * another page should be asked for straight away. lurker-android's `FeedPager` drives the
 * same type.
 *
 * A REST read paginated by a cursor rather than streamed: it fetches on open and pages as you
 * scroll, with pull-to-refresh to pick up anything that changed while it sat open.
 *
 * Port note: a value type in LurkerKit whose methods both mutate it and return what became of
 * the call. ⚠ Here it is a plain mutable class (PORTING.md, "Structs that mutate", case 3) with
 * exactly one owner, the feed's pager. It must never go into `ChatState` or travel through a
 * flow: a mutation in place neither publishes nor compares as a change. `items` is replaced on
 * every change, never edited in place, so a list read from it earlier stays as it was — what a
 * Swift array's copy gives for free.
 */
class FeedPaging(
    /**
     * Whether a `reload()` replaces a *first* page already in flight rather than being dropped.
     *
     * False for the feeds whose reload is idempotent: pulling Activity twice re-fetches the same
     * newest page, so the second pull buys nothing and dropping it keeps the refresh control
     * honest. True for Search, where two reloads are two *different questions* and the one the
     * user typed last is the only one whose answer they want.
     *
     * Only ever about a first page. A reload always replaces a page-in or a skip-ahead hop: the
     * pull asked for the newest page, not the older one on its way, and a hop chain that can't be
     * pulled out of runs its whole budget before the pull is even heard (lurker-ios#204).
     */
    val supersedes: Boolean,
) {
    /** Which of a feed's three placeholders stands in for its rows. Each feed words them itself. */
    enum class Placeholder { Loading, Empty, Error }

    /** One page to fetch, stamped with the reload it was asked for under. */
    @ConsistentCopyVisibility
    data class Fetch internal constructor(
        /** The reload generation this answers; an answer under a newer one is dropped. */
        val generation: Int,
        /** Where the page starts — null for the first (newest) page. */
        val cursor: FeedCursor?,
    ) {
        val isFirstPage: Boolean get() = cursor == null
    }

    /** What an answer did to the list. */
    @ConsistentCopyVisibility
    data class Landing internal constructor(
        /** `items` was replaced or grew, so the grouped view has to be rebuilt. */
        val rowsChanged: Boolean,
        /**
         * The round added no rows but a cursor is still live: ask for this next, now. See
         * `settle`.
         */
        val next: Fetch?,
    )

    /** All rows, newest-first as the server returns them, after the ignore filter. */
    var items: List<HighlightItem> = emptyList()
        private set

    /** Null while there are rows to show. */
    var placeholder: Placeholder? = Placeholder.Loading
        private set

    var isLoading: Boolean = false
        private set

    /** The next-page cursor from the last response; null once the server has no more. */
    private var nextCursor: FeedCursor? = null
    private var reachedEnd = false

    /**
     * What's in flight is a first page, not a page-in — only a first page can absorb a repeat
     * pull. Meaningless while `isLoading` is false.
     */
    private var firstInFlight = false

    /**
     * Bumped by every `reload()`. A page carries the generation it was requested under, and one
     * that lands under a newer generation is dropped — the list it was fetched for no longer
     * exists.
     *
     * Needed the moment a feed's reload can mean something *different* from the one in flight
     * (Search, where each keystroke is a new query), but it isn't only for that: a pull-to-refresh
     * landing while a page-in was in flight would otherwise append the old list's next page onto
     * the new list.
     */
    private var generation = 0

    /**
     * The first fetch failed — distinct from an empty result, so the placeholder can offer a
     * retry rather than claim the feed is empty.
     */
    private var loadFailed = false

    /**
     * Consecutive auto-page hops that yielded no visible rows.
     *
     * Each hop is a full history/FTS query on the server, and the chain is self-feeding: a page
     * that filters to nothing asks for the next one. Against a channel an ignored sender dominates
     * that can run the entire history, dozens of round trips deep, behind a spinner that never
     * resolves. The cap stops the runaway; paging isn't lost, because scrolling asks again and
     * every reload restores the budget.
     */
    private var fruitlessHops = 0

    /**
     * Whether `fetch` still answers the list on screen. Re-checked when its request actually
     * starts, not only when its answer arrives: the body of a fetch runs a turn after it was
     * handed out, and a superseded page still costs a full query on the server.
     */
    fun isCurrent(fetch: Fetch): Boolean = fetch.generation == generation

    /**
     * (Re)fetch from the newest page: on open, on a pull, and — for Search — every time the
     * question changes. Null when dropped: a repeat reload of the same question, on a feed that
     * doesn't supersede, while its first page is still loading. A page-in or a hop in flight is
     * superseded instead (see `supersedes`), and so is anything at all when `newQuestion` is set
     * — an answer to the old question is never worth waiting for, on any feed.
     *
     * `newQuestion` clears the old answer at once — rows, cursor and end — and shows the loading
     * placeholder (lurker-ios#203). Keeping them, as a pull does, read as the answer to the new
     * question: the "foo" rows stood under "bar" until it answered, and if it failed they stayed
     * with no error at all, while the next scroll paged foo's cursor and `fetchPage` read bar's
     * query, appending bar's matches from before foo's last id under foo's rows. A pull asks the
     * same question again, so the rows it already has stay up under the refresh control's own
     * spinner rather than blanking.
     *
     * The hop budget is restored here (lurker-ios#204), not only when rows are gained: otherwise
     * a feed or a question that once spent it would come back from a pull, or the next search
     * whose first page is all ignored lines, with an empty list while real rows sat a page away.
     */
    fun reload(newQuestion: Boolean = false): Fetch? {
        val repeatPull = isLoading && firstInFlight && !supersedes && !newQuestion
        if (repeatPull) return null
        generation += 1
        isLoading = true
        firstInFlight = true
        loadFailed = false
        fruitlessHops = 0
        if (newQuestion) {
            items = emptyList()
            nextCursor = null
            reachedEnd = false
        }
        // Only the full-screen spinner on a cold load; a pull keeps the list up.
        if (items.isEmpty()) placeholder = Placeholder.Loading
        return Fetch(generation = generation, cursor = null)
    }

    /** The next older page, if there is one and nothing is already loading. */
    fun loadMore(): Fetch? {
        if (isLoading || reachedEnd) return null
        val cursor = nextCursor ?: return null
        isLoading = true
        firstInFlight = false
        return Fetch(generation = generation, cursor = cursor)
    }

    /**
     * Give up on the page in flight without asking another — nobody is waiting for it. Its
     * answer can no longer land. The placeholder is left alone: an error would claim a failure
     * when the user simply left.
     *
     * True when that leaves nothing on screen, so the question must be asked again next time
     * it's shown. Rows already up still answer it: an abandoned page-in or pull leaves them, and
     * asking again would replace the pages the reader had scrolled through with page one.
     */
    fun abandon(): Boolean {
        generation += 1
        isLoading = false
        return items.isEmpty()
    }

    /**
     * A page arrived (null: the fetch failed). Null back when it was superseded and has touched
     * nothing — not even `isLoading`, since clearing that would let a scroll page the *old*
     * question's cursor into the new question's list. `visible` is the ignore filter, applied
     * as pages land.
     */
    fun land(
        page: HighlightsPage?,
        fetch: Fetch,
        visible: (List<HighlightItem>) -> List<HighlightItem>,
    ): Landing? {
        if (!isCurrent(fetch)) return null
        isLoading = false
        return if (fetch.isFirstPage) landFirst(page, visible) else landMore(page, visible)
    }

    private fun landFirst(
        page: HighlightsPage?,
        visible: (List<HighlightItem>) -> List<HighlightItem>,
    ): Landing {
        if (page == null) {
            // A failed pull under rows keeps them, and their cursor: it asked the same question,
            // so what's on screen still answers it. A failed new question has no rows by now
            // (`reload(newQuestion)` cleared them), so it says it failed.
            loadFailed = true
            if (items.isEmpty()) placeholder = Placeholder.Error
            return Landing(rowsChanged = false, next = null)
        }
        items = visible(page.items)
        nextCursor = page.next
        reachedEnd = !page.hasMore
        return Landing(rowsChanged = true, next = settle(gainedRows = items.isNotEmpty()))
    }

    private fun landMore(
        page: HighlightsPage?,
        visible: (List<HighlightItem>) -> List<HighlightItem>,
    ): Landing {
        if (page == null) {
            // A failed page-in leaves what we have and just stops paging; the user can pull to
            // refresh. Don't latch `reachedEnd` — the next scroll re-arms `loadMore`.
            //
            // Unless there's nothing left on screen: `remove` can page from an emptied list, and
            // there no scroll will ever retry. Say the fetch failed rather than spin forever.
            if (items.isEmpty()) {
                loadFailed = true
                settlePlaceholder()
            }
            return Landing(rowsChanged = false, next = null)
        }
        // Filtered before the emptiness check, so a page that holds nothing but ignored lines
        // takes the same path as one the server returned empty: record the cursor, then let
        // `settle` decide whether to page past it.
        val fresh = visible(page.items)
        nextCursor = page.next
        reachedEnd = !page.hasMore
        if (fresh.isEmpty()) return Landing(rowsChanged = false, next = settle(gainedRows = false))
        items = items + fresh
        return Landing(rowsChanged = true, next = settle(gainedRows = true))
    }

    /**
     * Drop one row, by message id. Null back when it's already gone.
     *
     * By id, not position: the list can be replaced underneath an open swipe (a pull lands), and
     * a position resolved again then removes whatever now occupies that slot. Emptying the list
     * by removing things is not failing to load it, so the failure latch clears — a refresh that
     * failed while rows were still up would otherwise leave "Couldn't load" as the epitaph for a
     * list the user just cleared.
     *
     * Pages in only when the removal leaves `prefetchWindow` rows or fewer — the point at which a
     * scroll would have asked anyway, so pass the screen's own prefetch threshold. And a removal
     * spends no skip-ahead hop: a swipe is not a page an ignore rule emptied. Settling it like
     * one fetched a page on every bookmark removed from a long list, and ten swipes spent the
     * whole budget, so a later page an ignore rule emptied stopped the feed dead on a live cursor.
     */
    fun remove(messageId: Long, prefetchWindow: Int): Landing? {
        val index = items.indexOfFirst { it.message.id == messageId }
        if (index < 0) return null
        items = items.filterIndexed { i, _ -> i != index }
        if (items.isEmpty()) loadFailed = false
        // `loadMore` declines while a page is already in flight; that page settles the list.
        if (items.size <= prefetchWindow) {
            val next = loadMore()
            if (next != null) {
                if (items.isEmpty()) placeholder = Placeholder.Loading
                return Landing(rowsChanged = true, next = next)
            }
        }
        settlePlaceholder()
        return Landing(rowsChanged = true, next = null)
    }

    /**
     * Close out a list mutation: either ask for the next page, or say what the list now shows.
     *
     * The rule is about rows *gained*, not about the list being empty. The screen pages off rows
     * coming on screen, so a round that adds none can never ask for another — a dead end whether
     * the list holds zero rows or three: a search whose second page is entirely an ignored sender
     * would otherwise stop at three results with a live cursor sitting there.
     */
    private fun settle(gainedRows: Boolean): Fetch? {
        if (gainedRows) fruitlessHops = 0
        val stalled = !gainedRows && !reachedEnd && nextCursor != null
        // A hop is spent only on a fetch actually issued. `loadMore` declines while a page is
        // already in flight — `remove` can get here mid-load — and that page will settle the list
        // itself when it lands.
        if (stalled && fruitlessHops < maxFruitlessHops) {
            val hop = loadMore()
            if (hop != null) {
                fruitlessHops += 1
                // Only claim to be loading when there's nothing to look at. Topping up beneath a
                // list the user is already reading should be silent.
                if (items.isEmpty()) placeholder = Placeholder.Loading
                return hop
            }
        }
        settlePlaceholder()
        return null
    }

    /**
     * What an empty list says: still loading while a page is on its way (a removal emptied it
     * mid-load), else the failure or the genuine empty.
     */
    private fun settlePlaceholder() {
        if (items.isNotEmpty()) {
            placeholder = null
            return
        }
        placeholder = if (isLoading) Placeholder.Loading else if (loadFailed) Placeholder.Error else Placeholder.Empty
    }

    companion object {
        internal const val maxFruitlessHops = 10
    }
}
