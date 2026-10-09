// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.commands.ArgKind
import net.amiantos.lurkerkit.commands.CommandCompletion
import net.amiantos.lurkerkit.commands.CommandRegistry
import net.amiantos.lurkerkit.commands.CommandSpec
import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.AwayStrip
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.MemberPrefix
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.NickCompletion
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.SpeakerMap
import net.amiantos.lurkerkit.model.member
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.commands.CommandParser

/*
 * The composer's decisions, with no Compose in them — lurker-ios's `ComposerBar`, `SuggestionsView`
 * and the composer half of `ChatViewController` (send, replies, drafts, the away strip, the
 * prompt), minus the views. Everything here is a function of plain values so it can be tested;
 * `ComposerState` holds the field and calls in.
 *
 * Offsets are UTF-16 throughout — a Kotlin `String`'s own indices, and what the kit's
 * `NickCompletion` and `CommandCompletion` take — so the field's selection goes straight in.
 */

/**
 * What kind of completion is live under the caret. The composer detects the *shape*
 * (`CommandCompletion` for a slash line, `NickCompletion` for a nick) and reports the query; the
 * candidates come from state the field never sees — the command table, the network's channels,
 * this buffer's members. iOS's `ComposerBar.Completion`.
 */
internal sealed interface Completion {
    /** Typing the command verb — `/jo|`. [query] excludes the slash. */
    data class Command(val query: String) : Completion

    /** Typing a channel argument of a command — `/join #li|`, `/part #|`. */
    data class ChannelArg(val query: String) : Completion

    /** Typing a nick argument of a command — `/msg al|`, `/whois b|`. */
    data class NickArg(val query: String) : Completion

    /** A nick being typed — `@al|`, or a bare `al|` (#57) — anywhere free text is allowed, including inside `/me …`. */
    data class Mention(val query: String) : Completion
}

/**
 * One floating pill's worth of content: what it says, what it inserts, what kind it is (which
 * decides its colour), and its spoken label. A nick and a command and a channel are all one shape
 * on screen — a capsule — differing only in tint and in what a tap inserts. iOS's `Suggestion`.
 *
 * The colour itself is the view's: it's a theme value, and this file has no theme.
 */
internal data class Suggestion(val title: String, val value: String, val kind: Kind, val accessibility: String) {
    enum class Kind {
        /** The app's accent, to read as an action. */
        Command,

        /** The neutral label colour. */
        Channel,

        /** The nick's own palette colour — the same identity signal the conversation uses. */
        Nick,
    }

    companion object {
        /** Shown with its slash; inserted by canonical name (the composer adds the slash and a space). */
        fun command(spec: CommandSpec) =
            Suggestion("/${spec.name}", spec.name, Kind.Command, "Command ${spec.name}, ${spec.summary}")

        /** Shown and inserted verbatim. */
        fun channel(name: String) = Suggestion(name, name, Kind.Channel, "Channel $name")

        fun nick(nick: String) = Suggestion(nick, nick, Kind.Nick, "Insert $nick")
    }
}

/** The field after an edit: its text, and where the caret lands. */
internal data class FieldEdit(val text: String, val caret: Int)

/**
 * The away strip's or the pending reply's words, or nothing — the one slot above the field. iOS's
 * `ComposerBar.renderStrip`.
 *
 * The reply wins while one is pending — it's what the next send does — and the away strip comes
 * back once it's spent or cancelled. One slot rather than two stacked strips, which would eat into
 * the little conversation a phone shows above the keyboard.
 */
internal sealed interface Strip {
    data object None : Strip

    /**
     * "Replying to alice: what she said", with ✕ to cancel. [name] is bold; [excerpt] may be empty
     * (a reply to a line with no words), and then there's no colon.
     */
    data class Reply(val name: String, val excerpt: String) : Strip {
        /** What TalkBack reads for the words. */
        val accessibility: String get() = "Replying to $name" + if (excerpt.isEmpty()) "" else ": $excerpt"
    }

