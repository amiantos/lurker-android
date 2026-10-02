// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.amiantos.lurkerkit.client.HistoryMode
import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.DccChatOffer
import net.amiantos.lurkerkit.model.DraftEntry
import net.amiantos.lurkerkit.model.DraftReply
import net.amiantos.lurkerkit.model.Drafts
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.IgnoreMatch
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.NickNoteSet
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.ReactionChange
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.RelayBot
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.SpeakerMap
import net.amiantos.lurkerkit.model.SystemLevel
import net.amiantos.lurkerkit.model.TypingActivity
import net.amiantos.lurkerkit.model.TypingEntry
import net.amiantos.lurkerkit.model.WhoisResult
import net.amiantos.lurkerkit.support.bumped
import net.amiantos.lurkerkit.support.moving
import net.amiantos.lurkerkit.support.setting
import java.time.Instant

/**
 * How the live socket stands, for a connection state the user can actually see.
 *
 * Port note: LurkerKit's `incompatible` case carries an unlabelled value; here it is named
 * `incompatibility`, the name of the accessor LurkerKit reads it through.
 */
sealed interface SocketStatus {
    data object Connecting : SocketStatus

    data object Connected : SocketStatus

    data object Reconnecting : SocketStatus

    /**
     * The server and this build can't talk (lurker-ios#17). Nothing reconnects: every attempt
     * would end the same way until the app or the server is updated.
     */
    data class Incompatible(override val incompatibility: Incompatibility) : SocketStatus

    /** Why the server and this build can't talk, when that's where things stand. */
    val incompatibility: Incompatibility? get() = null
}

/** A line the server refused, waiting for its composer — with the reply it went out as, if any. */
@ConsistentCopyVisibility
data class UnsentLine internal constructor(
    val text: String,
    val reply: PendingReply?,
)

/**
 * Immutable snapshot of everything the chat UI renders. The map keys are `BufferKey.id`.
 *
 * Port note: immutable (PORTING.md, "Structs that mutate", case 2). LurkerKit's thirteen
 * `mutating func`s keep their names here and return the updated state
 * (`state = state.applyReaction(change)`), and every field is a `val` changed through `copy`.
 * The fields LurkerKit declares `internal` or `internal(set)` are `internal` here, or public
 * where only the setter was internal — but `copy` can still set them; nothing outside the kit
 * should.
 *
 * Port note: message ids, and everything derived from one — `maxEventId`, `bookmarkedIds`,
 * the keys of `reactions` — are `Long`, as is the byte count `maxUploadBytes` (PORTING.md,
 * Types).
 *
 * Port note: a `data class`, so it has an `equals` LurkerKit's struct does not (it isn't
 * `Equatable` there). A `StateFlow` uses it on every assignment; unchanged fields share their
 * references, so the comparison stops at the identity check for everything but what moved.
 */
