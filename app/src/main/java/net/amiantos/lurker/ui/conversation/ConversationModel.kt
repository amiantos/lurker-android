// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurker.ui.shell.StatusTitle
import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferPlaceholder
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.MemberPrefix
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.MessageRows
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.SpeakerMap
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.model.TagSupport
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import java.time.Instant
import java.time.ZoneId
import net.amiantos.lurkerkit.model.PrefixMode

/**
 * Exactly the part of `ChatState` the conversation's rows draw from — lurker-ios's
 * `ChatViewController.subscribeToState` `removeDuplicates` block, made a type, as `BufferListInputs`
 * is for the list.
 *
 * ⚠⚠ `statePublisher` publishes the whole state on every frame, for every buffer. So the flow maps to
 * this and is distinct by [same] before it becomes Compose state; a plain class, so Compose's own
 * comparison of two is identity and [same] is the one real comparison. Every field the rows read is
 * here — anything left out goes stale behind a frame [same] dropped as a duplicate, which is the trap
 * iOS's comments name one field at a time:
 *
 *  - [buffer] is here for hydration and paging state, not for drawing: a buffer whose only change is
 *    exhausting its history, or a `/clear` issued on the web, still has to redraw.
 *  - [networks] whole, not just this buffer's: the system buffer labels its lines with *other*
 *    networks' names, a name arrives later than its network does, and your own away state (the
 *    presence markers) hangs off `Network`.
 *  - [typists] as the RENDERED list, not the raw entries: a peer re-sends `active` every ~3s with a
 *    fresh lease, and comparing entries would rebuild every row three times a second to draw a line
 *    that hasn't changed.
 *  - [settings]: consolidation and the event tier reshape the rows, and arrive on their own from
 *    another device.
 *  - [modePrefixes] as the DERIVED glyph map, not `members`: a `Member` carries `away`, which flips
 *    constantly with away-notify on a busy channel. Empty while the setting is off (the default).
 *  - [ignores] and [relayBots] by identity — see `IgnoreSet`, `RelayBotSet`.
 *  - [speakers]: the `smart` tier judges churn against it, and a `history` reply re-seeds it without
 *    changing a message.
 *  - [reactionsRevision]: reactions ride beside the rows, so a `reaction` frame changes a chip with no
 *    message changing. One integer per buffer; [reactions] rides along uncompared.
 *  - [support]: whether a chip can be tapped moves with the network and our own socket — and with
 *    which tags the network takes, since ours and anyone else's can differ (lurker#1101).
 */
internal class ConversationInputs(
    val key: BufferKey,
    val kind: BufferKind,
    /** This buffer's messages. Compared by identity: the store replaces a list it changed. */
    val messages: List<Message>?,
    val buffer: Buffer?,
    val networks: Map<Int, Network>,
    val settings: Settings,
    val ignores: IgnoreSet,
    val relayBots: RelayBotSet,
    val speakers: SpeakerMap?,
    val typists: List<String>,
    val rosterSettled: Boolean,
    val reactionsRevision: Int,
    /** Not compared — [reactionsRevision] stands for it. */
    val reactions: Map<Long, List<MessageReaction>>,
    /** `ChatState.tagSupport` for this buffer's network, resolved once per frame for every chip. */
    val support: TagSupport,
    val modePrefixes: Map<String, MemberPrefix.Mark>,
    /** Who the in-body nick colouring looks for — see `ConversationModel.highlighterNicks`. */
    val highlighterNicks: List<String>,
) {
    /** Your nick on this buffer's network, or null where there is none (the system buffer). */
    val ownNick: String? get() = key.networkId?.let { networks[it]?.nick }

    /** The chips for one line — `ChatState.reactionGroups`, over the map this frame carried. */
    fun reactionGroups(messageId: Long): List<ReactionGroup> {
        if (messageId == 0L) return emptyList()
        val list = reactions[messageId]
        if (list.isNullOrEmpty()) return emptyList()
        return Reactions.groups(list)
    }

    companion object {
        fun same(old: ConversationInputs, new: ConversationInputs): Boolean =
            old.key == new.key &&
                old.messages === new.messages &&
                old.buffer == new.buffer &&
                old.networks == new.networks &&
                old.settings == new.settings &&
                old.ignores === new.ignores &&
                old.relayBots === new.relayBots &&
                old.speakers == new.speakers &&
                old.typists == new.typists &&
                old.rosterSettled == new.rosterSettled &&
                old.reactionsRevision == new.reactionsRevision &&
                old.support == new.support &&
                old.modePrefixes == new.modePrefixes &&
                old.highlighterNicks == new.highlighterNicks
    }
}

