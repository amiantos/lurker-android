// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedAnswer
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.FeedPaging
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A feed's paging rules — lurker-ios's `HistoryFeedViewController`, the half that isn't a table. */
class FeedPagerTest {

    private fun item(id: Long, nick: String = "alice") =
        HighlightItem(Message(id = id, type = EventType.Message, nick = nick, text = "line $id", msgid = "m$id"), 1, "#lurker", "Libera")

    private fun page(vararg ids: Long, next: Long? = null) = HighlightsPage(ids.map { item(it) }, nextBefore = next)

    /** Hides every line from "spammer" — the ignore filter's shape. */
    private val hidingSpam: (List<HighlightItem>) -> List<HighlightItem> = { items -> items.filter { it.message.nick != "spammer" } }

    @Test
    fun aColdLoadSpinsThenShowsItsRows() {
        val pager = FeedPager(supersedes = false)
        val first = pager.reload()!!
        assertEquals(FeedPaging.Placeholder.Loading, pager.placeholder)
        assertNull(pager.land(first, page(3, 2, 1, next = 1)))
        assertEquals(listOf(3L, 2L, 1L), pager.items.map { it.message.id })
        assertNull(pager.placeholder)
    }

    @Test
    fun anEmptyAnswerAndAFailedOneAreDifferentPlaceholders() {
        val empty = FeedPager(supersedes = false)
        empty.land(empty.reload()!!, page())
        assertEquals(FeedPaging.Placeholder.Empty, empty.placeholder)

        val failed = FeedPager(supersedes = false)
        failed.land(failed.reload()!!, null)
        assertEquals(FeedPaging.Placeholder.Error, failed.placeholder)
    }

    @Test
    fun aSecondPullWhileLoadingIsDroppedAndEndsItsSpinner() {
        val pager = FeedPager(supersedes = false)
        pager.reload()
        assertNull(pager.reload(byPull = true))
        assertFalse(pager.refreshing)
    }

