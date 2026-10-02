// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.BufferListPlaceholder
import kotlin.test.Test
import kotlin.test.assertEquals

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

    // Waiting on LurkerStore, ServerFrame, FrameParser: testASnapshotAloneDoesNotMeanTheRosterLanded,
    // testBacklogCompleteLatchesTheRoster, testBacklogCompleteSurvivesAReconnect,
    // testResetClearsBacklogComplete, testTheTerminalFrameParses
}
