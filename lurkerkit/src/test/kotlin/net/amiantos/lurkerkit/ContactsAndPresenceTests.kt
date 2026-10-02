// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.PresenceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The favorites (Friends/Favorites) + peer-presence path, end to end: the parser reads the
 * real wire frames, and the store folds them — the wholesale favorites list, disconnected-
 * aware presence, null-state clearing. Presence derivation is the subtle part, so it's
 * exercised in every branch.
 */
class ContactsAndPresenceTests {

    // MARK: - Parser

    @Test
    fun testFavoritesChangedParsesEntriesInOrder() {
        val frame = FrameParser.parseWs(
            """{"kind":"favorites-changed","favorites":[{"networkId":2,"target":"darc","bufferId":41},{"networkId":3,"target":"#lurker","bufferId":7}]}""",
        )
        if (frame !is ServerFrame.FavoritesChanged) fail("expected favoritesChanged, got $frame")
        assertEquals(
            listOf(
                FavoriteEntry(networkId = 2, target = "darc", bufferId = 41),
                FavoriteEntry(networkId = 3, target = "#lurker", bufferId = 7),
            ),
            frame.favorites,
            "one global list, order preserved verbatim — position IS the user's answer",
        )
    }

    @Test
    fun testFavoritesChangedWithEmptyListParses() {
        // Unfavoriting the last entry ships an empty list, not an absent field.
        val frame = FrameParser.parseWs("""{"kind":"favorites-changed","favorites":[]}""")
        if (frame !is ServerFrame.FavoritesChanged) fail("expected favoritesChanged, got $frame")
        assertEquals(emptyList(), frame.favorites)
    }

    @Test
    fun testPeerPresenceRidesIrcWithServerTarget() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"peer-presence","nick":"darc","state":"online","stateAt":"2026-07-24T00:00:00Z","cameOnline":true}""",
        )
        if (frame !is ServerFrame.PeerPresence) fail("expected peerPresence, got $frame")
        val (networkId, nick, state) = frame
        assertEquals(2, networkId)
        assertEquals("darc", nick)
        assertEquals(PresenceState.Online, state)
    }

    @Test
    fun testPeerPresenceWithNullStateParsesAsNil() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"peer-presence","nick":"darc","state":null}""",
        )
        if (frame !is ServerFrame.PeerPresence) fail("expected peerPresence, got $frame")
        assertNull(frame.state)
    }

    @Test
    fun testPeerPresenceWithoutNetworkIsIgnored() {
        // Every real peer-presence carries a networkId (publishEphemeral stamps it); one
        // without has no map to route into.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","target":":server:2","type":"peer-presence","nick":"darc","state":"away"}""",
        )
        if (frame !is ServerFrame.Ignored) fail("expected ignored, got $frame")
    }

    @Test
    fun testPeerPresenceRoutesByNickEvenWithoutTarget() {
        // Presence is routed by nick, not target (`:server:<id>` is only a carrier). Parsing
        // must not hinge on the target field, so a frame missing it still routes.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"type":"peer-presence","nick":"darc","state":"online"}""",
        )
        if (frame !is ServerFrame.PeerPresence) fail("expected peerPresence, got $frame")
        val (networkId, nick, state) = frame
        assertEquals(2, networkId)
        assertEquals("darc", nick)
        assertEquals(PresenceState.Online, state)
    }

    @Test
    fun testSnapshotSeedsPeerPresence() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"peerPresence":{"darc":{"nick":"darc","state":"away","stateAt":null,"awayMessage":"brb"}}}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        assertEquals(PresenceState.Away, frame.networks.firstOrNull()?.peerPresence?.get("darc"))
    }

    // MARK: - Store: favorites

    // MARK: - Store: presence derivation

    // Waiting on LurkerStore, ChatState (and the private `entry`, `connectedNetwork` and
    // `connectedStore` helpers): testFavoritesChangedReplacesWholesaleKeepingServerOrder,
    // testFavoriteEntryFollowsAPlainBufferRename, testPresenceOfflineWhileClientIsDisconnected,
    // testPresenceOfflineWhenDeviceUnreachable, testRowPresenceIsUnknownWhileClientIsDisconnected,
    // testRowPresenceIsUnknownWhenDeviceUnreachable,
    // testRowPresenceStillReadsAPeerOnADisconnectedNetworkAsOffline,
    // testRowPresenceWaitsForTheReconnectSnapshot, testPresenceUnknownForNetworkWeDoNotHave,
    // testPresenceUnknownForConnectedNetworkWithNoRow, testPresenceReadsStoredRowCaseInsensitively,
    // testBackReadsAsOnline, testDisconnectedNetworkReadsOfflineRegardlessOfRow,
    // testLivePeerPresenceUpdatesAndNullClears, testPresenceIsPerNetwork,
    // testSnapshotReplacesPeerPresenceWholesale
}
