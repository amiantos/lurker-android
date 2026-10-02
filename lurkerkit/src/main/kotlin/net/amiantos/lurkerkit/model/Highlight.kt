// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * One row from `GET /api/highlights` — a message a highlight rule matched, carried with
 * the buffer it lives in. Unlike a `Message` in a buffer's log, a highlight is shown
 * *away* from its buffer (in the recent-highlights list), so it has to name its own
 * network and channel: the list spans every buffer at once.
 *
 * The server's row is a full `MessageEvent` plus `networkName`; `message` holds the event
 * (nick/text/time/matched/…) and the rest is the buffer address needed to render the
 * context line and to jump back to the conversation.
 */
data class HighlightItem(
    val message: Message,
    /**
     * The network the match happened on. Null would mean the app-scoped system buffer, which
     * never carries rule matches — so in practice this is always present, but it's nullable
     * to mirror `Buffer.networkId` and to build a `BufferKey` without a special case.
     */
    val networkId: Int?,
    /**
     * The channel or DM target, as the server stored it — used both to label the row and,
     * with `networkId`, to resolve the buffer to jump to.
     */
    val target: String,
    /**
     * The network's display name, resolved server-side so the list can name it without
     * waiting on the client's own roster to have loaded.
     */
    val networkName: String?,
    /**
     * Set on an activity-feed row that is someone's reaction to one of your lines
     * (lurker-ios#183), null on every highlight, bookmark and search hit. On such a row
     * `message` is the reaction as a line — the reactor's nick and host, the value as its
     * text, the reaction's time — because that is what an ignore rule judges and what the
     * header names; its `id` is still your line's, the jump target.
     */
    val reaction: FeedReaction? = null,
) {
    /** The buffer this match belongs to, for jumping back to the conversation. */
    val bufferKey: BufferKey get() = BufferKey(networkId = networkId, target = target)
}

/**
 * A page of highlights. `nextBefore` is the cursor for the next (older) page — the id to
 * pass as `before=` — or null when this page reached the end (the server returned fewer
 * rows than the limit). Mirrors the server's `{ items, nextBefore }` response.
 *
 * Port note: LurkerKit has two `init`s, told apart by their label (`nextBefore:` and `next:`).
 * Both are constructors here, told apart by the argument's type — so a bare `null` fits
 * either and has to be passed by name (`next = null`, or `nextBefore = null`; they build the
 * same page).
 */
data class HighlightsPage(
    val items: List<HighlightItem>,
    /** Where the next older page starts, or null at the end. */
    val next: FeedCursor?,
) {
    constructor(items: List<HighlightItem>, nextBefore: Long?) :
        this(items = items, next = nextBefore?.let { FeedCursor(beforeMessage = it) })

    /** The message-id cursor the single-source feeds page on. */
    val nextBefore: Long? get() = next?.beforeMessage

    /**
     * Whether another (older) page exists. The server signals the end by dropping
     * `nextBefore` / `next` (null) once a page doesn't fill the limit.
     */
    val hasMore: Boolean get() = next != null
}

/**
 * Where a feed's next older page starts. Highlights, bookmarks and search page on one message
 * id; the activity feed merges two sources and keeps a cursor for each (`GET /api/activity`) —
 * either may be absent while that side has given nothing yet. Passed back to the server as-is.
 *
 * Port note: `beforeMessage` is a message id, so it is a `Long` here (PORTING.md, Types) — and
 * so is `beforeReaction`, a reaction's row id: an event id from a server sequence like the
 * other, and `FeedReaction.reactionId` below with it.
 */
data class FeedCursor(
    val beforeMessage: Long? = null,
    val beforeReaction: Long? = null,
)

/** A reaction-to-you row's own facts (lurker-ios#183). */
data class FeedReaction(
    val reactionId: Long,
    val value: String,
    /** The text of your line it was given on. */
    val lineText: String?,
)
