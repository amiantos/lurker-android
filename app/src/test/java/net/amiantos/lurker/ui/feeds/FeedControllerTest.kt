// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller runs the pager's fetches: cancels what's superseded, follows a top-up. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedControllerTest {

    private fun item(id: Long, nick: String = "alice") =
        HighlightItem(Message(id = id, type = EventType.Message, nick = nick, text = "line $id", msgid = "m$id"), 1, "#lurker", "Libera")

    /** A fetch that waits to be answered, recording what it was asked and whether it was cancelled. */
    private class Server {
        val asked = mutableListOf<FeedCursor?>()
        val pending = mutableListOf<CompletableDeferred<HighlightsPage?>>()
        var cancelled = 0

        suspend fun fetch(cursor: FeedCursor?): HighlightsPage? {
            asked += cursor
            val answer = CompletableDeferred<HighlightsPage?>()
            pending += answer
            try {
                return answer.await()
            } catch (e: CancellationException) {
                cancelled += 1
                throw e
            }
        }
    }

    private fun TestScope.controller(server: Server, supersedes: Boolean, visible: (List<HighlightItem>) -> List<HighlightItem> = { it }) =
        FeedController(this.backgroundScope, supersedes, visible, server::fetch)

    @Test
    fun aSupersedingReloadCancelsTheRequestInFlight() = runTest {
        val server = Server()
        val feed = controller(server, supersedes = true)
        feed.reload()
        runCurrent()
        feed.reload()
        runCurrent()
        assertEquals(1, server.cancelled)
        assertEquals(2, server.asked.size)
        server.pending.last().complete(HighlightsPage(listOf(item(2)), nextBefore = null))
        runCurrent()
        assertEquals(listOf(2L), feed.snapshot.items.map { it.message.id })
        assertNull(feed.snapshot.placeholder)
    }

    @Test
    fun aRepeatPullOnAnIdempotentFeedAsksNothingNew() = runTest {
        val server = Server()
        val feed = controller(server, supersedes = false)
        feed.reload()
        runCurrent()
        feed.reload(byPull = true)
        runCurrent()
        assertEquals(1, server.asked.size)
        assertEquals(0, server.cancelled)
    }

    @Test
    fun aPageOfIgnoredLinesFetchesTheNextByItself() = runTest {
        val server = Server()
        val feed = controller(server, supersedes = false) { items -> items.filter { it.message.nick != "spammer" } }
        feed.reload()
        runCurrent()
        server.pending[0].complete(HighlightsPage(listOf(item(9, "spammer")), nextBefore = 9))
        runCurrent()
        assertEquals(listOf(null, FeedCursor(beforeMessage = 9)), server.asked)
        server.pending[1].complete(HighlightsPage(listOf(item(8)), nextBefore = null))
        runCurrent()
        assertEquals(listOf(8L), feed.snapshot.items.map { it.message.id })
        assertNull(feed.snapshot.placeholder)
    }

    @Test
    fun scrollingNearTheEndPagesInOnce() = runTest {
        val server = Server()
        val feed = controller(server, supersedes = false)
        feed.reload()
        runCurrent()
        server.pending[0].complete(HighlightsPage((20L downTo 11L).map { item(it) }, nextBefore = 11))
        runCurrent()
        feed.scrolledTo(9)
        feed.scrolledTo(9)
        runCurrent()
        assertEquals(listOf(null, FeedCursor(beforeMessage = 11)), server.asked)
        server.pending[1].complete(null)
        runCurrent()
        // A failed page-in leaves the rows up.
        assertEquals(10, feed.snapshot.items.size)
        assertTrue(feed.snapshot.placeholder == null)
    }

    @Test
    fun aPullCancelsAPageInAndReloads() = runTest {
        val server = Server()
        val feed = controller(server, supersedes = false)
        feed.reload()
        runCurrent()
        server.pending[0].complete(HighlightsPage((20L downTo 11L).map { item(it) }, nextBefore = 11))
        runCurrent()
        feed.scrolledTo(9)
        runCurrent()
        feed.reload(byPull = true)
        runCurrent()
        assertEquals(1, server.cancelled)
        assertEquals(listOf(null, FeedCursor(beforeMessage = 11), null), server.asked)
    }

    @Test
    fun theRetryRowAsksForTheFailedPageAgain() = runTest {
        val server = Server()
        val feed = controller(server, supersedes = false)
        feed.reload()
        runCurrent()
        server.pending[0].complete(HighlightsPage((20L downTo 11L).map { item(it) }, nextBefore = 11))
        runCurrent()
        feed.scrolledTo(9)
        runCurrent()
        server.pending[1].complete(null)
        runCurrent()
        assertTrue(feed.snapshot.pageInFailed)
        feed.retry()
        runCurrent()
        assertEquals(FeedCursor(beforeMessage = 11), server.asked.last())
        assertTrue(!feed.snapshot.pageInFailed)
    }
}