    /** "**Away** since 2:32 PM · lunch", with Back. */
    data class Away(val lead: String, val detail: String) : Strip {
        val accessibility: String get() = lead + detail
    }
}

/**
 * What the composer shows that comes from state rather than from typing: who you'll speak as, and
 * whether you're away (lurker-ios#135). iOS's `ChatViewController.ComposerChrome`.
 */
internal data class ComposerChrome(
    val nick: String?,
    /** Ours alone, not the nicklist: every away-notify flip in a busy channel would get through. */
    val ownModes: List<String>,
    /** The network's PREFIX, which says what [ownModes] look like (lurker-ios#191). */
    val prefix: List<PrefixMode>? = null,
    val dccSession: Boolean?,
    val away: AwayState?,
) {
    /**
     * What the chrome is made from, as cheap to compare on every frame as it can be. The nicklist is
     * held whole and unsearched, and compared by identity — the store replaces a list it changed —
     * because comparing it so is O(1) and searching it is O(n) every time.
     */
    class Inputs(
        val nick: String?,
        val members: List<Member>?,
        val prefix: List<PrefixMode>?,
        val dccSession: Boolean?,
        val away: AwayState?,
    ) {
        companion object {
            fun of(state: ChatState, key: BufferKey, kind: BufferKind): Inputs {
                val networkId = key.networkId
                return Inputs(
                    nick = networkId?.let { state.networks[it]?.nick },
                    members = if (kind == BufferKind.Channel) state.members[key.id] else null,
                    prefix = networkId?.let { state.networks[it]?.modeSpec?.prefix },
                    dccSession = if (kind == BufferKind.Dcc) state.dccChatSession(key) else null,
                    // ⚠ Not the list's `awayState`, which leaves the server log out on purpose: that's
                    // about where a divider is noise. This is whether you're away, and you are in every
                    // buffer on the network — the server log included, where `/back` is as likely to be
                    // typed.
                    away = networkId?.let { state.networks[it]?.away },
                )
            }

            fun same(old: Inputs, new: Inputs): Boolean =
                old.nick == new.nick && old.members === new.members && old.prefix == new.prefix &&
                    old.dccSession == new.dccSession && old.away == new.away
        }
    }

    companion object {
        val Empty = ComposerChrome(nick = null, ownModes = emptyList(), prefix = null, dccSession = null, away = null)

        fun of(inputs: Inputs) = ComposerChrome(
            nick = inputs.nick,
            ownModes = inputs.members?.member(named = inputs.nick ?: "")?.modes ?: emptyList(),
            prefix = inputs.prefix,
            dccSession = inputs.dccSession,
            away = inputs.away,
        )
    }
}

/**
 * What the pills' candidates are drawn from, off the store — compared by identity (the store replaces
 * what it changed), so a frame that moved none of it costs four reference checks. The lines the list
 * renders are the other source; they're Compose state, and observed there.
 */
internal class CandidateSources(
    val members: List<Member>?,
    val ignores: IgnoreSet,
    val buffers: Map<String, Buffer>,
    val selfNick: String?,
    val speakers: SpeakerMap?,
) {
    companion object {
        fun of(state: ChatState, key: BufferKey) = CandidateSources(
            speakers = state.speakers[key.id],
            members = state.members[key.id],
            ignores = state.ignores,
            buffers = state.buffers,
            selfNick = key.networkId?.let { state.networks[it]?.nick },
        )

        fun same(old: CandidateSources, new: CandidateSources): Boolean =
            old.members === new.members && old.ignores === new.ignores && old.buffers === new.buffers &&
                old.selfNick == new.selfNick && old.speakers === new.speakers
    }
}

/** The reply a Reply on a line starts — see [ComposerModel.replyPlan]. */
internal data class ReplyPlan(
    /** Cancel the pending reply first (taking back the address its Reply put in the draft). */
    val cancelFirst: Boolean,
    /** The reply to make pending, or null when this line can't take a real one right now. */
    val start: PendingReply?,
    /** Address this nick at the head of the draft, or null (your own line, a DM). */
    val address: String?,
    /** Mark the started reply as having put the address there, if the address went in. */
    val marksAddressed: Boolean,
)

