// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * Your *own* away state — the self half of this file's subject, where `PresenceState` below
 * is the peer half.
 *
 * User-scoped rather than network-scoped (`/away` hits every connection, see lurker's
 * `user_away_state`), but broadcast per network, so each network carries an identical copy
 * and reading any one of them reads the user's state.
 *
 * `since` and `message` deliberately survive `/back` — the completed away→back pair is what
 * the message-list dividers render, so clearing them on return would erase the marker at
 * the moment it becomes drawable.
 */
data class AwayState(
    /**
     * Whether the user is away *right now*. Note this is not `backAt == null`: the server
     * keeps both, and a client that inferred one from the other would get the window
     * between a re-`/away` and its broadcast wrong.
     */
    val active: Boolean,
    /** The away reason, if one was given. */
    val message: String? = null,
    /**
     * When the current (or most recent) away began.
     *
     * Not optional, because an away with no beginning is not a state this client can do
     * anything with — both markers are placed from it — and the server treats it as the
     * existence test too, sending `away: null` outright when there's no `since`. `FrameParser`
     * refuses a blob it can't read one out of, so the two agree.
     */
    val since: Instant,
    /** Whether the server set this from idle rather than the user typing `/away`. */
    val autoSet: Boolean = false,
    /** When the user came back, or null while still away. */
    val backAt: Instant? = null,
)

/**
 * The raw peer-presence state the server reports for a watched nick, over the MONITOR rails.
 * One transition at a time: `back` is the AFK-cleared counterpart of `away` and reads as
 * online. Fed by the connect snapshot's `peerPresence` blob and live `peer-presence` events.
 */
enum class PresenceState(val rawValue: String) {
    Online("online"),
    Offline("offline"),
    Away("away"),
    Back("back");

    companion object {
        fun fromRawValue(raw: String): PresenceState? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The derived, disconnected-aware status a friend row shows. `unknown` is a real state, not
 * an error: a network with no MONITOR support (or a peer we share no channel with) simply
 * can't be resolved, and "potentially online" is the honest reading — distinct from a known
 * `offline`.
 */
enum class FriendPresence {
    Online,
    Away,
    Offline,
    Unknown,
}
