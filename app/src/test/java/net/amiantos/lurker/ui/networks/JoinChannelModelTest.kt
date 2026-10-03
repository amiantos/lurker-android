// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Join Channel's rules — lurker-ios's `JoinChannelViewController` and `join(network:channel:)`. */
class JoinChannelModelTest {

    private fun option(id: Int, state: ConnectionState) = JoinNetworkOption(id, "Net$id", state)

    @Test
    fun optionsFollowTheBufferListsOrderAndNameUnnamedNetworks() {
        val state = ChatState(
            connection = SocketStatus.Connected,
            reachable = true,
            networks = mapOf(
                1 to Network(id = 1, name = "Zeta", position = 1, state = ConnectionState.Connected),
                2 to Network(id = 2, name = null, position = 0, state = ConnectionState.Disconnected),
            ),
        )
        assertEquals(
            listOf(JoinNetworkOption(2, "Unnamed network", ConnectionState.Disconnected), JoinNetworkOption(1, "Zeta", ConnectionState.Connected)),
            JoinChannelModel.options(state),
        )
    }

    @Test
    fun aNetworkIsNotJoinableWhileLurkersOwnSocketIsDown() {
        val networks = mapOf(1 to Network(id = 1, name = "Zeta", position = 0, state = ConnectionState.Connected))
        val reconnecting = JoinChannelModel.options(ChatState(connection = SocketStatus.Reconnecting, reachable = true, networks = networks))
        assertFalse(reconnecting.single().connected)
        assertFalse(JoinChannelModel.canJoin(1, reconnecting, "#swift"))
        val offline = JoinChannelModel.options(ChatState(connection = SocketStatus.Connected, reachable = false, networks = networks))
        assertFalse(offline.single().connected)
        val up = JoinChannelModel.options(ChatState(connection = SocketStatus.Connected, reachable = true, networks = networks))
        assertTrue(JoinChannelModel.canJoin(1, up, "#swift"))
    }

    @Test
    fun theDefaultIsTheFirstConnectedNetworkElseTheFirstOfAny() {
        assertEquals(2, JoinChannelModel.defaultSelection(listOf(option(1, ConnectionState.Disconnected), option(2, ConnectionState.Connected))))
        // Something is always selected, and Join stays disabled to say why.
        assertEquals(1, JoinChannelModel.defaultSelection(listOf(option(1, ConnectionState.Disconnected), option(2, ConnectionState.Connecting))))
        assertNull(JoinChannelModel.defaultSelection(emptyList()))
    }

    @Test
    fun aSecondNetworkFinishingItsConnectTakesTheSelection() {
        // ⚠ Re-picked, not merely validated: the selection would otherwise stay on the unusable row.
        val before = listOf(option(1, ConnectionState.Disconnected), option(2, ConnectionState.Connecting))
        val selected = JoinChannelModel.defaultSelection(before)
        assertEquals(1, selected)
        val after = listOf(option(1, ConnectionState.Disconnected), option(2, ConnectionState.Connected))
        assertEquals(2, JoinChannelModel.reconcile(selected, after))
    }

    @Test
    fun aWorkingSelectionIsTheUsersAndStays() {
        val options = listOf(option(1, ConnectionState.Connected), option(2, ConnectionState.Connected))
        assertEquals(2, JoinChannelModel.reconcile(2, options))
    }

    @Test
    fun aDeletedSelectionFallsBackToTheDefault() {
        assertEquals(1, JoinChannelModel.reconcile(9, listOf(option(1, ConnectionState.Connected))))
    }

    @Test
    fun joinNeedsAConnectedNetworkAndAChannelName() {
        val options = listOf(option(1, ConnectionState.Connected), option(2, ConnectionState.Disconnected))
        assertTrue(JoinChannelModel.canJoin(1, options, "swift"))
        assertFalse(JoinChannelModel.canJoin(2, options, "swift"))
        assertFalse(JoinChannelModel.canJoin(null, options, "swift"))
        // A bare sigil is not a name — of any of the four.
        for (sigilOnly in listOf("", " ", "#", "&", "+", "!", "##", " # ")) {
            assertFalse(sigilOnly, JoinChannelModel.canJoin(1, options, sigilOnly))
        }
    }

    @Test
    fun theNameSentIsTrimmedThenPrefixed() {
        assertEquals("#swift", JoinChannelModel.channelToSend("swift"))
        // ⚠ Trimmed BEFORE the prefix, or " #swift" becomes "# #swift".
        assertEquals("#swift", JoinChannelModel.channelToSend(" #swift\n"))
        // Any of the four sigils is left alone.
        assertEquals("&local", JoinChannelModel.channelToSend("&local"))
        assertEquals("+modeless", JoinChannelModel.channelToSend("+modeless"))
        assertEquals("!safe", JoinChannelModel.channelToSend("!safe"))
        assertEquals("##anime", JoinChannelModel.channelToSend("##anime"))
        assertNull(JoinChannelModel.channelToSend("#"))
        assertNull(JoinChannelModel.channelToSend("   "))
    }

    @Test
    fun copyMatchesIos() {
        assertEquals("A # is added if you leave it off.", JoinChannelModel.FIELD_FOOTER)
        assertEquals("not connected", JoinChannelModel.NOT_CONNECTED)
    }
}