/**
 * Maps every frame to a [ConversationInputs] for one buffer.
 *
 * A class rather than a function for one memo: the two values derived from the nicklist are rebuilt
 * only when the nicklist (or your nick, or the setting) actually changed, so a frame for some other
 * buffer doesn't walk a thousand-member channel to find nothing new.
 */
internal class ConversationProjector(private val key: BufferKey, private val kind: BufferKind) {
    private var lastMembers: List<Member>? = null
    private var lastOwnNick: String? = null
    private var lastShowsPrefix: Boolean? = null
    private var lastPrefix: List<PrefixMode>? = null
    private var modePrefixes: Map<String, MemberPrefix.Mark> = emptyMap()
    private var highlighterNicks: List<String> = emptyList()

    fun project(state: ChatState, now: Instant = Instant.now()): ConversationInputs {
        val members = state.members[key.id]
        val ownNick = key.networkId?.let { state.networks[it]?.nick }
        val showsPrefix = state.settings.bool("look.nick.show_mode_prefix", default = false)
        // The network's PREFIX (lurker-ios#191) — its ISUPPORT can land after the nicklist.
        val prefix = key.networkId?.let { state.networks[it]?.modeSpec?.prefix }
        val membersMoved = members !== lastMembers || ownNick != lastOwnNick
        if (lastShowsPrefix == null || membersMoved || showsPrefix != lastShowsPrefix || prefix != lastPrefix) {
            modePrefixes = ConversationModel.modePrefixes(kind, members.orEmpty(), showsPrefix, prefix)
        }
        // Not on a PREFIX or setting change: who to colour doesn't depend on either.
        if (lastShowsPrefix == null || membersMoved) {
            highlighterNicks = ConversationModel.highlighterNicks(kind, key, members.orEmpty(), ownNick)
        }
        lastMembers = members
        lastOwnNick = ownNick
        lastShowsPrefix = showsPrefix
        lastPrefix = prefix
        return ConversationInputs(
            key = key,
            kind = kind,
            messages = state.messages[key.id],
            buffer = state.buffers[key.id],
            networks = state.networks,
            settings = state.settings,
            ignores = state.ignores,
            relayBots = state.relayBots,
            speakers = state.speakers[key.id],
            typists = state.typists(key, now),
            rosterSettled = state.rosterSettled,
            reactionsRevision = state.reactionsRevision(key),
            reactions = state.reactions,
            support = state.tagSupport(networkId = key.networkId),
            modePrefixes = modePrefixes,
            highlighterNicks = highlighterNicks,
        )
    }
}


/**
 * The conversation's decisions, pure: which rows it draws, what its title says, what it shows when
 * there's nothing to draw. lurker-ios's `ChatViewController.apply` and the static helpers beside it.
 */
internal object ConversationModel {

    /**
     * Whether tapping each of [message]'s chips can go out now — ours takes it back, anyone else's
     * adds ours, and a network can allow one and not the other (irc.so takes a reaction but not a
     * take-back, lurker#1101). The kit's `Reactions.canToggle` over the group's own `mine`, with the
     * network's answer resolved once for the row (iOS's `ReactionContext.canToggle`). A chip that
     * can't opens the sheet instead, which says why.
     */
    fun chipToggles(message: Message, target: String, support: TagSupport): (ReactionGroup) -> Boolean =
        { group -> Reactions.canToggle(mine = group.mine, message = message, target = target, support = support) }