data class ChatState(
    val connection: SocketStatus = SocketStatus.Connecting,
    /**
     * Whether the device has a network path at all, per the OS — fed in by the app
     * (`ChatViewModel.setReachable`), the same way foreground/background is, so this
     * package stays free of the platform's network-path APIs (on iOS the `Network` framework,
     * whose `NWPath` would also collide with our own `Network` model type).
     *
     * Deliberately separate from `connection`: they're two different truths. The socket
     * only ever reports connecting/connected/reconnecting — it has no way to say "there
     * is no internet" — so without this the indicator could never legitimately show red.
     */
    val reachable: Boolean = true,
    /**
     * Whether this socket has delivered its `snapshot` yet.
     *
     * False from `socketOpen` until the burst's first frame lands. That's the window where
     * `connection` already reads `.connected` but `peerPresence` and every network's state are
     * still what they were before the drop, and `rowPresence` waits it out (lurker-ios#167). Not
     * `burstActive`: that only turns on when the snapshot itself arrives.
     */
    val snapshotSinceOpen: Boolean = false,
    /**
     * Highest persisted message id seen (excluding the system buffer, which has its own
     * id space) — replayed as `?since=` on reconnect so the server ships only the gap.
     * Populated now so lurker-ios#4 can resume without a store change.
     */
    val maxEventId: Long = 0,
    val networks: Map<Int, Network> = emptyMap(),
    val buffers: Map<String, Buffer> = emptyMap(),
    /**
     * Whether a full snapshot burst has finished this session — i.e. `buffers` is the
     * server's whole answer rather than a prefix of it.
     *
     * The buffer list needs this to tell "still arriving" from "you genuinely have no
     * buffers". Three plausible signals are all wrong, in ways that only show on some
     * accounts:
     *
     *  - **`connection`** — the socket reports `.connected` *before* the burst is applied,
     *    so it flashes the empty state in the gap. The same trap `BufferPlaceholder`
     *    documents for the message list.
     *  - **The `snapshot` frame** — not authoritative for buffer existence (see
     *    `ServerFrame.Snapshot`): its `channels` is empty for every network that isn't
     *    currently connected, and DMs and `:server:` logs are never in it. A DM-only account,
     *    or any launch while the networks are still connecting, would flash "no buffers".
     *  - **Emptiness itself** — indistinguishable from the answer being empty.
     *
     * So it's latched on `backlog-complete`, the frame that exists to say the burst is done.
     *
     * Never cleared on a drop. A reconnect resends everything, but the roster we already have
     * stays on screen while it does, so going back to "loading" would blank a list with
     * perfectly good content in it. `reset()` builds a fresh `ChatState`, so sign-out clears it.
     */
    val backlogComplete: Boolean = false,
    val messages: Map<String, List<Message>> = emptyMap(),
    val members: Map<String, List<Member>> = emptyMap(),
    /**
     * Each channel's modes, param values, creation time and topic setter (lurker#727), keyed
     * like `members`. A side table rather than fields on `Buffer` because the backlog path
     * replaces a buffer row wholesale, and this is state no backlog carries.
     *
     * ⚠⚠ Never holds the key — see `ChannelModeState`.
     */
    val channelModes: Map<String, ChannelModeState> = emptyMap(),
    /**
     * Buffer favorites (the Friends/Contacts successor): one server-authoritative
     * global ordered list spanning networks, seeded and corrected wholesale by
     * `favorites-changed`. The UI splits it by kind — DMs → Friends, channels →
     * Favorites.
     */
    val favorites: List<FavoriteEntry> = emptyList(),
    /**
     * Saved-message ids — what the bookmark toggle reads. **Read through
     * `isBookmarked(_:)`.**
     *
     * A cache of what this session has *seen*, not a mirror of what the account owns. There
     * is deliberately no bookmark snapshot in the connect burst: the server used to send
     * every saved id on every connect, which is the one piece of connect state that grows
     * without bound over an account's life. Instead each message row carries its own
     * `bookmarked` flag, so this fills in from the pages the client was going to render
     * anyway, and an id that isn't here is simply one whose line isn't loaded.
     *
     * That's sound because the only question ever asked of it is "is the line the user is
     * looking at saved?" — and a line being looked at is a line that was loaded. The full
     * Bookmarks list comes from `GET /api/bookmarks`, not from here.
     *
     * A page is authoritative for the rows it CONTAINS, in both directions — an unflagged
     * row means unsaved — but says nothing about the rows it doesn't. Silence about an id is
     * not an unsave. See `noteBookmarks(in:networkId:)`.
     *
     * The other way in is `noteBookmarked(ids:)`, for the `GET /api/bookmarks` feed, whose
     * rows carry no flag because every one of them is saved by definition.
     *
     * Ids are `messages` table ids. System-buffer lines have a *separate* id sequence that
     * overlaps this one, but they can't be bookmarked at all (the server's ownership check
     * joins through networks, which they have none of) — so nothing ever puts one in here,
     * and `MessageActions` never asks about one.
     */
    val bookmarkedIds: Set<Long> = emptySet(),
    /**
     * The IRCv3 reactions standing on each line we've seen, by message id (lurker-ios#183).
     * **Read through `reactionGroups(for:)`.**
     *
     * Beside the message rows rather than on them for the reason `bookmarkedIds` is: rows are
     * never patched in place, and a side map survives slices being swapped wholesale. Fed the
     * same two ways — each row that arrives is authoritative for itself (no `reactions` = none
     * stand on it now), and a live `reaction` frame adds or removes one. A cache of what this
     * session has SEEN; an id with no entry has no reactions as far as any loaded line goes.
     *
     * Network lines only. System-buffer rows have an id sequence of their own that overlaps
     * this one, so they never touch it. And only lines a buffer HOLDS: a `reaction` frame for a
     * line nobody loaded is dropped — the row brings its reactions with it when it's fetched —
     * so the map is bounded by what's loaded, and `dropBuffer` can free a buffer's share.
     */
    val reactions: Map<Long, List<MessageReaction>> = emptyMap(),
    /**
     * Bumped per buffer (`BufferKey.id`) whenever the reactions on its lines change. A chat
     * screen compares its own buffer's entry — one integer — so a reaction elsewhere doesn't
     * redraw it, and the gate that runs on every frame stays cheap.
     */
    val reactionsRevisions: Map<String, Int> = emptyMap(),
    /**
     * Peer presence keyed `networkId → lowercased nick → state`. Mirrors the server's
     * MONITOR-fed presence: seeded from each network snapshot's `peerPresence` blob and
     * patched by live `peer-presence` events. Read through `presence(networkId:nick:)`,
     * which layers the network's own connection state on top.
     */
    val peerPresence: Map<Int, Map<String, PresenceState>> = emptyMap(),
    /**
     * Peers with a live DCC chat session, keyed `networkId → display nicks` (lurker#270). Read
     * through `isDccChatLive`.
     *
     * ⚠ Independent of the network's state, which is the reverse of a DM: a DCC chat is a
     * socket the server holds straight to the peer, so it keeps working while the IRC link is
     * down — and a snapshot lists a disconnected network's chats for that reason. Seeded by
     * every snapshot, patched by `dcc-chat-state`.
     */
    val dccChats: Map<Int, List<String>> = emptyMap(),
    /**
     * DCC chat offers made to us and not yet answered, oldest first (lurker#270).
     *
     * Retired by `dcc-chat-offer-closed`, and reconciled against every snapshot's
     * `dccChatOffers` too, because that event is exactly the kind a phone misses: its socket
     * sleeps in the background. An offer that is gone but still here would leave the app asking
     * about it, and accepting then sends the peer a FRESH offer instead — a different act from
     * the one the question named.
     */
    val dccChatOffers: List<DccChatOffer> = emptyList(),
    /** The last id minted for a `DccChatOffer` — see its `id`. */
    internal val lastDccChatOfferId: Int = 0,
    /**
     * Pinned buffer targets per network, in the user's own order (`pins-changed`).
     *
     * A list the user arranged on the web and this app only renders — so it's held verbatim
     * rather than folded into the buffers themselves. Ordered, and a superset of what can be
     * shown: a pin row outlives its buffer being parted or closed.
     */
    val pinned: Map<Int, List<String>> = emptyMap(),
    /**
     * Who is composing, keyed `BufferKey.id → lowercased nick → entry`.
     *
     * Purely ephemeral — there is no snapshot for it, so this starts empty on every connect
     * and is fed entirely by live `typing` frames.
     *
     * **Read through `typists(in:now:)`, never directly.** An entry is removed only by an
     * event — a `done`/unrecognized state, a message from that nick, the buffer closing, the
     * socket dropping. Nothing prunes on a clock, so a lapsed entry stays in this map until
     * one of those happens; `typists` filters it out of the answer rather than deleting it.
     * Reading the map raw therefore reports people who stopped typing minutes ago.
     *
     * The residue is bounded and small — one entry per nick who typed here and then went
     * quiet without sending anything — so it's left to be cleaned up by the events above
     * rather than by a sweep that would only exist to tidy a map nobody reads directly.
     */
    val typing: Map<String, Map<String, TypingEntry>> = emptyMap(),
    /**
     * Who has spoken in each buffer and when, keyed `BufferKey.id`. Seeded from the server's
     * `speakers` list on `backlog`/`history` and kept current from live traffic — see
     * `SpeakerMap`. Drives the `.smart` event tier (lurker-ios#63) and consolidation's name
     * ranking.
     */
    val speakers: Map<String, SpeakerMap> = emptyMap(),
    /**
     * Bumped once per snapshot burst. The identity of "everything the server has told us
     * so far" — a change means it started over.
     *
     * This is what one-shot-per-connection requests must key off, NOT `connection`.
     * `connection` looks like the obvious signal and isn't: `handleClose` drops a close
     * callback that arrives after the socket has already been replaced (correct — a stale
     * close must not clobber its live replacement), so a socket can die and be replaced
     * with `connection` never leaving `.connected`. A request written to the dead socket
     * is then lost with no observable state change, and anything that latched "I already
     * asked" waits forever for a reply that cannot come.
     *
     * A burst, by contrast, is unmissable: every reconnect produces one, and it is the
     * server saying "here is everything again", which is exactly the moment a pending
     * request from the previous socket becomes void.
     */
    val burstGeneration: Int = 0,
    /**
     * Live events that arrived while a buffer was detached (lurker-ios#42), keyed like
     * `messages` — kept aside instead of being appended, and merged back in when it re-attaches.
     *
     * A detached buffer can't take a live event into its log: the slice sits far below the
     * tail, so appending would splice a hole. But *dropping* it loses the message outright,
     * because the re-attach fetch was built by the server BEFORE the event existed — a hole
     * the client can never notice and nothing ever refetches. That's the real cost, and it
     * isn't a rare race: walking a detached slice forward (lurker-ios#45) fires an `after` page
     * per screenful, and everything said during the last of those round trips falls in it.
     *
     * Capped per buffer at `heldLiveCap`, oldest dropped first. Trimming from the OLD edge is
     * what makes the cap safe: the re-attach keeps only the held events newer than the slice
     * it fetched, and that slice is itself the newest few hundred rows — so anything the trim
     * discards is either already in the fetch or older than it, never the gap above it.
     */
    internal val heldLive: Map<String, List<Message>> = emptyMap(),
    /**
     * Buffer keys the server has named in the snapshot burst currently in flight, so
     * `backlog-complete` can prune the ones it *didn't* — see `pruneToBurst`.
     */
    internal val burstSeen: Set<String> = emptySet(),
    /**
     * bufferId → storage key (`BufferKey.id`). Maintained by every path that
     * writes a `buffers` row with a known id; consumed by rename-following.
     */
    val keysById: Map<Int, String> = emptyMap(),
    /**
     * Whether a snapshot burst is in flight (a `snapshot` frame arrived, its terminal
     * `backlog-complete` hasn't). Guards the prune: a stray `backlog-complete` with no
     * burst behind it must not be read as "the server listed nothing", which would wipe
     * the roster.
     */
    internal val burstActive: Boolean = false,
    /**
     * The account's ignore rules (lurker #301). Seeded by the connect `snapshot` — global
     * rules from the frame, per-network ones from each network blob — and replaced a bucket
     * at a time by `ignore-list-updated`.
     *
     * Server-authoritative and read-only here: rules are authored on the web and fanned to
     * every device, so what this holds is the same set the server itself matched against
     * when it stamped the messages now on screen. Filtering happens at *render* time
     * (on iOS `ChatViewController.apply`, the member list, the feeds) rather than on the way
     * into this store, so a rule arriving mid-session re-filters the backlog already held —
     * and a rule going away brings those lines straight back with no refetch.
     */
    val ignores: IgnoreSet = IgnoreSet.empty,
    /**
     * The account's relay-bot marks (lurker#277). Seeded by the connect `snapshot` — one list
     * per network blob — and patched a nick at a time by `relay-bot-updated`.
     *
     * Server-authoritative and fanned to every device, like `ignores` above, and applied at the
     * same place for the same reason: the chat screen (on iOS `ChatViewController.apply`)
     * re-attributes at *render* time, so marking a bot re-labels the backlog already held and
     * unmarking hands it straight back.
     */
    val relayBots: RelayBotSet = RelayBotSet.empty,
    /**
     * The account's notes about nicks (lurker-ios#12). Seeded by the connect `snapshot` — one
     * list per network blob — and patched a nick at a time by `nick-note-updated`, exactly like
     * `relayBots` above and for the same reason: both are user-authored, server-stored, and
     * fanned to every device.
     */
    val nickNotes: NickNoteSet = NickNoteSet.empty,
    /**
     * Cached WHOIS replies, `networkId → folded nick → reply` (lurker-ios#12).
     *
     * ⚠ Volatile by nature and deliberately not persisted: who is on a network, idle for how
     * long and in which channels is stale within minutes, and a stale answer presented as a
     * current one is worse than none. It survives a reconnect within a session (matching the
     * web) because the screen re-asks on every open anyway — the cache exists to render
     * something *immediately* while that round trip is out, not to save the round trip.
     */
    val whois: Map<Int, Map<String, WhoisResult>> = emptyMap(),
    /**
     * The lookups currently out, as `networkId::folded nick`.
     *
     * ⚠⚠ **"A lookup is in flight", not "we asked once."** Three rules, each of which was a
     * shipped bug on the web (lurker#818) before it was a rule:
     *
     * 1. Freed when the lookup answers — **including a `not_found`, which IS an answer**.
     *    Leaving it claimed is what made a failed lookup un-retryable for the session.
     * 2. Claimed only when the WHOIS actually left the socket. A slot held for a request that
     *    never went out wedges identically: no reply is coming to free it.
     * 3. Readable per (network, nick), so a screen can demote a **cached miss** back to
     *    "waiting" while a refresh is out — otherwise reopening a profile seconds after that
     *    nick connected asserts they aren't on the network for a whole round trip.
     *
     * A `Set` rather than the web's single slot: this app pushes profiles onto a navigation
     * stack, so two can be waiting at once.
     */
    val whoisPending: Set<String> = emptySet(),
    /**
     * The user's server-side settings (lurker-ios#65). Seeded by `/api/settings/bootstrap` and
     * patched by live `settings` frames, so a change made on the web takes effect here without
     * a relaunch. Read through `settings.effective(_:)` / its typed helpers — never `values`.
     */
    val settings: Settings = Settings(),
    /**
     * The largest **file** this account may send, as the server last advertised it
     * (lurker#627, lurker-ios#149). Seeded by the `snapshot` frame and refreshed on every
     * reconnect; re-sent on a `settings` frame when the user changes their own cap.
     *
     * ⚠⚠ null is **"the server hasn't said"**, never "no cap" and never zero. A self-hosted
     * instance can legitimately be older than the app, and reading its silence as a number
     * would be exactly the hardcoded guess this replaced. Resolve it through
     * `Uploads.compressionTarget(advertised:)`, which is where the pre-snapshot fallback
     * lives, rather than unwrapping it at a call site.
     *
     * ⚠ It is a FILE cap: the multipart envelope is already subtracted server-side, so size
     * the file to exactly this and don't budget for the boundaries again.
     */
    val maxUploadBytes: Long? = null,
    val error: String? = null,
    /**
     * Text the server refused to send, waiting for the buffer it was TYPED IN. Keyed by
     * `BufferKey.id`.
     *
     * ⚠⚠ Held rather than handed straight to the composer, because the composer that should
     * get it may not be on screen. `/msg bob hi` activates the DM before the ACK lands, so the
     * failure belongs to a buffer the user has already navigated away from — writing it into
     * whatever composer is in front of them would strand the line in the wrong conversation.
     * The web hits this too and documents it on `restoreFailedSend`.
     *
     * Drained by `takeUnsent(_:)` when that buffer next has a composer to put it in.
     */
    val unsent: Map<String, List<UnsentLine>> = emptyMap(),
    /**
     * Each buffer's composer draft as the server holds it (lurker-ios#188), by `BufferKey.id` —
     * seeded by `draft-snapshot`, patched by `draft-updated`, and written by this device's own
     * flushes. Sparse: an empty draft has no entry, so a key here is a buffer with a draft.
     * **Read through `hasDraft(_:)`** for the list's pencil.
     *
     * Not what the composer reads while you type: an edit lives in the view model until it is
     * flushed (`ChatViewModel.draft(for:)`), so a keystroke doesn't publish a whole `ChatState`
     * to every screen. Kept apart from `buffers` too — a draft can arrive for a buffer whose
     * row the burst hasn't delivered yet.
     */
    val drafts: Map<String, ComposerDraft> = emptyMap(),
) {
    /**
     * Whether the roster is settled: a burst has completed and none is in flight, so
     * `buffers` is the server's whole answer *right now* and a missing key is proof the
     * buffer isn't open.
     *
     * `backlogComplete` alone isn't that proof — it latches for the session, so it stays
     * true while a *later* burst (a reconnect, an in-band resync) is still arriving and
     * `buffers` is mid-rebuild. Reading absence during that window would condemn buffers
     * whose frames simply hadn't landed yet. Both conditions together are what §4.3's
     * "absence is proof" actually licenses.
     */
    val rosterSettled: Boolean get() = backlogComplete && !burstActive

    /** Whether this buffer has a draft waiting — the buffer list's pencil. */
    fun hasDraft(key: BufferKey): Boolean = drafts[key.id] != null

    /**
     * Fold a `draft-snapshot`: the server's whole answer, except for the buffers in `keeping`,
     * where this device holds something newer (an unflushed edit, or a composition) and its own
     * copy stays — one the server didn't list included, since it may never have heard of it.
     */
    internal fun seedDrafts(entries: List<DraftEntry>, keeping: Set<String> = emptySet()): ChatState {
        val next = LinkedHashMap<String, ComposerDraft>()
        for (entry in entries) {
            val id = entry.key.id
            if (keeping.contains(id)) continue
            val draft = resolvedDraft(entry, reply = entry.reply)
            if (!draft.isEmpty) next[id] = draft
        }
        for (id in keeping) drafts[id]?.let { next[id] = it }
        return copy(drafts = next)
    }

    /** Fold a `draft-updated`. A frame with no `reply` key keeps the reply we hold. */
    internal fun applyDraftUpdate(entry: DraftEntry): ChatState {
        val id = entry.key.id
        var draft = resolvedDraft(entry, reply = entry.reply)
        if (!entry.carriesReply) draft = draft.copy(reply = drafts[id]?.reply)
        return copy(drafts = drafts.setting(id, if (draft.isEmpty) null else draft))
    }

    /** An entry as the composer holds it: the reply named as the timeline quotes its line. */
    private fun resolvedDraft(entry: DraftEntry, reply: DraftReply?): ComposerDraft =
        ComposerDraft(
            body = entry.body,
            reply = Drafts.pendingReply(
                reply = reply,
                networkId = entry.networkId,
                target = entry.target,
                ignores = ignores,
                relayBots = relayBots,
                ownNick = networks[entry.networkId]?.nick,
            ),
        )

    /**
     * Forget a buffer completely — the row and everything keyed to it.
     *
     * "Closed is absent" (lurker `CLIENT_PROTOCOL.md` §9.1): the server keeps the history,
     * so a reopen restores it in full and there is nothing here worth preserving. Clearing
     * `members`/`typing` alongside the messages is what stops a reopened buffer inheriting a
     * nicklist or a "still typing…" line from before the close.
     *
     * `speakers` goes with them, for the same reason: the reopen's hydrate carries a fresh
     * server list, and a stale one would decide which of the new backlog's events render.
     *
     * `peerPresence` is deliberately untouched — it's keyed by network+nick rather than by
     * buffer, and that nick may still be visible in channels we're in.
     *
     * The one shared definition of "drop it", used by all three paths that need it: this
     * device closing a buffer (`removeBuffer`), the server saying another device did
     * (`buffer-closed`), and reconciling a burst that no longer lists it (`pruneToBurst`).
     */
    internal fun dropBuffer(key: String): ChatState {
        var keysById = this.keysById
        val id = buffers[key]?.bufferId
        if (id != null && keysById[id] == key) keysById = keysById - id
        // The reactions on its lines go with them: a reopen's rows carry their own again. Network
        // buffers only — system-buffer ids are another sequence, and would free network lines'.
        // ⚠ Asked before the row goes, which is what says which kind this is.
        var reactions = this.reactions
        if (buffers[key]?.networkId != null) {
            reactions = reactions - messages[key].orEmpty().map { it.id }.toSet()
        }
        return copy(
            keysById = keysById,
            reactions = reactions,
            reactionsRevisions = reactionsRevisions - key,
            buffers = buffers - key,
            messages = messages - key,
            members = members - key,
            channelModes = channelModes - key,
            typing = typing - key,
            speakers = speakers - key,
            heldLive = heldLive - key,
            // ⚠ A refused line held for this buffer goes with it. Closing is deliberate, and
            // text keyed to a buffer that is no longer on the list would resurface without
            // warning whenever it was reopened. The cost is real — it is the user's own writing
            // — but a buffer they closed is not where they are looking for it.
            unsent = unsent - key,
            // The draft too: the server deletes its row on a close.
            drafts = drafts - key,
        )
    }

    /**
     * Forget a network and everything under it — the local half of a delete, whether it
     * happened here or on another client.
     *
     * Its buffers go with it: server-side the delete cascades, and a buffer whose network no
     * longer exists has no section to sit under, no connection to send to, and no way to be
     * closed. Everything else keyed by network id goes too, so a later network reusing the
     * id — ids are per-instance and a fresh row gets a fresh one, but this costs nothing —
     * cannot inherit the old one's presence.
     */
    internal fun dropNetwork(id: Int): ChatState {
        var next = this
        for (key in buffers.values.filter { it.networkId == id }.map { it.key.id }) next = next.dropBuffer(key)
        return next.copy(
            networks = next.networks - id,
            peerPresence = next.peerPresence - id,
            pinned = next.pinned - id,
            nickNotes = next.nickNotes.removing(networkId = id),
            whois = next.whois - id,
            // Nothing is coming back to free these: the connection they were asked over is
            // gone. Left behind, they'd be a permanent "waiting for whois reply…" on every nick
            // that had a lookup out at the moment the network was deleted — and, if the id
            // were reused, on whoever now answers to those nicks.
            whoisPending = next.whoisPending.filter { !it.startsWith("$id::") }.toSet(),
            // A deleted network's chats end with it (the server closes them first), and an
            // offer on it can no longer be answered — left here, the app would go on asking
            // about one.
            dccChats = next.dccChats - id,
            dccChatOffers = next.dccChatOffers.filter { it.networkId != id },
            // Drafts are kept apart from `buffers`, so the loop above misses one whose row we
            // never held.
            drafts = next.drafts.filter { !it.key.startsWith("$id::") },
        )
    }

    /**
     * Move everything keyed by `from` onto `to` — the rename mirror of
     * `dropBuffer`'s map set, plus `burstSeen` (a rename landing mid-burst
     * must not let the closing prune delete the survivor) and the id index.
     * The `to` slots are overwritten: the only caller (the `bufferRenamed`
     * reduce) drops the absorbed side first on a merge, so a collision here
     * means stale local state losing to the surviving buffer, which is right.
     */
    internal fun rekeyBuffer(from: String, to: String, newTarget: String): ChatState {
        val buf = buffers[from] ?: return this
        val renamed = buf.renamed(newTarget)
        // Casing-only rename: same storage key (BufferKey folds case), so nothing
        // moves — but the display name still changed, and it's the whole frame.
        if (from == to) {
            return copy(buffers = buffers + (to to renamed))
        }
        var burstSeen = this.burstSeen
        if (burstSeen.contains(from)) burstSeen = burstSeen - from + to
        var keysById = this.keysById
        val id = renamed.bufferId
        if (id != null) keysById = keysById + (id to to)
        return copy(
            buffers = buffers - from + (to to renamed),
            messages = messages.moving(from, to),
            members = members.moving(from, to),
            channelModes = channelModes.moving(from, to),
            typing = typing.moving(from, to),
            speakers = speakers.moving(from, to),
            heldLive = heldLive.moving(from, to),
            // ⚠ Moved, not dropped. Left under the dead key this is unreachable forever —
            // nothing ever reads that id again — and the user's refused line is gone with no
            // way to notice. Same class as the in-flight page flags `ChatViewModel` rekeys for
            // the same reason.
            unsent = unsent.moving(from, to),
            // The draft follows its buffer. On a merge the absorbed side was dropped first, so
            // this is the survivor's — what the server keeps too, with a `draft-updated` behind
            // the rename if that changed anything.
            drafts = drafts.moving(from, to),
            // Moved with its lines, so a renamed DM's chips still redraw when a reaction lands.
            reactionsRevisions = reactionsRevisions.moving(from, to),
            burstSeen = burstSeen,
            keysById = keysById,
        )
    }

    /**
     * Merge a frame's `speakers` list into this buffer's map.
     *
     * Always a merge, never a replace. Everything already in the map either came from an
     * earlier server list or from a live message this client watched arrive, and a later query
     * returning fewer rows retracts neither — the server's list was computed when it built the
     * frame, so on a `history` reply (fetched while the buffer is open) a replace would roll
     * back speech from the conversation currently on screen. The web merges for the same reason.
     *
     * Which makes a null list and an empty one both no-ops here, and that's fine: null is a
     * frame that never mentioned speakers (a shell, or a server too old to send them) and empty
     * is a buffer nobody has spoken in, and neither is grounds for forgetting anything.
     */
    internal fun seedSpeakers(list: List<Speaker>?, key: String): ChatState {
        if (list == null || list.isEmpty()) return this
        val map = (speakers[key] ?: SpeakerMap()).seed(list)
        return copy(speakers = speakers + (key to map))
    }

    /**
     * Learn (or confirm) a buffer's stable id → storage-key mapping. The
     * index is what lets a rename be followed by a screen that only knows
     * the id it was opened with.
     */
    internal fun indexBufferId(buffer: Buffer, key: String): ChatState {
        val id = buffer.bufferId ?: return this
        return copy(keysById = keysById + (id to key))
    }

    /**
     * Drop every buffer the just-finished snapshot burst didn't mention.
     *
     * The burst enumerates exactly the user's OPEN buffers — the server skips closed rows
     * when building it (`wsHub.ts:651`) — so after `backlog-complete`, a buffer we hold that
     * got no frame is one the server no longer considers open. That is the *only* way we
     * learn about a close that happened while this device wasn't listening, and on a phone
     * that's the common case: `buffer-closed` is fanned out to connected sockets only, and
     * reconnect is a `?since=` gap-fill that replays messages, not buffer lifecycle. Without
     * this, closing a buffer on the web while the phone is backgrounded left the row on the
     * phone until the app was relaunched.
     *
     * Safe because it drops only what's in memory. §4.3's warning — "don't purge local
     * history, drafts, or a saved read position on the strength of a missing frame" — is
     * about *persisted* state, of which this client has none per buffer: messages are a
     * cache refetched on open, and read state is server-owned. Rendering the buffer as
     * absent is exactly what a missing frame licenses.
     */
    internal fun pruneToBurst(): ChatState {
        if (!burstActive) return this
        // Collected before mutating: `buffers` is being written in the loop below.
        val doomed = buffers.keys.filter { !burstSeen.contains(it) }
        var next = this
        for (key in doomed) next = next.dropBuffer(key)
        return next.copy(burstActive = false, burstSeen = emptySet())
    }

    /**
     * What the app-icon badge should read: unread highlights across every buffer (lurker#490).
     *
     * Mirrors the server's `computeTotalHighlights`, which is what it stamps on each push
     * — so the number the icon shows while the app is closed and the number it shows once
     * it reopens come from the same definition rather than drifting apart. Per-buffer
     * counts are server-authoritative, so this is a sum, never a local tally.
     *
     * It has to exist client-side at all because a push only ever REVISES the badge: iOS
     * applies `aps.badge` and then nothing touches it again, so reading your messages
     * would leave the icon stuck on whatever the last notification claimed until another
     * one happened to arrive.
     */
    val totalHighlights: Int
        get() = buffers.values.fold(0) { sum, buffer -> sum + buffer.highlights }

    /**
     * The buffer for `key`, synthesizing an empty one when the store has no row yet.
     *
     * Every screen that navigates somewhere by *key* rather than by a buffer in hand needs
     * this: a launch restoring where you left off, a notification tap, a highlight tap, a
     * channel you just asked to join. In all four the row may legitimately not exist yet —
     * a push can beat its own backlog frame, a join's row arrives with `channel-joined` —
     * and the screen's `hydrateIfNeeded` fills it in once it does.
     *
     * It lives here because the four call sites had each written the synthesis out and one
     * had already drifted from `BufferKind.of`'s classification. One classifier
     * (`ChannelName.isChannelTarget`, matching the server) — a site that guesses a kind
     * gives its screen a member list and nick coloring the store row disagrees with.
     */
    fun buffer(key: BufferKey): Buffer =
        buffers[key.id]
            ?: Buffer(
                networkId = key.networkId,
                target = key.target,
                kind = BufferKind.of(networkId = key.networkId, target = key.target),
            )

    /**
     * Whether `key` is a channel we hold a row for and aren't in: parted, kicked, refused on
     * rejoin, or on a network whose connection dropped (lurker#915 parts every channel then).
     *
     * ⚠ Asks the stored row, never `buffer(for:)`. That synthesizes a buffer whose `joined` is
     * the initializer's `false` for any key the store hasn't materialized — a default, not a
     * statement — and a favorite's chip reading it would dim a channel nobody said we'd left.
     * DMs have no membership to lose, whatever their flag says.
     */
    fun isParted(key: BufferKey): Boolean {
        val buffer = buffers[key.id] ?: return false
        return buffer.kind == BufferKind.Channel && !buffer.joined
    }

    /**
     * The status of a watched (network, nick), disconnected-aware — what a profile reads, and
     * what `rowPresence` builds on. Mirrors the web client's `peerFor` + `deriveState`, plus a
     * check the web doesn't need (its socket state drives the same store):
     *  - if THIS client can't reach the server — no device network path, or a socket that
     *    isn't up (connecting/reconnecting) — every cached row is stale, because presence only
     *    ever arrives over a live socket. Report `offline` rather than a green dot over a dead
     *    link; a stale snapshot can otherwise leave `Network.state` reading `.connected`.
     *    ⚠ `ProfileStatus` depends on it being `offline`: it falls back to a cached WHOIS reply
     *    whenever this says `unknown`, and that reply outlives the socket, so `unknown` here
     *    would show a stale "Online" and offer Send DM while disconnected;
     *  - a network we hold but that isn't connected reads `offline` (its cached presence
     *    rows are stale, and a peer on a network we've dropped is unreachable from here);
     *  - a connected network with no row for the nick reads `unknown` ("potentially online",
     *    the no-MONITOR case), as does a network we've never heard of;
     *  - otherwise the stored state maps through: `online`/`back` → online, `away`, `offline`.
     */
    fun presence(networkId: Int, nick: String): FriendPresence {
        if (!(reachable && connection == SocketStatus.Connected)) return FriendPresence.Offline
        val network = networks[networkId]
        if (network != null && network.state != ConnectionState.Connected) return FriendPresence.Offline
        return when (peerPresence[networkId]?.get(nick.lowercase())) {
            PresenceState.Online, PresenceState.Back -> FriendPresence.Online
            PresenceState.Away -> FriendPresence.Away
            PresenceState.Offline -> FriendPresence.Offline
            null -> FriendPresence.Unknown
        }
    }

    /**
     * `presence` as the buffer list shows it on a DM row or chip (lurker-ios#167): the same
     * answer, except `unknown` until this client can see the server again and has heard from it.
     *
     * A row puts an offline peer's name in italics, and `offline` is a claim about the peer.
     * While our own connection is down we know nothing about anyone, and `presence`'s `offline`
     * would put every DM in italics under the "Connecting…" banner each time the app came back.
     * Nor right after the socket reopens: `connection` reads `.connected` before the reconnect's
     * snapshot replaces the cached rows, and passing those through put last session's away or
     * offline back on a row for a moment (`snapshotSinceOpen`).
     *
     * The profile keeps `presence`'s answer — see the ⚠ there.
     */
    fun rowPresence(networkId: Int, nick: String): FriendPresence {
        if (!(reachable && connection == SocketStatus.Connected && snapshotSinceOpen)) return FriendPresence.Unknown
        return presence(networkId = networkId, nick = nick)
    }

    /**
     * Whether a DCC chat buffer has a live session behind it — whether a line typed there will
     * arrive (lurker#270). False for anything that isn't a `=nick` buffer.
     *
     * The DCC counterpart to `presence`, and deliberately blind to the network's state: the chat
     * keeps working while the IRC link is down, and a dead chat can't be resumed, only replaced.
     */
    fun isDccChatLive(key: BufferKey): Boolean {
        val networkId = key.networkId
        if (networkId == null || !DccChat.isTarget(key.target)) return false
        val peer = DccChat.peer(key.target).lowercase()
        return dccChats[networkId]?.any { it.lowercase() == peer } ?: false
    }

    /**
     * `isDccChatLive` as a screen should show it: null while this app can't know, because its
     * socket is down or is back but hasn't had its snapshot yet.
     *
     * ⚠ `dccChats` keeps the last session's list through a reconnect (a snapshot replaces it,
     * nothing else clears it), so read raw it shows a chat as live or dead on the strength of a
     * list that may have gone stale — and the info sheet offered End or Start on that basis. The
     * title light, the composer and the sheet all read this, so they can't disagree.
     */
    fun dccChatSession(key: BufferKey): Boolean? {
        if (!(connection == SocketStatus.Connected && snapshotSinceOpen)) return null
        return isDccChatLive(key)
    }

    /**
     * The last WHOIS reply for this nick, or null if we've never had one. A `not_found` reply
     * is cached like any other — it is an answer, and `WhoisResult.isNotFound` is how a caller
     * tells the two apart.
     */
    fun whoisResult(networkId: Int, nick: String): WhoisResult? = whois[networkId]?.get(nick.lowercase())

    /**
     * Whether a lookup for this nick is still out. See `whoisPending` for why callers need
     * this to tell "no answer yet" from "the answer was nobody".
     */
    fun isWhoisPending(networkId: Int, nick: String): Boolean =
        whoisPending.contains(whoisKey(networkId = networkId, nick = nick))

    /**
     * Whether this line is saved. See `bookmarkedIds` for why an unknown id reads as
     * unsaved rather than unknown.
     */
    fun isBookmarked(messageId: Long): Boolean = bookmarkedIds.contains(messageId)

    /**
     * The chips for one line: its reactions grouped by value, first-reacted first. Empty for a
     * line with none, or one this session hasn't loaded.
     */
    fun reactionsRevision(key: BufferKey): Int = reactionsRevisions[key.id] ?: 0

    fun reactionGroups(messageId: Long): List<ReactionGroup> {
        if (messageId == 0L) return emptyList()
        val list = reactions[messageId]
        if (list == null || list.isEmpty()) return emptyList()
        return Reactions.groups(list)
    }

    /**
     * Whether a reaction — or a reply's tags — can go out on this network right now: it's
     * connected and its last registration said yes (§5.1). The server's own gate needs a reply
     * tag allowed too, so this is also the nearest signal for "a reply will carry its tag".
     *
     * ⚠ Our own socket first, like `presence`: while it's down `network.state` is whatever the
     * last snapshot said, and nothing we send goes anywhere.
     */
    fun canReact(networkId: Int?): Boolean {
        if (!(reachable && connection == SocketStatus.Connected) || networkId == null) return false
        val network = networks[networkId] ?: return false
        return network.state == ConnectionState.Connected && network.canReact
    }

    /**
     * The newest lines of every loaded network buffer, for a `sync-reactions` after a resume:
     * a `reaction` frame only reaches a connected socket and the resume ships only new rows, so
     * a react or unreact on a loaded line while we were away would otherwise never land. The
     * newest `Reactions.syncPerBuffer` of each — where reactions land — within the server's cap.
     */
    fun reactionSyncIds(): List<Long> {
        val ids = mutableListOf<Long>()
        for ((key, buffer) in buffers) {
            if (buffer.networkId == null) continue
            val lines = messages[key].orEmpty()
            ids.addAll(
                lines.asReversed().asSequence().map { it.id }.filter { it != 0L }.take(Reactions.syncPerBuffer),
            )
            if (ids.size >= Reactions.syncMaxIds) break
        }
        return ids.take(Reactions.syncMaxIds)
    }

    /**
     * Membership in the favorites list, fold-consistent with every other key comparison
     * (`BufferKey.id` lowercases both sides). The one owner of the predicate — screens
     * must not hand-roll `favorites.contains { ... lowercased() ... }` copies that a
     * future fold correction would have to chase individually.
     */
    fun isFavorite(key: BufferKey): Boolean = favorites.any { it.key.id == key.id }

    /**
     * Reconcile `bookmarkedIds` against a page of rows.
     *
     * Each row is authoritative FOR ITSELF, in both directions: the server computes
     * `bookmarked` per row and omits it when false, so a row that arrives unflagged is
     * saying it isn't saved. Without the clear, an unsave made on another device while this
     * client was offline would never land — the `bookmark-updated` echo was missed, and the
     * reconnect backlog that does carry the truth would be read for additions only, leaving
     * the line lit until someone tapped it.
     *
     * What it must NOT do is evict ids the page says nothing about: a page knows its own
     * slice and no more, so silence about an id is not an unsave.
     *
     * `networkId` gates the whole thing. System-buffer rows come from a different table with
     * its own id sequence that overlaps this one, and they never carry the flag — reconciling
     * against them would clear real bookmarks that happen to share an id.
     */
    internal fun noteBookmarks(messages: List<Message>, networkId: Int?): ChatState {
        if (networkId == null) return this
        val ids = LinkedHashSet(bookmarkedIds)
        for (message in messages) {
            if (message.id == 0L) continue
            if (message.bookmarked) {
                ids.add(message.id)
            } else {
                ids.remove(message.id)
            }
        }
        return copy(bookmarkedIds = ids)
    }

    /**
     * Reconcile `reactions` against a page of rows — the same contract as `noteBookmarks`: each
     * row says what stands on it, in both directions, and silence about an id is not a removal.
     */
    internal fun noteReactions(messages: List<Message>, networkId: Int?, key: String): ChatState {
        if (networkId == null) return this
        val reactions = LinkedHashMap(this.reactions)
        var changed = false
        for (message in messages) {
            if (message.id == 0L) continue
            val list = message.reactions.orEmpty()
            if (list.isEmpty()) {
                if (reactions.remove(message.id) != null) changed = true
            } else if (reactions[message.id] != list) {
                reactions[message.id] = list
                changed = true
            }
        }
        if (!changed) return this
        return copy(reactions = reactions, reactionsRevisions = reactionsRevisions.bumped(key))
    }

    /** One live `reaction` frame, for a line its buffer holds (see `reactions`). */
    internal fun applyReaction(change: ReactionChange): ChatState {
        val key = BufferKey(networkId = change.networkId, target = change.target).id
        if (messages[key]?.any { it.id == change.messageId } != true) return this
        val current = reactions[change.messageId].orEmpty()
        // ⚠⚠ Ours is matched by `isSelf`, never by nick, and nobody else's ever matches ours: we
        // may have reacted under an older nick (so a nick match would leave ours standing
        // forever — the server's rule too), and someone may since have taken that nick (so a
        // nick match would fold their reaction into ours, and their unreact would take ours).
        val same = { r: MessageReaction ->
            if (r.value != change.value || r.isSelf != change.isSelf) {
                false
            } else {
                change.isSelf || r.nick.lowercase() == change.nick.lowercase()
            }
        }
        var next = current
        if (change.remove) {
            next = current.filter { !same(it) }
        } else if (!current.any(same)) {
            // Appended, so a group keeps its place and a new value goes last.
            next = current + MessageReaction(nick = change.nick, value = change.value, isSelf = change.isSelf)
        }
        if (next == current) return this
        return copy(
            reactions = reactions.setting(change.messageId, if (next.isEmpty()) null else next),
            reactionsRevisions = reactionsRevisions.bumped(key),
        )
    }

    /**
     * The answer to `sync-reactions`: authoritative for every id it names that a buffer still
     * holds (a buffer closed while the question was out has nothing to show them on).
     */
    internal fun applyReactionsSync(messageIds: List<Long>, found: Map<Long, List<MessageReaction>>): ChatState {
        val asked = messageIds.filter { it != 0L }.toMutableSet()
        if (asked.isEmpty()) return this
        val reactions = LinkedHashMap(this.reactions)
        var revisions = reactionsRevisions
        for ((key, buffer) in buffers) {
            if (buffer.networkId == null) continue
            var changed = false
            for (message in messages[key].orEmpty()) {
                if (!asked.remove(message.id)) continue
                val list = found[message.id].orEmpty()
                if (list.isEmpty()) {
                    if (reactions.remove(message.id) != null) changed = true
                } else if (reactions[message.id] != list) {
                    reactions[message.id] = list
                    changed = true
                }
            }
            if (changed) revisions = revisions.bumped(key)
            if (asked.isEmpty()) break
        }
        return copy(reactions = reactions, reactionsRevisions = revisions)
    }

    /**
     * Mark ids saved wholesale, for a source that carries no per-row flag because every row
     * in it is saved by definition — the `GET /api/bookmarks` feed. Purely additive: unlike a
     * message page, this one has nothing to say about the rows it doesn't contain.
     */
    internal fun noteBookmarked(ids: List<Long>): ChatState =
        copy(bookmarkedIds = bookmarkedIds + ids.filter { it != 0L })

    /**
     * The peers currently composing in `key`, display-cased, longest-running first.
     *
     * `now` is a parameter rather than an internal clock read so this is deterministic under
     * test. The lease is evaluated *here* — the map itself is never pruned — which means a
     * caller that stops asking simply sees expired entries gone the next time it does, with
     * no timer needed to keep the state honest in the meantime.
     *
     * Ordered by when each peer started their current run, tie-broken on the folded nick so
     * two entries stamped from the same instant (which is every fixture in the tests, and a
     * real possibility for two frames in one runloop) still come out in a fixed order rather
     * than at the mercy of dictionary iteration.
     *
     * An ignored peer is left out: someone whose messages you've hidden shouldn't announce
     * that they're about to send one — the line would name a person whose next line you'll
     * never see. The filter sits here rather than at the call site because every surface that
     * shows typists reads through this one method, and a gate you have to remember to apply
     * at each of them isn't one. Only a whole-identity rule counts (`isIgnored`): a typing tag
     * carries no body or event type, so a content or level-scoped rule has nothing to judge.
     *
     * Port note: the tie-break compares the folded nicks by UTF-16 unit, where Swift compares
     * `String`s by Unicode scalar. The two orders differ only between a BMP character above
     * the surrogate range and an astral one.
     */
    fun typists(key: BufferKey, now: Instant = Instant.now()): List<String> {
        val entries = typing[key.id] ?: return emptyList()
        // Gated once rather than per entry: this runs inside the chat screen's
        // `removeDuplicates` on every frame the socket delivers, and again every second from
        // the typing ticker, so an account with no rules should pay one dictionary lookup for
        // the whole call and not one per person composing.
        val filtering = !ignores.isEmpty(key.networkId)
        return entries.values
            .filter {
                if (!it.isLive(now)) return@filter false
                if (!filtering) return@filter true
                // Scoped to this buffer, so a `-channels #foo` rule silences the indicator in
                // the same buffer it silences the messages.
                !ignores.isIgnored(
                    networkId = key.networkId, nick = it.nick, userhost = it.userhost,
                    channel = key.target, now = now,
                )
            }
            .sortedWith(compareBy<TypingEntry>({ it.startedAt }, { it.nick.lowercase() }))
            .map { it.nick }
    }

    /**
     * A channel's members minus anyone an ignore rule erases (lurker #301).
     *
     * Here, next to `typists(in:)`, for the reason that one gives: `members` is read by the
     * nicklist, the buffer-info count and nick completion, and a filter each of them has to
     * remember to apply isn't one — the info sheet reported a count that included people the
     * nicklist next to it was hiding.
     *
     * Only a whole-identity `ALL` rule removes somebody (see `IgnoreMatch.isMemberHidden`). A
     * content, level-scoped or `NOHIGHLIGHT` rule leaves them listed, because they are still
     * in the channel and still talking — a nicklist that disagreed with who is actually
     * present would be lying about the room rather than filtering it.
     *
     * You are always listed. A hostmask rule can legitimately cover your own nick (a shared
     * bouncer host, a wildcard on the network you're on), and disappearing yourself from your
     * own nicklist is never what such a rule meant.
     *
     * Port note: LurkerKit recognises your own nick with Foundation's `caseInsensitiveCompare`;
     * here it is `IgnoreMatch.FoldedLiteral`, the kit's stand-in for that compare (see its
     * comment for why `equals(ignoreCase = true)` is not one).
     */
    fun visibleMembers(key: BufferKey): List<Member> {
        val members = this.members[key.id].orEmpty()
        if (ignores.isEmpty(key.networkId)) return members
        val ownNick = key.networkId?.let { networks[it]?.nick }
        val own = ownNick?.let { IgnoreMatch.FoldedLiteral(it) }
        return members.filter { member ->
            if (own != null && own.matches(member.nick)) return@filter true
            !ignores.isMemberHidden(
                networkId = key.networkId,
                nick = member.nick,
                userhost = member.userhost,
                channel = key.target,
            )
        }
    }

    companion object {
        /**
         * The one spelling of a `(network, nick)` cache key, so `whois` and `whoisPending` can't
         * drift into folding differently — the two have to agree for a pending lookup to be
         * matched with the reply that answers it.
         */
        fun whoisKey(networkId: Int, nick: String): String = "$networkId::${nick.lowercase()}"
    }
}

