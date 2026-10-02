// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.BufferListPlaceholder
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.SocketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the buffer list shows when it has no rows.
 *
 * The resolver is three lines; the interesting part is the *signal* it reads. Two plausible
 * ones are wrong in ways that only show on some accounts — the socket is up before the burst
 * is applied, and the `snapshot` frame isn't authoritative for buffer existence — so both are
 * pinned here as regressions rather than left to be re-derived.
 */
class BufferListPlaceholderTests {

    @Test
    fun testBuffersPresentMeansNoPlaceholder() {
        assertEquals(
            BufferListPlaceholder.None,
            BufferListPlaceholder.of(hasBuffers = true, hasNetworks = true, backlogComplete = true),
        )
        // Even mid-burst: rows on screen beat any placeholder.
        assertEquals(
            BufferListPlaceholder.None,
            BufferListPlaceholder.of(hasBuffers = true, hasNetworks = true, backlogComplete = false),
        )
    }

    @Test
    fun testNothingYetIsLoadingUntilTheBurstFinishes() {
        assertEquals(
            BufferListPlaceholder.Loading,
            BufferListPlaceholder.of(hasBuffers = false, hasNetworks = false, backlogComplete = false),
        )
        // Networks known but the burst still running — the DM-only / all-disconnected case.
        // This is the one that flashed when the flag was latched on the `snapshot` frame.
        assertEquals(
            BufferListPlaceholder.Loading,
            BufferListPlaceholder.of(hasBuffers = false, hasNetworks = true, backlogComplete = false),
        )
    }

    @Test
    fun testAFreshAccountIsToldToAddANetwork() {
        assertEquals(
            BufferListPlaceholder.NoNetworks,
            BufferListPlaceholder.of(hasBuffers = false, hasNetworks = false, backlogComplete = true),
        )
    }

    @Test
    fun testAnAccountWithNetworksIsNotToldToAddOne() {
        // A network that never connects has no buffers, and this is its steady state — not a
        // flash. Telling someone to add a network they've already added reads as the app not
        // knowing its own state.
        assertEquals(
            BufferListPlaceholder.NoBuffers,
            BufferListPlaceholder.of(hasBuffers = false, hasNetworks = true, backlogComplete = true),
        )
    }

    // MARK: - The signal itself

    /**
     * The `snapshot` frame must NOT be what latches this. Its per-network `channels` is empty
     * for every network without a live connection, and DMs and `:server:` logs are never in it
     * at all — so on a launch while the networks are still connecting it would claim the roster
     * had landed with nothing in it, and the list would flash "No buffers yet".
     */
    @Test
    fun testASnapshotAloneDoesNotMeanTheRosterLanded() {
        val store = LurkerStore()
        store.apply(ServerFrame.Snapshot(emptyList(), globalIgnores = emptyList(), maxUploadBytes = null))
        assertFalse(store.state.backlogComplete, "the snapshot is a prefix, not the whole answer")
    }

    @Test
    fun testBacklogCompleteLatchesTheRoster() {
        val store = LurkerStore()
        assertFalse(store.state.backlogComplete)

        store.apply(ServerFrame.BacklogComplete)
        assertTrue(store.state.backlogComplete, "an empty burst is still an answer")
    }

    /**
     * It has to survive a socket drop. A reconnect re-sends everything, but the roster we
     * already have stays on screen while it does — going back to `.loading` would blank a list
     * with perfectly good content in it.
     */
    @Test
    fun testBacklogCompleteSurvivesAReconnect() {
        val store = LurkerStore()
        store.apply(ServerFrame.BacklogComplete)

        store.apply(ServerFrame.SocketOpen)
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertEquals(SocketStatus.Reconnecting, store.state.connection)
        assertTrue(store.state.backlogComplete, "a drop doesn't un-answer the question")
    }

    /**
     * Sign-out has to clear it, or the next account's cold launch reads the previous one's
     * answer and shows "No networks yet" before its own burst lands.
     */
    @Test
    fun testResetClearsBacklogComplete() {
        val store = LurkerStore()
        store.apply(ServerFrame.BacklogComplete)
        assertTrue(store.state.backlogComplete)

        store.reset()
        assertFalse(store.state.backlogComplete)
    }

    /**
     * The frame carries no payload, so the only thing that can go wrong is not recognizing
     * its `kind` — in which case it parses as `Ignored` and the list spins forever.
     */
    @Test
    fun testTheTerminalFrameParses() {
        assertEquals(ServerFrame.BacklogComplete, FrameParser.parseWs("""{"kind":"backlog-complete"}"""))
    }
}
