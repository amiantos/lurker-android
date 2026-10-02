// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.store.SocketStatus

/**
 * The connection banner shown across the top of the chat screen — the loud counterpart to
 * the title's `StatusLight` subtitle. The subtitle is always-on ambient; this only appears
 * when something is wrong, and says so where a glance can't miss it.
 *
 * It keys off the same two truths the light's outer layers do — the OS network path and the
 * Lurker socket — and in the same order: no path beats everything, because it's the one
 * failure the user can act on and the socket's own state is meaningless underneath it.
 * The exception is a server that can't take this build (lurker-ios#17), which outranks even
 * that. The IRC network layer is deliberately absent: a single disconnected network is the
 * subtitle's job, not a screen-wide banner claiming the whole app is offline.
 *
 * Port note: LurkerKit's `incompatible` case carries an unlabelled value; here it is named
 * `incompatibility`, as `SocketStatus` names its own.
 */
sealed interface ConnectionBannerState {
    /** Connected and reachable — the banner is gone. */
    data object Hidden : ConnectionBannerState

    /** The first connect of the session hasn't landed yet (launch, or a fresh sign-in). */
    data object Connecting : ConnectionBannerState

    /** The socket dropped and we're backing off toward it — reachable, so it's ours to fix. */
    data object Reconnecting : ConnectionBannerState

    /** No network path at all. Nothing else can be true, and it's the user's to fix. */
    data object Offline : ConnectionBannerState

    /**
     * The server and this build can't talk (lurker-ios#17). Nothing is retrying, and one side
     * needs an update.
     */
    data class Incompatible(val incompatibility: Incompatibility) : ConnectionBannerState

    /**
     * Whether this state is one we're actively working on (so the view spins). Offline is
     * not — there's nothing to spin about until the user brings a path back — and neither is
     * an incompatible server, which nothing retries.
     */
    val isWorking: Boolean
        get() = when (this) {
            Connecting, Reconnecting -> true
            Hidden, Offline, is Incompatible -> false
        }

    companion object {
        /**
         * Resolve the banner from the device path and the live socket.
         *
         * `reachable` is checked before the socket's progress for the same reason `StatusLight`
         * checks it first: the socket has no way to report "no internet", so a stale
         * `.reconnecting` under a dead path would otherwise read as "we're on it" when the
         * honest message is "you have no internet".
         *
         * An incompatible server comes before both. The app learned that from the server rather
         * than guessing it from a dead path, and coming back online won't change it.
         */
        fun of(reachable: Boolean, connection: SocketStatus): ConnectionBannerState {
            if (connection is SocketStatus.Incompatible) return Incompatible(connection.incompatibility)
            if (!reachable) return Offline
            return when (connection) {
                SocketStatus.Connected -> Hidden
                SocketStatus.Connecting -> Connecting
                SocketStatus.Reconnecting -> Reconnecting
                is SocketStatus.Incompatible -> Incompatible(connection.incompatibility)
            }
        }
    }
}