    /**
     * The messages this frame draws, filtered: iOS's `apply` filter chain, its `messages`.
     *
     * Filter by what this *kind* of buffer renders: the system buffer's content is entirely
     * `type: "system"`, which a channel never shows, and a blanket speech filter left it empty. Then
     * the render-time ignore filter (lurker #301) — at render rather than on the way into the store,
     * which is what makes a rule retroactive both ways. Then relay re-attribution (#277), which has to
     * see what the ignore filter left (the rules match the bot's real nick and its whole envelope).
     * Replies (lurker-ios#184) last of all: a quote and its stripped address are judged against the
     * line as it now displays, and the quote screens the CURRENT ignore rules.
     *
     * [keeping] is the jump target (`RowOptions.keeping`), which the ignore filter must not drop: the
     * landing resolves its anchor against RENDERED rows, so a target a rule now covers would never
     * resolve and the jump would never land.
     */
    fun visibleMessages(inputs: ConversationInputs, keeping: Long? = null, now: Instant = Instant.now()): List<Message> {
        val key = inputs.key
        return Replies.presenting(
            inputs.relayBots.reattributing(
                inputs.ignores.visible(
                    inputs.messages.orEmpty().filter { inputs.kind.renders(it.type) && it.isRenderable },
                    networkId = key.networkId,
                    target = key.target,
                    keeping = keeping,
                    now = now,
                ),
                networkId = key.networkId,
            ),
            networkId = key.networkId,
            target = key.target,
            ignores = inputs.ignores,
            relayBots = inputs.relayBots,
            ownNick = inputs.ownNick,
            now = now,
        )
    }

    /**
     * The rows for this frame — [visibleMessages], then iOS's `rebuildRows` — and everything else one
     * build produces, for the screen — built off the main thread, so composition only
     * draws. Safe there because every input is immutable: `ConversationInputs` holds the store's own
     * lists and kit models (`ChatState` is replaced, never mutated), `RowOptions` is a value, and the
     * kit's filters and `MessageRows.build` are pure functions of their arguments — their only
     * caches are `by lazy` regexes, which are synchronized.
     */
    fun built(
        inputs: ConversationInputs,
        options: RowOptions,
        seq: Long = 0,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): BuiltRows {
        val visible = visibleMessages(inputs, options.keeping, now)
        return BuiltRows(
            inputs = inputs,
            options = options,
            visible = visible,
            rows = rows(inputs, visible, options, now, zone),
            seq = seq,
        )
    }

    private fun rows(
        inputs: ConversationInputs,
        visible: List<Message>,
        options: RowOptions,
        now: Instant,
        zone: ZoneId,
    ): List<MessageRow> {
        val buffer = inputs.buffer
        return MessageRows.build(
            messages = visible,
            // The read boundary, latched once `readStateKnown` says the server told us where it is
            // (`ConversationScroll`). Null until then, which draws no divider.
            dividerAfterId = options.dividerAfterId,
            // True when unknown: "no more history" has to be something the server told us, or an
            // unhydrated buffer claims to have reached its beginning.
            hasMoreOlder = buffer?.hasMoreOlder ?: true,
            hasMoreNewer = buffer?.hasMoreNewer == true,
            clearedBeforeId = buffer?.clearedBeforeId ?: 0,
            clearedAt = buffer?.clearedAt,
            // A jump onto a row the `/clear` marker hides peels it back — screen state, see
            // `ConversationScroll.revealIfJumpTargetHidden`.
            showsClearedHistory = options.showsClearedHistory,
            typists = inputs.typists,
            settings = inputs.settings,
            speakers = inputs.speakers ?: SpeakerMap(),
            ownNick = inputs.ownNick,
            away = awayState(inputs.networks, inputs.key, inputs.kind),
            now = now,
            zone = zone,
        )
    }

    /**
     * The title, and the status in the subtitle under it. iOS's `updateTitle`.
     *
     * The light is layered outside-in through `StatusLight.of` — the device's path, our socket, then
     * the network — except a DCC chat's, which is its own session and never the network's (lurker#270).
     * A DM's subtitle reports the peer once the link is good (lurker-ios#55), read through
     * `rowPresence`, the buffer list's reading, so the title and the DM's row never disagree.
     */
    fun title(state: ChatState, key: BufferKey, kind: BufferKind): StatusTitle {
        val networkName = key.networkId?.let { state.networks[it]?.name }
        val buffer = state.buffer(key)
        val status = if (kind == BufferKind.Dcc) {
            StatusLight.ofDccChat(reachable = state.reachable, connection = state.connection, live = state.dccChatSession(key))
        } else {
            StatusLight.of(
                reachable = state.reachable,
                connection = state.connection,
                network = key.networkId?.let { state.networks[it]?.state },
            )
        }
        // What the subtitle names beside the status: the network a conversation is on. Nothing for a
        // server buffer, whose title already is the network, or the system buffer, which has none.
        val detail = when (kind) {
            BufferKind.Channel, BufferKind.Dm -> networkName
            BufferKind.Dcc -> "DCC chat"
            BufferKind.Server, BufferKind.System -> null
        }
        val networkId = key.networkId
        val peer = if (kind == BufferKind.Dm && networkId != null) state.rowPresence(networkId, buffer.target) else null
        return StatusTitle(title = buffer.displayName(networkName), status = status, detail = detail, peer = peer)
    }