/**
 * Holds the domain state and folds `ServerFrame`s into it. The fold is a pure function
 * (`reduce`) with no I/O, so it is fully unit-testable; the store just wraps it in a
 * `MutableStateFlow` the UI observes. Confined to the main thread, because the client
 * marshals every frame to main before applying it, so no locking is needed.
 *
 * Port note: `clock` is port-only. LurkerKit's `apply` lets `reduce` read `Date()`; here it
 * reads `clock`, which defaults to the system and lets a test pin the typing lease and the
 * speaker map's fallback time through the store.
 *
 * Port note: a `StateFlow` conflates — assigning a state equal to the current one publishes
 * nothing — where LurkerKit's `CurrentValueSubject` publishes on every assignment. So a frame
 * that changes nothing (a `send-result`, a `join-error`, a patch for a row we don't hold)
 * wakes no subscriber here, where on iOS it re-sent the same state.
 */
internal class LurkerStore(private val clock: () -> Instant = Instant::now) {
    private val subject = MutableStateFlow(ChatState())

    val state: ChatState get() = subject.value
    val statePublisher: StateFlow<ChatState> get() = subject.asStateFlow()

    /**
     * Clear everything session-scoped. Reachability survives: it's a fact about the
     * device, not the session, and nothing re-reports it on sign-out — resetting it to
     * the `true` default would leave an offline phone claiming it's online.
     */
    fun reset() {
        subject.value = ChatState(reachable = subject.value.reachable)
    }

