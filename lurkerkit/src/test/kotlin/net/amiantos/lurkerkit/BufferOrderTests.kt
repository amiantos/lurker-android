// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The buffer list's order, where the order is the user's own: their network arrangement and
 * their pins, both made on the web and only rendered here.
 */
class BufferOrderTests {

    // MARK: - Networks

    // MARK: - Pins

    // MARK: - What a network section lists

    // MARK: - The server log

    @Test
    fun testTheSynthesizedTargetMatchesTheServers() {
        // `:server:<id>`, the same string the server and the web use — a client-built target
        // that didn't match would open a second, empty buffer beside the real one.
        assertEquals(":server:2", Buffer.serverTarget(2))
        assertEquals(BufferKind.Server, BufferKind.of(networkId = 2, target = Buffer.serverTarget(2)))
    }

    // MARK: - The ordinary order (moved here from the view controller)

    // MARK: - Store

    // Waiting on BufferOrder (and the private `network` and `buffer` helpers):
    // testNetworksFollowTheUsersOrderNotTheAlphabet, testTiesBreakOnIdTheWayTheServerBreaksThem,
    // testANetworkWeHaveNoRosterRowForSortsLast, testPinnedBuffersComeBackInTheUsersPinOrder,
    // testTheRestKeepsTheOrdinaryOrder, testAPinnedOnlyNetworkHasNothingLeftOver,
    // testAPinWithNoOpenBufferContributesNothing, testASigilIsPartOfTheTargetNotNoiseToFoldAway,
    // testPinsMatchTargetsCaseInsensitively, testADuplicatedPinDoesNotPrintTheBufferTwice,
    // testNoPinsIsJustTheOrdinaryOrder, testAFavoritedBufferIsNotAlsoListedUnderItsNetwork,
    // testAFavoriteMatchesRegardlessOfItsSpelling, testTheSystemBufferHasNoNetworkSection,
    // testANetworkAlwaysHasItsServerLog, testAnExistingServerLogIsNotDuplicated,
    // testANetworkWhoseOnlyBufferIsFavoritedKeepsItsSection,
    // testANetworkWithNoRowsAtAllGetsNoRowInvented, testTheServerLogSortsLastWithinItsNetwork,
    // testChannelsThenDmsThenTheServerLog, testTheAlphabeticalKeyStripsEverySigil
    //
    // Waiting on FrameParser, ServerFrame: testPinsChangedParsesItsOrderedList,
    // testTheRosterCarriesThePosition, testAnOlderServerWithNoPositionSortsLast
    //
    // Waiting on LurkerStore, ChatState (and FrameParser):
    // testTheSnapshotSeedsPinsAndReplacesThemWholesale, testPinsChangedReplacesOneNetworksList,
    // testDeletingANetworkTakesItsPinsWithIt,
    // testTheRosterMergeCarriesThePositionOntoAnExistingNetwork
}
