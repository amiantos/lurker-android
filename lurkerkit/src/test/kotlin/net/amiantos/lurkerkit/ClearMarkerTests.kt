// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `/clear` marker (lurker-ios#121): what the wire says, what the store keeps, and what the
 * row builder draws.
 *
 * `/clear` is not a local wipe. It is a per-user, per-buffer boundary the SERVER holds, so
 * the marker is shared with every other device and nothing is ever deleted — which is why
 * most of what matters here is that the hidden messages are still present and reachable.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Clear marker"), where each case carries a
 * display name. The method names are kept and the display name is the comment above each.
 */
class ClearMarkerTests {

    private val clearedAt: Instant = Instant.ofEpochSecond(1_784_548_800)

    // MARK: - The wire

    // MARK: - The store

    // MARK: - The rows

    // MARK: - Paging past the boundary

    private fun cleared(beforeId: Long, detached: Boolean = false): Buffer =
        Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true)
            .copy(hasMoreNewer = detached)
            .applyCleared(beforeId = beforeId, at = clearedAt)

    // "⚠⚠ paging older stops once the cursor reaches the clear boundary"
    @Test
    fun pagingStopsAtTheBoundary() {
        // The ~5s "Loading messages…" QA saw after /clear on iOS. A cleared buffer builds to one
        // divider, which is unscrollable, which asks for another page, which is also entirely
        // hidden, which is still unscrollable — walking the whole buffer into memory behind a
        // stuck spinner.
        assertFalse(cleared(50).olderPageCouldBeVisible(oldestHeldId = 10))
        assertFalse(cleared(50).olderPageCouldBeVisible(oldestHeldId = 50), "the boundary is hidden too")
    }

    // "but it still pages while there is visible history between the two"
    @Test
    fun pagingContinuesAboveTheBoundary() {
        // A clear anchors at the tail, so new messages accumulate above it; once the held slice
        // starts above the boundary there is a visible gap worth fetching.
        assertTrue(cleared(50).olderPageCouldBeVisible(oldestHeldId = 51))
    }

    // "an uncleared buffer pages normally"
    @Test
    fun anUnclearedBufferPages() {
        assertTrue(cleared(0).olderPageCouldBeVisible(oldestHeldId = 1))
    }

    // "and a detached buffer pages, because it ignores the marker"
    @Test
    fun aDetachedBufferPages() {
        assertTrue(cleared(50, detached = true).olderPageCouldBeVisible(oldestHeldId = 10))
    }

    // "so does a revealed one — the reader can scroll up through what was hidden"
    @Test
    fun aRevealedBufferPages() {
        assertTrue(
            cleared(50).olderPageCouldBeVisible(oldestHeldId = 10, showingClearedHistory = true)
        )
    }

    // MARK: - Jumping to a hidden message

    // MARK: - The command

    // Waiting on FrameParser, ServerFrame: backlogCarriesTheMarker, backlogWithoutAMarker,
    // bufferClearedParses, undoParses, aHalfStatedMarkerIsDiscarded
    //
    // Waiting on LurkerStore, ChatState, ServerFrame (and the private `clearedBuffer` helper):
    // fanOutMovesTheMarker, undoDropsBothHalves, aClearForAnUnknownBufferIsIgnored,
    // aBacklogRetractsTheMarker, aClearDropsLocalLines, anUndoKeepsLocalLines,
    // aRevealNeverTouchesTheMarker
    //
    // Waiting on MessageRows, MessageRow (and the private `rows` and `msg` helpers): theBoundaryHides,
    // theDividerIsFirst, anEmptyVisibleRegionKeepsTheUndo, anUnclearedBufferIsUntouched,
    // detachedIgnoresTheMarker, startOfHistoryIsSuppressed, localLinesAreNeverHidden,
    // theRowBuilderRefusesAHalfMarker, revealingShowsAHiddenAnchor
    //
    // Waiting on CommandParser, CommandEffect (and the private `parse` helper): theCommandParses,
    // anUnknownArgumentStillClears
}