    /**
     * Loading, empty, or nothing at all behind the list. Keyed off the BUILT rows, not the raw
     * messages: since the `none` event tier (#666) a quiet channel can hold only joins and modes and
     * build to nothing, and reading the messages there would suppress the placeholder over a blank
     * screen. iOS's `updatePlaceholder`.
     *
     * [forceLoading]: a top-up page is on its way because the filters thinned the window out to
     * nothing (`ConversationScroll.wantsTopUp`), so it says "Loading messages…" rather than claiming
     * the buffer is empty while a page is in the air to prove otherwise.
     */
    fun placeholder(hasRows: Boolean, inputs: ConversationInputs, forceLoading: Boolean = false): BufferPlaceholder =
        if (forceLoading && !hasRows) BufferPlaceholder.Loading else BufferPlaceholder.of(
            hasMessages = hasRows,
            hydrated = inputs.buffer?.hydrated ?: false,
            hydratesOnDemand = inputs.kind.hydratesOnDemand,
            bufferExists = inputs.buffer != null,
            rosterSettled = inputs.rosterSettled,
        )

    /**
     * The empty-state copy, per kind — a just-joined channel and a fresh DM are different
     * invitations, and the system and server buffers aren't conversations at all. With iOS's
     * symbols: a speech bubble for the conversations, the server rack, sparkles for the welcome.
     */
    fun emptyState(kind: BufferKind, target: String): StateModel =
        when (kind) {
            BufferKind.Channel -> StateModel("No messages yet", StateSymbol.Conversation, "Messages in $target will show up here.")
            BufferKind.Dm -> StateModel("No messages yet", StateSymbol.Conversation, "Say hello to $target.")
            BufferKind.Dcc -> StateModel("No messages yet", StateSymbol.Conversation, "A direct chat with ${DccChat.peer(target)}.")
            BufferKind.Server -> StateModel("Nothing from the server yet", StateSymbol.Server)
            BufferKind.System -> StateModel("Welcome to Lurker", StateSymbol.Welcome, "Run /commands to see what you can do.")
        }

    /** "Loading messages…" — the one loading state a conversation has. */
    val LOADING: StateModel = StateModel("Loading messages…", isLoading = true)

    /**
     * Your own away state as it applies to this buffer (lurker-ios#68), or null where it doesn't.
     * Conversations only: the server log and the system buffer are narration about the connection,
     * and a marker there would be noise where noise is already densest — the web's call too.
     */
    fun awayState(networks: Map<Int, Network>, key: BufferKey, kind: BufferKind): AwayState? =
        when (kind) {
            BufferKind.Channel, BufferKind.Dm, BufferKind.Dcc -> key.networkId?.let { networks[it]?.away }
            BufferKind.Server, BufferKind.System -> null
        }

    /**
     * The channel-mode glyph for each current member, keyed by lowercased nick. Empty — and free —
     * unless `look.nick.show_mode_prefix` is on, and only for channels. Members only, deliberately:
     * backlog from someone who has since left gets no glyph rather than a guessed one, as on the web.
     */
    fun modePrefixes(
        kind: BufferKind,
        members: List<Member>,
        showsPrefix: Boolean,
        prefix: List<PrefixMode>?,
    ): Map<String, MemberPrefix.Mark> {
        if (kind != BufferKind.Channel || !showsPrefix) return emptyMap()
        val prefixes = HashMap<String, MemberPrefix.Mark>()
        for (member in members) {
            MemberPrefix.mark(member.modes, prefix)?.let { prefixes[member.nick.lowercase()] = it }
        }
        return prefixes
    }

