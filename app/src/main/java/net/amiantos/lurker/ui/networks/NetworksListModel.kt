// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurker.ui.list.label
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkRow
import net.amiantos.lurkerkit.store.ChatState

/**
 * Where the networks screen's config list is in its lifecycle — lurker-ios's
 * `NetworksViewController.Load`.
 *
 * The three unhappy paths are distinguished rather than collapsed into "no rows" (lurker-ios#19): a
 * fresh account with no networks, a fetch still running, and a fetch that failed are three different
 * things to say, and only one of them is the user's to act on.
 */
sealed interface NetworksLoad {
    data object Loading : NetworksLoad
    data class Loaded(val configs: List<NetworkConfig>) : NetworksLoad
    data object Failed : NetworksLoad
}

/**
 * The most recent refusal of a row's action, pinned under the row it belongs to.
 *
 * In the row rather than in a dialog: a dialog for an *asynchronous* result is droppable — anything
 * else opened in the round trip (the delete confirmation, say) competes with it — and a row that
 * didn't change with no reason why is the failure. A row can't lose its own subtitle.
 */
data class RowError(val id: Int, val message: String)

/** What the networks screen shows instead of rows: iOS's `StateView.Model`s for this screen. */
data class NetworksPlaceholder(
    val title: String,
    val subtitle: String? = null,
    val actionTitle: String? = null,
    val isLoading: Boolean = false,
    val symbol: StateSymbol? = null,
)

/**
 * The networks screen's rules (lurker-ios#11), pure so they can be pinned in JVM tests. The screen
 * — `NetworksListPage` — owns the state; this decides what it means.
 *
 * **Two sources, one row.** The configuration (name, host, port, whether the admin's allowlist
 * blocks it) comes from `GET /api/networks`, fetched when the screen appears. The connection state
 * comes from the store, live, because that's where `state` events land. So the list is re-fetched on
 * appearance and after anything that changes its membership, while the dots move on their own
 * without re-asking the server.
 */
object NetworksListModel {

    /**
     * The state a reload starts from.
     *
     * ⚠⚠ Gated on "have we ever loaded", NOT on "is the list empty". Those differ on the exact
     * account this screen exists for: a fresh one, whose first load lands as `Loaded([])`. Keyed on
     * emptiness, every appearance replaced "No networks yet" with the spinner and back — and, because
     * the load had already been overwritten by the time the fetch returned, the keep-what-we-had arm
     * of [afterFetch] could never match for it, so one dropped request told a brand-new user
     * "Couldn't load networks". A refresh behind a list that exists doesn't blank it.
     */
    fun beforeReload(load: NetworksLoad): NetworksLoad = if (load is NetworksLoad.Loaded) load else NetworksLoad.Loading

    /**
     * What a fetch's answer makes of the list.
     *
     * ⚠ Null is "we couldn't ask", never "no networks" — the kit draws that distinction
     * deliberately, and collapsing it here would greet a failed request with the empty state's
     * welcome.
     *
     * ⚠⚠ So a failed *refresh* keeps whatever we already knew, an empty list included: the
     * configuration hasn't changed just because one request didn't arrive, and the next appearance
     * asks again. Only a screen that has never had an answer shows the failure — the only one where
     * there's nothing truer to show instead.
     */
    fun afterFetch(load: NetworksLoad, fetched: List<NetworkConfig>?): NetworksLoad =
        when {
            fetched != null -> NetworksLoad.Loaded(fetched)
            load is NetworksLoad.Loaded -> load
            else -> NetworksLoad.Failed
        }

    fun configs(load: NetworksLoad): List<NetworkConfig> = (load as? NetworksLoad.Loaded)?.configs ?: emptyList()

    /** What to show instead of rows, or null when there are rows. iOS's copy. */
    fun placeholder(load: NetworksLoad): NetworksPlaceholder? =
        when (load) {
            NetworksLoad.Loading -> NetworksPlaceholder(title = "Loading networks…", isLoading = true)
            NetworksLoad.Failed -> NetworksPlaceholder(
                title = "Couldn't load networks",
                subtitle = "Check your connection and try again.",
                actionTitle = "Try Again",
                symbol = StateSymbol.Warning,
            )
            is NetworksLoad.Loaded -> if (load.configs.isEmpty()) {
                NetworksPlaceholder(
                    title = "No networks yet",
                    subtitle = "Add the IRC network you want to talk on.",
                    actionTitle = "Add Network",
                    symbol = StateSymbol.Network,
                )
            } else {
                null
            }
        }

