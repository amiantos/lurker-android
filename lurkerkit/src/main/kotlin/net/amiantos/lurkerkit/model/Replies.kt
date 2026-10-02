// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.support.unicodeRegex
import java.time.Instant

/**
 * The line an IRCv3 reply answers, as the server found it by msgid in the reply's own buffer
 * (lurker#995, lurker-ios#184). Its `text` is clipped to 300 characters with formatting codes
 * intact.
 *
 * Port note: `id` is a message id, so it is a `Long` here (PORTING.md, Types) — as are
 * `ReplyQuote.id` and `PendingReply.messageId` below.
 */
data class ReplyParent(
    /** The jump target. */
    val id: Long,
    val nick: String,
    val type: EventType,
    val text: String,
    /** For the ignore check: a line from someone ignored since shows as unavailable. */
    val userhost: String? = null,
    /** One of your own lines. */
    val isSelf: Boolean = false,
)

/**
 * On a message row that is a reply. `parent` is null when no line we hold carries that msgid —
 * retention took it, it predates our history, it was a reaction, or its author was ignored when
 * it arrived — and the reply then shows without its context.
 */
data class ReplyContext(
    val msgid: String,
    val parent: ReplyParent?,
)

/**
 * The answered line as a reply's quote shows it: when it came through a marked relay bot, as the
 * person inside the envelope, with the bot and the `[source]` kept (lurker#996).
 *
 * Port note: built inside the kit only, as in LurkerKit, where the struct has no public
 * `init` — so the constructor and `copy` are internal.
 */
@ConsistentCopyVisibility
data class ReplyQuote internal constructor(
    val id: Long,
    val nick: String,
    val type: EventType,
    val text: String,
    /** Whether the person quoted is you — for a relayed line, whether the person INSIDE it is. */
    val isSelf: Boolean,
    val relayBot: String?,
    val relaySource: String?,
)

/**
 * The reply a composer is writing (the web's `PendingReply`): the line it answers, and whether
 * the Reply put `nick: ` into the draft — only then does cancelling take it back out.
 */
data class PendingReply(
    /** The stored line being answered — what `replyTo` names on the wire. */
    val messageId: Long,
    val nick: String,
    val type: EventType,
    val text: String,
    /** A reply to your own line — the bar says "yourself". */
    val isSelf: Boolean,
    val addressed: Boolean = false,
)

/**
 * The rules about IRCv3 replies that don't belong to any one screen — the web's `replyText.ts`,
 * `useReplyQuote` and the reply half of `useMessageActions`.
 */
object Replies {

    /**
     * Whether a Reply can make a real reply of this line: it needs the msgid the reply names and a
     * channel or DM to send it in. Not an E2E line — the server sends no reply tags on an
     * encrypted channel — and not the `:server:` console or a `=nick` DCC chat. The server
     * re-checks all of it and sends a plain line when it can't; this decides what Reply does.
     */
    fun replyable(message: Message, target: String): Boolean =
        message.id != 0L &&
            !message.msgid.isNullOrEmpty() &&
            !message.isE2E &&
            message.type.isSpeech &&
            Reactions.isConversation(target)

    /**
     * A DM or a DCC chat: the line goes to the one other person anyway, so a Reply there puts no
     * `nick: ` in the draft (lurker#1015 — halloy skips it in queries too).
     *
     * Port note: reads the first UTF-16 unit, where LurkerKit reads the first `Character` — a
     * `:` or a channel sigil carrying a combining mark is private there and not here.
     */
    fun isPrivate(target: String): Boolean =
        target.isNotEmpty() && !target.startsWith(":") && !ChannelName.isChannelTarget(target)

    /**
     * Whether the composer line `text` would go out as a reply if one is pending: a plain line,
     * a `//`-escaped one, or a `/me` with something in it. Every other command leaves the reply
     * pending — the web's rule, so a `/whois` typed mid-reply doesn't spend it.
     */
    fun consumes(text: String): Boolean {
        if (text.startsWith("//") || !text.startsWith("/")) return text.isNotEmpty()
        val body = text.substring(1)
        val verb = body.takeWhile { !it.isSwiftWhitespace() }
        if (verb.lowercase() != "me") return false
        return body.substring(verb.length).trimmingWhitespacesAndNewlines().isNotEmpty()
    }

    private val lineBreaks = unicodeRegex("""\s*\n+\s*""")

    /**
     * The answered line as one line of plain text: formatting codes dropped, line breaks folded
     * to spaces. The view clips it to its width.
     */
    fun excerpt(text: String): String =
        IRCFormatting.strip(text)
            .replace(lineBreaks, " ")
            .trimmingWhitespacesAndNewlines()

    /**
     * A reply's text without the `nick: ` it opens with when it names the author it answers —
     * how halloy, goguma and our own composer send one, so a client without replies still sees
     * who it's for. The quote above already names them. Only a nick followed by punctuation
     * counts: a reply to `will` saying "will you come?" keeps its first word. Never strips to
     * nothing.
     *
     * The scalar walk is `NickCompletion`'s, so what Reply writes, what Cancel takes back and
     * what this hides agree on what a nick and a mark are — and it costs no regex per reply on a
     * list that's re-presented on every frame.
     */
    fun stripAddress(text: String, nick: String): String =
        NickCompletion.removingReplyAddress(text, nick = nick)

