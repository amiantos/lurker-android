// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.support.unicodeRegex

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

    // stripAddress, shown, presenting, continues: wait for NickCompletion, IgnoreSet and
    // RelayBotSet (see LEDGER).

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
