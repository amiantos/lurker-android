// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkRow
import net.amiantos.lurkerkit.store.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The networks screen's rules — each one a comment in lurker-ios's `NetworksViewController`. */
class NetworksListModelTest {

    private fun config(id: Int, name: String = "Net$id", blocked: Boolean = false) =
        NetworkConfig(id = id, name = name, host = "irc$id.example", port = 6697, tls = true, nick = "me", blocked = blocked)

    // MARK: - Loading

    @Test
    fun aFirstLoadShowsTheSpinnerButARefreshKeepsTheList() {
        assertEquals(NetworksLoad.Loading, NetworksListModel.beforeReload(NetworksLoad.Loading))
        assertEquals(NetworksLoad.Loading, NetworksListModel.beforeReload(NetworksLoad.Failed))
        val loaded = NetworksLoad.Loaded(listOf(config(1)))
        assertSame(loaded, NetworksListModel.beforeReload(loaded))
    }

    @Test
    fun aFreshAccountsEmptyListIsNotReplacedByTheSpinnerOnReappearing() {
        // ⚠⚠ Keyed on "have we loaded", not "is it empty" — the fresh account is the one this screen
        // exists for.
        val empty = NetworksLoad.Loaded(emptyList())
        assertSame(empty, NetworksListModel.beforeReload(empty))
    }

    @Test
    fun aFailedRefreshKeepsWhatWeKnewEvenAnEmptyList() {
        val empty = NetworksLoad.Loaded(emptyList())
        assertSame(empty, NetworksListModel.afterFetch(NetworksListModel.beforeReload(empty), null))
        val loaded = NetworksLoad.Loaded(listOf(config(1)))
        assertSame(loaded, NetworksListModel.afterFetch(loaded, null))
    }

    @Test
    fun onlyAScreenThatNeverHadAnAnswerShowsTheFailure() {
        assertEquals(NetworksLoad.Failed, NetworksListModel.afterFetch(NetworksLoad.Loading, null))
        assertEquals(NetworksLoad.Loaded(emptyList()), NetworksListModel.afterFetch(NetworksLoad.Loading, emptyList()))
        assertEquals(NetworksLoad.Loaded(listOf(config(2))), NetworksListModel.afterFetch(NetworksLoad.Failed, listOf(config(2))))
    }

    @Test
    fun placeholdersSayIosWords() {
        assertEquals(StateModel("Loading networks…", isLoading = true), NetworksListModel.placeholder(NetworksLoad.Loading))
        assertEquals(
            StateModel("Couldn't load networks", StateSymbol.Warning, "Check your connection and try again.", actionTitle = "Try Again"),
            NetworksListModel.placeholder(NetworksLoad.Failed),
        )
        assertEquals(
            StateModel("No networks yet", StateSymbol.Network, "Add the IRC network you want to talk on.", actionTitle = "Add Network"),
            NetworksListModel.placeholder(NetworksLoad.Loaded(emptyList())),
        )
        assertNull(NetworksListModel.placeholder(NetworksLoad.Loaded(listOf(config(1)))))
    }

    // MARK: - Rows

    @Test
    fun theRowFollowsTheStoreAndANetworkTheStoreHasntHeardOfIsOffline() {
        val live = mapOf(1 to ConnectionState.Connected)
        assertEquals(NetworkRow(ConnectionState.Connected, isBlocked = false), NetworksListModel.row(config(1), live))
        assertEquals(NetworkRow(ConnectionState.Disconnected, isBlocked = true), NetworksListModel.row(config(2, blocked = true), live))
    }

    @Test
    fun liveStatesAreTheStoresConnectionsAlone() {
        val state = ChatState(
            networks = mapOf(
                1 to Network(id = 1, name = "A", state = ConnectionState.Connected, nick = "x"),
                2 to Network(id = 2, name = "B", state = ConnectionState.Reconnecting),
            ),
        )
        assertEquals(mapOf(1 to ConnectionState.Connected, 2 to ConnectionState.Reconnecting), NetworksListModel.liveStates(state))
        // A nick change moves nothing this screen draws.
        val renamed = state.copy(networks = state.networks + (1 to state.networks.getValue(1).copy(nick = "y")))
        assertEquals(NetworksListModel.liveStates(state), NetworksListModel.liveStates(renamed))
    }