    /**
     * What [shown] answers: a reply's quote, and its own text.
     *
     * Port note: the Swift returns a named tuple, `(quote: ReplyQuote?, text: String?)`.
     */
    data class Shown(val quote: ReplyQuote?, val text: String?)

    /**
     * How a reply reads — its quote, and its own text — the ONE rule, so a reply reads the same
     * in the timeline and wherever else it turns up (the web's `useReplyQuote.shownReply`).
     *
     * - The quote is null ("unavailable") when the server found no line, and also when its author
     *   is ignored NOW: the server only screens out who was ignored when the reply arrived. Your
     *   own line is never hidden. Judged as the line it was, in the reply's buffer.
     * - A quoted line from a marked relay bot shows the person inside its envelope, as the
     *   timeline shows that line; `isSelf` then says whether that person is you.
     * - The text loses the `alice: ` it opens with when the quote names her — or the bot, which a
     *   client that knows nothing of relay marks addresses instead. Only on a `message`, and only
     *   when there's a quote: with none, that address is the only sign of who it's to.
     */
    fun shown(
        context: ReplyContext,
        line: Message,
        networkId: Int?,
        target: String,
        ignores: IgnoreSet,
        relayBots: RelayBotSet,
        ownNick: String?,
        now: Instant = Instant.now(),
    ): Shown {
        val parent = context.parent ?: return Shown(null, line.text)
        val asLine = Message(
            id = parent.id, type = parent.type, nick = parent.nick, text = parent.text,
            isSelf = parent.isSelf, userhost = parent.userhost,
        )
        if (!parent.isSelf &&
            ignores.isMessageHidden(networkId = networkId, message = asLine, target = target, now = now)
        ) {
            return Shown(null, line.text)
        }
        val unwrapped = relayBots.reattributing(listOf(asLine), networkId = networkId).firstOrNull() ?: asLine
        val relayed = unwrapped.relayBot != null
        val quote = ReplyQuote(
            id = parent.id,
            nick = unwrapped.nick ?: parent.nick,
            type = parent.type,
            text = unwrapped.text ?: parent.text,
            isSelf = if (relayed) {
                ownNick?.let { NickCompletion.sameNick(it, unwrapped.nick ?: "") } ?: false
            } else {
                parent.isSelf
            },
            relayBot = unwrapped.relayBot,
            relaySource = unwrapped.relaySource,
        )
        val text = line.text
        if (line.type != EventType.Message || text == null) return Shown(quote, line.text)
        var stripped = stripAddress(text, nick = quote.nick)
        val bot = quote.relayBot
        if (stripped == text && bot != null) stripped = stripAddress(text, nick = bot)
        return Shown(quote, stripped)
    }

    /**
     * `shown` over a list the screen is about to draw: every reply gets its quote and its
     * stripped text, everything else passes through. Run after relay re-attribution, so a reply
     * that came through a bridge is judged as the line it reads as.
     */
    fun presenting(
        messages: List<Message>,
        networkId: Int?,
        target: String,
        ignores: IgnoreSet,
        relayBots: RelayBotSet,
        ownNick: String?,
        now: Instant = Instant.now(),
    ): List<Message> {
        if (messages.none { it.replyTo != null }) return messages
        return messages.map { line ->
            val context = line.replyTo ?: return@map line
            val result = shown(
                context, line = line, networkId = networkId, target = target,
                ignores = ignores, relayBots = relayBots, ownNick = ownNick, now = now,
            )
            line.showingReply(quote = result.quote, text = result.text)
        }
    }

    /**
     * Whether a reply should show its quote again, or is the next chunk of one already quoted:
     * obby and goguma tag every chunk of a long reply where Lurker and halloy tag the first. Only
     * while it reads as one run of the same speaker's lines (the caller's author run), answering
     * the same msgid with the same kind of line. A divider, anyone else's line, or a later reply
     * to the same line, and the quote shows again.
     */
    fun continues(line: Message, previous: Message?): Boolean {
        val mine = line.replyTo
        val theirs = previous?.replyTo
        if (previous == null || mine == null || theirs == null) return false
        return mine.msgid == theirs.msgid &&
            previous.type == line.type &&
            previous.isSelf == line.isSelf &&
            previous.relaySource == line.relaySource &&
            NickCompletion.sameNick(previous.nick ?: "", line.nick ?: "")
    }

    /**
     * The pending reply a Reply on `message` starts, drawn from the line as it's SHOWN — a relayed
     * line as the person inside it, whom the Reply addresses and a cancel un-addresses.
     */
    fun pending(message: Message, addressed: Boolean = false): PendingReply =
        PendingReply(
            messageId = message.id,
            nick = message.nick ?: "",
            type = message.type,
            text = message.text ?: "",
            isSelf = message.isSelf,
            addressed = addressed,
        )
}