    /**
     * Who the in-body nick colouring looks for: the channel's members, or the DM's peer — minus your
     * own nick, which a self-mention leaves in the body colour, matching the web. A DCC chat's peer,
     * not its buffer name: `=bob` never appears in a line, bob does. iOS's `refreshHighlighter`.
     */
    fun highlighterNicks(kind: BufferKind, key: BufferKey, members: List<Member>, ownNick: String?): List<String> {
        val candidates = when (kind) {
            BufferKind.Channel -> members.map { it.nick }
            BufferKind.Dm -> listOf(key.target)
            BufferKind.Dcc -> listOf(DccChat.peer(key.target))
            BufferKind.Server, BufferKind.System -> emptyList()
        }
        val own = ownNick?.lowercase() ?: return candidates
        return candidates.filter { it.lowercase() != own }
    }

    /**
     * What to call the network a nick-less line belongs to. Two sources, for two different lines: a
     * system line is app-scoped and carries the network it's *about*, while server text simply
     * belongs to whichever server buffer it's in.
     */
    fun networkName(message: Message, key: BufferKey, networks: Map<Int, Network>): String? =
        (message.originNetworkId ?: key.networkId)?.let { networks[it]?.name }

    /**
     * Whether the list is parked at its newest row — iOS's `isNearBottom` (within 80pt), read from the
     * previous rows' layout as new ones arrive. Following them down takes more than this
     * (`ConversationScroll.onRows`: not on a detached slice, not mid-landing). The list is
     * reverse-laid-out, so the newest row is item 0 and the bottom is its start.
     */
    fun followsTail(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, thresholdPx: Int): Boolean =
        firstVisibleIndex == 0 && firstVisibleOffsetPx <= thresholdPx
}

/**
 * Whether to ask for this buffer's history, and when to ask again. lurker-ios's `hydrateIfNeeded`,
 * with its state.
 *
 * Channel and DM buffers arrive as shells and aren't read until the client asks. The conversation
 * can exist before there's a socket to ask over (a launch restore), so it asks once the socket is up.
 *
 * Asks with `history mode:latest` (`ChatViewModel.hydrate`), not `open-buffer`. Both answer with the
 * newest slice, but `open-buffer` is a WRITE: it reopens a closed row and announces that to every one
 * of the user's devices, so merely opening a screen reopened a buffer everywhere — and the server's
 * paused-account gate, correctly classing writes as writes, left a paused account on "Loading
 * messages…" forever.
 *
 * The system buffer and `:server:` logs never ask (`BufferKind.hydratesOnDemand`): they ship their
 * real backlog in the burst.
 *
 * One optional rather than a flag plus a generation: two variables tracking one fact can disagree,
 * and on iOS they did — a reset that cleared the flag but left the generation made the screen ask
 * twice on one burst and take two full backlogs.
 */
internal class HydrateGate(private val kind: BufferKind) {
    /** The burst during which the one request went out, or null if it hasn't (or was voided). */
    private var requestedAtGeneration: Int? = null

    /** Whether the row was hydrated last time — to see it become UN-hydrated (a rename-merge reset). */
    private var sawHydrated = false

