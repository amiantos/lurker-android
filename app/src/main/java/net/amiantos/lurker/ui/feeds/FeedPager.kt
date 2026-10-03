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
 * The paging rules of a cross-buffer feed — lurker-ios's `HistoryFeedViewController`, the half that
 * isn't table plumbing, synchronous and pure so every rule is tested. `FeedController` runs the
 * fetches it asks for.
 *
 * A REST read paginated by a cursor rather than streamed: it fetches on open and pages as you scroll,
 * with pull-to-refresh to pick up anything that changed while it sat open. It deliberately doesn't
 * follow live state — something arriving in some channel is a push/badge concern, not a reason to
 * mutate a list you're reading.
 *
 * Five places part ways with the iOS original, which has these bugs too: a new question clears the
 * old answer ([reload]), a reload resets the hop budget, a pull supersedes a page-in, a removed row
 * stays removed across a reload already in flight ([remove]), and a removal pages only when it has to.
 *
 * @param supersedes whether a reload replaces a FIRST page already in flight rather than being dropped
 *   — iOS's `reloadSupersedes`. False for the feeds whose reload is idempotent: pulling twice re-fetches
 *   the same newest page, so the second pull buys nothing and dropping it keeps the refresh spinner
 *   honest. True for Search, where two reloads are two different questions and only the last typed is
 *   wanted. Either way a reload supersedes a page-in or a hop chain: the pull asked for the newest page,
 *   not for the older one still on its way.
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

    /** What's in flight is a first page, not a page-in — only a first page can absorb a repeat pull. */
    private var firstInFlight = false

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

    private var pageInFailed = false
    private var epoch = 0

    /**
     * Consecutive auto-page hops that yielded no visible rows. Each is a full history/FTS query on the
     * server, and the chain feeds itself: a page that filters to nothing asks for the next. Against a
     * channel an ignored sender dominates, that can run the whole history behind a spinner that never
     * resolves; the cap stops it. Reset by every reload, so one exhausted query can't stop the next from
     * paging past an all-ignored page; a scroll asks again too.
     */
    private var fruitlessHops = 0

    /**
     * Rows the reader removed, by message id, with the generation they were removed under. A reload
     * already in flight was answered from before the removal, so its page still holds the row — filtered
     * out here until a reload issued AFTER the removal lands, whose answer is the server's word on it.
     */
    private val removed = HashMap<Long, Int>()

    fun snapshot(): FeedSnapshot = FeedSnapshot(items, placeholder, refreshing, pageInFailed, epoch)

    /** Whether [fetch] still answers the list on screen — re-checked when its request actually starts. */
    fun isCurrent(fetch: FeedFetch): Boolean = fetch.generation == generation

    /**
     * (Re)fetch from the newest page: on open, on a pull, and — for Search — every time the question
     * changes ([newQuestion]). Null when dropped: a repeat reload of an idempotent feed while its first
     * page is still loading, which also ends the pull's spinner, or it would spin forever.
     *
     * A new question clears the old answer at once — rows, cursor and end — and shows Loading: otherwise
     * the old query's rows would stand under the new query until it answered, and if it failed, stay
     * there with no error while the next scroll paged the OLD cursor under the new question. A pull asks
     * the same question again, so the rows it already has stay up under its own spinner.
     */
    fun reload(byPull: Boolean = false, newQuestion: Boolean = false): FeedFetch.First? {
        if (isLoading && firstInFlight && !supersedes) {
            refreshing = false
            return null
        }
        generation += 1
        isLoading = true
        firstInFlight = true
        loadFailed = false
        pageInFailed = false
        fruitlessHops = 0
        refreshing = byPull
        if (newQuestion) {
            items = emptyList()
            nextCursor = null
            reachedEnd = false
        }
        if (items.isEmpty()) placeholder = FeedPlaceholder.Loading
        return FeedFetch.First(generation)
    }

    /** The next older page, if there is one and nothing is already loading. */
    fun loadMore(): FeedFetch.More? {
        if (isLoading || reachedEnd) return null
        val cursor = nextCursor ?: return null
        isLoading = true
        firstInFlight = false
        pageInFailed = false
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
        // This answer was asked for after these removals, so it is the server's word on them.
        removed.values.removeAll { it < fetch.generation }
        epoch += 1
        items = shown(page)
        nextCursor = page.next
        reachedEnd = !page.hasMore
        return settle(gainedRows = items.isNotEmpty())
    }

    /**
     * An older page landed (null: the fetch failed). A failed page-in keeps what's on screen and ends the
     * list in a retry row ([FeedSnapshot.pageInFailed]) — not latching the end — unless the list is
     * empty, where it says the fetch failed rather than spin forever.
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
            } else {
                pageInFailed = true
            }
            return null
        }
        val fresh = shown(page)
        nextCursor = page.next
        reachedEnd = !page.hasMore
        if (fresh.isEmpty()) return settle(gainedRows = false)
        items = items + fresh
        return settle(gainedRows = true)
    }

    /** A page's rows as shown: the ignore filter, less what the reader removed meanwhile. */
    private fun shown(page: HighlightsPage): List<HighlightItem> {
        val rows = visible(page.items)
        return if (removed.isEmpty()) rows else rows.filter { it.message.id !in removed }
    }

    /**
     * Drop one row — a bookmark swiped away. By message id, not position: the list can be replaced
     * under an open swipe (a pull lands), and a position resolved again then would remove whatever now
     * sits there. A row already gone is a no-op. Remembered against a reload in flight ([removed]).
     *
     * Pages in only when the removal leaves the list empty or inside the prefetch window — the point at
     * which a scroll would have asked anyway. A swipe is not a fruitless page: it spends no hop, and
     * it doesn't fetch another page on every bookmark removed.
     *
     * Emptying the list by removing things isn't failing to load it, so the failure latch clears: a
     * refresh that failed with rows still up would otherwise leave "Couldn't load" as the epitaph of a
     * list the reader just cleared.
     */
    fun remove(messageId: Long): FeedFetch.More? {
        removed[messageId] = generation
        val index = items.indexOfFirst { it.message.id == messageId }
        if (index < 0) return null
        items = items.toMutableList().apply { removeAt(index) }
        if (items.isEmpty()) loadFailed = false
        if (items.size <= PREFETCH) {
            val more = loadMore()
            if (more != null) {
                if (items.isEmpty()) placeholder = FeedPlaceholder.Loading
                return more
            }
        }
        showPlaceholderForState()
        return null
    }

    /**
     * Close out a page landing: re-arm paging, or say what the list now shows.
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
