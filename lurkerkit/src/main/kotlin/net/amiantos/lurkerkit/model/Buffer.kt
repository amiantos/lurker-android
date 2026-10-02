// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * A conversation surface: a channel, a DM, a per-network server buffer, or the
 * app-scoped system buffer. Identified on the wire by `bufferId` — the server's
 * stable integer that survives renames (protocol §5.2) — with `(networkId,
 * target)` as the always-present name-shaped address; `bufferId` is null only
 * until the first id-carrying frame lands (an optimistically-created buffer, or
 * a pre-id server). `networkId` is null ONLY for the system buffer.
 *
 * Per-buffer counts (`unread`/`highlights`/`lastReadId`) are server-authoritative:
 * they ship in `backlog`/`read-state` frames and the client never derives them
 * locally (full read-state handling is lurker-ios#7).
 *
 * Port note: immutable. LurkerKit mutates a buffer field by field (`buffer.unread = 0`) and
 * through `applyCleared`; here every property is a `val`, a change is a `copy(...)`, and
 * `applyCleared` returns the updated copy (PORTING.md, "Structs that mutate", case 2).
 *
 * Port note: the constructor takes its arguments in the order of LurkerKit's `init` —
 * `bufferId` last — which is the order every call site is written in. The Swift declares the
 * stored `bufferId` first; nothing reads that order.
 *
 * Port note: `lastReadId` and `clearedBeforeId` are message ids, so they are `Long` here
 * (PORTING.md, Types). `bufferId`, `networkId` and the two counts stay `Int`.
 *
 * Port note: ⚠ `networkId`, `target` and `kind` are `let` in LurkerKit and `copy(...)` will
 * change them here all the same. Never `copy(target = …)`: `kind` is decided from the target
 * and would be left describing the old one. A rename goes through `renamed`.
 */
data class Buffer(
    val networkId: Int?,
    val target: String,
    val kind: BufferKind,
    val unread: Int = 0,
    val highlights: Int = 0,
    val lastReadId: Long = 0,
    val joined: Boolean = false,
    /**
     * False until the server has actually read this buffer's history. On a fresh
     * connect channel/DM buffers arrive as SHELLS (`events: []`); their history is
     * not read until the client asks for it (`ChatViewModel.hydrate`).
     */
    val hydrated: Boolean = false,
    /**
     * Whether `lastReadId` is something the server SAID, rather than this class's default.
     *
     * Three paths materialize a buffer row without any read state attached — the connect
     * `snapshot` (a row per joined channel), a live event for an unseen target (a new DM), and
     * a `history` reply (which flips `hydrated` but carries no read fields at all). Under all
     * three, `lastReadId` reads 0, which is indistinguishable from "read nothing" and cannot be
     * told apart by looking at the value. Only `backlog` and `read-state` frames carry the
     * pointer, so only they set this.
     *
     * It exists because a reader whose boundary is latched from a defaulted 0 loses their
     * unread divider outright, and anything that marks the buffer read before the real pointer
     * arrives destroys it for good — the pointer is the only record of where they left off.
     * `hydrated` is NOT a stand-in for this: `history mode:latest` sets it while saying nothing
     * about read state, which is exactly the case that bug arrived through.
     */
    val readStateKnown: Boolean = false,
    /**
     * Whether more history exists above what's loaded — gates the scroll-up pagination
     * (lurker-ios#6). Defaults true (an unopened buffer has all its history still to fetch).
     */
    val hasMoreOlder: Boolean = true,
    /**
     * Whether the loaded slice sits *below* the live tail — true only after a jump lands an
     * `around` slice centered on an older message (lurker-ios#42). While set, the buffer is
     * "detached": live events are held out of the log (they'd splice a hole past the slice),
     * and the jump-to-latest pill re-attaches by fetching the latest. A normal (latest) buffer
     * is at the tail, so this defaults false.
     */
    val hasMoreNewer: Boolean = false,
    /**
     * The `/clear` marker's boundary: the highest message id hidden from the live view
     * (lurker-ios#121). 0 means the buffer has never been cleared, or the user undid it.
     *
     * Server-side and per-user, so it is shared with every other device — which is why this
     * is honoured whether or not this client can *issue* a clear. It survives a close and
     * reopen: the server keeps it on `buffer_reads`, which closing doesn't touch.
     *
     * ⚠⚠ The filter is suppressed while the buffer is **detached**. A jump to a search hit or
     * a highlight shows context around its anchor regardless of the marker — the user asked
     * to see that message, and hiding it would answer their tap with an empty screen. Same
     * rule as the web (`MessageList.vue:1064`), and the reason the filter lives at row-build
     * time rather than in the store: the messages are still there, they are just not drawn.
     */
    val clearedBeforeId: Long = 0,
    /**
     * When the clear was issued, for the divider's label. Null exactly when `clearedBeforeId`
     * is 0.
     *
     * An instant rather than a formatted string, like `awayDivider`'s: what the label says is
     * a render-time decision, so a redraw corrects it when the locale or the day changes.
     */
    val clearedAt: Instant? = null,
    /**
     * A channel's topic, when it has one. Null on a channel with no topic set *and* on
     * every other kind — nothing but a channel has one.
     *
     * Fed from three places, because the server has three ways of saying it: the
     * `snapshot` (on connect), a `channel-topic` ephemeral (RPL_TOPIC on join, silent),
     * and a `topic` event (someone changed it, which also prints a line).
     */
    val topic: String? = null,
    /**
     * The server's stable buffer id (never changes, renames included). Not fixed at
     * creation: a row created before the id was known learns it from a later frame.
     */
    val bufferId: Int? = null,
) {
    val key: BufferKey get() = BufferKey(networkId = networkId, target = target)

    /**
     * Whether a page of older history could contain a row this buffer would actually draw.
     *
     * False when a `/clear` is in force and `oldestHeldId` is already at or below the
     * boundary: everything older than the oldest held message is older still, so every row a
     * page could bring is hidden by definition and the fetch cannot add a visible line.
     *
     * ⚠⚠ Without this the screen becomes a history vacuum rather than merely over-fetching. A
     * cleared buffer builds to a single divider, which is unscrollable, which asks for another
     * page, which is also entirely hidden, which is still unscrollable — walking the whole
     * buffer into memory an invisible page at a time behind a stuck spinner. The web guards
     * the same thing at the same point (`MessageList.vue:1564`).
     *
     * Detached is exempt because the FILTER is exempt: a jump slice shows its context
     * regardless of the marker, so those pages are visible and worth fetching.
     * `showingClearedHistory` is the reader's own decision to look past the marker — a jump
     * landed on a hidden row — and it re-arms paging, because they can now scroll up through
     * what was hidden. It is view state, owned by the screen (on iOS, `ChatViewController`),
     * which is why it arrives as an argument rather than living here: it lasts exactly as long
     * as the screen does, and a buffer reopened later is cleared again.
     */
    internal fun olderPageCouldBeVisible(oldestHeldId: Long, showingClearedHistory: Boolean = false): Boolean {
        if (hasMoreNewer || showingClearedHistory || clearedBeforeId <= 0) return true
        return oldestHeldId > clearedBeforeId
    }

    /**
     * Move the `/clear` marker (lurker-ios#121) — a `buffer-cleared` frame, from this device
     * or another.
     *
     * The two fields move together or not at all. `beforeId <= 0` is the UNDO and clears
     * both, which is exactly what the server sends for `/clear off`.
     *
     * ⚠⚠ So is a boundary with no instant — the state that would hide every row and draw no
     * divider, leaving the reader a blank buffer whose only way out is a `/clear off` nobody
     * told them about. Showing messages the user cleared is the safe direction to fail in.
     *
     * Port note: returns the updated buffer, where LurkerKit's mutates in place.
     */
    internal fun applyCleared(beforeId: Long, at: Instant?): Buffer {
        if (beforeId <= 0 || at == null) {
            return copy(clearedBeforeId = 0, clearedAt = null)
        }
        return copy(clearedBeforeId = beforeId, clearedAt = at)
    }

    /**
     * This buffer under a new name — the rename primitive's client half. Same
     * id (that's the point), same counts and read state; `kind` re-derives in
     * case the sigil changed, and paging state carries as-is (the caller wipes
     * it separately on a merge, where history actually changed).
     */
    internal fun renamed(newTarget: String): Buffer =
        Buffer(
            networkId = networkId,
            target = newTarget,
            kind = BufferKind.of(networkId = networkId, target = newTarget),
            unread = unread,
            highlights = highlights,
            lastReadId = lastReadId,
            joined = joined,
            hydrated = hydrated,
            readStateKnown = readStateKnown,
            hasMoreOlder = hasMoreOlder,
            hasMoreNewer = hasMoreNewer,
            clearedBeforeId = clearedBeforeId,
            clearedAt = clearedAt,
            topic = topic,
            bufferId = bufferId,
        )

    /**
     * What to call this buffer wherever the user sees it.
     *
     * Shared rather than per-screen: the chat title and the buffer switcher name the same
     * buffer one tap apart, and two copies of this drifted immediately on iOS — the switcher
     * called a server log "Server" while the title it opened called it "libera".
     *
     * `networkName` is the network this buffer belongs to, when it's known; only a server
     * log uses it, and it falls back rather than requiring the caller to have resolved the
     * roster yet.
     */
    fun displayName(networkName: String? = null): String =
        when (kind) {
            BufferKind.System -> "Lurker" // the app's own buffer, not a target you'd recognize
            BufferKind.Server -> networkName ?: "Server"
            BufferKind.Channel, BufferKind.Dm, BufferKind.Dcc -> target
        }

    companion object {
        /** The server's sentinel target for the app-scoped system buffer. */
        const val systemTarget = ":system:"

        /**
         * A network's server-log target. The web's `serverTarget()`, and the same string the
         * server uses.
         *
         * ⚠ The iOS client only ever *recognised* the prefix before, never built one — so a
         * network's server log appeared in the buffer list solely when a row for it happened to
         * arrive, and vanished when the connect burst's prune didn't name it. The web has no
         * such gap: its network header **is** the server buffer, addressed by a target it
         * constructs itself, so every network always has one visible way in.
         */
        fun serverTarget(networkId: Int): String = ":server:$networkId"

        /**
         * The system buffer, constructed without the server. It's app-scoped and always
         * exists, so the app can open it as its landing screen before any frame has arrived;
         * the real one folds in over this when the backlog lands.
         */
        val system = Buffer(networkId = null, target = systemTarget, kind = BufferKind.System)
    }
}

/**
 * Stable identity for a buffer, plus its string form for use as a map key.
 *
 * IRC targets are case-insensitive and servers send them inconsistently cased
 * (`#Chan` on join vs. `#chan` in a snapshot; DM nick-case drift). So identity
 * folds case while [target] keeps the original casing for display and for echoing
 * back to the server on send. The fold is client-internal, so any deterministic
 * mapping works; house style is lowercase.
 *
 * Port note: as in LurkerKit, it is [id] that folds — two keys that differ only in the
 * casing of `target` are NOT equal to each other, and have the same `id`. Key a map on `id`.
 */
data class BufferKey(
    val networkId: Int?,
    val target: String,
) {
    /**
     * `"<networkId>::<target, lowercased>"`, with `sys` standing in for a null network.
     *
     * Port note: LurkerKit's `lowercased()`, so the whole of Unicode folds, not only ASCII.
     * Checked against the Swift over a corpus, `lowercase()` gives the same string except in
     * one place: it applies the final-sigma rule (a word-final `Σ` becomes `ς`, as the web's
     * `toLowerCase()` does) where Swift maps every `Σ` to `σ`. So `#ΟΔΟΣ` and `#οδοσ` share an
     * id on iOS and not here, and `#ΟΔΟΣ` and `#οδος` share one here and not on iOS. The fold
     * is client-internal and every key on this side is built here, so the two clients never
     * compare ids.
     */
    val id: String get() = "${networkId?.toString() ?: "sys"}::${target.lowercase()}"
}

enum class BufferKind {
    Channel,
    Dm,

    /**
     * A `=nick` DCC chat (lurker#270): a conversation with one person, like a DM, but carried
     * on a direct socket rather than over IRC. See `DccChat`.
     *
     * Its own kind rather than a flavour of `Dm`, and the server draws the same line
     * (`kind: 'dcc'`). Classed as a DM it gets everything a DM gets, and most of that puts the
     * name somewhere it doesn't belong: a presence row that goes offline when the network drops
     * (the chat doesn't), a Friends entry the server refuses, a WHOIS for `=bob` on the wire.
     */
    Dcc,
    Server,
    System;

    /**
     * Whether an event of this type is something this buffer kind shows.
     *
     * This is per-kind and not a single global predicate because a buffer kind's content
     * is not the same shape everywhere:
     *
     *  - A **channel or DM** shows speech *and* its structural traffic — joins, parts,
     *    quits, nick changes, modes, kicks, topics, invites, and the odd inline error or
     *    encryption line. Consecutive membership churn collapses (see `Consolidation`), so
     *    showing it is signal, not noise. What a channel never carries is the app/server
     *    scoped `motd`/`system`, or the unmodeled `Other` state events (usermode, lag,
     *    peer-presence) that have no body — those are excluded here.
     *  - The **system buffer**'s content is *entirely* `type: "system"` lines, which are
     *    not speech — filtering it by `isSpeech` renders it permanently empty.
     *  - A **server log** is a log: it shows everything. What the server actually sends
     *    there is `motd`, `usermode`, `error`, `notice` and `invite`, and only `notice`
     *    is speech — so `isSpeech` hid the entire MOTD, every mode line, and every server
     *    error, leaving a buffer that looked almost empty rather than broken.
     */
    fun renders(type: EventType): Boolean =
        when (this) {
            System -> type == EventType.System
            Server -> true
            Channel, Dm, Dcc ->
                when (type) {
                    EventType.Motd, EventType.System, EventType.Other -> false
                    else -> true
                }
        }

    /**
     * Whether this kind has a shell that needs filling in.
     *
     * It doesn't for the system buffer or a `:server:` log: their history ships complete in
     * the connect backlog, so there is nothing on demand to fetch.
     *
     * This used to carry a sharper warning — hydration went through `open-buffer`, which
     * discards both kinds up front and sends NO reply, so asking stranded the caller on a
     * reply the server had already thrown away. Hydration is `{type:'history',
     * mode:'latest'}` now, which answers for any target it can read, so the hazard is gone
     * and this is once again just "don't ask for what you already have".
     */
    val hydratesOnDemand: Boolean
        get() = when (this) {
            Channel, Dm, Dcc -> true
            System, Server -> false
        }

    companion object {
        /**
         * Classify a target the way the server does (kindForTarget): all FOUR IRC channel
         * sigils count — `ChannelName.isChannelTarget`, the one owner of the question — or a
         * favorited `+foo` would masquerade as a person under Friends, presence dot and all.
         *
         * Port note: every test here reads UTF-16 units from the front of `target`, as the
         * server's do. LurkerKit's read `Character`s, so a sigil, a `=` or the `:` of
         * `:server:` that carries a combining mark is some other character there (the target
         * falls through to a DM) and is itself here. No IRC target starts that way.
         */
        fun of(networkId: Int?, target: String): BufferKind {
            if (networkId == null || target == Buffer.systemTarget) return System
            if (target.startsWith(":server:")) return Server
            if (ChannelName.isChannelTarget(target)) return Channel
            if (DccChat.isTarget(target)) return Dcc
            return Dm
        }
    }
}
