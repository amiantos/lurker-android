// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.store.LurkerStore
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

    private fun entry(id: Int, target: String, net: Int = 2): FavoriteEntry =
        FavoriteEntry(networkId = net, target = target, bufferId = id)

    @Test
    fun testFavoritesChangedReplacesWholesaleKeepingServerOrder() {
        val store = LurkerStore()
        store.apply(ServerFrame.FavoritesChanged(listOf(entry(1, "zed"), entry(2, "#alpha"), entry(3, "bob"))))
        // NOT re-sorted: the server's global order is user-controlled.
        assertEquals(listOf("zed", "#alpha", "bob"), store.state.favorites.map { it.target })
        // A later frame replaces, never merges — removal and reorder are the same op.
        store.apply(ServerFrame.FavoritesChanged(listOf(entry(3, "bob"))))
        assertEquals(listOf("bob"), store.state.favorites.map { it.target })
    }

    @Test
    fun testFavoriteEntryFollowsAPlainBufferRename() {
        // The server only republishes favorites after MERGES, so a plain
        // nick-follow rename must rewrite the entry locally — by bufferId, the
        // identity the frame proves — or the Friends chip ghosts under the dead
        // nick while the renamed DM leaks back into its network roster.
        val store = LurkerStore()
        store.apply(ServerFrame.FavoritesChanged(listOf(entry(41, "zed", net = 2), entry(7, "#alpha", net = 2))))
        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 2, from = "zed", to = "zed_", bufferId = 41, merged = false,
                mergedFromBufferId = null,
            ),
        )
        assertEquals(listOf("zed_", "#alpha"), store.state.favorites.map { it.target })
        assertEquals(listOf(41, 7), store.state.favorites.map { it.bufferId }, "identity untouched")
    }

    // MARK: - Store: presence derivation

    private fun connectedNetwork(id: Int, presence: Map<String, PresenceState> = emptyMap()): ServerFrame =
        ServerFrame.Snapshot(
            listOf(
                NetworkSnapshot(
                    id = id, state = ConnectionState.Connected, nick = "me", channels = emptyList(), peerPresence = presence,
                ),
            ),
            globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
        )

    /**
     * A store with a live socket. presence() now gates on the client's own link, so a test
     * asserting a specific peer status must first be "connected" or every dot reads offline.
     */
    private fun connectedStore(): LurkerStore {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        return store
    }

    @Test
    fun testPresenceOfflineWhileClientIsDisconnected() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        assertEquals(FriendPresence.Online, store.state.presence(networkId = 2, nick = "darc"))
        // Socket drops → reconnecting: the cached row is stale, so the dot must not claim online
        // even though the network's own state is still .connected from the last snapshot.
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertEquals(FriendPresence.Offline, store.state.presence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testPresenceOfflineWhenDeviceUnreachable() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        store.setReachable(false)
        assertEquals(FriendPresence.Offline, store.state.presence(networkId = 2, nick = "darc"))
    }

    /**
     * A DM row claims nothing while we can't see the server (lurker-ios#167): `offline` would put
     * every DM in italics under the "Connecting…" banner. The profile keeps `presence`'s
     * `offline`, which is what stops it falling back to a WHOIS reply cached before the drop.
     */
    @Test
    fun testRowPresenceIsUnknownWhileClientIsDisconnected() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        assertEquals(FriendPresence.Online, store.state.rowPresence(networkId = 2, nick = "darc"))

        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertEquals(FriendPresence.Unknown, store.state.rowPresence(networkId = 2, nick = "darc"))
        assertEquals(FriendPresence.Offline, store.state.presence(networkId = 2, nick = "darc"), "the profile's answer stands")
    }

    @Test
    fun testRowPresenceIsUnknownWhenDeviceUnreachable() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        store.setReachable(false)
        assertEquals(FriendPresence.Unknown, store.state.rowPresence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testRowPresenceStillReadsAPeerOnADisconnectedNetworkAsOffline() {
        // The case lurker-ios#167 was filed for: our own connection is fine, the network's isn't.
        val store = connectedStore()
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 2, state = ConnectionState.Disconnected, nick = "me", channels = emptyList(),
                        peerPresence = mapOf("darc" to PresenceState.Online),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        assertEquals(FriendPresence.Offline, store.state.rowPresence(networkId = 2, nick = "darc"))
    }

    /**
     * `socketOpen` reads `.connected` before the reconnect's snapshot replaces the cached rows, so
     * passing `presence` through in that window put last session's away or offline back on a row
     * for a moment — the flash `rowPresence` exists to prevent.
     */
    @Test
    fun testRowPresenceWaitsForTheReconnectSnapshot() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Offline)))
        assertEquals(FriendPresence.Offline, store.state.rowPresence(networkId = 2, nick = "darc"))

        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        store.apply(ServerFrame.SocketOpen)
        assertEquals(
            FriendPresence.Unknown, store.state.rowPresence(networkId = 2, nick = "darc"),
            "reconnected, but the cache from before the drop hasn't been replaced yet",
        )

        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        assertEquals(FriendPresence.Online, store.state.rowPresence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testPresenceUnknownForNetworkWeDoNotHave() {
        val store = connectedStore()
        assertEquals(FriendPresence.Unknown, store.state.presence(networkId = 99, nick = "darc"))
    }

    @Test
    fun testPresenceUnknownForConnectedNetworkWithNoRow() {
        val store = connectedStore()
        store.apply(connectedNetwork(2))
        assertEquals(FriendPresence.Unknown, store.state.presence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testPresenceReadsStoredRowCaseInsensitively() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        assertEquals(FriendPresence.Online, store.state.presence(networkId = 2, nick = "Darc"))
    }

    @Test
    fun testBackReadsAsOnline() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Back)))
        assertEquals(FriendPresence.Online, store.state.presence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testDisconnectedNetworkReadsOfflineRegardlessOfRow() {
        val store = connectedStore()
        // A network we hold but that isn't connected: its cached rows are stale, so a friend
        // there is unreachable → offline, even if a stale row said otherwise.
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 2, state = ConnectionState.Reconnecting, nick = "me", channels = emptyList(),
                        peerPresence = mapOf("darc" to PresenceState.Online),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        assertEquals(FriendPresence.Offline, store.state.presence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testLivePeerPresenceUpdatesAndNullClears() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        store.apply(ServerFrame.PeerPresence(networkId = 2, nick = "Darc", state = PresenceState.Away))
        assertEquals(FriendPresence.Away, store.state.presence(networkId = 2, nick = "darc"))
        // A null state clears the row → unknown (network is still connected).
        store.apply(ServerFrame.PeerPresence(networkId = 2, nick = "darc", state = null))
        assertEquals(FriendPresence.Unknown, store.state.presence(networkId = 2, nick = "darc"))
    }

    @Test
    fun testPresenceIsPerNetwork() {
        val store = connectedStore()
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 2, state = ConnectionState.Connected, nick = "me", channels = emptyList(),
                        peerPresence = mapOf("darc" to PresenceState.Away),
                    ),
                    NetworkSnapshot(
                        id = 3, state = ConnectionState.Connected, nick = "me", channels = emptyList(),
                        peerPresence = mapOf("darc" to PresenceState.Online),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        // A Friends chip reads the presence of ITS network's peer — the same nick elsewhere
        // is a different person as far as the dot is concerned.
        assertEquals(FriendPresence.Away, store.state.presence(networkId = 2, nick = "darc"))
        assertEquals(FriendPresence.Online, store.state.presence(networkId = 3, nick = "darc"))
    }

    @Test
    fun testSnapshotReplacesPeerPresenceWholesale() {
        val store = connectedStore()
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online, "naia" to PresenceState.Away)))
        // A fresh snapshot for the network is authoritative — a peer no longer watched drops out.
        store.apply(connectedNetwork(2, presence = mapOf("darc" to PresenceState.Online)))
        assertEquals(FriendPresence.Online, store.state.presence(networkId = 2, nick = "darc"))
        assertEquals(FriendPresence.Unknown, store.state.presence(networkId = 2, nick = "naia"))
    }
}