/** What sending does to where the list is. iOS's `send`. */
internal enum class SendScroll {
    /** Detached: the line isn't in the slice — re-attach, which lands at the new tail. */
    Reattach,

    /** Carry the reader down to their own line. */
    ToBottom,

    /** They asked to keep their place (`chat.keep_position_on_send`), and they're up in history. */
    Stay,
}

internal object ComposerModel {

    /**
     * How tall the text may grow before it scrolls inside instead. Five lines is the Messages
     * ceiling too — past that you're writing a paragraph, and the conversation behind the bar has
     * given up enough room.
     */
    const val MAX_LINES = 5

    /** How many channel chips a channel argument offers. */
    const val CHANNEL_LIMIT = 4

    // MARK: - Completion

    /**
     * The completion under the caret, or null. A slash line is classified first
     * (`CommandCompletion`): a channel/nick argument or the verb itself wins, and anything else —
     * free text, an unknown command — falls through to nick detection, so `/me @al|` and
     * `/me al|` still complete a nick. A selection (start ≠ end) is editing, never mid-token.
     */
    fun completion(text: String, selectionStart: Int, selectionEnd: Int): Completion? {
        if (selectionStart != selectionEnd) return null
        val caret = selectionStart
        when (val context = CommandCompletion.context(text, caret)) {
            is CommandCompletion.Context.Command -> return Completion.Command(context.query)
            is CommandCompletion.Context.Argument ->
                return if (context.kind == ArgKind.Channel) Completion.ChannelArg(context.query) else Completion.NickArg(context.query)
            null -> Unit
        }
        return NickCompletion.activeMention(text, caret)?.let { Completion.Mention(it.query) }
    }

    /**
     * The pills for [completion]: command chips from the table, channel chips, or nick chips ranked
     * the web client's way — best first; the view reverses them so the best sits nearest the field.
     * [channels] and [nicks] are asked only for the kind that needs them.
     */
    fun suggestions(
        completion: Completion?,
        channels: (query: String) -> List<String>,
        nicks: (query: String) -> List<String>,
    ): List<Suggestion> = when (completion) {
        is Completion.Command -> CommandRegistry.matching(completion.query).map(Suggestion::command)
        is Completion.ChannelArg -> channels(completion.query).map(Suggestion::channel)
        is Completion.NickArg -> nicks(completion.query).map(Suggestion::nick)
        is Completion.Mention -> nicks(completion.query).map(Suggestion::nick)
        null -> emptyList()
    }

    /**
     * Channels on [networkId] whose name matches [query], best-effort. Both sides are compared with a
     * leading channel sigil dropped (`ChannelName.fold`), so `/join li` still finds `#linux` — the
     * `#` the user hasn't typed yet shouldn't hide it. Any sigil, not just `#`.
     */
    fun channelCandidates(buffers: Collection<Buffer>, networkId: Int?, query: String, limit: Int = CHANNEL_LIMIT): List<String> {
        val needle = ChannelName.fold(query)
        return networkChannels(buffers, networkId)
            .filter { ChannelName.fold(it).startsWith(needle) }
            .take(limit)
    }

    /** [networkId]'s channels, case-insensitively sorted — the one list the pills and Tab both draw from. */
    private fun networkChannels(buffers: Collection<Buffer>, networkId: Int?): List<String> =
        buffers.filter { it.networkId == networkId && it.kind == BufferKind.Channel }
            .map { it.target }
            .sortedBy { it.lowercase() }

    /**
     * The channels Tab offers for a `#` word (`TabCompletion`, lurker-android#63), best first: the
     * buffer you're in leads when it's a channel, then the rest of [networkId]'s channels sorted
     * case-insensitively. The web ranks the rest by recency; the apps keep no recency list of
     * channels, so they go alphabetically, as the pills do. Unfiltered — `TabCompletion` filters by
     * what was typed.
     */
    fun tabChannels(buffers: Collection<Buffer>, networkId: Int?, current: BufferKey): List<String> {
        val channels = networkChannels(buffers, networkId)
        val here = buffers.firstOrNull { it.key.id == current.id && it.networkId == networkId && it.kind == BufferKind.Channel }
            ?: return channels
        return listOf(here.target) + channels.filter { it != here.target }
    }

