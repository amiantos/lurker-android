// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage

/** Which of a feed's three placeholders stands in for its rows — each feed words them itself. */
enum class FeedPlaceholder { Loading, Empty, Error }

/** A placeholder's words: a title and an optional subtitle — iOS's `StateView.Model`, minus the symbol. */
data class StateWords(val title: String, val subtitle: String? = null)

/** One page to fetch: the first (newest) one, or the next older one from [cursor]. */
sealed interface FeedFetch {
    /** The reload generation this fetch answers; an answer under a newer one is dropped. */
    val generation: Int

    data class First(override val generation: Int) : FeedFetch

    data class More(override val generation: Int, val cursor: FeedCursor) : FeedFetch
}

/** What a feed draws, as of the last change — one value, so the screen recomposes once per change. */
data class FeedSnapshot(
    val items: List<HighlightItem> = emptyList(),
    val placeholder: FeedPlaceholder? = FeedPlaceholder.Loading,
    /** A pull's own spinner is up — the page keeps its spinner away while it is. */
    val refreshing: Boolean = false,
)

/**
 * The paging rules of a cross-buffer feed — lurker-ios's `HistoryFeedViewController`, the half that
 * isn't table plumbing, synchronous and pure so every rule is tested. `FeedController` runs the
 * fetches it asks for.
 *
 * A REST read paginated by a cursor rather than streamed: it fetches on open and pages as you scroll,
 * with pull-to-refresh to pick up anything that changed while it sat open. It deliberately doesn't
 * follow live state — something arriving in some channel is a push/badge concern, not a reason to
 * mutate a list you're reading.
 *
 * @param supersedes whether a reload replaces a page already in flight rather than being dropped —
 *   iOS's `reloadSupersedes`. False for the feeds whose reload is idempotent: pulling twice re-fetches
 *   the same newest page, so the second pull buys nothing and dropping it keeps the refresh spinner
 *   honest. True for Search, where two reloads are two different questions and only the last typed is
 *   wanted — dropping it would leave the list answering a prefix of what's in the field.
 * @param visible the rows an ignore rule doesn't hide (lurker#301), judged as pages land.
 */
