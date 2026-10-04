// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.FeedPaging
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The cross-buffer feeds' paging rules: which answer may land, what a new question does to the
 * old answer (lurker-ios#203), and the skip-ahead budget past pages an ignore rule empties
 * (lurker-ios#204).
 */
class FeedPagingTests {

    // MARK: - Fixtures

    private fun item(id: Long, nick: String = "alice"): HighlightItem =
        HighlightItem(
            message = Message(id = id, type = EventType.Message, nick = nick, text = "hi"),
            networkId = 1, target = "#a", networkName = null,
        )

    private fun page(ids: List<Long>, nick: String = "alice", nextBefore: Long?): HighlightsPage =
        HighlightsPage(items = ids.map { item(it, nick = nick) }, nextBefore = nextBefore)

    /** The ignore filter, as the screen applies it: "mallory" is ignored. */
    private val visible: (List<HighlightItem>) -> List<HighlightItem> = { items ->
        items.filter { it.message.nick != "mallory" }
    }

    /** A feed showing ids 30...21 with a live cursor at 21. */
    private fun loaded(supersedes: Boolean): FeedPaging {
        val paging = FeedPaging(supersedes = supersedes)
        val first = paging.reload()!!
        paging.land(page((30L downTo 21L).toList(), nextBefore = 21), first, visible)
        return paging
    }

    private fun ids(paging: FeedPaging): List<Long> = paging.items.map { it.message.id }

    // MARK: - A new question (lurker-ios#203)

    @Test
    fun testANewQuestionClearsTheOldAnswerAndShowsLoading() {
        val paging = loaded(supersedes = true)
        assertNotNull(paging.reload(newQuestion = true))
        assertEquals(emptyList(), ids(paging), "foo's rows would read as the answer to bar")
        assertEquals(FeedPaging.Placeholder.Loading, paging.placeholder)
    }

    /** The issue's own steps: "foo" up, type "bar", bar fails, scroll. */
    @Test
    fun testAFailedNewQuestionShowsTheErrorAndCannotPageTheOldCursor() {
        val paging = loaded(supersedes = true)
        val bar = paging.reload(newQuestion = true)!!
        val landing = paging.land(null, bar, visible)
        assertEquals(FeedPaging.Landing(rowsChanged = false, next = null), landing)
        assertEquals(emptyList(), ids(paging))
        assertEquals(FeedPaging.Placeholder.Error, paging.placeholder)
        assertNull(paging.loadMore(), "foo's cursor would page bar's query under foo's rows")
    }

    @Test
    fun testANewQuestionThatAnswersPagesFromItsOwnCursor() {
        val paging = loaded(supersedes = true)
        val bar = paging.reload(newQuestion = true)!!
        paging.land(page(listOf(9L, 8L), nextBefore = 8), bar, visible)
        assertEquals(listOf(9L, 8L), ids(paging))
        assertNull(paging.placeholder)
        assertEquals(FeedCursor(beforeMessage = 8), paging.loadMore()?.cursor)
    }

    @Test
    fun testAPageInFromTheOldQuestionLandingAfterTheNewOneIsDropped() {
        val paging = loaded(supersedes = true)
        val fooMore = paging.loadMore()!!
        val bar = paging.reload(newQuestion = true)!!
        assertFalse(paging.isCurrent(fooMore), "re-checked before the request goes out")
        assertNull(paging.land(page(listOf(20L, 19L), nextBefore = 19), fooMore, visible))
        assertEquals(emptyList(), ids(paging))
        assertTrue(paging.isLoading, "bar is still out; clearing this would let a scroll page")
        paging.land(page(listOf(9L), nextBefore = null), bar, visible)
        assertEquals(listOf(9L), ids(paging))
    }

    @Test
    fun testAFirstPageFromTheOldQuestionLandingAfterTheNewOneIsDropped() {
        val paging = FeedPaging(supersedes = true)
        val foo = paging.reload()!!
        val bar = paging.reload(newQuestion = true)!!
        assertNull(paging.land(page(listOf(30L), nextBefore = null), foo, visible))
        assertNull(paging.land(null, foo, visible), "nor may its failure")
        assertEquals(FeedPaging.Placeholder.Loading, paging.placeholder)
        paging.land(page(listOf(9L), nextBefore = null), bar, visible)
        assertEquals(listOf(9L), ids(paging))
    }

    // MARK: - The same question again (a pull)

    @Test
    fun testAPullKeepsItsRowsWhileItLoads() {
        val paging = loaded(supersedes = false)
        assertNotNull(paging.reload())
        assertEquals(10, paging.items.size)
        assertNull(paging.placeholder, "the refresh control spins; the rows stay up")
    }

    /** A failed pull asked the same question, so the rows and their cursor still answer it. */
    @Test
    fun testAFailedPullKeepsItsRowsAndTheirCursor() {
        val paging = loaded(supersedes = true)
        val pull = paging.reload()!!
        paging.land(null, pull, visible)
        assertEquals(10, paging.items.size)
        assertNull(paging.placeholder)
        assertEquals(FeedCursor(beforeMessage = 21), paging.loadMore()?.cursor)
    }

    @Test
    fun testAPullThatAnswersReplacesTheRows() {
        val paging = loaded(supersedes = false)
        val pull = paging.reload()!!
        paging.land(page(listOf(31L, 30L), nextBefore = null), pull, visible)
        assertEquals(listOf(31L, 30L), ids(paging))
        assertNull(paging.loadMore(), "the end the new page reported")
    }

    @Test
    fun testARepeatReloadOfAFeedThatDoesNotSupersedeIsDropped() {
        val paging = FeedPaging(supersedes = false)
        val first = paging.reload()!!
        assertNull(paging.reload(), "a second pull re-fetches the same newest page")
        paging.land(page(listOf(1L), nextBefore = null), first, visible)
        assertEquals(listOf(1L), ids(paging))
    }

    // MARK: - Skipping ahead past ignored pages (lurker-ios#204)

    @Test
    fun testAPageThatFiltersToNothingPagesPastItself() {
        val paging = FeedPaging(supersedes = false)
        val first = paging.reload()!!
        val landing = paging.land(page(listOf(30L, 29L), nick = "mallory", nextBefore = 29), first, visible)
        assertEquals(FeedCursor(beforeMessage = 29), landing?.next?.cursor)
        assertEquals(FeedPaging.Placeholder.Loading, paging.placeholder)
    }

    /**
     * Answers `fetch` with a page holding one ignored line and a live cursor; the hop it asks
     * for in return, if any.
     */
    private fun landIgnored(paging: FeedPaging, id: Long, fetch: FeedPaging.Fetch): FeedPaging.Fetch? =
        paging.land(page(listOf(id), nick = "mallory", nextBefore = id), fetch, visible)?.next

    /**
     * Answers every hop with an ignored page until the paging state stops asking; how many it
     * asked for.
     */
    private fun hopsUntilItStops(paging: FeedPaging, from: FeedPaging.Fetch, at: Long): Int {
        var hops = 0
        var next = landIgnored(paging, at, from)
        while (next != null) {
            hops += 1
            next = landIgnored(paging, at - hops, next)
        }
        return hops
    }

    /** Spends the whole budget on all-ignored pages and returns the paging state there. */
    private fun exhausted(supersedes: Boolean): FeedPaging {
        val paging = FeedPaging(supersedes = supersedes)
        val first = paging.reload()!!
        assertEquals(
            FeedPaging.maxFruitlessHops, hopsUntilItStops(paging, from = first, at = 1000),
            "the cap stops the runaway",
        )
        assertEquals(FeedPaging.Placeholder.Empty, paging.placeholder)
        return paging
    }

    @Test
    fun testAPullRestoresTheSkipAheadBudget() {
        val paging = exhausted(supersedes = false)
        val pull = paging.reload()!!
        assertNotNull(
            landIgnored(paging, 500, pull),
            "a spent budget left the feed empty with rows a page away",
        )
        assertEquals(FeedPaging.Placeholder.Loading, paging.placeholder)
    }

    @Test
    fun testANewQuestionRestoresTheSkipAheadBudget() {
        val paging = exhausted(supersedes = true)
        val bar = paging.reload(newQuestion = true)!!
        assertNotNull(landIgnored(paging, 500, bar), "\"No matches\" while matches sat a page away")
    }

    /**
     * A new question typed while the old one is mid-hop: the hop's answer is dropped, and the new
     * question starts with the whole budget rather than what the old one left.
     */
    @Test
    fun testANewQuestionDuringAHopSupersedesItWithAFreshBudget() {
        val paging = FeedPaging(supersedes = true)
        var fetch = paging.reload()!!
        for (id in 999L downTo 991L) {
            fetch = landIgnored(paging, id, fetch)!!
        }
        val bar = paging.reload(newQuestion = true)!!
        assertNull(paging.land(page(listOf(1L), nextBefore = null), fetch, visible))
        assertEquals(FeedPaging.maxFruitlessHops, hopsUntilItStops(paging, from = bar, at = 500))
    }

    @Test
    fun testGainingRowsRestoresTheBudget() {
        val paging = FeedPaging(supersedes = false)
        var fetch = paging.reload()!!
        for (id in 999L downTo 991L) {
            fetch = landIgnored(paging, id, fetch)!!
        }
        paging.land(page(listOf(990L), nextBefore = 990), fetch, visible)
        assertEquals(FeedPaging.maxFruitlessHops, hopsUntilItStops(paging, from = paging.loadMore()!!, at = 500))
        assertEquals(listOf(990L), ids(paging))
    }

    // MARK: - What a reload may replace

    /**
     * A pull during a scroll's page-in asks for the newest page; the older one on its way is
     * dropped rather than the pull.
     */
    @Test
    fun testAPullDuringAPageInSupersedesIt() {
        val paging = loaded(supersedes = false)
        val more = paging.loadMore()!!
        val pull = paging.reload() ?: fail("dropped: the pull's spinner ends and nothing is refreshed")
        assertNull(paging.land(page(listOf(20L), nextBefore = 20), more, visible))
        paging.land(page(listOf(31L), nextBefore = null), pull, visible)
        assertEquals(listOf(31L), ids(paging))
    }

    /**
     * The lurker-ios#204 case on a feed that doesn't supersede: a pull while a hop chain is still
     * spending its budget restarts the feed with a fresh one, instead of waiting the chain out.
     */
    @Test
    fun testAPullDuringAHopChainRestartsItWithAFreshBudget() {
        val paging = FeedPaging(supersedes = false)
        var fetch = paging.reload()!!
        for (id in 999L downTo 995L) {
            fetch = landIgnored(paging, id, fetch)!!
        }
        val pull = paging.reload() ?: fail("the pull waits out the chain")
        assertNull(landIgnored(paging, 994, fetch), "the chain's next page is dropped")
        assertEquals(FeedPaging.maxFruitlessHops, hopsUntilItStops(paging, from = pull, at = 500))
    }

    /**
     * A new question supersedes on any feed — waiting out the old question's first page would
     * leave its rows and cursor standing under the new one (lurker-ios#203).
     */
    @Test
    fun testANewQuestionSupersedesEvenOnAFeedThatDoesNot() {
        val paging = loaded(supersedes = false)
        val pull = paging.reload()!!
        val bar = paging.reload(newQuestion = true)
        assertNotNull(bar)
        assertEquals(emptyList(), ids(paging))
        assertNull(paging.land(page(listOf(30L), nextBefore = null), pull, visible))
    }

    // MARK: - Page-ins, removals, abandoning

    @Test
    fun testAFailedPageInUnderRowsKeepsThemAndCanBeRetried() {
        val paging = loaded(supersedes = false)
        val more = paging.loadMore()!!
        paging.land(null, more, visible)
        assertEquals(10, paging.items.size)
        assertNull(paging.placeholder)
        assertEquals(FeedCursor(beforeMessage = 21), paging.loadMore()?.cursor, "not latched at the end")
    }

    @Test
    fun testRemovingTheLastRowAfterAFailedPullIsEmptyNotAnError() {
        val paging = FeedPaging(supersedes = false)
        val first = paging.reload()!!
        paging.land(page(listOf(1L), nextBefore = null), first, visible)
        val pull = paging.reload()!!
        paging.land(null, pull, visible)
        assertEquals(true, paging.remove(messageId = 1, prefetchWindow = 8)?.rowsChanged)
        assertEquals(FeedPaging.Placeholder.Empty, paging.placeholder)
        assertNull(paging.remove(messageId = 1, prefetchWindow = 8), "already gone")
    }

    /**
     * A removal that empties the list while a page is in flight can't page — and must not spend
     * a hop on the fetch it couldn't issue. The page in flight settles the list when it lands.
     */
    @Test
    fun testARemovalDuringALoadSpendsNoHop() {
        val paging = FeedPaging(supersedes = false)
        val first = paging.reload()!!
        paging.land(page(listOf(1L), nextBefore = 1), first, visible)
        val more = paging.loadMore()!!
        assertNull(paging.remove(messageId = 1, prefetchWindow = 8)?.next)
        assertEquals(FeedPaging.Placeholder.Loading, paging.placeholder, "a page is still on its way")
        assertEquals(FeedPaging.maxFruitlessHops, hopsUntilItStops(paging, from = more, at = 500))
    }

    /** A removal from a long list doesn't page: the next scroll into the prefetch window asks. */
    @Test
    fun testARemovalFromALongListDoesNotPage() {
        val paging = loaded(supersedes = false)
        val landing = paging.remove(messageId = 30, prefetchWindow = 8)
        assertEquals(true, landing?.rowsChanged)
        assertNull(landing?.next, "nine rows left, outside the window")
        assertNull(paging.placeholder)
        assertEquals(FeedCursor(beforeMessage = 21), paging.loadMore()?.cursor, "the cursor is still live")
    }

    @Test
    fun testARemovalThatRunsTheListShortPages() {
        val paging = loaded(supersedes = false)
        assertNull(paging.remove(messageId = 30, prefetchWindow = 8)?.next)
        assertEquals(
            FeedCursor(beforeMessage = 21),
            paging.remove(messageId = 29, prefetchWindow = 8)?.next?.cursor,
            "eight rows left: inside the window, where a scroll would have asked",
        )
        assertNull(paging.placeholder, "topping up beneath rows is silent")
    }

    /**
     * A swipe is not a page an ignore rule emptied: the page a removal asks for leaves the whole
     * skip-ahead budget for the fruitless pages after it.
     */
    @Test
    fun testARemovalSpendsNoHop() {
        val paging = loaded(supersedes = false)
        paging.remove(messageId = 30, prefetchWindow = 8)
        val fetch = paging.remove(messageId = 29, prefetchWindow = 8)!!.next!!
        assertEquals(FeedPaging.maxFruitlessHops, hopsUntilItStops(paging, from = fetch, at = 20))
    }

    @Test
    fun testAnAbandonedFetchCannotLand() {
        val paging = FeedPaging(supersedes = true)
        val first = paging.reload()!!
        paging.abandon()
        assertFalse(paging.isLoading)
        assertNull(paging.land(page(listOf(1L), nextBefore = null), first, visible))
        assertEquals(FeedPaging.Placeholder.Loading, paging.placeholder, "left alone: the user simply left")
        val again = paging.reload()!!
        paging.land(page(listOf(2L), nextBefore = null), again, visible)
        assertEquals(listOf(2L), ids(paging))
    }

    @Test
    fun testAbandoningOnlyAsksAgainWhenNothingIsOnScreen() {
        // A cold load abandoned leaves no answer; a page-in abandoned under rows does — asking
        // again on return would swap the reader's scrolled pages for page one.
        val cold = FeedPaging(supersedes = true)
        cold.reload()
        assertTrue(cold.abandon())

        val paged = loaded(supersedes = true)
        assertNotNull(paged.loadMore())
        assertFalse(paged.abandon())
        assertFalse(paged.items.isEmpty())
    }
}