    /**
     * The key to hydrate now, or null. [row] is the store's row for this buffer; [jumpPending]
     * is `ConversationScroll`'s — a pending jump hydrates through an `around` slice instead, and asking for both would
     * double-fetch and have the latest slice fight the jump.
     */
    fun check(connection: SocketStatus, row: Buffer?, burstGeneration: Int, jumpPending: Boolean = false): BufferKey? {
        if (!kind.hydratesOnDemand) return null
        if (jumpPending) return null
        if (connection != SocketStatus.Connected) {
            // A reconnect resyncs buffers as shells, so ask again on the next one. A fast path only:
            // it fires when a drop is actually reported, which it isn't when a close lands after the
            // socket was already replaced. The burst check below is the one that always fires.
            requestedAtGeneration = null
            return null
        }
        // The row went away, so whatever we asked for is void — re-arm. Roster reconciliation can
        // drop the row under a live socket, and whatever re-materializes it brings back an unhydrated
        // shell; with the request still latched, nothing would ever ask again over that connection.
        // Keyed on ABSENCE rather than "unhydrated": unhydrated is also the normal window between
        // asking and the reply, and re-arming there would ask on every frame until it landed.
        if (row == null) requestedAtGeneration = null
        // A row PRESENT but newly un-hydrated is the other void: a rename-merge wiped the slice (the
        // survivor's history interleaved server-side). Keyed on the hydrated→unhydrated TRANSITION,
        // for the same reason as above.
        if (row != null) {
            if (row.hydrated) {
                sawHydrated = true
            } else if (sawHydrated) {
                sawHydrated = false
                requestedAtGeneration = null
            }
        }
        // A new burst voids a request made during the previous one. The socket can die and be
        // replaced with `connection` never leaving Connected, and a request written to the dying
        // socket is lost with no state change to notice — observed on iOS exactly that way. A
        // reconnect always produces a burst, and a burst means the server is re-sending everything.
        val asked = requestedAtGeneration
        if (asked != null && asked != burstGeneration) requestedAtGeneration = null
        if (requestedAtGeneration != null || row == null || row.hydrated) return null
        requestedAtGeneration = burstGeneration
        // The row's key, not the screen's: the screen's can carry a casing the server doesn't store
        // (the join flow opens a screen from the TYPED name — `#idlerpg` typed, `#idleRPG` stored).
        return row.key
    }
}

/**
 * Whether the buffer this screen shows is still there. lurker-ios's `handleBufferDisappeared`.
 *
 * `ChatState` drops buffer rows four ways, and all of them should take the reader off the screen:
 * `buffer-closed` (another device closed it), `removeBuffer` (this device did), `pruneToBurst` (a
 * reconnect revealed a close we slept through), and `reset()` on sign-out — which `AppRoot` handles
 * by swapping the whole app out, so the screen leaves that one alone.
 *
 * Absence alone is not the trigger, because absence is also the normal state early on: a screen
 * reached by *key* (a launch restore, a join) exists before its row lands. It takes one of two
 * proofs: we held a row and it was taken away, or we never held one but the roster is settled — the
 * server has listed everything it has and this key wasn't in it (lurker #635).
 */
internal class BufferWatch(private val key: BufferKey, private val kind: BufferKind) {

    sealed interface Verdict {
        /** The row is here. */
        data object Present : Verdict

        /** No row, and no proof yet that one isn't coming. */
        data object Waiting : Verdict

        /** Not gone — moved: a rename kept its id and changed its key. Follow it. */
        data class Moved(val key: BufferKey) : Verdict

        /** Gone: leave. */
        data object Gone : Verdict
    }

    /** Whether the store has ever held a row for this buffer. Latches. */
    private var sawBufferRow = false

    /**
     * The row's server id, learned the first time a row states one — a screen reached by key is born
     * without it, and the rename follow can only chase an id it holds.
     */
    private var bufferId: Int? = null

    fun check(state: ChatState): Verdict {
        val row = state.buffers[key.id]
        if (row != null) {
            sawBufferRow = true
            if (bufferId == null) bufferId = row.bufferId
            return Verdict.Present
        }
        // Not gone — MOVED. A rename keeps the buffer's id and changes its key, and the id index still
        // knows where it went. (The other direction needs no code: a viewer in the ABSORBED buffer of a
        // merge holds the key the survivor now occupies, so its row never disappears.)
        val id = bufferId
        if (id != null) {
            val moved = state.keysById[id]?.let { state.buffers[it] }
            if (moved != null) return Verdict.Moved(moved.key)
        }
        // ⚠⚠ A SERVER LOG's absence is not evidence of a close. It can't be closed, and the buffer list
        // offers a network's log whether or not a row has arrived — so "no row" is "no row yet", and
        // without this, opening one popped straight back to the list. Still gated on the NETWORK
        // existing: deleting a network really does take its log with it.
        val networkId = key.networkId
        if (kind == BufferKind.Server && networkId != null && state.networks[networkId] != null) return Verdict.Waiting
        // Port addition: the system buffer is the same case, more so — app-scoped, never closable,
        // and opened from the list's menu (or rested on side by side) whether or not its row has
        // landed (`Buffer.system`). Its absence can only mean "no row yet".
        if (kind == BufferKind.System) return Verdict.Waiting
        if (!sawBufferRow && !state.rosterSettled) return Verdict.Waiting
        return Verdict.Gone
    }
}