    /**
     * Claim the in-flight slot for a WHOIS that **has already gone out**.
     *
     * ⚠⚠ Call this only after the send returned true. Claiming first and sending second is
     * the wedge described on `whoisPending`: nothing frees a slot whose request never left
     * the socket, so that nick's lookup declines to retry for the rest of the session.
     * `ChatViewModel.requestWhois` is the only caller, and holds that order.
     */
    fun markWhoisPending(networkId: Int, nick: String) {
        val next = subject.value
        subject.value = next.copy(whoisPending = next.whoisPending + ChatState.whoisKey(networkId = networkId, nick = nick))
    }

    /**
     * Drop a buffer and its cached messages/members — the optimistic local half of a
     * close-buffer (the server then hides it, so it won't re-appear on the next snapshot).
     */
    fun removeBuffer(key: BufferKey) {
        subject.value = subject.value.dropBuffer(key.id)
    }

    fun clearError() {
        subject.value = subject.value.copy(error = null)
    }

    /**
     * Keep a refused line for the buffer it was typed in — see `ChatState.unsent`.
     *
     * ⚠⚠ A QUEUE, not a slot, and the single-slot version lost text. There is one composer, so
     * only one line can be handed back at a time; a slot then had to choose between overwriting
     * the waiting line and discarding the new one, and both lose a message the user wrote. It
     * happens whenever two sends are outstanding when the wire goes: the first refusal restores
     * into the composer, the second is left waiting, and re-sending the first — which fails
     * again — arrives to find the slot occupied.
     *
     * Oldest first, so lines come back in the order they were written. Unbounded because every
     * entry costs a deliberate send, so the user is the limit.
     */
    fun holdUnsent(key: BufferKey, text: String, reply: PendingReply? = null) {
        if (text.isEmpty()) return
        val next = subject.value
        val queue = next.unsent[key.id].orEmpty() + UnsentLine(text = text, reply = reply)
        subject.value = next.copy(unsent = next.unsent + (key.id to queue))
    }

    /**
     * Take back the oldest line held for `key`, if any.
     *
     * Read-and-clear because it is a handoff, not a mirror: once the text is in a composer the
     * composer owns it, and leaving a copy here would re-fill the field every time the buffer
     * was reopened.
     */
    fun takeUnsentLine(key: BufferKey): UnsentLine? {
        val next = subject.value
        val queue = next.unsent[key.id]
        if (queue == null || queue.isEmpty()) return null
        val line = queue.first()
        val rest = queue.drop(1)
        subject.value = next.copy(unsent = next.unsent.setting(key.id, if (rest.isEmpty()) null else rest))
        return line
    }

    /** `takeUnsentLine`'s text alone. */
    fun takeUnsent(key: BufferKey): String? = takeUnsentLine(key)?.text

    /**
     * Append a client-authored info line to a buffer — the app answering the user in
     * place, the web client's `localInfo`. Ephemeral by construction: id 0 never
     * persists, so resyncs and reloads drop it, exactly like the web's local lines.
     */
    fun appendLocal(key: BufferKey, text: String) {
        val next = subject.value
        val line = Message(id = 0, type = EventType.System, nick = null, text = text, level = SystemLevel.Info)
        subject.value = next.copy(messages = next.messages + (key.id to (next.messages[key.id].orEmpty() + line)))
    }

    /**
     * Mirror the OS's view of network reachability into the state. Not a `ServerFrame`
     * because it isn't one — it comes from the device, not the server — so it sits
     * alongside the other direct mutations rather than lying in `reduce`.
     */
    fun setReachable(reachable: Boolean) {
        if (subject.value.reachable == reachable) return
        subject.value = subject.value.copy(reachable = reachable)
    }

    /**
     * Record that the server and this build can't talk (lurker-ios#17). A direct mutation
     * rather than a fold, because the answer comes from `/api/config` as often as from the
     * socket.
     */
    fun setIncompatible(incompatibility: Incompatibility) {
        if (subject.value.connection == SocketStatus.Incompatible(incompatibility)) return
        subject.value = subject.value.copy(connection = SocketStatus.Incompatible(incompatibility))
    }

    /** The server takes this build again. What follows is a fresh connect, not a reconnect. */
    fun clearIncompatible() {
        if (subject.value.connection.incompatibility == null) return
        subject.value = subject.value.copy(connection = SocketStatus.Connecting)
    }

    /**
     * Record a fetched page of saved messages as bookmarked, in ONE mutation.
     *
     * Not a `ServerFrame` because it isn't one — it comes from a REST read, like
     * `setReachable` comes from the device. Per-id `bookmark-updated` frames would say the
     * same thing, but each `apply` publishes a whole `ChatState`, so a 50-row page would
     * wake every subscriber 50 times to communicate a single set union.
     */
    fun noteBookmarked(ids: List<Long>) {
        if (ids.isEmpty()) return
        subject.value = subject.value.noteBookmarked(ids)
    }

    fun apply(frame: ServerFrame) {
        subject.value = reduce(subject.value, frame, now = clock())
    }

    /** `draft-snapshot`, keeping this device's own copy wherever it holds something newer. */
    fun seedDrafts(entries: List<DraftEntry>, keeping: Set<String>) {
        subject.value = subject.value.seedDrafts(entries, keeping = keeping)
    }

    /**
     * Record a draft this device just sent — or tried to: the pencil and the next visit read
     * it either way, and an edit that found no socket goes out after the next snapshot.
     */
    fun setDraft(key: BufferKey, draft: ComposerDraft) {
        val value: ComposerDraft? = if (draft.isEmpty) null else draft
        if (subject.value.drafts[key.id] == value) return
        subject.value = subject.value.copy(drafts = subject.value.drafts.setting(key.id, value))
    }

