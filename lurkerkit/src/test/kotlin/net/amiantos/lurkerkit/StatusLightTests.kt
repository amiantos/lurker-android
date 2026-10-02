// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.store.SocketStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The indicator light under a buffer's title. Three states, and which one wins when several
 * layers are unhappy at once.
 */
class StatusLightTests {

    // MARK: - No path beats everything

    @Test
    fun testNoNetworkPathIsRedRegardlessOfWhatElseClaims() {
        // The socket can't report "there is no internet" — it only ever says
        // connecting/connected/reconnecting — so a stale `.connected` must not paint the
        // light green on a phone in airplane mode.
        assertEquals(
            StatusLight.Bad,
            StatusLight.of(reachable = false, connection = SocketStatus.Connected, network = ConnectionState.Connected),
        )
        assertEquals(StatusLight.Bad, StatusLight.of(reachable = false, connection = SocketStatus.Connecting, network = null))
    }

    // MARK: - The socket layer

    @Test
    fun testAConnectingOrReconnectingSocketIsAmberNeverRed() {
        // A dropped socket is always retrying, so it's "still trying", not "broken".
        // There is deliberately no red here.
        assertEquals(StatusLight.Warn, StatusLight.of(reachable = true, connection = SocketStatus.Connecting, network = null))
        assertEquals(StatusLight.Warn, StatusLight.of(reachable = true, connection = SocketStatus.Reconnecting, network = null))
        assertEquals(
            StatusLight.Warn,
            StatusLight.of(reachable = true, connection = SocketStatus.Reconnecting, network = ConnectionState.Connected),
            "no Lurker means the network state we hold is stale, whatever it says",
        )
    }

    @Test
    fun testAServerThatCantTakeThisBuildIsRed() {
        // The one socket state that isn't retrying, so amber would promise a fix that isn't coming.
        assertEquals(
            StatusLight.Bad,
            StatusLight.of(
                reachable = true,
                connection = SocketStatus.Incompatible(Incompatibility.AppTooOld),
                network = ConnectionState.Connected,
            ),
        )
        assertEquals(
            StatusLight.Bad,
            StatusLight.of(reachable = true, connection = SocketStatus.Incompatible(Incompatibility.ServerTooOld), network = null),
        )
    }

    // MARK: - The system buffer

    @Test
    fun testTheSystemBufferIsGreenOnceTheSocketIsUp() {
        // null network = the system buffer, whose whole story is the socket.
        assertEquals(StatusLight.Good, StatusLight.of(reachable = true, connection = SocketStatus.Connected, network = null))
    }

    // MARK: - Per-network

    @Test
    fun testANetworkBufferTracksItsNetwork() {
        fun light(state: ConnectionState): StatusLight =
            StatusLight.of(reachable = true, connection = SocketStatus.Connected, network = state)
        assertEquals(StatusLight.Good, light(ConnectionState.Connected))
        assertEquals(StatusLight.Warn, light(ConnectionState.Connecting))
        assertEquals(StatusLight.Warn, light(ConnectionState.Reconnecting))
        assertEquals(StatusLight.Bad, light(ConnectionState.Disconnected), "the server gave up; it isn't coming back on its own")
    }

    @Test
    fun testMatchesTheWebClientsNetworkIndicator() {
        // vue_client BufferList.vue stateClass(): connected → good, connecting/
        // reconnecting → warn, otherwise bad. Same signal, same color, both clients.
        val cases = listOf(
            ConnectionState.Connected to StatusLight.Good,
            ConnectionState.Connecting to StatusLight.Warn,
            ConnectionState.Reconnecting to StatusLight.Warn,
            ConnectionState.Disconnected to StatusLight.Bad,
        )
        for ((state, expected) in cases) {
            assertEquals(
                expected,
                StatusLight.of(reachable = true, connection = SocketStatus.Connected, network = state),
                "$state should be $expected",
            )
        }
    }

    // MARK: - Subtitle words

    @Test
    fun testLurkerItselfSaysHowItsOwnConnectionIsDoing() {
        assertEquals("Connected", StatusLight.Good.subtitle(detail = null))
        assertEquals("Connecting…", StatusLight.Warn.subtitle(detail = null))
        assertEquals("Disconnected", StatusLight.Bad.subtitle(detail = null))
    }

    @Test
    fun testAChannelNamesItsNetworkAndWhetherItsUp() {
        assertEquals("Libera · Online", StatusLight.Good.subtitle(detail = "Libera"))
        assertEquals("Libera · Connecting…", StatusLight.Warn.subtitle(detail = "Libera"))
        assertEquals("Libera · Disconnected", StatusLight.Bad.subtitle(detail = "Libera"))
    }

    @Test
    fun testADmSaysWhetherThePersonIsThereNotWhetherTheNetworkIs() {
        assertEquals("Libera · Online", StatusLight.Good.subtitle(detail = "Libera", peer = FriendPresence.Online))
        assertEquals("Libera · Away", StatusLight.Good.subtitle(detail = "Libera", peer = FriendPresence.Away))
        assertEquals("Libera · Offline", StatusLight.Good.subtitle(detail = "Libera", peer = FriendPresence.Offline))
        // No MONITOR, or nothing heard yet: "Online" would be the network's word read as theirs.
        assertEquals("Libera", StatusLight.Good.subtitle(detail = "Libera", peer = FriendPresence.Unknown))
        // A network whose name hasn't arrived: never a blank subtitle.
        assertEquals("Connected", StatusLight.Good.subtitle(detail = null, peer = FriendPresence.Unknown))
    }

    @Test
    fun testADmOnADownedLinkSaysTheLinkNotAGuessAboutThePeer() {
        // `presence` reads `.offline` for every peer on a network we've lost; the subtitle must
        // not pass that on as a fact about them.
        assertEquals("Libera · Disconnected", StatusLight.Bad.subtitle(detail = "Libera", peer = FriendPresence.Offline))
        assertEquals("Libera · Connecting…", StatusLight.Warn.subtitle(detail = "Libera", peer = FriendPresence.Away))
    }
}
