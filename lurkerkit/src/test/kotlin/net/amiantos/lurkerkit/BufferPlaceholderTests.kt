// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.BufferPlaceholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the message list shows when it has no messages: still-loading vs. genuinely-empty,
 * which a blank list can't tell apart.
 */
class BufferPlaceholderTests {

    @Test
    fun testMessagesPresentMeansNoPlaceholder() {
        // Whatever else is true, if there are lines to show, show them.
        assertEquals(
            BufferPlaceholder.None,
            BufferPlaceholder.of(hasMessages = true, hydrated = false, hydratesOnDemand = true, bufferExists = false),
        )
        assertEquals(
            BufferPlaceholder.None,
            BufferPlaceholder.of(hasMessages = true, hydrated = true, hydratesOnDemand = false, bufferExists = true),
        )
    }

    // MARK: - On-demand buffers (channels, DMs) — key off `hydrated`

    @Test
    fun testOnDemandBufferLoadsUntilHydrated() {
        // A channel/DM arrives as a shell (the row exists!) and isn't read until its
        // open-buffer reply lands — so row-existence can't tell them apart, only hydration.
        assertEquals(
            BufferPlaceholder.Loading,
            BufferPlaceholder.of(hasMessages = false, hydrated = false, hydratesOnDemand = true, bufferExists = true),
        )
    }

    @Test
    fun testOnDemandBufferHydratedButEmptyIsEmpty() {
        // The server read the history and there was none — a just-joined channel. Real, not
        // a failure.
        assertEquals(
            BufferPlaceholder.Empty,
            BufferPlaceholder.of(hasMessages = false, hydrated = true, hydratesOnDemand = true, bufferExists = true),
        )
    }

    // MARK: - Off-demand buffers (system, server logs) — key off row existence

    @Test
    fun testOffDemandBufferLoadsUntilItsRowMaterializes() {
        // The system/server row is created BY its connect backlog. Before that the row is
        // absent — and the socket can already read `Connected`, so keying off the socket
        // would flash `Empty` on the launch screen in the gap. Row absent → still loading.
        assertEquals(
            BufferPlaceholder.Loading,
            BufferPlaceholder.of(hasMessages = false, hydrated = false, hydratesOnDemand = false, bufferExists = false),
        )
    }

    @Test
    fun testOffDemandBufferEmptyOnceItsRowExists() {
        // Backlog landed and the row is here. An empty system buffer looks exactly like
        // this.
        assertEquals(
            BufferPlaceholder.Empty,
            BufferPlaceholder.of(hasMessages = false, hydrated = true, hydratesOnDemand = false, bufferExists = true),
        )
    }

    @Test
    fun testEmptyServerLogIsEmptyNotStuckLoading() {
        // The regression guard: an empty `:server:` backlog never sets `hydrated` (the
        // server omits `hasMoreOlder`, the parser defaults it true) and `:server:` can't
        // hydrate on demand to fix that — so a `hydrated`-keyed rule would spin forever.
        // Row existence is what saves it.
        assertEquals(
            BufferPlaceholder.Empty,
            BufferPlaceholder.of(hasMessages = false, hydrated = false, hydratesOnDemand = false, bufferExists = true),
        )
    }

    // MARK: - historyLanded, shared with the unread banner's `dividerSeen` latch

    @Test
    fun testOnDemandHistoryLandsOnlyWhenHydrated() {
        // The stub an unread banner must not be judged against: the row is there, but what it
        // holds is the live events that outran the backlog, not the buffer.
        assertFalse(
            BufferPlaceholder.historyLanded(hydrated = false, hydratesOnDemand = true, bufferExists = true)
        )
        assertTrue(
            BufferPlaceholder.historyLanded(hydrated = true, hydratesOnDemand = true, bufferExists = true)
        )
    }

    @Test
    fun testOffDemandHistoryLandsWithTheRow() {
        // The other half of the regression guard above, and why the latch can't key off
        // `hydrated` raw: a `:server:` log can stay un-hydrated for its whole life, and a gate
        // that waited for it would hold the banner's retire-latch open all session.
        assertTrue(
            BufferPlaceholder.historyLanded(hydrated = false, hydratesOnDemand = false, bufferExists = true)
        )
        assertFalse(
            BufferPlaceholder.historyLanded(hydrated = false, hydratesOnDemand = false, bufferExists = false)
        )
    }

    // MARK: - A server log with no row

    @Test
    fun testAnAbsentServerLogWaitsWhileTheBurstIsStillRunning() {
        // Mid-burst its row may still be on its way, so "loading" is the honest answer.
        assertEquals(
            BufferPlaceholder.Loading,
            BufferPlaceholder.of(
                hasMessages = false, hydrated = false, hydratesOnDemand = false,
                bufferExists = false, rosterSettled = false,
            ),
        )
    }

    @Test
    fun testAnAbsentServerLogIsEmptyOnceTheBurstHasFinished() {
        // ⚠⚠ Otherwise it spins forever. The buffer list now offers a network's log whether
        // or not a row has arrived — the web always has, its network header being that
        // buffer — so "no row" is something a reader can be looking at, and a server buffer
        // can't hydrate on demand to correct itself.
        assertEquals(
            BufferPlaceholder.Empty,
            BufferPlaceholder.of(
                hasMessages = false, hydrated = false, hydratesOnDemand = false,
                bufferExists = false, rosterSettled = true,
            ),
        )
    }

    @Test
    fun testASettledRosterChangesNothingForAChannel() {
        // On-demand kinds still key off `hydrated` alone: a channel shell exists long before
        // its history, and reading a settled roster as "landed" would call it empty while its
        // hydrate reply is in flight.
        assertEquals(
            BufferPlaceholder.Loading,
            BufferPlaceholder.of(
                hasMessages = false, hydrated = false, hydratesOnDemand = true,
                bufferExists = true, rosterSettled = true,
            ),
        )
    }
}
