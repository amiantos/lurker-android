// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.store.SocketStatus

/**
 * How a buffer's connection is doing, in the web client's three `.indicator` states (`good` /
 * `warn` / `bad`). The title says it in words (`subtitle`); the networks screen still draws it
 * as a dot.
 *
 * Three states, not two: amber is the honest default while something is still trying,
 * and it's what the web client shows for a connecting/reconnecting network. Red is
 * reserved for "this is actually broken and isn't fixing itself".
 */
enum class StatusLight {
    Good,
    Warn,
    Bad;

    /**
     * What a title's subtitle says: "Connected", "Libera · Online", "Libera · Away".
     *
     * Words only — there's no coloured dot beside them. `detail` is what the title doesn't
     * already name (a channel's network); without one, the subtitle is about Lurker's own
     * connection and says "Connected".
     *
     * `peer` is a DM's other person, and once the link is good it replaces "Online": the
     * question on a DM is whether *they're* there, and the network being up says nothing about
     * that. It never overrides a light that isn't good. A peer on a network we've lost can't be
     * seen at all, and "Offline" there would be a claim about them we have no grounds for —
     * "Disconnected" is the true thing to say. `unknown` says nothing past the network name,
     * for the same reason (no MONITOR, or not heard from yet) — and with no name to say
     * either, falls back to "Connected" rather than leaving the subtitle blank.
     */
    fun subtitle(detail: String?, peer: FriendPresence? = null): String {
        val words: String? = when (this) {
            Good -> when (peer) {
                FriendPresence.Online -> "Online"
                FriendPresence.Away -> "Away"
                FriendPresence.Offline -> "Offline"
                FriendPresence.Unknown -> if (detail == null) "Connected" else null
                null -> if (detail == null) "Connected" else "Online"
            }
            Warn -> "Connecting…"
            Bad -> "Disconnected"
        }
        return listOfNotNull(detail, words).joinToString(" · ")
    }

    companion object {
        /**
         * Resolve the light for a buffer.
         *
         * The layers are checked outside-in, because an outer failure makes every inner
         * state meaningless:
         *  1. **No network path at all** → red. Nothing else can be true, and it's the one
         *     failure the user can act on (turn on wifi).
         *  2. **The Lurker socket** → amber while connecting/reconnecting: a dropped socket is
         *     always retrying, so it's amber, not broken. The one red is a server that can't
         *     take this build (lurker-ios#17), which retrying won't fix.
         *  3. **The IRC network** → green connected, amber connecting/reconnecting, red
         *     disconnected. Red means the server gave up and isn't coming back on its own.
         *
         * `network` is null for the system buffer, whose whole story is the socket — so once
         * the socket is up, it's green.
         *
         * DM buffers pass their *network's* state like a channel does. The peer's own presence
         * only means something once that's good, so `subtitle` layers it on top rather than
         * folding it in here.
         */
        fun of(
            reachable: Boolean,
            connection: SocketStatus,
            network: ConnectionState?,
        ): StatusLight {
            if (!reachable) return Bad
            when (connection) {
                SocketStatus.Connecting, SocketStatus.Reconnecting -> return Warn
                is SocketStatus.Incompatible -> return Bad
                SocketStatus.Connected -> {}
            }
            if (network == null) return Good
            return when (network) {
                ConnectionState.Connected -> Good
                ConnectionState.Connecting, ConnectionState.Reconnecting -> Warn
                ConnectionState.Disconnected -> Bad
            }
        }

        /**
         * Resolve the light for a `=nick` DCC chat (lurker#270): the same outer layers, then the
         * chat's own session in place of the network.
         *
         * ⚠ Never the network's state. The session is a socket the server holds straight to the
         * peer, so it keeps working while the IRC link is down — a network light here went red
         * over a chat that worked, and stayed green over one that had died with a server restart.
         *
         * Red for a chat with no session, not amber: a dead chat is broken and doesn't fix
         * itself — it can't be resumed, only replaced with `/dcc chat`. `live` is null until this
         * socket's snapshot has said which chats are live, and that reads amber, like anything
         * still settling.
         */
        fun ofDccChat(reachable: Boolean, connection: SocketStatus, live: Boolean?): StatusLight {
            val outer = of(reachable = reachable, connection = connection, network = null)
            if (outer != Good) return outer
            if (live == null) return Warn
            return if (live) Good else Bad
        }
    }
}
