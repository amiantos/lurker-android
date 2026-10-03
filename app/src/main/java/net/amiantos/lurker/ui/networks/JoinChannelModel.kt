// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurkerkit.model.BufferOrder
import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * One network in the Join Channel picker: exactly what a row draws and the join reads, so the
 * dialog's projection of the store compares only this.
 */
data class JoinNetworkOption(val id: Int, val name: String, val state: ConnectionState, val appConnected: Boolean = true) {
    /**
     * Whether a JOIN can travel now: the network is up AND Lurker's own socket is — the test
     * `ChatViewModel.requestJoin` makes. While the app is reconnecting a network's state is
     * last-known, and offering Join then would dismiss the dialog only to say "not connected".
     */
    val connected: Boolean get() = appConnected && state == ConnectionState.Connected
}

/**
 * Join a channel: type its name, pick the network — lurker-ios's `JoinChannelViewController` and the
 * buffer list's `join(network:channel:)`, as rules. The screen is `JoinChannelDialog`.
 *
 * It replaces a `+` menu that listed every network and an alert per network. That shape put the
 * *rarer* half of the decision first — which network, a thing most accounts answer the same way
 * every time — and grew with the account. Here the channel name is the first field and the network
 * is a picker under it with a sensible default; a one-network account never touches it.
 */
object JoinChannelModel {

    /** The picker's networks, in the buffer list's order. */
    fun options(state: ChatState): List<JoinNetworkOption> {
        val appConnected = state.reachable && state.connection == SocketStatus.Connected
        return BufferOrder.networks(state.networks).map {
            JoinNetworkOption(id = it.id, name = it.displayName, state = it.state, appConnected = appConnected)
        }
    }

    /** The network this would join on, when there is one that could actually carry it. */
    fun target(selected: Int?, options: List<JoinNetworkOption>): JoinNetworkOption? =
        options.firstOrNull { it.id == selected }?.takeIf { it.connected }

    /**
     * The first connected network, since a JOIN needs a socket to travel down — falling back to the
     * first of any kind rather than to nothing, so the picker always shows a selection and Join
     * stays disabled to say why it can't be used yet.
     */
    fun defaultSelection(options: List<JoinNetworkOption>): Int? = (options.firstOrNull { it.connected } ?: options.firstOrNull())?.id

    /**
     * The selection after the networks moved.
     *
     * ⚠ Re-picked, not merely validated. The default is "the first connected one", and that answer
     * changes: two disconnected networks open the dialog with the first one selected, and when the
     * *second* finishes connecting the selection would have stayed on the unusable row with Join
     * still disabled, over a working socket. A selection deleted from the web is the same problem
     * from the other end — nothing selected at all, against a picker whose rule is that something
     * always is. A selection that still works is the user's, and stays.
     */
    fun reconcile(selected: Int?, options: List<JoinNetworkOption>): Int? =
        if (target(selected, options) == null) defaultSelection(options) else selected

    /**
     * Whether Join can be used. Literally the same test the join makes ([channelToSend]), so the
     * button can't offer what the action would refuse — `namesAChannel` exists because the two used
     * to be separate spellings of one rule, with a comment claiming they agreed.
     */
    fun canJoin(selected: Int?, options: List<JoinNetworkOption>, channel: String): Boolean =
        target(selected, options) != null && ChannelName.namesAChannel(channel)

    /**
     * The name to send, or null when what's typed names no channel. A bare sigil is not a name:
     * `ensurePrefix("#")` would send a JOIN for "#".
     *
     * ⚠ The TRIMMED name. `namesAChannel` trims before testing — that's the point of it owning the
     * rule — so passing the raw string on means " #swift" clears the guard and then gets a sigil
     * prepended to a leading space: `JOIN "# #swift"`. A channel is any of the four sigils, so a
     * name that already carries one is left alone.
     */
    fun channelToSend(typed: String): String? {
        if (!ChannelName.namesAChannel(typed)) return null
        return ChannelName.ensurePrefix(typed.trimmingWhitespacesAndNewlines())
    }

    /** Said under the field, because "#" is the part people leave off. */
    const val FIELD_FOOTER = "A # is added if you leave it off."

    /** A network that can't carry a JOIN, named rather than hidden: the network is still yours. */
    const val NOT_CONNECTED = "not connected"
}