    /**
     * The one slice of the store this screen follows: each network's connection. `statePublisher`
     * fires on every frame the store reduces — every message in every buffer — and this screen cares
     * about one map's worth of it; mapped to this and deduplicated, an open networks screen doesn't
     * rebuild on the traffic of an idle channel.
     */
    fun liveStates(state: ChatState): Map<Int, ConnectionState> = state.networks.mapValues { it.value.state }

    /** A row's model: the live connection (offline when the store hasn't heard of it) and the allowlist. */
    fun row(config: NetworkConfig, live: Map<Int, ConnectionState>): NetworkRow =
        NetworkRow(connection = live[config.id] ?: ConnectionState.Disconnected, isBlocked = config.blocked)

    /**
     * `host:port · state`, plus the allowlist when it applies — appended rather than substituted,
     * since a blocked network can be connected and the connection is the more urgent of the two
     * facts.
     */
    fun subtitle(config: NetworkConfig, row: NetworkRow): String {
        val parts = mutableListOf("${config.host}:${config.port}", row.connection.label)
        if (row.isBlocked) parts.add("not allowed here")
        return parts.joinToString(" · ")
    }

    /**
     * Said once, under the list, rather than in every blocked row's subtitle: it explains a policy,
     * and a policy repeated per row reads as a per-row problem.
     */
    fun footer(configs: List<NetworkConfig>): String? = if (configs.any { it.blocked }) BLOCKED_EXPLANATION else null

    /**
     * The networks whose row a live transition actually moved, against [shown] — the row model each
     * network was last drawn with.
     *
     * ⚠ [shown] covers the whole list, not just what's on screen. On iOS it was first populated from
     * the visible cells, so every row never scrolled to counted as "changed" on every emission —
     * which quietly retired a refusal pinned under a row nobody had touched, before its owner could
     * scroll back and read it.
     */
    fun movedIds(configs: List<NetworkConfig>, shown: Map<Int, NetworkRow>, live: Map<Int, ConnectionState>): Set<Int> =
        configs.filter { shown[it.id] != row(it, live) }.map { it.id }.toSet()

    /** The row model of every network, as [movedIds] reads it next time. */
    fun shownRows(configs: List<NetworkConfig>, live: Map<Int, ConnectionState>): Map<Int, NetworkRow> =
        configs.associate { it.id to row(it, live) }

    /**
     * A refusal survives a re-read only while its network is still listed: a list that no longer
     * holds a network shouldn't keep its refusal around to attach to whatever takes its place.
     */
    fun retainedError(error: RowError?, configs: List<NetworkConfig>): RowError? =
        error?.takeIf { e -> configs.any { it.id == e.id } }

    /**
     * The list with a certificate the form just wrote put onto its copy of the row, or null when
     * there's no such row to update.
     *
     * ⚠⚠ Not left to the re-read on the way back. The form writes certificates without a Save, and
     * a re-read that fails keeps what we had, while a tap can beat one that hasn't landed — either
     * way the form reopened from the old row, offering Generate over a certificate the user had just
     * registered.
     */
    fun withCertificate(load: NetworksLoad, id: Int, certificate: ClientCertificate?): NetworksLoad? {
        val configs = (load as? NetworksLoad.Loaded)?.configs ?: return null
        val index = configs.indexOfFirst { it.id == id }
        if (index < 0) return null
        return NetworksLoad.Loaded(configs.toMutableList().also { it[index] = it[index].copy(clientCertificate = certificate) })
    }

    /** The delete confirmation's title. */
    fun deleteTitle(config: NetworkConfig): String = "Delete ${config.name}?"

    /**
     * Named plainly because it is not recoverable and is much larger than the row being tapped: the
     * server cascades the network's buffers, so this is the conversation history too, on every
     * device.
     */
    const val DELETE_MESSAGE = "This removes its channels, direct messages and history from Lurker, on all your devices."

    /**
     * Why Connect and Reconnect are missing on a blocked network. iOS's `NetworkRow.blockedExplanation`
     * (`NetworkCopy.swift`).
     */
    const val BLOCKED_EXPLANATION = "This server's administrator limits which networks can be connected to."
}

/**
 * A network verb in words — iOS's `NetworkAction.title` (`NetworkCopy.swift`). With
 * `ConnectionState.label` (the buffer list's, `ui/list`), the copy both the networks screen and the
 * server buffer's sheet (U-later) use, so "Offline" and "Disconnect" can't drift between them.
 */
val NetworkAction.title: String
    get() = when (this) {
        NetworkAction.Connect -> "Connect"
        NetworkAction.Disconnect -> "Disconnect"
        NetworkAction.Reconnect -> "Reconnect"
        NetworkAction.Delete -> "Delete"
    }