class FeedPager(
    private val supersedes: Boolean,
    private val visible: (List<HighlightItem>) -> List<HighlightItem> = { it },
) {
    var items: List<HighlightItem> = emptyList()
        private set
    var placeholder: FeedPlaceholder? = FeedPlaceholder.Loading
        private set
    var refreshing: Boolean = false
        private set

    /** The next-page cursor from the last response; null once the server has no more. */
    private var nextCursor: FeedCursor? = null
    private var reachedEnd = false
    var isLoading: Boolean = false
        private set

    /**
     * Bumped by every [reload]. A page carries the generation it was requested under, and one that
     * lands under a newer generation is dropped — the list it was fetched for no longer exists. Not only
     * for Search, where each keystroke is a new query: a pull landing while a page-in was in flight would
     * otherwise append the old list's next page onto the new list.
     */
    private var generation = 0

    /**
     * The first fetch failed with nothing to show — distinct from an empty result, so the placeholder
     * says so and offers the pull rather than claiming the feed is empty.
     */
    private var loadFailed = false

    /**
     * Consecutive auto-page hops that yielded no visible rows. Each is a full history/FTS query on the
     * server, and the chain feeds itself: a page that filters to nothing asks for the next. Against a
     * channel an ignored sender dominates, that can run the whole history behind a spinner that never
     * resolves; the cap stops it. Paging isn't lost — a scroll asks again and a pull re-arms everything.
     */
    private var fruitlessHops = 0

    fun snapshot(): FeedSnapshot = FeedSnapshot(items, placeholder, refreshing)

    /** Whether [fetch] still answers the list on screen — re-checked when its request actually starts. */
    fun isCurrent(fetch: FeedFetch): Boolean = fetch.generation == generation

    /**
     * (Re)fetch from the newest page: on open, on a pull, and — where [supersedes] — every time the
     * feed's question changes. Null when dropped (a non-superseding reload with a page in flight), which
     * also ends the pull's spinner, or it would spin forever.
     *
     * The full-page spinner shows only on a cold load; a refresh keeps the list up under the pull's own.
     */
    fun reload(byPull: Boolean = false): FeedFetch.First? {
        if (isLoading && !supersedes) {
            refreshing = false
            return null
        }
        generation += 1
        isLoading = true
        loadFailed = false
        refreshing = byPull
        if (items.isEmpty()) placeholder = FeedPlaceholder.Loading
        return FeedFetch.First(generation)
    }

    /** The next older page, if there is one and nothing is already loading. */
    fun loadMore(): FeedFetch.More? {
        if (isLoading || reachedEnd) return null
        val cursor = nextCursor ?: return null
        isLoading = true
        return FeedFetch.More(generation, cursor)
    }

    /**
     * Whether a scroll that has drawn row [index] should ask for more: within [PREFETCH] rows of the
     * true end, however the channel+day runs happen to be sized — so the list extends before the reader
     * hits the end rather than stalling on it.
     */
    fun wantsMore(index: Int): Boolean = index >= items.size - PREFETCH

    /**
     * The first page landed (null: the fetch failed). Returns a follow-up fetch when the page filtered
     * to nothing and a cursor is still live — see [settle].
     *
     * A superseded answer returns before touching `isLoading`: clearing it here would let a scroll
     * page the old query's cursor into the new query's list.
     */
    fun firstPage(fetch: FeedFetch.First, page: HighlightsPage?): FeedFetch.More? {
        if (!isCurrent(fetch)) return null
        isLoading = false
        refreshing = false
        if (page == null) {
            loadFailed = true
            if (items.isEmpty()) placeholder = FeedPlaceholder.Error
            return null
        }
        items = visible(page.items)
        nextCursor = page.next
        reachedEnd = !page.hasMore
        return settle(gainedRows = items.isNotEmpty())
    }

    /**
     * An older page landed (null: the fetch failed). A failed page-in keeps what's on screen and just
     * stops paging — not latching the end, so the next scroll re-arms it — unless the list is empty,
     * where no scroll will ever retry, so it says the fetch failed rather than spin forever.
     *
     * Filtered before the emptiness check, so a page holding nothing but ignored lines takes the same
     * path as an empty one: record the cursor, then let [settle] decide whether to page past it.
     */
    fun appendPage(fetch: FeedFetch.More, page: HighlightsPage?): FeedFetch.More? {
        if (!isCurrent(fetch)) return null
        isLoading = false
        if (page == null) {
            if (items.isEmpty()) {
                loadFailed = true
                showPlaceholderForState()
            }
            return null
        }
        val fresh = visible(page.items)
        nextCursor = page.next
        reachedEnd = !page.hasMore
        if (fresh.isEmpty()) return settle(gainedRows = false)
        items = items + fresh
        return settle(gainedRows = true)
    }

    /**
     * Drop one row — a bookmark swiped away. By message id, not position: the list can be replaced
     * under an open swipe (a pull lands), and a position resolved again then would remove whatever now
     * sits there. A row already gone is a no-op.
     *
     * Emptying the list by removing things isn't failing to load it, so the failure latch clears: a
     * refresh that failed with rows still up would otherwise leave "Couldn't load" as the epitaph of a
     * list the reader just cleared.
     */
    fun remove(messageId: Long): FeedFetch.More? {
        val index = items.indexOfFirst { it.message.id == messageId }
        if (index < 0) return null
        items = items.toMutableList().apply { removeAt(index) }
        if (items.isEmpty()) loadFailed = false
        return settle(gainedRows = false)
    }

    /**
     * Close out a change to the list: re-arm paging, or say what the list now shows. Every path that
     * changes [items] ends here.
     *
     * The rule is about rows GAINED, not about the list being empty. Paging is driven by rows coming on
     * screen, so a round that adds none can never ask for another — a dead end whether the list holds
     * zero rows or three (a search whose second page is all an ignored sender would stop at three with a
     * live cursor sitting there). So a stalled round asks for the next page itself, quietly beneath rows
     * already being read, behind the spinner when there are none.
     */
    private fun settle(gainedRows: Boolean): FeedFetch.More? {
        if (gainedRows) fruitlessHops = 0
        val stalled = !gainedRows && !reachedEnd && nextCursor != null
        if (stalled && fruitlessHops < MAX_FRUITLESS_HOPS) {
            fruitlessHops += 1
            if (items.isEmpty()) placeholder = FeedPlaceholder.Loading
            return loadMore()
        }
        showPlaceholderForState()
        return null
    }

    private fun showPlaceholderForState() {
        placeholder = if (items.isEmpty()) (if (loadFailed) FeedPlaceholder.Error else FeedPlaceholder.Empty) else null
    }

    companion object {
        /** Fetch the next page once a scroll comes within this many rows of the end. */
        const val PREFETCH = 8

        const val MAX_FRUITLESS_HOPS = 10
    }
}