    @Test
    fun aPullSpinsItsOwnSpinnerOverRowsAlreadyShown() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(2, 1))
        val pull = pager.reload(byPull = true)!!
        assertTrue(pager.refreshing)
        // The rows stay up under the pull's spinner, rather than blanking to the page's.
        assertNull(pager.placeholder)
        pager.land(pull, page(4, 3))
        assertFalse(pager.refreshing)
        assertEquals(listOf(4L, 3L), pager.items.map { it.message.id })
    }

    @Test
    fun aSupersedingReloadDropsTheAnswerToTheOldQuestion() {
        val pager = FeedPager(supersedes = true)
        val old = pager.reload()!!
        val new = pager.reload()!!
        assertFalse(pager.isCurrent(old))
        assertNull(pager.land(old, page(9)))
        assertTrue(pager.items.isEmpty())
        // Still loading the new question: the stale answer didn't clear the flag, so no scroll can page
        // the old query's cursor into the new list.
        assertTrue(pager.isLoading)
        pager.land(new, page(1))
        assertEquals(listOf(1L), pager.items.map { it.message.id })
    }

    @Test
    fun aPageInLandingAfterAPullIsNotSplicedOntoTheNewList() {
        val pager = FeedPager(supersedes = true)
        pager.land(pager.reload()!!, page(5, 4, next = 4))
        val more = pager.loadMore()!!
        val pull = pager.reload(byPull = true)!!
        assertNull(pager.land(more, page(3, 2)))
        pager.land(pull, page(6, 5))
        assertEquals(listOf(6L, 5L), pager.items.map { it.message.id })
    }

    @Test
    fun pagingNeedsACursorAndNothingInFlight() {
        val pager = FeedPager(supersedes = false)
        assertNull(pager.loadMore())
        pager.land(pager.reload()!!, page(5, 4, next = 4))
        val more = pager.loadMore()!!
        assertEquals(FeedCursor(beforeMessage = 4), more.cursor)
        assertNull(pager.loadMore())
        pager.land(more, page(3))
        // No `next`: the end.
        assertNull(pager.loadMore())
        assertEquals(listOf(5L, 4L, 3L), pager.items.map { it.message.id })
    }

    @Test
    fun theActivityFeedsTwoCursorsGoBackExactlyAsTheyCame() {
        val pager = FeedPager(supersedes = false)
        val cursor = FeedCursor(beforeMessage = 40, beforeReaction = 7)
        pager.land(pager.reload()!!, HighlightsPage(listOf(item(41)), next = cursor))
        assertEquals(cursor, pager.loadMore()!!.cursor)
    }

    @Test
    fun aScrollAsksWithinEightRowsOfTheEnd() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(*(20L downTo 1L).toList().toLongArray()))
        assertFalse(pager.wantsMore(11))
        assertTrue(pager.wantsMore(12))
    }

    @Test
    fun aPageThatFiltersToNothingPagesPastItself() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        val first = pager.reload()!!
        val spam = HighlightsPage(listOf(item(5, "spammer"), item(4, "spammer")), nextBefore = 4)
        val next = pager.land(first, spam)
        assertNotNull(next)
        assertEquals(FeedCursor(beforeMessage = 4), next!!.cursor)
        // Nothing to look at yet, so it still says it's loading rather than empty.
        assertEquals(FeedPaging.Placeholder.Loading, pager.placeholder)
        assertNull(pager.land(next, page(3)))
        assertEquals(listOf(3L), pager.items.map { it.message.id })
        assertNull(pager.placeholder)
    }

    @Test
    fun aRowsWorthOfIgnoredPagesStillPagesPastThemBeneathTheRows() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        pager.land(pager.reload()!!, page(9, next = 9))
        val more = pager.loadMore()!!
        val next = pager.land(more, HighlightsPage(listOf(item(8, "spammer")), nextBefore = 8))
        assertNotNull(next)
        // Quietly: the rows already up stay up, no spinner over them.
        assertNull(pager.placeholder)
    }

    @Test
    fun fruitlessHopsStopAfterTenAndSayEmpty() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        var cursor = 1_000L
        fun spamPage() = HighlightsPage(listOf(item(cursor, "spammer")), nextBefore = --cursor)
        var next: FeedPaging.Fetch? = pager.land(pager.reload()!!, spamPage())
        var hops = 0
        while (next != null) {
            hops += 1
            next = pager.land(next, spamPage())
        }
        assertEquals(MAX_FRUITLESS_HOPS, hops)
        assertEquals(FeedPaging.Placeholder.Empty, pager.placeholder)
        // Not lost: the next scroll or pull asks again.
        assertNotNull(pager.loadMore())
    }

    @Test
    fun aFailedPageInKeepsTheRowsButAnEmptyListSaysItFailed() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(2, next = 2))
        pager.land(pager.loadMore()!!, null)
        assertEquals(listOf(2L), pager.items.map { it.message.id })
        assertNull(pager.placeholder)
        // The list ends in a retry row, since no row will come on screen to ask again…
        assertNotNull(pager.snapshot().failureUnderRows)
        // …and it isn't latched at the end: asking again works, and takes the retry row down.
        assertNotNull(pager.loadMore())
        assertNull(pager.snapshot().failureUnderRows)

        val emptied = FeedPager(supersedes = false, visible = hidingSpam)
        val next = emptied.land(emptied.reload()!!, HighlightsPage(listOf(item(2, "spammer")), nextBefore = 2))!!
        emptied.land(next, null)
        assertEquals(FeedPaging.Placeholder.Error, emptied.placeholder)
    }

    @Test
    fun removingByIdSurvivesTheListMovingAndEmptyingIsNotAFailure() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(3, 2))
        // A refresh that failed while rows were up latches the failure…
        pager.land(pager.reload(byPull = true)!!, null)
        assertNull(pager.remove(99))
        pager.remove(3)
        assertEquals(listOf(2L), pager.items.map { it.message.id })
        pager.remove(2)
        // …but clearing the list by hand is the empty state, not "Couldn't load".
        assertEquals(FeedPaging.Placeholder.Empty, pager.placeholder)
    }

    @Test
    fun aNewQuestionClearsTheOldAnswerAndAFailureSaysSo() {
        val pager = FeedPager(supersedes = true)
        pager.land(pager.reload()!!, page(5, 4, next = 4))
        val next = pager.reload(newQuestion = true)!!
        // The old query's rows never stand under the new one.
        assertTrue(pager.items.isEmpty())
        assertEquals(FeedPaging.Placeholder.Loading, pager.placeholder)
        pager.land(next, null)
        assertEquals(FeedPaging.Placeholder.Error, pager.placeholder)
        // And the old cursor is gone: no scroll can page it under the new question.
        assertNull(pager.loadMore())
    }

    @Test
    fun aPullOfTheSameQuestionKeepsItsRowsAndCursor() {
        val pager = FeedPager(supersedes = true)
        pager.land(pager.reload()!!, page(5, 4, next = 4))
        pager.land(pager.reload(byPull = true)!!, null)
        assertEquals(listOf(5L, 4L), pager.items.map { it.message.id })
        assertNotNull(pager.loadMore())
    }

    @Test
    fun aReloadRestoresTheHopBudget() {
        val pager = FeedPager(supersedes = true, visible = hidingSpam)
        var cursor = 1_000L
        fun spamPage() = HighlightsPage(listOf(item(cursor, "spammer")), nextBefore = --cursor)
        var next: FeedPaging.Fetch? = pager.land(pager.reload()!!, spamPage())
        while (next != null) next = pager.land(next, spamPage())
        // Exhausted — and the next question can still page past an all-ignored first page.
        assertNotNull(pager.land(pager.reload(newQuestion = true)!!, spamPage()))
    }

    @Test
    fun aPullSupersedesAPageInEvenOnAnIdempotentFeed() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(5, 4, next = 4))
        val more = pager.loadMore()!!
        val pull = assertNotNullAndGet(pager.reload(byPull = true))
        assertNull(pager.land(more, page(3)))
        pager.land(pull, page(6, 5))
        assertEquals(listOf(6L, 5L), pager.items.map { it.message.id })
    }

    @Test
    fun aRemovalOutlivesAReloadAlreadyInFlight() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(3, 2, 1))
        val inFlight = pager.reload(byPull = true)!!
        pager.remove(2)
        // Answered from before the removal: the row is still in it, and stays gone.
        pager.land(inFlight, page(3, 2, 1))
        assertEquals(listOf(3L, 1L), pager.items.map { it.message.id })
        // A reload asked after the removal is the server's word: if it lists the row, it's there.
        pager.land(pager.reload(byPull = true)!!, page(3, 2, 1))
        assertEquals(listOf(3L, 2L, 1L), pager.items.map { it.message.id })
    }

    @Test
    fun aRemovalPagesOnlyWhenTheListRunsShort() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(*(20L downTo 1L).toList().toLongArray(), next = 1))
        // Plenty left: nothing fetched, nothing spent.
        assertNull(pager.remove(20))
        assertFalse(pager.isLoading)
        val short = FeedPager(supersedes = false)
        short.land(short.reload()!!, page(3, 2, next = 2))
        assertEquals(FeedCursor(beforeMessage = 2), short.remove(3)?.cursor)
    }

    @Test
    fun eachLandedFirstPageIsANewEpoch() {
        val pager = FeedPager(supersedes = false)
        assertEquals(0, pager.snapshot().epoch)
        pager.land(pager.reload()!!, page(1))
        assertEquals(1, pager.snapshot().epoch)
        pager.land(pager.reload(byPull = true)!!, null)
        assertEquals(1, pager.snapshot().epoch)
    }

    @Test
    fun aRemovalThatEmptiesTheListMidLoadSaysLoading() {
        // Deliberate change with the move onto FeedPaging: this used to say Empty until the page landed.
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(1, next = 1))
        val more = pager.loadMore()!!
        assertNull("the page in flight settles the list when it lands", pager.remove(1))
        assertEquals("a page is still on its way", FeedPaging.Placeholder.Loading, pager.placeholder)
        pager.land(more, page())
        assertEquals(FeedPaging.Placeholder.Empty, pager.placeholder)
    }

    @Test
    fun aRemovalThatDoesNotPageLeavesTheNextScrollItsCursor() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(*(20L downTo 11L).toList().toLongArray(), next = 11))
        assertNull(pager.remove(20))
        assertFalse(pager.isLoading)
        assertNull(pager.placeholder)
        // The hop LurkerKit asked for was given back, so the next scroll's page lands like any other.
        val more = pager.loadMore()!!
        assertEquals(FeedCursor(beforeMessage = 11), more.cursor)
        pager.land(more, page(10, 9))
        assertEquals((19L downTo 9L).toList(), pager.items.map { it.message.id })
    }

    @Test
    fun aRemovalPagesEvenWhenTheHopBudgetIsSpent() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        pager.land(pager.reload()!!, page(1000, next = 1000))
        var cursor = 999L
        fun spamPage() = HighlightsPage(listOf(item(cursor, "spammer")), nextBefore = --cursor)
        var next: FeedPaging.Fetch? = pager.loadMore()
        while (next != null) next = pager.land(next, spamPage())
        // The budget is spent with the one row up and a live cursor; removing it still asks.
        val asked = pager.remove(1000)
        assertNotNull("a swipe is not a fruitless page", asked)
        assertEquals(FeedPaging.Placeholder.Loading, pager.placeholder)
        pager.land(asked!!, page(5))
        assertEquals(listOf(5L), pager.items.map { it.message.id })
    }

    @Test
    fun aRemovalStaysGoneFromAPageInAfterItsHopWasGivenBack() {
        val pager = FeedPager(supersedes = false)
        val rows = (30L downTo 11L).toList().toLongArray()
        pager.land(pager.reload()!!, page(*rows, next = 11))
        val inFlight = pager.reload(byPull = true)!!
        pager.remove(15)
        pager.land(inFlight, page(*rows, next = 11))
        assertFalse(15L in pager.items.map { it.message.id })
        // A removal while nothing is in flight gives LurkerKit's hop back, which moves its generation:
        // the next page-in still leaves the removed row out.
        assertNull(pager.remove(14))
        pager.land(pager.loadMore()!!, page(14, 10))
        val ids = pager.items.map { it.message.id }
        assertFalse(14L in ids)
        assertEquals(10L, ids.last())
    }

    @Test
    fun aHopAfterAFailedPageInTakesTheRetryRowDown() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        pager.land(pager.reload()!!, page(9, next = 9))
        pager.land(pager.loadMore()!!, null)
        assertNotNull(pager.snapshot().failureUnderRows)
        val retry = pager.loadMore()!!
        assertNull(pager.snapshot().failureUnderRows)
        val hop = pager.land(retry, HighlightsPage(listOf(item(8, "spammer")), nextBefore = 8))
        assertNotNull(hop)
        assertNull(pager.snapshot().failureUnderRows)
    }

    // A failure says so, and keeps the rows (lurker#1308)

    @Test
    fun aFailedPullKeepsTheRowsAndSaysWhyUnderThem() {
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(3, 2, next = 2))
        val pull = pager.reload(byPull = true)!!
        assertNull(pager.land(pull, FeedAnswer.Unavailable(reason = "search took too long")))
        val shown = pager.snapshot()
        assertEquals(listOf(3L, 2L), shown.items.map { it.message.id })
        assertNull(shown.placeholder)
        assertFalse("the pull's spinner ends, failed or not", shown.refreshing)
        // The server's words, as a sentence; the feed's own when it gave none.
        assertEquals("Search took too long.", shown.failureUnderRows?.sentence(fallback = "Couldn't load activity."))
        pager.land(pager.reload(byPull = true)!!, FeedAnswer.Failed)
        assertEquals("Couldn't load activity.", pager.snapshot().failureUnderRows?.sentence(fallback = "Couldn't load activity."))
        // The cursor the rows had is still theirs: a failed pull asked the same question.
        assertEquals(FeedCursor(beforeMessage = 2), pager.loadMore()?.cursor)
    }

    @Test
    fun aFailedOpenSaysTheServersReasonInThePlaceholder() {
        val error = StateModel("Couldn't search", StateSymbol.Warning, "Pull to try again, or edit your search.")
        val pager = FeedPager(supersedes = true)
        pager.land(pager.reload()!!, FeedAnswer.Unavailable(reason = "too many searches are waiting"))
        val shown = pager.snapshot()
        assertEquals(FeedPaging.Placeholder.Error, shown.placeholder)
        // No rows, so nothing to put a retry row under: the placeholder says it.
        assertNull(shown.failureUnderRows)
        assertEquals(error.copy(subtitle = "Too many searches are waiting. Pull to try again."), shown.errorWords(error))
        // With no reason given, the feed's own words stand.
        pager.land(pager.reload()!!, FeedAnswer.Failed)
        assertEquals(error, pager.snapshot().errorWords(error))
        pager.land(pager.reload()!!, FeedAnswer.Unavailable(reason = null))
        assertEquals(error, pager.snapshot().errorWords(error))
    }

    @Test
    fun tryAgainRepeatsTheFetchThatFailed() {
        // A failed pull: the newest page again, with the rows kept and no pull spinner put up.
        val pulled = FeedPager(supersedes = false)
        pulled.land(pulled.reload()!!, page(3, 2, next = 2))
        pulled.land(pulled.reload(byPull = true)!!, FeedAnswer.Unavailable(reason = "search took too long"))
        val again = pulled.retry()!!
        assertTrue(again.isFirstPage)
        assertNull("asking again takes the row down", pulled.snapshot().failure)
        assertEquals(listOf(3L, 2L), pulled.items.map { it.message.id })
        assertFalse(pulled.snapshot().refreshing)
        val before = pulled.snapshot().epoch
        pulled.land(again, page(5, 4))
        assertEquals(listOf(5L, 4L), pulled.items.map { it.message.id })
        assertEquals(before + 1, pulled.snapshot().epoch)

        // A failed page-in: the same older page again.
        val paged = FeedPager(supersedes = false)
        paged.land(paged.reload()!!, page(3, 2, next = 2))
        paged.land(paged.loadMore()!!, FeedAnswer.Failed)
        assertEquals(FeedCursor(beforeMessage = 2), paged.retry()?.cursor)

        // Nothing failed: nothing to retry.
        val fine = FeedPager(supersedes = false)
        fine.land(fine.reload()!!, page(3, 2, next = 2))
        assertNull(fine.retry())
    }

    @Test
    fun aRowRemovedWhileARetryIsOutStaysRemovedWhenItLands() {
        // A retry of a first page is a reload like any other to the removal ledger: it was asked
        // before the removal, so its page still holds the row.
        val pager = FeedPager(supersedes = false)
        pager.land(pager.reload()!!, page(3, 2))
        pager.land(pager.reload(byPull = true)!!, FeedAnswer.Failed)
        val again = pager.retry()!!
        pager.remove(3)
        pager.land(again, page(3, 2))
        assertEquals(listOf(2L), pager.items.map { it.message.id })
    }

    @Test
    fun theServers503ForADisplacedSearchIsNeverShown() {
        // The server answers a search this client replaced with 503 "search superseded". By then a
        // newer question owns the list, so that answer lands nowhere: no error, no retry row.
        val pager = FeedPager(supersedes = true)
        pager.land(pager.reload()!!, page(3, 2, next = 2))
        val stale = pager.reload(newQuestion = true)!!
        val current = pager.reload(newQuestion = true)!!
        assertNull(pager.land(stale, FeedAnswer.Unavailable(reason = "search superseded")))
        assertNull(pager.snapshot().failure)
        assertEquals(FeedPaging.Placeholder.Loading, pager.snapshot().placeholder)
        assertTrue(pager.isLoading)
        // And a superseded page-in's failure leaves a pull's rows alone.
        pager.land(current, page(9, 8, next = 8))
        val older = pager.loadMore()!!
        pager.reload(byPull = true)
        assertNull(pager.land(older, FeedAnswer.Failed))
        assertNull(pager.snapshot().failure)
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        /** LurkerKit's `FeedPaging.maxFruitlessHops`, which is internal to the kit. */
        const val MAX_FRUITLESS_HOPS = 10
    }
}
