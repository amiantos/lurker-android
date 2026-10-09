// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * An IRC network the account is configured on, as the app *renders* it: the roster's
 * name plus whatever the socket last said about the connection. The editable
 * configuration behind it — host, port, credentials — is `NetworkConfig`, fetched on
 * demand; this one is read on the hot path of every frame the store reduces.
 *
 * `name` comes from REST (`GET /api/networks`); the live `state`/`nick` come from the WS
 * `snapshot` and the `state` event, and are merged in — neither carries a name.
 *
 * Port note: immutable. LurkerKit sets a network's fields one by one (`network.state = …`)
 * and through `mergeRoster`; here every property is a `val`, a change is a `copy(...)`, and
 * `mergeRoster` returns the updated copy (PORTING.md, "Structs that mutate", case 2).
 *
 * Port note: `position` defaults to `Int.MAX_VALUE` — 32-bit, where LurkerKit's `.max` is
 * 64-bit. It is only ever a "sorts last" sentinel beside small ordinals.
 */
data class Network(
    val id: Int,
    /**
     * The roster's name for this network, or null when we haven't heard it yet.
     *
     * ⚠⚠ Optional rather than a placeholder string, and that is the whole of lurker-ios#136.
     * The snapshot can name a network the roster doesn't hold — a failed roster fetch, or a
     * network added from another client mid-session — and the row it materialized used the
     * literal `"network"`, which is indistinguishable from a real name by anything
     * downstream. So the app said "network" where a name belonged and no code could tell
     * it was lying. Null is the honest reading, and it's what triggers the roster re-read
     * (`ChatViewModel.refreshRosterIfAnyNetworkIsNameless`).
     */
    val name: String?,
    /**
     * Where this network sits in the user's own ordering — the `position` column, which
     * `GET /api/networks` is already sorted by and which the web sidebar's drag-to-reorder
     * writes.
     *
     * REST-only, like `name`: no frame carries it. A network the roster hasn't described yet
     * gets `Int.MAX_VALUE` and sorts last rather than jumping to the front, which is the less
     * startling of the two ways to be wrong for the moment before the roster lands.
     */
    val position: Int = Int.MAX_VALUE,
    val state: ConnectionState = ConnectionState.Disconnected,
    val nick: String = "",
    /**
     * Your own away state, as this network last reported it (lurker-ios#68).
     *
     * Network-scoped (lurker#994): `/away` sets the network it's typed on, or every network for
     * `-all` or the `away.all_networks` setting, so one network can be away while another isn't —
     * read this network's value as this network's alone, never as the account's. Null means the
     * server hasn't reported one, which is also what it sends for a user who has never been away.
     *
     * Port note: LurkerKit's copy of this comment still says user-scoped, with every network
     * carrying the same value — stale since lurker#994, which changed `Presence.swift`'s
     * `AwayState` doc and not this one.
     */
    val away: AwayState? = null,
    /**
     * True when the instance admin's allowlist excludes this network's host (lurker#298).
     *
     * REST-only like `name` and `position` — no frame carries it — and held here rather than
     * left to `NetworkConfig` because it is a fact about what a *rendered* network can be
     * asked to do: the server buffer's info sheet decides whether to offer Connect off this,
     * live from the store, rather than re-reading the roster every time it opens
     * (lurker-ios#152). It gates only *new* connections, so a network can be blocked and
     * connected at once — see `NetworkRow.isBlocked`.
     *
     * Absent reads as "not blocked": an older server has no allowlist to be excluded from,
     * and a network the roster hasn't described yet has nothing to say about it.
     */
    val blocked: Boolean = false,
    /**
     * Which client tags can go out on this network as the server last said — a reaction, taking
     * one of ours back, a reply's tag (the snapshot, then `react-support`). **Read through
     * `ChatState.tagSupport(networkId:)`** and its accessors, which also require the network to
     * be connected: this describes the last registration, and a dropped link carries nothing.
     */
    val tagSupport: TagSupport = TagSupport.nothing,
    /**
     * The network's channel-mode vocabulary (lurker#727) — which letters are lists, flags and
     * params, its PREFIX ladder, MODES and TOPICLEN.
     *
     * ⚠⚠ Null means UNKNOWN: the server sends null until the registration burst ends, and this
     * goes back to null whenever the link drops (the next registration restates it). Never read a
     * null as the RFC defaults — a default is not the network saying so.
     */
    val modeSpec: ModeSpec? = null,
) {
    /**
     * Take the roster's word for the REST-only fields — `name`, `position`, `blocked` — and
     * keep everything the socket said.
     *
     * Here, beside the declarations, rather than as assignments in the store's merge: the
     * store's copy of this list was missed once on iOS (`position` didn't survive a merge until
     * a review caught it, f69c30a) and extended by hand once more (`blocked`). A field added
     * above and to `FrameParser.parseNetworks` has one more place to be added, and it's the
     * next line down.
     *
     * Port note: returns the updated network, where LurkerKit's mutates in place.
     */
    fun mergeRoster(roster: Network): Network =
        copy(
            name = roster.name,
            position = roster.position,
            blocked = roster.blocked,
        )

    /**
     * What to call this network wherever the user sees one named.
     *
     * Shared so the fallback can't drift between the join menu, the networks screen and
     * anything later: an unnamed network is a transient state (the re-fetch closes it) but
     * it still has to render as *something*, and every site inventing its own word is how
     * lurker-ios#136's placeholder got mistaken for a name in the first place.
     */
    val displayName: String get() = name ?: unnamedDisplayName

    companion object {
        /**
         * What to call a network whose name we haven't heard.
         *
         * Exposed because the buffer list also has to name a *section* for buffers whose network
         * has no roster entry at all — a case with no `Network` to ask. That site had its own
         * literal on iOS, and the literal was `"network"`: lurker-ios#136's placeholder, still
         * lying in the one place the fix didn't reach.
         */
        const val unnamedDisplayName = "Unnamed network"
    }
}

/**
 * What a network lets this client's tags carry (§5.1): a reaction, taking one of ours back, and a
 * reply's tag — three answers, not one, because a network can allow some and deny others. irc.so's
 * UnrealIRCd takes `+draft/react` and `+reply` but denies `+draft/unreact` (lurker#1101), and a
 * reply's tag needs no echo-message, so it can be allowed where reactions aren't.
 *
 * ⚠ Resolved at parse time from the server's four booleans (`FrameParser.tagSupport`): a server
 * that predates the split sends only `canReact`, which then stands for all three.
 */
data class TagSupport(
    /** A `react` verb without `remove` can go out. */
    val canAddReaction: Boolean,
    /** A `react` with `remove: true` can go out. The server refuses a removal otherwise, silently. */
    val canRemoveReaction: Boolean,
    /**
     * A line sent with `replyTo` carries its reply tag. Where it can't, it still goes out, as a
     * plain line.
     */
    val canReply: Boolean,
) {
    companion object {
        /** Nothing goes out: before the burst ends, and on a link that's down. */
        val nothing = TagSupport(canAddReaction = false, canRemoveReaction = false, canReply = false)
    }
}

/** Mirrors the server's per-network `state` string. */
enum class ConnectionState(val rawValue: String) {
    Connecting("connecting"),
    Connected("connected"),
    Reconnecting("reconnecting"),
    Disconnected("disconnected");

    companion object {
        fun fromRawValue(raw: String): ConnectionState? = entries.firstOrNull { it.rawValue == raw }

        fun from(raw: String?): ConnectionState =
            when (raw) {
                "connecting" -> Connecting
                "connected" -> Connected
                "reconnecting" -> Reconnecting
                else -> Disconnected
            }
    }
}