    companion object {
        /**
         * The pure core. Given the current state and a frame, produce the next state.
         *
         * `now` exists only for the typing lease — the one piece of state whose meaning depends
         * on the clock. It's a defaulted parameter rather than a clock read inside the typing
         * branch so this stays a pure function of its inputs and the lease behavior is testable
         * without sleeping through it.
         */
        fun reduce(state: ChatState, frame: ServerFrame, now: Instant = Instant.now()): ChatState =
            when (frame) {
                is ServerFrame.Networks -> applyNetworks(state, frame.networks)
                is ServerFrame.Snapshot -> {
                    // Frame 1 of every burst (CLIENT_PROTOCOL.md §4.3), so this is where the
                    // roster reconciliation window opens. Start collecting the keys the server
                    // names; `backlog-complete` closes the window and prunes the rest.
                    // Open the window BEFORE applying, not after: `applySnapshot` materializes
                    // rows and records them as seen, and clearing afterwards threw those
                    // entries away — leaving any buffer the snapshot named but that got no
                    // backlog frame of its own to be pruned by the terminal frame.
                    val next = state.copy(
                        burstSeen = emptySet(),
                        burstActive = true,
                        burstGeneration = state.burstGeneration + 1,
                        // This socket has spoken: from here `peerPresence` and the networks'
                        // states are its own, not what was left over from before a drop (see
                        // `rowPresence`).
                        snapshotSinceOpen = true,
                        // Assigned outright, null included: the snapshot is the cap's refresh
                        // point, so a reconnect to an instance that no longer advertises one has
                        // to put us back on the fallback rather than leave a number from the
                        // last server in force.
                        maxUploadBytes = frame.maxUploadBytes,
                    )
                    applySnapshot(next, frame.networks, globalIgnores = frame.globalIgnores)
                }
                ServerFrame.BacklogComplete -> {
                    // The burst is over, so whatever `buffers` holds now is the whole roster —
                    // even when that's nothing. Latched: a later resync re-sends it, and
                    // re-asserting true costs nothing, but going back to false would blank a
                    // populated list.
                    //
                    // ...and "the whole roster" cuts both ways: anything we still hold that the
                    // burst didn't name is no longer open. This is the only signal for a close
                    // that happened while this device wasn't connected.
                    state.copy(backlogComplete = true).pruneToBurst()
                }
                is ServerFrame.Backlog ->
                    applyBacklog(
                        state, frame.buffer, frame.messages,
                        hydrated = frame.hydrated, append = frame.append, speakers = frame.speakers,
                    )
                is ServerFrame.Live ->
                    applyLive(
                        state, networkId = frame.networkId, target = frame.target, message = frame.message, now = now,
                    )
                is ServerFrame.ChannelTopic -> {
                    var next = applyChannelTopic(
                        state, networkId = frame.networkId, target = frame.target, topic = frame.topic,
                    )
                    // The setter rides with the topic only when the server stated it; absent
                    // leaves the held pair alone. Patched onto a channel we hold, like the topic
                    // itself.
                    val key = BufferKey(networkId = frame.networkId, target = frame.target).id
                    val meta = frame.meta
                    if (meta != null && next.buffers[key] != null) {
                        val held = next.channelModes[key] ?: ChannelModeState()
                        next = next.copy(
                            channelModes = next.channelModes +
                                (key to held.copy(topicSetBy = meta.setBy, topicSetAt = meta.setAt)),
                        )
                    }
                    next
                }
                is ServerFrame.ChannelModes -> {
                    // Resolve, never materialize — a mode state for a channel with no row has
                    // nowhere to show, and the snapshot that brings the row carries its modes.
                    // Replaces the mode half wholesale: the frame IS the whole mode string.
                    val key = BufferKey(networkId = frame.networkId, target = frame.target).id
                    if (state.buffers[key] == null) {
                        state
                    } else {
                        val held = (state.channelModes[key] ?: ChannelModeState()).copy(
                            modes = frame.modes,
                            params = frame.params,
                            createdAt = frame.createdAt,
                        )
                        state.copy(channelModes = state.channelModes + (key to held))
                    }
                }
                is ServerFrame.ModeSpec -> {
                    // Never materializes a network, like `react-support`.
                    val network = state.networks[frame.networkId]
                    if (network == null) {
                        state
                    } else {
                        state.copy(networks = state.networks + (frame.networkId to network.copy(modeSpec = frame.spec)))
                    }
                }
                is ServerFrame.BufferCleared -> {
                    // Patched onto a buffer we already hold, never conjuring one: the marker is
                    // a property OF a buffer, and a clear for a row this client has never seen
                    // has nothing to hide. The backlog that brings the row carries the marker
                    // itself.
                    var next = state
                    val key = BufferKey(networkId = frame.networkId, target = frame.target).id
                    val buffer = next.buffers[key]
                    if (buffer != null) {
                        next = next.copy(
                            buffers = next.buffers +
                                (key to buffer.applyCleared(beforeId = frame.clearedBeforeId, at = frame.clearedAt)),
                        )
                    }
                    // ⚠ Local lines go with them. `appendLocal` synthesizes dateless `.system`
                    // rows with id 0 — an unrecognized command, a refusal — and the row filter
                    // cannot judge those against a boundary, having neither an id nor a date to
                    // compare. Left alone they outlive a clear they predate and render UNDER the
                    // divider, as though they had arrived after it. They are ephemeral by
                    // construction (id 0 never persists, and a resync drops them anyway), so a
                    // clear is exactly the moment to let them go.
                    if (frame.clearedBeforeId > 0) {
                        val held = next.messages[key]
                        if (held != null) {
                            next = next.copy(messages = next.messages + (key to held.filter { it.id != 0L }))
                        }
                    }
                    next
                }
                is ServerFrame.ChannelMembers ->
                    applyChannelMembers(
                        state, networkId = frame.networkId, target = frame.target, members = frame.members,
                    )
                is ServerFrame.MemberUpdate ->
                    applyMemberUpdate(state, networkId = frame.networkId, target = frame.target, member = frame.member)
                is ServerFrame.ChannelJoined -> {
                    // Marks the row we hold — after a reconnect the only thing that does, since
                    // the re-sent buffers carry `joined` read before the rejoins land — and
                    // materializes one when there's no row, which is how a first join's buffer
                    // appears (§9.1).
                    val key = BufferKey(networkId = frame.networkId, target = frame.target).id
                    val buffer = state.buffers[key]
                        ?: Buffer(
                            networkId = frame.networkId, target = frame.target,
                            kind = BufferKind.of(networkId = frame.networkId, target = frame.target),
                        )
                    state.copy(
                        buffers = state.buffers + (key to buffer.copy(joined = true)),
                        // The server just named this buffer, as a backlog frame does, so it
                        // survives the burst's closing prune: a rejoin can land inside a burst
                        // built before it.
                        burstSeen = state.burstSeen + key,
                    )
                }
                is ServerFrame.ChannelParted -> {
                    // Resolve, never materialize (§9.1): a forward's part names a channel we
                    // never had. The members and typists go even without a row — both are side
                    // tables, and anything they hold for a channel we're not in is stale. Left,
                    // a typist from before the part stayed on screen until their lease ran out,
                    // up to 30 seconds.
                    val key = BufferKey(networkId = frame.networkId, target = frame.target).id
                    val buffer = state.buffers[key]
                    state.copy(
                        buffers = if (buffer != null) state.buffers + (key to buffer.copy(joined = false)) else state.buffers,
                        members = state.members - key,
                        typing = state.typing - key,
                    )
                }
                is ServerFrame.JoinError -> {
                    // Nothing to record: the refused channel is one we're not in, and above all
                    // there is no row to make (lurker-ios#168). Telling the user is the view
                    // model's job, and only when this device asked (lurker-ios#57).
                    state
                }
                is ServerFrame.OwnNick -> {
                    // Patched onto a network we already know, never conjuring one: a nick for a
                    // network with no row is nothing to apply it to, and the snapshot that
                    // creates the row carries the current nick anyway.
                    val network = state.networks[frame.networkId]
                    if (network == null) {
                        state
                    } else {
                        state.copy(networks = state.networks + (frame.networkId to network.copy(nick = frame.nick)))
                    }
                }
                is ServerFrame.PinsChanged -> {
                    // Replaces wholesale, unlike most of this reducer's patches: the server
                    // re-sends the whole list on every pin, unpin and reorder, so it IS this
                    // network's set, and merging would keep a pin the user just dropped.
                    state.copy(pinned = state.pinned + (frame.networkId to frame.pinned))
                }
                is ServerFrame.NetworkState -> {
                    // ⚠⚠ This one DOES materialize an unknown network, unlike `own-nick` and
                    // `away-state`. Those describe a network the connect snapshot has already
                    // named; this one is also the first thing said about a network created
                    // *since* we connected — `POST /api/networks` starts the connection before it
                    // answers, so its `connecting` (and against a fast server its `connected`)
                    // can beat the roster re-read that would otherwise create the row. Dropped
                    // here, the network then appeared with `ConnectionState`'s default and read
                    // "offline" while genuinely connected, with no further transition coming to
                    // correct it.
                    //
                    // Nameless is a real state now (lurker-ios#136) and it is self-correcting: a
                    // network with no name makes `ChatViewModel` re-read the roster, which either
                    // names it or — since that read is authoritative — removes it again.
                    //
                    // The nick is applied only when the frame carried one — it rides the connect
                    // transition alone, and treating its absence as "" would blank the nick on
                    // every disconnect.
                    val connection = frame.state
                    val nick = frame.nick
                    val existing = state.networks[frame.networkId]
                    val network = if (existing != null) {
                        var updated = existing.copy(state = connection)
                        if (nick != null) updated = updated.copy(nick = nick)
                        // A link that drops comes back through a fresh registration, and until
                        // its burst ends the server can't say what it allows (it re-announces
                        // `react-support` then). Holding the old answer would offer React on the
                        // strength of the last connection's CLIENTTAGDENY.
                        if (connection != ConnectionState.Connected) updated = updated.copy(canReact = false)
                        // Same for the mode vocabulary: the next registration restates it once
                        // its burst ends, and until then the last link's answer is not this one's.
                        if (connection != ConnectionState.Connected) updated = updated.copy(modeSpec = null)
                        updated
                    } else {
                        Network(id = frame.networkId, name = null, state = connection, nick = nick ?: "")
                    }
                    state.copy(networks = state.networks + (frame.networkId to network))
                }
                is ServerFrame.History ->
                    applyHistory(
                        state, networkId = frame.networkId, target = frame.target,
                        events = frame.events, mode = frame.mode, hasMoreOlder = frame.hasMoreOlder,
                        hasMoreNewer = frame.hasMoreNewer, speakers = frame.speakers,
                    )
                is ServerFrame.ReadState ->
                    applyReadState(
                        state, networkId = frame.networkId, target = frame.target,
                        lastReadId = frame.lastReadId, unread = frame.unread, highlights = frame.highlights,
                    )
                is ServerFrame.FavoritesChanged -> state.copy(favorites = frame.favorites)
                is ServerFrame.Reaction -> state.applyReaction(frame.change)
                is ServerFrame.ReactionsSync ->
                    state.applyReactionsSync(messageIds = frame.messageIds, found = frame.reactions)
                is ServerFrame.ReactSupport -> {
                    // Never materializes a network: it describes one the snapshot already named.
                    val network = state.networks[frame.networkId]
                    if (network == null) {
                        state
                    } else {
                        state.copy(
                            networks = state.networks + (frame.networkId to network.copy(canReact = frame.canReact)),
                        )
                    }
                }
                is ServerFrame.DraftSnapshot -> {
                    // Unprotected: what this device has unflushed is the view model's to know,
                    // and it folds through `seedDrafts(_:keeping:)` itself.
                    state.seedDrafts(frame.entries)
                }
                is ServerFrame.DraftUpdated -> state.applyDraftUpdate(frame.entry)
                is ServerFrame.BookmarkUpdated -> {
                    if (frame.saved) {
                        state.copy(bookmarkedIds = state.bookmarkedIds + frame.messageId)
                    } else {
                        // A remove for an id we never held is normal, not a lost update: without
                        // a connect snapshot, the set only knows the lines this session has
                        // loaded.
                        state.copy(bookmarkedIds = state.bookmarkedIds - frame.messageId)
                    }
                }
                is ServerFrame.IgnoreListUpdated -> {
                    // One bucket at a time — the server fans the global list or one network's,
                    // never both — and the list it carries is complete for that scope, so it
                    // replaces rather than merges. A removal has no other way to reach us.
                    state.copy(ignores = state.ignores.replacing(networkId = frame.networkId, rules = frame.rules))
                }
                is ServerFrame.RelayBotUpdated -> {
                    // One nick at a time, so this patches where the ignore arm above replaces —
                    // the difference is in the frames, not in the two features (see
                    // `RelayBotSet.applying`).
                    state.copy(
                        relayBots = state.relayBots.applying(
                            networkId = frame.networkId, nick = frame.nick, marked = frame.marked,
                            pattern = frame.pattern,
                        ),
                    )
                }
                is ServerFrame.NickNoteUpdated -> {
                    // A patch, like the relay mark above. Fanned to every device including the
                    // one that asked, so the editor writes nothing locally — this frame IS the
                    // save, and a note written in a browser reaches the phone by exactly this
                    // route.
                    state.copy(
                        nickNotes = state.nickNotes.applying(
                            networkId = frame.networkId, nick = frame.nick, note = frame.note,
                            updatedAt = frame.updatedAt,
                        ),
                    )
                }
                is ServerFrame.WhoisResult -> {
                    val whois = frame.whois
                    // Keyed by the server's spelling of the nick, folded. Not by what was asked
                    // for: a reply can name a different casing, and the asker's key has to find
                    // it.
                    val byNick = state.whois[frame.networkId].orEmpty() + (whois.nick.lowercase() to whois)
                    state.copy(
                        whois = state.whois + (frame.networkId to byNick),
                        // ⚠⚠ The lookup answered, so the slot is free — and a `not_found`
                        // answered too. Not freeing it here is the whole of lurker#818: the slot
                        // stayed claimed for the session, so reopening the same nick declined to
                        // retry forever.
                        whoisPending = state.whoisPending - ChatState.whoisKey(networkId = frame.networkId, nick = whois.nick),
                    )
                }
                is ServerFrame.BufferClosed -> {
                    // The live half: a close on another device while this one is connected. The
                    // offline half — a close we were never told about — is `pruneToBurst`.
                    state.dropBuffer(BufferKey(networkId = frame.networkId, target = frame.target).id)
                }
                is ServerFrame.BufferRenamed -> applyBufferRenamed(state, frame)
                is ServerFrame.PeerPresence -> {
                    val nick = frame.nick.lowercase()
                    val held = state.peerPresence[frame.networkId].orEmpty()
                    // A null state (server reports nothing known) is stored as absence, so it
                    // reads back as `unknown` rather than a stale prior state.
                    val peerState = frame.state
                    val map = if (peerState != null) held + (nick to peerState) else held - nick
                    state.copy(peerPresence = state.peerPresence + (frame.networkId to map))
                }
                is ServerFrame.AwayState -> {
                    // Only ever a patch onto a network we already hold. The away stream is
                    // broadcast from a live connection, so its network is in the snapshot by
                    // definition — materializing a row from this frame would invent a network
                    // with no name, no state and no channels, which the roster would then render.
                    val network = state.networks[frame.networkId]
                    if (network == null) {
                        state
                    } else {
                        state.copy(networks = state.networks + (frame.networkId to network.copy(away = frame.away)))
                    }
                }
                is ServerFrame.DccChatOffer -> {
                    // A fresh id even when this peer already had an offer waiting: they offered
                    // again, and that is a new question. The server replaces its record the same
                    // way.
                    val id = state.lastDccChatOfferId + 1
                    state.copy(
                        dccChatOffers = state.dccChatOffers.filter { !it.isFrom(frame.nick, networkId = frame.networkId) } +
                            DccChatOffer(id = id, networkId = frame.networkId, nick = frame.nick, passive = frame.passive),
                        lastDccChatOfferId = id,
                    )
                }
                is ServerFrame.DccChatOfferClosed ->
                    state.copy(
                        dccChatOffers = state.dccChatOffers.filter { !it.isFrom(frame.nick, networkId = frame.networkId) },
                    )
                is ServerFrame.DccChatState -> {
                    val nick = frame.nick.lowercase()
                    var peers = state.dccChats[frame.networkId].orEmpty().filter { it.lowercase() != nick }
                    if (frame.live) peers = peers + frame.nick
                    state.copy(dccChats = state.dccChats.setting(frame.networkId, if (peers.isEmpty()) null else peers))
                }
                is ServerFrame.Typing ->
                    applyTyping(
                        state, networkId = frame.networkId, target = frame.target,
                        nick = frame.nick, activity = frame.activity, userhost = frame.userhost, now = now,
                    )
                is ServerFrame.SettingsBootstrap ->
                    state.copy(settings = state.settings.load(registry = frame.registry, values = frame.values))
                is ServerFrame.SettingsChanged -> {
                    // Patch, never replace — the frame carries only what moved, so assigning it
                    // wholesale would drop every other stored setting until the next bootstrap.
                    var next = state.copy(settings = state.settings.apply(frame.changes))
                    // ⚠⚠ Conditional, for the same reason: the cap rides this frame ONLY when it
                    // was the thing that changed. Assigning it unconditionally would clear the
                    // advertised number every time the user toggled anything else, quietly
                    // putting the compressor back on the fallback until the next reconnect.
                    val maxUploadBytes = frame.maxUploadBytes
                    if (maxUploadBytes != null) next = next.copy(maxUploadBytes = maxUploadBytes)
                    next
                }
                is ServerFrame.SettingsValues -> {
                    // Replace: this one IS the full stored set, and it can be smaller than what
                    // we hold (see `Settings.replaceValues`).
                    state.copy(settings = state.settings.replaceValues(frame.values))
                }
                is ServerFrame.ServerError -> state.copy(error = frame.text)
                is ServerFrame.SendResult -> {
                    // ⚠⚠ Deliberately inert here, and the restore is done by `ChatViewModel`
                    // instead — it is the only thing holding the correlation from `clientId`
                    // back to the buffer and the line as typed.
                    //
                    // ⚠⚠ It used to set `state.error`, which surfaces a MODAL ALERT. That was
                    // written when this frame never actually arrived, so the cost was invisible;
                    // since lurker#809's writable-connection gate, `ok:false` is the ordinary
                    // outcome of any reconnect, and a modal per send during an outage is not a
                    // thing to inflict on somebody. The `ConnectionBanner` is already saying
                    // "Reconnecting…" — the failure is announced, and what was missing was the
                    // message coming back.
                    //
                    // ⚠ The failures that DO warrant words still get them: `unknown-network` and
                    // `account-paused` arrive as bare `error` frames alongside this one and keep
                    // the alert unchanged. `not-connected` is the case the server deliberately
                    // does not announce, because the client already knows.
                    state
                }
                ServerFrame.SocketOpen ->
                    state.copy(
                        connection = SocketStatus.Connected,
                        // Connected, but this socket hasn't said anything yet: until its snapshot
                        // lands, every presence row and network state is left over from before
                        // the drop.
                        snapshotSinceOpen = false,
                        error = null,
                    )
                is ServerFrame.SocketClosed -> {
                    // Once we've been connected, a drop is a reconnect; a drop before the first
                    // open is still the initial connect.
                    val connection = when (val held = state.connection) {
                        SocketStatus.Connected, SocketStatus.Reconnecting -> SocketStatus.Reconnecting
                        SocketStatus.Connecting -> SocketStatus.Connecting
                        // Closed because the server can't take this build (lurker-ios#17).
                        // That's still the story, and nothing is reconnecting.
                        is SocketStatus.Incompatible -> held
                    }
                    state.copy(
                        connection = connection,
                        // Every network's mode vocabulary was this socket's word, and a dropped
                        // socket can't hear the `state` frame that would retire it. The next
                        // snapshot restates it; until then it's unknown, which is what null says.
                        networks = state.networks.mapValues { it.value.copy(modeSpec = null) },
                        // Nobody is typing at us over a socket that isn't there. The lease would
                        // retire these on its own, but a `paused` entry holds for 30s — long
                        // enough to survive a reconnect and show a peer composing when we've
                        // heard nothing from them since before the drop.
                        typing = emptyMap(),
                        // ⚠⚠ And no WHOIS still out over that socket is going to be answered
                        // either. This is the third in-flight set cleared on a drop — the view
                        // model does the same to `loadingOlder`/`loadingNewer` — and leaving it
                        // claimed is the wedge documented on `whoisPending`, arriving by the most
                        // ordinary route there is: a reconnect between asking and RPL_ENDOFWHOIS.
                        // `requestWhois` would then refuse that nick for the rest of the session,
                        // leaving the profile on "waiting…" with an inert Refresh.
                        whoisPending = emptySet(),
                    )
                }
                ServerFrame.Unauthorized, is ServerFrame.Incompatible, ServerFrame.Ignored -> {
                    // Session-level / no-op; the view model intercepts `.unauthorized` and
                    // `.incompatible` first.
                    state
                }
                is ServerFrame.UploadProgress -> {
                    // Doesn't reach here — `LurkerClient` consumes it: an upload's progress drives
                    // the readout of the upload that minted its token. Handled explicitly rather
                    // than folded into the no-op case above so that "this is a reply, not state"
                    // stays a stated rule instead of a silent one.
                    //
                    // ⚠ It used to be one of two, alongside a WS `search-result` that resumed the
                    // call awaiting it. Search is a REST read now (lurker-ios#123).
                    state
                }
            }

        /**
         * `buffer-renamed`.
         *
         * Port note: LurkerKit folds this frame inline in `reduce`; it is lifted into its own
         * function here only to keep `reduce`'s `when` readable. The body is the Swift arm's.
         */
        private fun applyBufferRenamed(state: ChatState, frame: ServerFrame.BufferRenamed): ChatState {
            // Protocol §9.7: same buffer, new name. On a merge the ABSORBED
            // buffer (the stale one that already held `to`) is dropped FIRST,
            // so the rekey lands with no collision; its storage key equals the
            // survivor's post-rename key (BufferKey folds case), which is why
            // a viewer sitting in the absorbed DM self-heals — the key they
            // watch simply becomes the survivor. The survivor's history
            // interleaves two streams server-side, so its local slice is
            // wiped and de-hydrated: the ordinary hydrate path refetches
            // rather than guessing at the interleave.
            val merged = frame.merged
            val bufferId = frame.bufferId
            val to = frame.to
            var next = state
            val fromKey = BufferKey(networkId = frame.networkId, target = frame.from).id
            val toKey = BufferKey(networkId = frame.networkId, target = to).id
            // Taken before the absorbed row is dropped below, which takes its draft with it: the
            // server adopts that draft when the survivor has none (`renameBuffer.ts`).
            val absorbedDraft = if (merged && toKey != fromKey) next.drafts[toKey] else null
            val heldTo = next.buffers[toKey]
            if (next.buffers[fromKey] != null) {
                // `toKey != fromKey` is belt-and-braces: the server never merges a
                // casing-only rename, but if a malformed frame said so, dropping
                // `toKey` here would delete the very row being renamed.
                if (merged && toKey != fromKey) next = next.dropBuffer(toKey)
                next = next.rekeyBuffer(from = fromKey, to = toKey, newTarget = to)
            } else if (merged && heldTo != null) {
                // We never held the source (a live rename can beat the source's
                // backlog frame mid-burst, or a reconnect left a gap) — so the row
                // we hold under the new name is the ABSORBED one, already swallowed
                // server-side by a survivor this device hasn't seen. CONVERT it
                // rather than drop it: dropping vanishes the buffer under a reader,
                // and conversion is exactly what the absorbed-seat self-heal
                // produces anyway. Its id is the dead one — un-index and clear it;
                // the frame's id (stamped below) is the survivor's.
                val dead = heldTo.bufferId
                if (dead != null && next.keysById[dead] == toKey) {
                    next = next.copy(keysById = next.keysById - dead)
                }
                next = next.copy(buffers = next.buffers + (toKey to heldTo.copy(bufferId = null).renamed(to)))
            }
            var survivor = next.buffers[toKey]
            if (survivor != null) {
                if (bufferId != null) survivor = survivor.copy(bufferId = bufferId)
                if (merged) {
                    survivor = survivor.copy(hydrated = false, hasMoreOlder = true)
                    next = next.copy(messages = next.messages + (toKey to emptyList()))
                }
                next = next.copy(buffers = next.buffers + (toKey to survivor))
                next = next.indexBufferId(survivor, key = toKey)
            }
            // A draft can be held for a buffer whose row hasn't arrived (`drafts`), and then
            // `rekeyBuffer` never ran. It follows the rename all the same — over the absorbed
            // side's on a merge, since the renamed buffer is the one that survives.
            if (toKey != fromKey) {
                val draft = next.drafts[fromKey]
                if (draft != null) next = next.copy(drafts = next.drafts - fromKey + (toKey to draft))
            }
            if (next.drafts[toKey] == null && absorbedDraft != null) {
                next = next.copy(drafts = next.drafts + (toKey to absorbedDraft))
            }
            // The favorites list carries target strings too, and the server only
            // republishes favorites-changed after MERGES — a plain nick-follow
            // rename would otherwise leave the entry pointing at the dead name
            // until the next echo: a ghost Friends chip under the old nick, plus
            // the renamed DM leaking back into its network roster (every section
            // split keys off entry targets). Rewrite by bufferId — the identity
            // the frame proves — and drop nothing: a merge's absorbed-favorite
            // adoption arrives via its own favorites-changed follow-up.
            if (bufferId != null) {
                next = next.copy(
                    favorites = next.favorites.map { entry ->
                        if (entry.bufferId == bufferId) {
                            FavoriteEntry(networkId = entry.networkId, target = to, bufferId = bufferId)
                        } else {
                            entry
                        }
                    },
                )
            }
            return next
        }

        // MARK: - Reducers

        /**
         * Fold a peer's `+typing` tag into the typing map.
         *
         * **Resolve, never materialize.** A typing tag must not conjure a buffer. It's an ambient
         * signal *about* a conversation, not evidence one exists, and a DM row invented from one
         * would be a phantom the user never opened — the incoming PRIVMSG is what opens a DM
         * (`CLIENT_PROTOCOL.md:632`; this was web bug lurker#292). So an entry is stored only
         * when a buffer row is already present, and the frame is otherwise dropped.
         *
         * Case folding comes free from `BufferKey.id`, which lowercases the target: a tag for
         * `#Chan` lands on a buffer joined as `#chan`, and one cased `Bob` finds the DM opened as
         * `bob`. Servers are inconsistent here and it has bitten before (web lurker#289).
         */
        private fun applyTyping(
            state: ChatState,
            networkId: Int?,
            target: String,
            nick: String,
            activity: TypingActivity?,
            userhost: String?,
            now: Instant,
        ): ChatState {
            val key = BufferKey(networkId = networkId, target = target).id
            if (state.buffers[key] == null) return state
            val entries = LinkedHashMap(state.typing[key].orEmpty())
            // One entry per peer regardless of how the server cased them across two tags.
            val canon = nick.lowercase()

            if (activity == null) {
                // `done`, or a value we don't recognize: they've stopped. Removing beats storing
                // a terminal state — an entry parked here with no lease left to run out is
                // precisely the stuck indicator the web had to go back and fix.
                entries.remove(canon)
                return state.copy(typing = state.typing.setting(key, if (entries.isEmpty()) null else entries))
            }

            // A refresh preserves the original start time so the displayed order doesn't shuffle
            // mid-run — but only while the previous entry is still live. Once a peer's lease has
            // lapsed they stopped and started again, and that's a new run which belongs at the
            // end of the list rather than back at its original place.
            val previous = entries[canon]
            val startedAt = previous?.let { if (it.isLive(now)) it.startedAt else null } ?: now
            entries[canon] = TypingEntry(
                nick = nick,
                activity = activity,
                startedAt = startedAt,
                expiresAt = now.plus(activity.lease),
                userhost = userhost,
            )
            return state.copy(typing = state.typing + (key to entries))
        }

        /**
         * `GET /api/networks` is the whole roster, so it decides membership as well as names.
         *
         * ⚠⚠ Authoritative in BOTH directions. Merging names in and never removing anything left
         * a deleted network on screen for the life of the process — its section header in the
         * buffer list, its entry in the join menu, its name resolving `on:` in search — with no
         * frame able to retract it: `pruneToBurst` prunes buffers only, and `applySnapshot`
         * merges. Deleting from this app couldn't reconcile its own delete, and a network deleted
         * from the web survived every reconnect.
         *
         * ⚠ This is why `parseNetworks` refuses an unreadable body instead of reporting an empty
         * roster: with removal in play, "we couldn't read the answer" would wipe every network
         * the user has.
         */
        private fun applyNetworks(state: ChatState, networks: List<Network>): ChatState {
            val merged = LinkedHashMap(state.networks)
            for (network in networks) {
                // Merge the REST fields in without clobbering any live state the snapshot set.
                // Which fields those are is `Network`'s to say — the list lives beside the
                // declarations it tracks, after a copy kept here left `position` out (f69c30a)
                // and needed `blocked` added by hand. Reachable whenever the roster read loses a
                // race with the socket: a failed initial fetch, or a network created while the
                // app is running.
                val existing = merged[network.id]
                merged[network.id] = existing?.mergeRoster(network) ?: network
            }
            var next = state.copy(networks = merged)
            // Collected before mutating, matching `pruneToBurst` above. Iterating `keys` while
            // dropping is actually well-defined in Swift — the view holds its own reference, so
            // the mutation copies on write and the loop walks the pre-mutation snapshot — but
            // that is a language guarantee a reader has to know to be sure of, and one file
            // should not spell the same operation two ways.
            val named = networks.map { it.id }.toSet()
            val doomed = next.networks.keys.filter { !named.contains(it) }
            for (id in doomed) next = next.dropNetwork(id)
            return next
        }

        private fun applySnapshot(
            state: ChatState,
            networks: List<NetworkSnapshot>,
            globalIgnores: List<IgnoreRule>,
        ): ChatState {
            // Ignore rules replace wholesale, both buckets at once — the snapshot IS the
            // account's rule set, so a rule deleted while this device was away has to disappear
            // here rather than survive as a leftover. Built from the frame alone for the same
            // reason: merging the per-network buckets into what we already held would keep a
            // rule belonging to a network that has since been removed.
            val ignoresByNetwork = LinkedHashMap<Int, List<IgnoreRule>>()
            for (snapshot in networks) {
                if (snapshot.ignoredMasks.isNotEmpty()) ignoresByNetwork[snapshot.id] = snapshot.ignoredMasks
            }
            // Relay marks replace wholesale for exactly the reason the rules above do: the
            // snapshot IS the account's set, so a bot unmarked while this device was away has to
            // disappear here rather than survive as a leftover that keeps rewriting its lines'
            // authors.
            val relayBotsByNetwork = LinkedHashMap<Int, List<RelayBot>>()
            for (snapshot in networks) {
                if (snapshot.relayBots.isNotEmpty()) relayBotsByNetwork[snapshot.id] = snapshot.relayBots
            }
            // Notes replace wholesale for the same reason: the snapshot is the account's whole
            // set, so a note cleared on the web while this device was away has to be gone here
            // rather than survive as a leftover the profile screen would keep showing.
            val nickNotesByNetwork = LinkedHashMap<Int, List<NickNote>>()
            for (snapshot in networks) {
                if (snapshot.nickNotes.isNotEmpty()) nickNotesByNetwork[snapshot.id] = snapshot.nickNotes
            }
            // DCC chats (lurker#270) replace wholesale too: the snapshot lists every live
            // session, a disconnected network's included, so a chat that ended while this device
            // was away is gone here rather than left reading as live.
            val dccChats = LinkedHashMap<Int, List<String>>()
            for (snapshot in networks) {
                if (snapshot.dccChats.isNotEmpty()) dccChats[snapshot.id] = snapshot.dccChats
            }
            // Offers reconcile rather than replace. One the snapshot still lists keeps the offer
            // we hold, id and `passive` flag included, so a reconnect doesn't make it a new
            // question; one we'd missed arrives new; one it doesn't list is over.
            val offers = mutableListOf<DccChatOffer>()
            var lastDccChatOfferId = state.lastDccChatOfferId
            for (snapshot in networks) {
                for (nick in snapshot.dccChatOffers) {
                    if (offers.any { it.isFrom(nick, networkId = snapshot.id) }) continue
                    val held = state.dccChatOffers.firstOrNull { it.isFrom(nick, networkId = snapshot.id) }
                    if (held != null) {
                        offers.add(held)
                    } else {
                        lastDccChatOfferId += 1
                        offers.add(
                            DccChatOffer(
                                id = lastDccChatOfferId, networkId = snapshot.id, nick = nick, passive = false,
                            ),
                        )
                    }
                }
            }
            val nets = LinkedHashMap(state.networks)
            val buffers = LinkedHashMap(state.buffers)
            val members = LinkedHashMap(state.members)
            val channelModes = LinkedHashMap(state.channelModes)
            val burstSeen = LinkedHashSet(state.burstSeen)
            val peerPresence = LinkedHashMap(state.peerPresence)
            val pinned = LinkedHashMap(state.pinned)
            for (snapshot in networks) {
                val existing = nets[snapshot.id]
                if (existing != null) {
                    nets[snapshot.id] = existing.copy(
                        state = snapshot.state,
                        nick = snapshot.nick,
                        // Assigned rather than merged, null included: the snapshot is this
                        // network's whole live state, so an away cleared while this device was
                        // disconnected has to disappear here. Keeping the old value would leave
                        // a stale "away" divider in every buffer with no event able to retract
                        // it.
                        away = snapshot.away,
                        canReact = snapshot.canReact,
                        modeSpec = snapshot.modeSpec,
                    )
                } else {
                    // ⚠⚠ No name, rather than a placeholder that reads like one
                    // (lurker-ios#136). The snapshot carries no network names at all, so a
                    // network the roster hasn't named — a roster fetch that failed, or a network
                    // added from another client while this app was running — arrives here
                    // nameless. The literal `"network"` this used to store was
                    // indistinguishable downstream from a real name, which is why the bug
                    // presented as the app calling a network "network" forever instead of as a
                    // fetch that never happened. `ChatViewModel.handle` watches for a null name
                    // and re-reads the roster.
                    nets[snapshot.id] = Network(
                        id = snapshot.id, name = null, state = snapshot.state, nick = snapshot.nick,
                        away = snapshot.away, canReact = snapshot.canReact, modeSpec = snapshot.modeSpec,
                    )
                }
                for (channel in snapshot.channels) {
                    val key = BufferKey(networkId = snapshot.id, target = channel.name).id
                    val buffer = buffers[key]
                        ?: Buffer(networkId = snapshot.id, target = channel.name, kind = BufferKind.Channel)
                    buffers[key] = buffer.copy(joined = true, topic = channel.topic)
                    members[key] = channel.members
                    channelModes[key] = channel.modeState
                    // Third path that can materialize a row, so it owes `burstSeen` an entry
                    // like the other two — otherwise the burst's closing prune could drop a
                    // buffer the snapshot itself just created. Unreachable against today's
                    // server (this list and the burst's enumeration are built from the same
                    // live `conn.channels` map, so anything here also gets its own frame), but
                    // that's a cross-repo invariant this client can't enforce and shouldn't
                    // silently depend on.
                    burstSeen.add(key)
                }
                // The snapshot is authoritative for this network's presence — replace wholesale,
                // so a reconnect drops any stale rows for peers no longer watched.
                peerPresence[snapshot.id] = snapshot.peerPresence
                // Same rule for pins, and the same reason: a pin dropped from the web while this
                // device was away has to disappear here rather than survive as a leftover.
                pinned[snapshot.id] = snapshot.pinned
            }
            return state.copy(
                ignores = IgnoreSet(global = globalIgnores, byNetwork = ignoresByNetwork),
                relayBots = RelayBotSet(byNetwork = relayBotsByNetwork),
                nickNotes = NickNoteSet(byNetwork = nickNotesByNetwork),
                dccChats = dccChats,
                dccChatOffers = offers,
                lastDccChatOfferId = lastDccChatOfferId,
                networks = nets,
                buffers = buffers,
                members = members,
                channelModes = channelModes,
                burstSeen = burstSeen,
                peerPresence = peerPresence,
                pinned = pinned,
            )
        }

        private fun applyBacklog(
            state: ChatState,
            frameBuffer: Buffer,
            messages: List<Message>,
            hydrated: Boolean,
            append: Boolean,
            speakers: List<Speaker>?,
        ): ChatState {
            var next = state
            next = next.noteBookmarks(messages, networkId = frameBuffer.networkId)
            next = next.noteReactions(messages, networkId = frameBuffer.networkId, key = frameBuffer.key.id)
            val key = frameBuffer.key.id
            next = next.seedSpeakers(speakers, key = key)
            // The server named this buffer, so it survives the burst's closing prune. Recorded
            // unconditionally: outside a burst `burstSeen` is dead state that the next `snapshot`
            // clears, so there's nothing to guard against.
            next = next.copy(burstSeen = next.burstSeen + key)
            val prior = next.buffers[key]
            // The buffer is parked on an `around` slice below the live tail (lurker-ios#42). A
            // backlog frame has nothing to say about what's on screen, and every way of applying
            // one is wrong:
            //
            //  - Appending a resume gap splices a PERMANENT hole. The gap starts at `?since=`,
            //    which `applyLive` advanced past every event it held back during the detach — so
            //    the rows between the slice and the gap were never delivered and never will be.
            //  - Replacing throws away the window the user jumped to, silently, while they're
            //    reading it.
            //  - Either way the frame's own `hasMoreNewer` (parseBacklog never sets it, so it's
            //    the `false` default) would clear the detach flag — retiring the jump-to-latest
            //    pill, resuming live appends onto the old slice, and leaving a buffer that reads
            //    as live while missing everything in between. That was the bug: jump to an old
            //    message, background the app, come back, and the reconnect's resume frame quietly
            //    stitched the present onto two months ago.
            //
            // So the log waits. Re-attaching is always a fresh `history mode:latest` fetch, which
            // is where the live tail comes from — the same call the web's `replaceBacklog` makes
            // (`vue_client/src/stores/buffers.ts:520`). Buffer-level state below still applies:
            // read counts and `joined` are slice-independent.
            val detached = prior?.hasMoreNewer == true
            var buffer = frameBuffer.copy(
                // Never un-hydrate: a later shell for an already-read buffer keeps its history.
                hydrated = hydrated || prior?.hydrated == true,
                // …and never un-know the read state, for the same reason: a frame that omits
                // `lastReadId` hasn't retracted one we were already told.
                readStateKnown = frameBuffer.readStateKnown || prior?.readStateKnown == true,
            )
            // Which also means keeping the VALUES a pointer-less frame would otherwise overwrite
            // with its defaults. `frameBuffer` replaces `prior` wholesale, and all three of these
            // parse to 0 when absent — so a frame that says nothing about read state would
            // otherwise say "read nothing, no unreads", which is a claim it never made.
            if (!frameBuffer.readStateKnown && prior != null && prior.readStateKnown) {
                buffer = buffer.copy(
                    lastReadId = prior.lastReadId,
                    unread = prior.unread,
                    highlights = prior.highlights,
                )
            }
            buffer = buffer.copy(
                // The frame carries no topic — the server doesn't put one there — so assigning it
                // wholesale would blank whatever the connect `snapshot` had just set, and it ships
                // BEFORE the per-buffer backlogs it would be blanked by.
                topic = frameBuffer.topic ?: prior?.topic,
                // Never un-learn the id either: a frame from a pre-id server (or a
                // synthesized row) hasn't retracted the id a real frame stated.
                bufferId = frameBuffer.bufferId ?: prior?.bufferId,
            )
            // ⚠ The `/clear` marker is deliberately NOT rescued from `prior` the way the three
            // above are. It rides the server's `bufferStateFields`, which every backlog frame
            // shares — `buildBufferBacklog`, `buildBufferShell` and the snapshot loop alike — so
            // even a shell states it, and the frame is authoritative. Rescuing it would keep a
            // marker the server has since dropped: an unclear on another device fans out a
            // `buffer-cleared`, but a reconnect right after it would restore the stale boundary.
            // A resync shell (hasMoreOlder defaults true) must not reset the paging or detach
            // state of a buffer we've already paged into or jumped within (lurker-ios#42) — and
            // neither may a real backlog while we're detached, for the reasons above. Only a
            // hydrated backlog for an ATTACHED buffer is the latest tail, and only it gets to say.
            if (prior != null && (detached || (!hydrated && prior.hydrated))) {
                buffer = buffer.copy(hasMoreOlder = prior.hasMoreOlder, hasMoreNewer = prior.hasMoreNewer)
            }
            next = next.copy(buffers = next.buffers + (key to buffer))
            next = next.indexBufferId(buffer, key = key)
            // Not in the channel, so nobody's list and nobody typing. The live `channel-parted`
            // clears both too, but a part this device never heard — the connection dropped while
            // the app was away — reaches it only as this frame's `joined`, and the old nicklist
            // would otherwise outlive the part.
            if (buffer.kind == BufferKind.Channel && !buffer.joined) {
                next = next.copy(members = next.members - key, typing = next.typing - key)
            }

            if (detached) {
                // The slice stands. See above.
            } else if (!hydrated) {
                // Shell: register the buffer but keep any messages we already hold.
                if (next.messages[key] == null) next = next.copy(messages = next.messages + (key to emptyList()))
            } else if (append) {
                // Resume gap slice: append past the tail, de-duping by persisted id.
                next = next.copy(messages = next.messages + (key to appendMerged(next.messages[key].orEmpty(), messages)))
            } else {
                // Full / latest backlog: replace — but keep any live events that arrived after
                // the server built this backlog (id past its tail), so hydrating mid-traffic
                // (e.g. a message lands between open-buffer and its reply) can't punch a hole.
                val tail = messages.maxOfOrNull { it.id } ?: 0L
                val heldNewer = next.messages[key].orEmpty().filter { it.id > tail }
                next = next.copy(messages = next.messages + (key to (messages + heldNewer)))
            }
            return next.copy(maxEventId = maxEventId(next.maxEventId, frameBuffer.networkId, messages))
        }

        private fun applyLive(
            state: ChatState,
            networkId: Int?,
            target: String,
            message: Message,
            now: Instant,
        ): ChatState {
            var next = state
            val key = BufferKey(networkId = networkId, target = target).id
            val existing = next.messages[key].orEmpty()
            // De-dupe backlog/live overlap by persisted id; id 0 is ephemeral and always
            // appended.
            if (message.id != 0L && existing.any { it.id == message.id }) return next
            // ⚠⚠ A channel row comes from a persisted line or not at all (§9.1: `channel-joined`
            // is the materialization signal, and our own join's line is the persisted one that
            // beats it). An ephemeral event can name a channel we're NOT in — a refused join's
            // `join-error` is aimed at the channel it refused — and a row minted for it read
            // joined, so the list showed the refused channel and `/join`'s wait switched the user
            // into it.
            if (next.buffers[key] == null && message.id == 0L &&
                BufferKind.of(networkId = networkId, target = target) == BufferKind.Channel
            ) {
                return next
            }
            if (next.buffers[key] == null) {
                // A live event can be the first sign of a buffer (a new incoming DM), so
                // materialize a row for it. Unhydrated, so tapping it fetches history.
                val kind = BufferKind.of(networkId = networkId, target = target)
                next = next.copy(
                    buffers = next.buffers + (
                        key to Buffer(
                            networkId = networkId, target = target, kind = kind,
                            // A line is no statement that we left a channel, and our own join's
                            // line lands before its `channel-joined` — so a channel minted here
                            // reads joined until a part or a backlog says otherwise. At the
                            // initializer's `false`, a fresh join read as parted until
                            // `channel-joined` landed.
                            joined = kind == BufferKind.Channel,
                        )
                        ),
                    // A DM that materializes mid-burst was created after the server enumerated
                    // the roster, so the burst legitimately won't name it — mark it seen or the
                    // closing prune would drop a buffer that just arrived.
                    burstSeen = next.burstSeen + key,
                )
            }
            // A topic change is both a line and the topic itself. This has to sit *below* the
            // id de-dupe above, not with the parse: a `topic` event replayed by a backlog/live
            // overlap would otherwise re-apply an old topic over the current one, silently
            // reverting the channel's topic to whatever it was at replay time. The Vue client
            // hit this first and its handler carries the same warning.
            if (message.type == EventType.Topic) {
                val buffer = next.buffers[key]
                if (buffer != null) {
                    // …and who set it, from the same line: its author and its time.
                    val held = next.channelModes[key] ?: ChannelModeState()
                    next = next.copy(
                        buffers = next.buffers + (key to buffer.copy(topic = message.text)),
                        channelModes = next.channelModes +
                            (key to held.copy(topicSetBy = message.nick, topicSetAt = message.date)),
                    )
                }
            }
            // Membership churn folds into the member list here, and only here — the same
            // seat below the id de-dupe the topic needs, and for the same reason: a
            // replayed join must not resurrect a member who has since parted. Backlog and
            // history replays deliberately don't fold — the snapshot/`names` list is the
            // authoritative baseline those events predate.
            next = next.copy(members = next.members.setting(key, foldMembership(next.members[key], message)))
            // Who spoke here and when (lurker-ios#63) — the same seat below the id de-dupe as the
            // fold above, so a replayed line can't restate a speaker's recency.
            //
            // This is the half of the map no fetch supplies: the server's list was computed when
            // it built the frame, and the join-unmask rule is entirely about speech that happens
            // after that. `message`/`action` only, matching what the server's own `listSpeakers`
            // counts — a notice is a bot talking at the channel, not somebody in the
            // conversation. Our own messages don't count for the same reason they don't there:
            // the question the filter asks is whether anyone *else* was talking to this nick.
            val nick = message.nick
            if ((message.type == EventType.Message || message.type == EventType.Action) && !message.isSelf && nick != null) {
                val map = (next.speakers[key] ?: SpeakerMap()).record(nick = nick, date = message.date ?: now)
                next = next.copy(speakers = next.speakers + (key to map))
            }
            // A rename carries the entry with it, so someone who spoke and then went `_afk`
            // doesn't read as a stranger when they quit ten seconds later.
            val newNick = message.newNick
            if (message.type == EventType.Nick && nick != null && newNick != null) {
                val map = next.speakers[key]
                if (map != null) next = next.copy(speakers = next.speakers + (key to map.rename(old = nick, new = newNick)))
            }
            // Anything we hear *from* this nick ends their typing run, and the same seat below
            // the id de-dupe applies for the same reason.
            //
            // This is the spec's first clear condition, not an optimization: `typing=done` is
            // only sent "when the user clears the text-input field WITHOUT sending a message"
            // (`client-tags/typing.md:38`), so a conforming client — senpai, Goguma, gamja —
            // never announces `done` on send. Without this, their message lands and the "alice is
            // typing…" line stays pinned underneath it for the rest of the lease: 6s after an
            // `active`, 30s after a `paused`. Lurker-to-Lurker hides the bug, because our own
            // composer emits a (non-spec) `done` on send.
            //
            // Covers the spec's second condition too — a part/quit/kick from someone
            // mid-sentence clears them rather than leaving a ghost typing in a channel they've
            // left. The web does the same, keyed off the resolved buffer (`stores/buffers.ts:444`).
            val speaker = message.nick?.lowercase()
            val typists = next.typing[key]
            if (speaker != null && typists != null && typists[speaker] != null) {
                val remaining = typists - speaker
                next = next.copy(typing = next.typing.setting(key, if (remaining.isEmpty()) null else remaining))
            }
            // A detached buffer (showing an `around` slice below the live tail, lurker-ios#42)
            // holds live events out of the log — appending them would splice a hole past the
            // slice. Member and topic state above stays current; only the message log waits.
            //
            // HELD, not dropped (`heldLive`). The re-attach fetches the latest slice, but the
            // server built that slice before this event existed, so dropping it leaves a hole at
            // the seam that nothing ever fetches again — the client can't even tell it's there.
            // Keeping it means the re-attach can put back exactly what it can't have asked for.
            //
            // `maxEventId` still advances so the resume cursor doesn't re-request an event the
            // client has already been given.
            if (next.buffers[key]?.hasMoreNewer == true) {
                return next.copy(
                    heldLive = next.heldLive + (key to trimmedToCap(next.heldLive[key].orEmpty() + message)),
                    maxEventId = maxEventId(next.maxEventId, networkId, listOf(message)),
                )
            }
            return next.copy(
                messages = next.messages + (key to (existing + message)),
                maxEventId = maxEventId(next.maxEventId, networkId, listOf(message)),
            )
        }

        /**
         * One live membership event applied to a channel's member list. Nicks match
         * case-insensitively throughout: servers echo inconsistent casing, and `toLowerCase`
         * matching is house style (see `BufferKey`).
         *
         * A join can seed a list from null — our own join precedes the `names` broadcast, so
         * the list briefly holds just us until the full roster lands. Removals against null
         * stay null: a quit fans out to DM buffers too, which have no list to edit.
         */
        private fun foldMembership(members: List<Member>?, message: Message): List<Member>? =
            when (message.type) {
                EventType.Join -> {
                    val nick = message.nick
                    if (nick == null || nick.isEmpty()) {
                        members
                    } else {
                        val list = members.orEmpty()
                        // Already present (e.g. the list arrived via `names` before our fold
                        // ran): keep the existing entry and whatever modes/away it carries.
                        if (list.any { it.nick.lowercase() == nick.lowercase() }) {
                            members
                        } else {
                            list + Member(nick = nick)
                        }
                    }
                }
                EventType.Part, EventType.Quit -> removingMember(members, nick = message.nick)
                // `kicked` is who left; `nick` is the actor doing the kicking.
                EventType.Kick -> removingMember(members, nick = message.kicked)
                EventType.Nick -> {
                    val old = message.nick?.lowercase()
                    val new = message.newNick
                    if (old == null || new == null || new.isEmpty()) {
                        members
                    } else {
                        members?.map { member ->
                            if (member.nick.lowercase() != old) {
                                member
                            } else {
                                Member(
                                    nick = new, modes = member.modes, away = member.away,
                                    user = member.user, host = member.host,
                                )
                            }
                        }
                    }
                }
                else -> members
            }

        private fun removingMember(members: List<Member>?, nick: String?): List<Member>? {
            if (nick == null || nick.isEmpty()) return members
            return members?.filter { it.nick.lowercase() != nick.lowercase() }
        }

        /**
         * RPL_TOPIC on join. Unlike a `topic` event this carries no id, so there's nothing to
         * de-dupe against — the server only sends it when it's telling us the current truth.
         *
         * Deliberately does not materialize a missing buffer, which `applyLive` does: a topic
         * for a channel we have no row for is nothing to show and nowhere to show it, and the
         * snapshot that creates the row carries the topic anyway.
         */
        private fun applyChannelTopic(
            state: ChatState,
            networkId: Int?,
            target: String,
            topic: String?,
        ): ChatState {
            val key = BufferKey(networkId = networkId, target = target).id
            val buffer = state.buffers[key] ?: return state
            return state.copy(buffers = state.buffers + (key to buffer.copy(topic = topic)))
        }

        /**
         * A `names` broadcast: replace the member list wholesale — it IS the list, the same
         * authority the snapshot carries. Stored even if no buffer row exists yet: `members`
         * is a side table keyed like the buffers, so an early entry creates nothing visible,
         * and the row that makes it visible is on its way (our own join precedes `names`).
         */
        private fun applyChannelMembers(
            state: ChatState,
            networkId: Int?,
            target: String,
            members: List<Member>,
        ): ChatState =
            state.copy(members = state.members + (BufferKey(networkId = networkId, target = target).id to members))

        /**
         * A `member-update` patch: replace the matching member with the server's snapshot.
         * Wholesale replace is safe because the server always sends the complete member
         * (its `memberSnapshot`), never a partial. Resolve, never create — an attribute
         * patch for a nick we don't hold has nothing to attach to (matching the web
         * client's `updateMember`), and matching is case-insensitive because a CHGHOST
         * echoes the nick as the server holds it, which needn't match what NAMES gave us.
         */
        private fun applyMemberUpdate(
            state: ChatState,
            networkId: Int?,
            target: String,
            member: Member,
        ): ChatState {
            val key = BufferKey(networkId = networkId, target = target).id
            val list = state.members[key] ?: return state
            val index = list.indexOfFirst { it.nick.lowercase() == member.nick.lowercase() }
            if (index < 0) return state
            val updated = list.toMutableList()
            updated[index] = member
            return state.copy(members = state.members + (key to updated))
        }

        /**
         * Mirror server-authoritative read counts onto the buffer. The counts are never
         * derived locally — a `read-state` broadcast (from this device's mark-read, another
         * device's, or any countable event) is the single source of truth.
         */
        private fun applyReadState(
            state: ChatState,
            networkId: Int?,
            target: String,
            lastReadId: Long,
            unread: Int,
            highlights: Int,
        ): ChatState {
            val key = BufferKey(networkId = networkId, target = target).id
            val buffer = state.buffers[key] ?: return state
            val updated = buffer.copy(
                lastReadId = lastReadId,
                unread = unread,
                highlights = highlights,
                // This frame carries the pointer by definition, so it's one of the two that can
                // say the read state is known (see `Buffer.readStateKnown`).
                readStateKnown = true,
            )
            return state.copy(buffers = state.buffers + (key to updated))
        }

        /**
         * Splice a `history` page in: `before` prepends older, `after` appends newer,
         * `latest`/`around` replace. Always de-dupes by persisted id — a page can overlap
         * events the live fan-out already delivered.
         */
        private fun applyHistory(
            state: ChatState,
            networkId: Int?,
            target: String,
            events: List<Message>,
            mode: HistoryMode,
            hasMoreOlder: Boolean,
            hasMoreNewer: Boolean,
            speakers: List<Speaker>?,
        ): ChatState {
            var next = state
            next = next.noteBookmarks(events, networkId = networkId)
            next = next.noteReactions(events, networkId = networkId, key = BufferKey(networkId = networkId, target = target).id)
            val key = BufferKey(networkId = networkId, target = target).id
            next = next.seedSpeakers(speakers, key = key)
            val existing = next.messages[key].orEmpty()
            val wasDetached = next.buffers[key]?.hasMoreNewer == true
            val spliced = when (mode) {
                HistoryMode.Before -> {
                    val held = existing.mapNotNull { if (it.id != 0L) it.id else null }.toSet()
                    events.filter { it.id == 0L || !held.contains(it.id) } + existing
                }
                HistoryMode.After -> appendMerged(existing, events)
                HistoryMode.Latest -> {
                    // Return-to-live: replace, but keep live events newer than this slice's tail
                    // so a message that outran the fetch isn't dropped (the slice is at the tail,
                    // so there's no gap).
                    val tail = events.maxOfOrNull { it.id } ?: 0L
                    events + existing.filter { it.id > tail }
                }
                // A jump slice is centered on an arbitrary (possibly old) message, so anything
                // already held is on the far side of a gap — keeping it would splice the old
                // window onto unrelated newer messages. Replace outright, like the web's
                // applyAroundSlice.
                HistoryMode.Around -> events
            }
            next = next.copy(messages = next.messages + (key to spliced))
            val held = next.buffers[key]
            if (held != null) {
                var buffer = held.copy(hydrated = true)
                if (mode != HistoryMode.After) buffer = buffer.copy(hasMoreOlder = hasMoreOlder) // `after` pages newer
                // Detach state (lurker-ios#42): an `around` slice may sit below the tail →
                // detached; `latest` is the tail → re-attached; `after` carries whether newer
                // remains. `before` pages older within whatever attachment we already have, so
                // it's left untouched.
                buffer = when (mode) {
                    HistoryMode.Around, HistoryMode.After -> buffer.copy(hasMoreNewer = hasMoreNewer)
                    HistoryMode.Latest -> buffer.copy(hasMoreNewer = false)
                    HistoryMode.Before -> buffer
                }
                next = next.copy(buffers = next.buffers + (key to buffer))
            }
            // Detach transitions decide what happens to the events held aside while detached.
            val isDetached = next.buffers[key]?.hasMoreNewer == true
            if (isDetached && mode == HistoryMode.Around) {
                // A fresh jump. Whatever was held belongs to the window we just left, and this
                // slice is somewhere else entirely — start the hold over.
                next = next.copy(heldLive = next.heldLive + (key to emptyList()))
            } else if (wasDetached && !isDetached) {
                // Re-attached. The slice we just fetched is the server's answer as of the moment
                // it ran the query; everything said *after* that is what we've been holding, and
                // it is contiguous with the slice's tail because the socket delivered every one
                // of them. Anything at or below the tail is already in the slice — `appendMerged`
                // de-dupes those by id.
                val heldLive = next.heldLive[key].orEmpty()
                next = next.copy(heldLive = next.heldLive - key)
                val tail = next.messages[key].orEmpty().maxOfOrNull { it.id } ?: 0L
                // `id == 0` is an ephemeral the server never persisted — a `ctcp` or `e2e`
                // status line — so no fetch can ever contain it and no id can place it. It
                // survives on the same terms it does everywhere else in the store
                // (`appendMerged`): always kept, always appended. Comparing it against the tail
                // would discard every one of them (0 is never greater), which is precisely the
                // loss this hold exists to stop, and the only kind of it that's unrecoverable.
                next = next.copy(
                    messages = next.messages + (
                        key to appendMerged(
                            next.messages[key].orEmpty(), heldLive.filter { it.id == 0L || it.id > tail },
                        )
                        ),
                )
            }
            return next.copy(maxEventId = maxEventId(next.maxEventId, networkId, events))
        }

        /**
         * How many live events a single detached buffer holds before the oldest start falling
         * off.
         *
         * Generous next to what re-attaching actually fetches (a `latest` slice is a couple of
         * hundred rows), which is the number that has to be covered — see `ChatState.heldLive`.
         * Bounded at all because a buffer can sit detached indefinitely: leave a jump slice open
         * on a busy channel overnight and an unbounded hold is the session's whole traffic.
         */
        private const val heldLiveCap = 500

        private fun trimmedToCap(held: List<Message>): List<Message> =
            if (held.size > heldLiveCap) held.takeLast(heldLiveCap) else held

        /** Append `incoming` onto `existing`, dropping any persisted id already present. */
        private fun appendMerged(existing: List<Message>, incoming: List<Message>): List<Message> {
            val seen = existing.mapNotNull { if (it.id != 0L) it.id else null }.toSet()
            return existing + incoming.filter { it.id == 0L || !seen.contains(it.id) }
        }

        /**
         * The `?since=` watermark. The system buffer (null networkId) is skipped — it has a
         * separate id space, so its ids must not pollute the resume cursor.
         */
        private fun maxEventId(current: Long, networkId: Int?, messages: List<Message>): Long {
            if (networkId == null) return current
            return maxOf(current, messages.maxOfOrNull { it.id } ?: 0L)
        }
    }
}