    /**
     * What a pick inserts, by the context it was offered in. A command inserts its verb, a channel or
     * nick argument inserts that value, a nick being typed inserts the nick with its addressing suffix.
     * Null when the caret has moved off the token since (the pick is stale).
     */
    fun pick(text: String, selectionStart: Int, selectionEnd: Int, completion: Completion?, value: String, punctuation: String): FieldEdit? {
        if (selectionStart != selectionEnd) return null
        return when (completion) {
            is Completion.Command -> completeCommand(text, selectionStart, value)
            is Completion.ChannelArg, is Completion.NickArg -> completeArgument(text, selectionStart, value)
            is Completion.Mention -> completeMention(text, selectionStart, value, punctuation)
            null -> null
        }
    }

    /**
     * Replace the nick being typed — an `@…` or a bare word — with [nick] plus its addressing
     * suffix: the web picker's exact insertion, so both clients send the same line. An `@` goes: IRC
     * addresses by bare nick, and the sent line highlights by containing it. The whole word, not just
     * up to the caret: completing `@al|ice` must swallow the tail, not weld the pick onto it.
     *
     * A pick the word under the caret no longer leads to is stale and inserts nothing. A bare word
     * makes nearly any word a token, so "is there one" no longer tells a pick made for this word from
     * one made for the word the caret just left.
     *
     * [punctuation] is the resolved `input.completion.nick_suffix` — PUNCTUATION only, the space is
     * always ours to add (`NickCompletion.addressPunctuation`).
     */
    fun completeMention(text: String, caret: Int, nick: String, punctuation: String): FieldEdit? {
        val token = NickCompletion.activeMention(text, caret) ?: return null
        if (!nick.lowercase().startsWith(token.query.lowercase())) return null
        val replacement = nick + NickCompletion.addressingSuffix(beforeTokenAt = token.start, text = text, punctuation = punctuation)
        return replace(text, TextRange(token.start, token.end), replacement)
    }

    /**
     * Replace the verb under the caret with the picked command, trailing a space so the caret lands
     * where the first argument goes — picking `/join` leaves `/join |`, and the channel chips float
     * for the empty slot at once.
     */
    fun completeCommand(text: String, caret: Int, name: String): FieldEdit? {
        val context = CommandCompletion.context(text, caret) as? CommandCompletion.Context.Command ?: return null
        return replace(text, context.range, "/$name ")
    }

    /**
     * Replace the channel/nick argument under the caret with the pick, trailing a space so the next
     * argument (a key, a message, another nick) can follow.
     */
    fun completeArgument(text: String, caret: Int, value: String): FieldEdit? {
        val context = CommandCompletion.context(text, caret) as? CommandCompletion.Context.Argument ?: return null
        return replace(text, context.range, "$value ")
    }

    /** Swap [range] for [replacement] and drop the caret just past it. */
    private fun replace(text: String, range: TextRange, replacement: String): FieldEdit =
        FieldEdit(text.substring(0, range.start) + replacement + text.substring(range.end), range.start + replacement.length)

    // MARK: - Replies (lurker-ios#184)

    /**
     * Address [nick] at the head of the draft — what Reply does (lurker-ios#60). Prepends the
     * addressing form unless the draft already opens that way, keeps whatever was being typed, and
     * leaves the caret at the END so you carry on writing rather than in front of your own words.
     * Same insertion as the web's `addressInComposer`, so a reply reads identically whichever client
     * sent it.
     *
     * The already-addressed test is `NickCompletion.isAddressed`, not a prefix test on the form
     * about to be written: a draft can carry an older setting's mark, or the web's, and drafts sync —
     * a prefix test would stack a second address onto `bob: sure`.
     *
     * Returns the edit and whether it put the address there — a pending reply's cancel takes back
     * only an address its Reply inserted. Null for an empty nick.
     */
    fun address(text: String, nick: String, punctuation: String): Pair<FieldEdit, Boolean>? {
        if (nick.isEmpty()) return null
        val already = NickCompletion.isAddressed(text, nick = nick, punctuation = punctuation)
        val next = if (already) text else "$nick$punctuation $text"
        return FieldEdit(next, next.length) to !already
    }

