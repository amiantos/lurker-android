// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkRow
import net.amiantos.lurkerkit.model.StatusLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What a networks-screen row says and offers (lurker-ios#11). Worth its own tests because the
 * rule is mostly about which actions are *absent*, and an action that shouldn't be there is
 * invisible in a screenshot — you only find it by tapping it and reading the server's refusal,
 * and one that should be there and isn't is found by needing it.
 */
class NetworkRowTests {

    private fun row(connection: ConnectionState, blocked: Boolean = false): NetworkRow =
        NetworkRow(connection = connection, isBlocked = blocked)

    @Test
    fun testADisconnectedNetworkOffersToConnect() {
        assertEquals(listOf(NetworkAction.Connect, NetworkAction.Delete), row(ConnectionState.Disconnected).actions)
    }

    @Test
    fun testAConnectedNetworkOffersToCycleIt() {
        // Reconnect as well as disconnect: a config change needs a way to take effect, and a
        // wedged connection needs cycling without two taps and a wait between them.
        assertEquals(
            listOf(NetworkAction.Disconnect, NetworkAction.Reconnect, NetworkAction.Delete),
            row(ConnectionState.Connected).actions,
        )
    }

    @Test
    fun testANetworkMidAttemptOffersToCancel() {
        // Disconnect during connecting/reconnecting IS the cancel — the one useful thing to
        // do to a network stuck retrying. Connect would be a no-op and reconnect a restart of
        // something that hasn't started.
        assertEquals(listOf(NetworkAction.Disconnect, NetworkAction.Delete), row(ConnectionState.Connecting).actions)
        assertEquals(listOf(NetworkAction.Disconnect, NetworkAction.Delete), row(ConnectionState.Reconnecting).actions)
    }

    // MARK: - Blocked

    @Test
    fun testABlockedNetworkOffersNoWayToConnect() {
        // The server answers connect and reconnect with 403 on an off-list host (lurker#298), so
        // either would be an action whose only outcome is an error message.
        assertEquals(listOf(NetworkAction.Delete), row(ConnectionState.Disconnected, blocked = true).actions)
        assertFalse(row(ConnectionState.Connected, blocked = true).actions.contains(NetworkAction.Reconnect))
    }

    @Test
    fun testABlockedNetworkCanStillBeDisconnected() {
        // ⚠⚠ The allowlist gates NEW connections — `/connect` and `/reconnect` check it,
        // `/disconnect` does not. So a network can be blocked and connected at once, which is
        // what an admin tightening the list under a live connection produces. Withholding
        // disconnect there would leave the user unable to stop a connection the server would
        // happily stop.
        assertEquals(
            listOf(NetworkAction.Disconnect, NetworkAction.Delete),
            row(ConnectionState.Connected, blocked = true).actions,
        )
    }

    @Test
    fun testBlockedDoesNotRepaintALiveConnectionAsBroken() {
        // Blocked is a fact about what this network can do next, not about whether it works
        // now. A connected row works now, whatever the allowlist says about reconnecting it
        // later.
        assertEquals(StatusLight.Good, row(ConnectionState.Connected, blocked = true).light)
        assertEquals(StatusLight.Warn, row(ConnectionState.Connecting, blocked = true).light)
    }

    // MARK: - Connection-only surfaces (lurker-ios#152)

    @Test
    fun testConnectionActionsOfferTheSameVerbsWithoutDelete() {
        // The server buffer's info sheet manages the connection, not the row: the same offers
        // as the menu, minus the one that destroys the network and its history.
        assertEquals(listOf(NetworkAction.Connect), row(ConnectionState.Disconnected).connectionActions)
        assertEquals(
            listOf(NetworkAction.Disconnect, NetworkAction.Reconnect),
            row(ConnectionState.Connected).connectionActions,
        )
        assertEquals(listOf(NetworkAction.Disconnect), row(ConnectionState.Reconnecting).connectionActions)
        for (connection in listOf(
            ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Reconnecting,
            ConnectionState.Disconnected,
        )) {
            assertFalse(row(connection, blocked = true).connectionActions.contains(NetworkAction.Delete))
        }
    }

    @Test
    fun testABlockedOfflineNetworkHasNoConnectionActionsAtAll() {
        // Nothing to offer and no Delete to fill the gap — the sheet explains in its footer
        // rather than showing an action whose only outcome is a 403.
        assertEquals(emptyList(), row(ConnectionState.Disconnected, blocked = true).connectionActions)
    }

    // MARK: - Shape

    @Test
    fun testDeleteIsAlwaysAvailableAndAlwaysLast() {
        // A network you can't connect to is exactly one you might want gone — including a
        // blocked one, which is otherwise inert. Last, so it isn't adjacent to the action
        // someone actually came to tap.
        for (connection in listOf(
            ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Reconnecting,
            ConnectionState.Disconnected,
        )) {
            for (blocked in listOf(true, false)) {
                val actions = row(connection, blocked = blocked).actions
                assertEquals(NetworkAction.Delete, actions.lastOrNull(), "$connection blocked:$blocked")
                assertEquals(1, actions.count { it == NetworkAction.Delete }, "$connection blocked:$blocked")
            }
        }
    }

    @Test
    fun testDeleteIsTheOnlyDestructiveAction() {
        // Everything else is undone by its opposite, so it's the only one owed a confirmation.
        assertEquals(listOf(NetworkAction.Delete), NetworkAction.entries.filter { it.isDestructive })
    }

    @Test
    fun testTheDotSharesThePillsVocabulary() {
        // One colour, one meaning, app-wide: amber is "still trying", red is "not fixing
        // itself" — the distinction `StatusLight` exists to keep.
        assertEquals(StatusLight.Good, row(ConnectionState.Connected).light)
        assertEquals(StatusLight.Warn, row(ConnectionState.Connecting).light)
        assertEquals(StatusLight.Warn, row(ConnectionState.Reconnecting).light)
        assertEquals(StatusLight.Bad, row(ConnectionState.Disconnected).light)
    }
}
