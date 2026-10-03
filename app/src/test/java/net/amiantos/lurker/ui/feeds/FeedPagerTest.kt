// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedCursor
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
        assertEquals(FeedPlaceholder.Loading, pager.placeholder)
        assertNull(pager.firstPage(first, page(3, 2, 1, next = 1)))
        assertEquals(listOf(3L, 2L, 1L), pager.items.map { it.message.id })
        assertNull(pager.placeholder)
    }

    @Test
    fun anEmptyAnswerAndAFailedOneAreDifferentPlaceholders() {
        val empty = FeedPager(supersedes = false)
        empty.firstPage(empty.reload()!!, page())
        assertEquals(FeedPlaceholder.Empty, empty.placeholder)

        val failed = FeedPager(supersedes = false)
        failed.firstPage(failed.reload()!!, null)
        assertEquals(FeedPlaceholder.Error, failed.placeholder)
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
        pager.firstPage(pager.reload()!!, page(2, 1))
        val pull = pager.reload(byPull = true)!!
        assertTrue(pager.refreshing)
        // The rows stay up under the pull's spinner, rather than blanking to the page's.
        assertNull(pager.placeholder)
        pager.firstPage(pull, page(4, 3))
        assertFalse(pager.refreshing)
        assertEquals(listOf(4L, 3L), pager.items.map { it.message.id })
    }

    @Test
    fun aSupersedingReloadDropsTheAnswerToTheOldQuestion() {
        val pager = FeedPager(supersedes = true)
        val old = pager.reload()!!
        val new = pager.reload()!!
        assertFalse(pager.isCurrent(old))
        assertNull(pager.firstPage(old, page(9)))
        assertTrue(pager.items.isEmpty())
        // Still loading the new question: the stale answer didn't clear the flag, so no scroll can page
        // the old query's cursor into the new list.
        assertTrue(pager.isLoading)
        pager.firstPage(new, page(1))
        assertEquals(listOf(1L), pager.items.map { it.message.id })
    }

    @Test
    fun aPageInLandingAfterAPullIsNotSplicedOntoTheNewList() {
        val pager = FeedPager(supersedes = true)
        pager.firstPage(pager.reload()!!, page(5, 4, next = 4))
        val more = pager.loadMore()!!
        val pull = pager.reload(byPull = true)!!
        assertNull(pager.appendPage(more, page(3, 2)))
        pager.firstPage(pull, page(6, 5))
        assertEquals(listOf(6L, 5L), pager.items.map { it.message.id })
    }

    @Test
    fun pagingNeedsACursorAndNothingInFlight() {
        val pager = FeedPager(supersedes = false)
        assertNull(pager.loadMore())
        pager.firstPage(pager.reload()!!, page(5, 4, next = 4))
        val more = pager.loadMore()!!
        assertEquals(FeedCursor(beforeMessage = 4), more.cursor)
        assertNull(pager.loadMore())
        pager.appendPage(more, page(3))
        // No `next`: the end.
        assertNull(pager.loadMore())
        assertEquals(listOf(5L, 4L, 3L), pager.items.map { it.message.id })
    }

    @Test
    fun theActivityFeedsTwoCursorsGoBackExactlyAsTheyCame() {
        val pager = FeedPager(supersedes = false)
        val cursor = FeedCursor(beforeMessage = 40, beforeReaction = 7)
        pager.firstPage(pager.reload()!!, HighlightsPage(listOf(item(41)), next = cursor))
        assertEquals(cursor, pager.loadMore()!!.cursor)
    }

    @Test
    fun aScrollAsksWithinEightRowsOfTheEnd() {
        val pager = FeedPager(supersedes = false)
        pager.firstPage(pager.reload()!!, page(*(20L downTo 1L).toList().toLongArray()))
        assertFalse(pager.wantsMore(11))
        assertTrue(pager.wantsMore(12))
    }

    @Test
    fun aPageThatFiltersToNothingPagesPastItself() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        val first = pager.reload()!!
        val spam = HighlightsPage(listOf(item(5, "spammer"), item(4, "spammer")), nextBefore = 4)
        val next = pager.firstPage(first, spam)
        assertNotNull(next)
        assertEquals(FeedCursor(beforeMessage = 4), next!!.cursor)
        // Nothing to look at yet, so it still says it's loading rather than empty.
        assertEquals(FeedPlaceholder.Loading, pager.placeholder)
        assertNull(pager.appendPage(next, page(3)))
        assertEquals(listOf(3L), pager.items.map { it.message.id })
        assertNull(pager.placeholder)
    }

    @Test
    fun aRowsWorthOfIgnoredPagesStillPagesPastThemBeneathTheRows() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        pager.firstPage(pager.reload()!!, page(9, next = 9))
        val more = pager.loadMore()!!
        val next = pager.appendPage(more, HighlightsPage(listOf(item(8, "spammer")), nextBefore = 8))
        assertNotNull(next)
        // Quietly: the rows already up stay up, no spinner over them.
        assertNull(pager.placeholder)
    }

    @Test
    fun fruitlessHopsStopAfterTenAndSayEmpty() {
        val pager = FeedPager(supersedes = false, visible = hidingSpam)
        var cursor = 1_000L
        fun spamPage() = HighlightsPage(listOf(item(cursor, "spammer")), nextBefore = --cursor)
        var next: FeedFetch.More? = pager.firstPage(pager.reload()!!, spamPage())
        var hops = 0
        while (next != null) {
            hops += 1
            next = pager.appendPage(next, spamPage())
        }
        assertEquals(FeedPager.MAX_FRUITLESS_HOPS, hops)
        assertEquals(FeedPlaceholder.Empty, pager.placeholder)
        // Not lost: the next scroll or pull asks again.
        assertNotNull(pager.loadMore())
    }

    @Test
    fun aFailedPageInKeepsTheRowsButAnEmptyListSaysItFailed() {
        val pager = FeedPager(supersedes = false)
        pager.firstPage(pager.reload()!!, page(2, next = 2))
        pager.appendPage(pager.loadMore()!!, null)
        assertEquals(listOf(2L), pager.items.map { it.message.id })
        assertNull(pager.placeholder)
        // Not latched at the end: the next scroll re-arms it.
        assertNotNull(pager.loadMore())

        val emptied = FeedPager(supersedes = false, visible = hidingSpam)
        val next = emptied.firstPage(emptied.reload()!!, HighlightsPage(listOf(item(2, "spammer")), nextBefore = 2))!!
        emptied.appendPage(next, null)
        assertEquals(FeedPlaceholder.Error, emptied.placeholder)
    }

    @Test
    fun removingByIdSurvivesTheListMovingAndEmptyingIsNotAFailure() {
        val pager = FeedPager(supersedes = false)
        pager.firstPage(pager.reload()!!, page(3, 2))
        // A refresh that failed while rows were up latches the failure…
        pager.firstPage(pager.reload(byPull = true)!!, null)
        assertNull(pager.remove(99))
        pager.remove(3)
        assertEquals(listOf(2L), pager.items.map { it.message.id })
        pager.remove(2)
        // …but clearing the list by hand is the empty state, not "Couldn't load".
        assertEquals(FeedPlaceholder.Empty, pager.placeholder)
    }
}