    /**
     * Take back the `nick: ` a Reply put at the head of the draft — a cancelled reply's half of
     * [address]. Anything else in the field stays, and a draft that no longer opens with it is left
     * alone (null): the user has rewritten it, and it's theirs now.
     */
    fun removeAddress(text: String, nick: String, punctuation: String): FieldEdit? {
        val next = NickCompletion.removingAddress(text, nick = nick, punctuation = punctuation)
        return if (next == text) null else FieldEdit(next, next.length)
    }

    /**
     * Reply on a line — the web's `onReply`, iOS's `reply(to:)`. A line the server stamped gets a
     * real reply, pending above the composer. In a channel the composer also addresses its author,
     * which is what a client without replies sees (and what the quote hides for us). On your own
     * line, or in a DM, there's nobody to address: the tag is all it is, so it needs the network to
     * carry one right now ([canReply], re-checked at the tap).
     *
     * ⚠ Unlike the web, a channel reply needs its tag to go out too ([canReply]) before it's PENDING:
     * the strip says "Replying to alice", and on a network that can't carry the tag right now that
     * would be a promise the server then quietly breaks with a plain line. The address still goes in.
     * `canReply`, not whether reactions go out: irc.so takes a reply's tag and refuses a reaction's
     * take-back (lurker#1101).
     *
     * Reply again to the same author in a channel keeps the address the first Reply put there, so a
     * cancel may still take it back. Any other pending reply goes first, with its address — or the
     * next line would go out still addressed to them, or as a reply to their line while addressed to
     * someone else. (The web leaves `bob: alice: ` here; this doesn't.)
     *
     * Null when the line has no author to reply to.
     */
    fun replyPlan(message: Message, target: String, canReply: Boolean, pending: PendingReply?): ReplyPlan? {
        val nick = message.nick
        if (nick.isNullOrEmpty()) return null
        val unaddressed = message.isSelf || Replies.isPrivate(target)
        val started = Replies.replyable(message, target = target) && canReply
        val keepsAddress = started && !unaddressed && pending?.addressed == true &&
            NickCompletion.sameNick(pending.nick, nick)
        return ReplyPlan(
            cancelFirst = pending != null && !keepsAddress,
            start = if (started) Replies.pending(message, addressed = keepsAddress) else null,
            address = if (unaddressed) null else nick,
            marksAddressed = started,
        )
    }

    /**
     * [replyPlan] as the composer asks it, at the tap: whether a reply's tag goes out on [key]'s
     * network right now is `ChatState.canReply` — its own answer, not whether reactions do.
     */
    fun replyPlan(message: Message, key: BufferKey, state: ChatState, pending: PendingReply?): ReplyPlan? =
        replyPlan(message, key.target, canReply = state.canReply(networkId = key.networkId), pending = pending)

    /** The strip: the pending reply if there is one, else the away strip if you're away, else none. */
    fun strip(reply: PendingReply?, away: AwayStrip?): Strip = when {
        reply != null -> Strip.Reply(
            name = if (reply.isSelf) "yourself" else reply.nick,
            excerpt = Replies.excerpt(reply.text),
        )
        away != null -> Strip.Away(away.lead, away.detail)
        else -> Strip.None
    }

    // MARK: - The prompt (lurker-ios#135)

