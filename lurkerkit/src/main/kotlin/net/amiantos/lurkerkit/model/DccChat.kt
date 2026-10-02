// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * DCC CHAT buffer names (lurker#270) — the client-side twin of `isDccChatTarget` and
 * `dccChatPeer` in the server's `shared/channels.ts`.
 *
 * `=bob` is a direct conversation with bob, carried on a socket the Lurker server holds open
 * to bob's client. It is **not an IRC target**: the server routes a line sent to it over that
 * socket, and the name must never reach the IRC wire as `PRIVMSG =bob`, `WHOIS =bob` or a CTCP.
 * The convention is irssi's.
 *
 * ⚠⚠ `=` is the third answer to "what is this target", after channel and DM, so every site that
 * reads "not a channel" as "a nick" is wrong for it. The structural defence is `BufferKind.Dcc`;
 * the helpers here are for the places that hold a bare target string, and for turning a buffer
 * name back into the person it is with.
 */
object DccChat {
    /**
     * Whether `target` names a DCC chat buffer. Any `=`-prefixed string, bare `=` included —
     * no nick or channel starts with `=`, so this is exact rather than over-broad (the server
     * made the same call after a bare `=` reached the wire as `PRIVMSG =`).
     *
     * Port note: tests the first UTF-16 unit, as the server's `startsWith('=')` does. LurkerKit
     * tests the first `Character`, so a `=` with a combining mark on it is not a chat there
     * and is one here.
     */
    fun isTarget(target: String): Boolean =
        target.firstOrNull() == '='

    /**
     * The peer a `=nick` buffer is chatting with; any other target comes back unchanged, so a
     * caller holding either a buffer name or a nick can pass it without testing first.
     */
    fun peer(target: String): String =
        if (isTarget(target)) target.substring(1) else target

    /** The buffer a chat with `nick` lives in. */
    fun target(nick: String): String =
        "=$nick"
}

/**
 * A peer's offer to open a DCC chat with us, still waiting on an answer.
 *
 * An offer is never auto-accepted (lurker#270): accepting makes the server dial an address the
 * peer chose, so it stays a deliberate act. It lives until the server says it is over — accepted,
 * declined, expired after ten minutes, or torn down — via `dcc-chat-offer-closed`, and every
 * snapshot re-lists the offers still pending in case that event was missed.
 */
data class DccChatOffer(
    /**
     * Minted by this client, not the server — the wire names an offer only by its peer. A fresh
     * id means a fresh offer: a peer offering again gets a new one, a snapshot re-listing an
     * offer we already hold keeps the old one. That is what lets the app ask about an offer
     * once, rather than once per reconnect.
     */
    val id: Int,
    val networkId: Int,
    /** The peer, as the server spelled them. */
    val nick: String,
    /**
     * The peer is firewalled and wants our server to listen. Only a live `dcc-chat-offer` says
     * so; an offer first learned from a snapshot reads `false`.
     */
    val passive: Boolean,
) {
    /** The buffer this chat would live in. */
    val key: BufferKey get() = BufferKey(networkId = networkId, target = DccChat.target(nick))

    /** Whether this is `nick`'s offer on that network — folded, as every IRC name comparison is. */
    internal fun isFrom(nick: String, networkId: Int): Boolean =
        this.networkId == networkId && this.nick.lowercase() == nick.lowercase()
}