    @Test
    fun subtitleIsHostPortAndStateWithTheAllowlistAppended() {
        val connected = NetworkRow(ConnectionState.Connected, isBlocked = false)
        assertEquals("irc1.example:6697 · Connected", NetworksListModel.subtitle(config(1), connected))
        // Appended, not substituted: a blocked network can be connected, and that's the more urgent fact.
        val blocked = NetworkRow(ConnectionState.Connected, isBlocked = true)
        assertEquals("irc1.example:6697 · Connected · not allowed here", NetworksListModel.subtitle(config(1), blocked))
        val offline = NetworkRow(ConnectionState.Disconnected, isBlocked = false)
        assertEquals("irc1.example:6697 · Offline", NetworksListModel.subtitle(config(1), offline))
        assertEquals(
            "irc1.example:6697 · Reconnecting…",
            NetworksListModel.subtitle(config(1), NetworkRow(ConnectionState.Reconnecting, isBlocked = false)),
        )
    }

    @Test
    fun theAllowlistIsExplainedOnceUnderTheListOnlyWhenItApplies() {
        assertNull(NetworksListModel.footer(listOf(config(1))))
        assertEquals(
            "This server's administrator limits which networks can be connected to.",
            NetworksListModel.footer(listOf(config(1), config(2, blocked = true))),
        )
    }

    @Test
    fun actionWordsMatchIos() {
        assertEquals(
            listOf("Connect", "Disconnect", "Reconnect", "Delete"),
            listOf(NetworkAction.Connect, NetworkAction.Disconnect, NetworkAction.Reconnect, NetworkAction.Delete).map { it.title },
        )
    }

    // MARK: - Refusals

    @Test
    fun onlyTheRowsALiveTransitionMovedCountAsMoved() {
        val configs = listOf(config(1), config(2), config(3))
        val before = mapOf(1 to ConnectionState.Connected, 2 to ConnectionState.Disconnected, 3 to ConnectionState.Connected)
        val shown = NetworksListModel.shownRows(configs, before)
        val after = before + (2 to ConnectionState.Connecting)
        assertEquals(setOf(2), NetworksListModel.movedIds(configs, shown, after))
        assertTrue(NetworksListModel.movedIds(configs, shown, before).isEmpty())
    }

    @Test
    fun rowsNeverScrolledToDontCountAsMoved() {
        // ⚠ The shown rows cover the whole list, so a refusal under a row nobody scrolled to isn't
        // retired by every emission.
        val configs = (1..30).map { config(it) }
        val live = configs.associate { it.id to ConnectionState.Connected }
        val shown = NetworksListModel.shownRows(configs, live)
        assertTrue(NetworksListModel.movedIds(configs, shown, live).isEmpty())
    }

    @Test
    fun aRefusalOutlivesAReReadOnlyWhileItsNetworkIsListed() {
        val error = RowError(2, "Your account is paused.")
        assertEquals(error, NetworksListModel.retainedError(error, listOf(config(1), config(2))))
        assertNull(NetworksListModel.retainedError(error, listOf(config(1))))
        assertNull(NetworksListModel.retainedError(null, listOf(config(1))))
    }

    @Test
    fun deleteConfirmationSaysIosWords() {
        assertEquals("Delete Libera?", NetworksListModel.deleteTitle(config(1, name = "Libera")))
        assertEquals(
            "This removes its channels, direct messages and history from Lurker, on all your devices.",
            NetworksListModel.DELETE_MESSAGE,
        )
    }

    // MARK: - Certificates

    @Test
    fun aCertificateTheFormWroteLandsOnTheListsRow() {
        val load = NetworksLoad.Loaded(listOf(config(1), config(2)))
        val usable = ClientCertificate.Usable(expires = null)
        val next = NetworksListModel.withCertificate(load, 2, usable) as NetworksLoad.Loaded
        assertEquals(usable, next.configs[1].clientCertificate)
        assertNull(next.configs[0].clientCertificate)
        // And off it again on a remove.
        val removed = NetworksListModel.withCertificate(next, 2, null) as NetworksLoad.Loaded
        assertNull(removed.configs[1].clientCertificate)
    }

    @Test
    fun aCertificateForNoListedRowChangesNothing() {
        assertNull(NetworksListModel.withCertificate(NetworksLoad.Loaded(listOf(config(1))), 9, null))
        assertNull(NetworksListModel.withCertificate(NetworksLoad.Loading, 1, null))
        assertNull(NetworksListModel.withCertificate(NetworksLoad.Failed, 1, null))
    }
}