    /**
     * What the empty field says: who you'll be speaking as — your nick on this network, with your
     * rank in a channel (`@amiantos`), the prompt irssi and WeeChat put beside their input line. The
     * title already names the conversation and the network, so the field doesn't repeat either; what
     * it adds is the thing that changes under you, a `/nick` or a collision's `amiantos_`. The rank
     * is shown whatever `look.nick.show_mode_prefix` says: that setting decorates other people's
     * lines, and this is you. Before the network has told us a nick there's nothing true to say, so
     * it says "Message". The system buffer is the app's own command console, so it invites one.
     *
     * A DCC chat isn't spoken over the network, so it names the chat instead — and when it has no
     * session, the field is the one place always in view to say so before a line is typed into
     * nothing.
     */
    fun placeholder(chrome: ComposerChrome, key: BufferKey, kind: BufferKind): String {
        if (key.networkId == null) return "Type a command…"
        if (kind == BufferKind.Dcc) {
            if (chrome.dccSession != false) return "DCC Chat"
            return "Not connected — /dcc chat ${DccChat.peer(key.target)}"
        }
        val nick = chrome.nick
        if (nick.isNullOrEmpty()) return "Message"
        // The network's own glyph (its PREFIX), the one your own lines and the nicklist show — the
        // prompt disagreeing with them about you would be the stranger mistake.
        return MemberPrefix.of(chrome.ownModes, chrome.prefix) + nick
    }

    // MARK: - Send

    /**
     * The line the send button sends, or null when there's nothing but whitespace — the button is off
     * then anyway. The kit's `CommandParser.sendable`: trailing whitespace dropped, LEADING kept, since
     * ` /whois bob` is text to the channel (lurker-ios#210).
     */
    fun sendable(text: String): String? = CommandParser.sendable(text)

    /** Whether the field is empty — nothing typed, nothing but whitespace. */
    fun isBlank(text: String): Boolean = CommandParser.sendable(text) == null

    /**
     * Whether the screen should leave the reader where they are rather than carrying them to the
     * newest message — `chat.keep_position_on_send` on, and the reader actually up in history
     * rather than parked at the tail (where there's nothing to preserve). iOS's
     * `keepsPositionWhileReading`; asked on a send and when the keyboard arrives, which on a phone
     * is where the setting is felt first.
     */
    fun keepsPositionWhileReading(settings: Settings, nearBottom: Boolean): Boolean =
        settings.bool("chat.keep_position_on_send", default = false) && !nearBottom

    /**
     * Where a send leaves the list. A detached slice holds live traffic out, so the line just sent
     * isn't loaded and no amount of scrolling reaches it — left alone that reads as a failed send —
     * so it re-attaches, whatever the setting says: the setting is about keeping a place in a
     * conversation, and this is a place the conversation isn't. Otherwise down to your own line,
     * unless you asked to keep your place.
     */
    fun sendScroll(detached: Boolean, keepsPosition: Boolean): SendScroll = when {
        detached -> SendScroll.Reattach
        keepsPosition -> SendScroll.Stay
        else -> SendScroll.ToBottom
    }

    /**
     * Whether a refused line can come back into the composer now (lurker-ios#128).
     *
     * ⚠⚠ Never over the top of something the user has since written. The line is theirs either way,
     * and the one in front of them is the one they can see; clobbering it to recover the older one
     * would lose a message to save a message. And not while a reply is pending: one started on your
     * own line or in a DM leaves the field empty, and the restored line would go out as THAT reply
     * rather than the one it was sent as. Either way it waits, held, for the next free moment.
     */
    fun canRestoreRefused(fieldText: String, pendingReply: PendingReply?): Boolean =
        isBlank(fieldText) && pendingReply == null

    // MARK: - Drafts (lurker-ios#188)

    /**
     * Whether the stored draft changing should repaint the field. A write that lands between your
     * pauses does repaint — last write wins, as on the web — but ⚠⚠ never over an edit of yours the
     * server hasn't heard, nor mid-composition: the view model drops those writes, and [protected]
     * is its answer (`isDraftProtected`), not the store's copy, which the drop never touched. And
     * only a CHANGE to the stored copy: this device's own flush coming back matches what it saw.
     */
    fun repaintsDraft(stored: ComposerDraft?, lastSeen: ComposerDraft?, protected: Boolean): Boolean =
        stored != lastSeen && !protected
}
