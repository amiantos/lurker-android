// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.graphemeBoundaries
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.Result

/**
 * One IRCv3 reaction (`+draft/react`) standing on a line, as it rides a message row: who, what,
 * and whether it's ours (lurker#990, lurker-ios#183).
 */
data class MessageReaction(
    val nick: String,
    /** An emoji or a short bit of text — the spec allows either, and IRC people use both. */
    val value: String,
    /**
     * Ours, from any of our clients. ⚠ Judged by the server, never by comparing `nick` to our
     * own: a reaction given before a nick change is still ours, and only this flag says so.
     */
    val isSelf: Boolean,
)

/**
 * One chip on a line: a value, everyone who reacted with it (first-reacted first), and whether
 * we're among them.
 */
data class ReactionGroup(
    val value: String,
    val nicks: List<String>,
    val mine: Boolean,
)

/**
 * A live `reaction` frame: one reaction added or taken back on the line `messageId`.
 *
 * Port note: `messageId` is a message id, so it is a `Long` here (PORTING.md, Types).
 */
data class ReactionChange(
    val networkId: Int,
    val target: String,
    val messageId: Long,
    val nick: String,
    val value: String,
    val isSelf: Boolean,
    val remove: Boolean,
    /** The line is ours, so this belongs in the activity feed. */
    val toSelf: Boolean,
)

/** The rules about reactions that don't belong to any one screen. */
object Reactions {

    /**
     * The picks a reaction sheet offers before anything is typed — the web's list, so the two
     * clients nudge people toward the same handful.
     */
    val quickPicks: List<String> = listOf("👍", "❤️", "😂", "🎉", "😮", "😢", "👀", "🙏")

    /**
     * The server's `MAX_REACTION_GRAPHEMES`. Longer values are dropped, not truncated, on the
     * way in from the network (halloy's rule), and refused on the way out.
     */
    const val maxGraphemes = 64

    /**
     * How many of each buffer's newest lines a resume re-reads, and how many in all — the
     * latter is the server's `MAX_REACTION_SYNC_IDS`, past which it ignores the rest.
     */
    const val syncPerBuffer = 200
    const val syncMaxIds = 5000

    /**
     * Whether `value` can go out as a reaction: something visible, on one line, no longer than
     * the server takes. The length is in grapheme clusters, which is the unit the server
     * counts in, so a flag or a family emoji is one.
     *
     * Port note: Swift's `count` is grapheme clusters for free; a Kotlin `length` is UTF-16
     * units, under which a family emoji is eleven and the limit would refuse values the
     * server takes. So the clusters are counted, by [graphemeCount].
     */
    fun isValidValue(value: String): Boolean {
        if (value.trimmingWhitespacesAndNewlines().isEmpty()) return false
        // Port note: the Swift tests each `Character` against "\n", "\r" and "\r\n". A line
        // break is always a cluster of its own (UAX #29 breaks on both sides of CR, LF and
        // CRLF), so asking the UTF-16 units is the same question.
        if (value.any { it == '\n' || it == '\r' }) return false
        return graphemeCount(value) <= maxGraphemes
    }

    /**
     * How many grapheme clusters `value` is — Swift's `String.count`. Port-only.
     *
     * Port note: ⚠ `java.text.BreakIterator` is two implementations, and neither is Swift's.
     * On Android it is ICU; on the host JVM it segments by extended grapheme cluster only
     * from JDK 20 on (an older one splits a ZWJ sequence and a flag into their parts). Each
     * carries its own version of Unicode's rules and data, and so does the Swift runtime, so a
     * sequence one of them joins and another doesn't is counted differently.
     *
     * Checked against the Swift on the host (JDK 21) at 64 and 65 repeats: ZWJ families
     * (Unicode 15.1's included), flags, tag-sequence flags, skin tones, keycaps, Hangul jamo,
     * and combining marks all count as Swift counts them. Indic conjuncts do not — `क्ष` is one
     * cluster to Swift (Unicode 15.1's rule GB9c) and two to JDK 21 — so a run of them reaches
     * the limit here at half the length it does on iOS (63 and 64 are accepted there and
     * refused here). A device's answer depends on its Android version and is unverified.
     * Where the two differ it is by splitting what the other joins, so only for a value
     * within reach of the limit.
     */
    private fun graphemeCount(value: String): Int = graphemeBoundaries(value).size

    /**
     * Reactions grouped by value for display: groups in the order their first reaction arrived,
     * nicks within a group the same.
     *
     * Port note: values group by code-unit equality. Swift's `==` is canonical equivalence, so
     * a precomposed `é` and `e` + U+0301 are one chip there and two here (PORTING.md, Strings).
     * `❤` and `❤️` are two chips on both.
     */
    fun groups(list: List<MessageReaction>): List<ReactionGroup> {
        // Port note: gathered by value in first-seen order and frozen once; LurkerKit appends to
        // the group in place, where a copy per reaction here would be quadratic.
        class Gathering(var mine: Boolean) {
            val nicks = mutableListOf<String>()
        }
        val groups = LinkedHashMap<String, Gathering>()
        for (reaction in list) {
            val group = groups.getOrPut(reaction.value) { Gathering(mine = false) }
            group.nicks.add(reaction.nick)
            group.mine = group.mine || reaction.isSelf
        }
        return groups.map { (value, group) -> ReactionGroup(value = value, nicks = group.nicks, mine = group.mine) }
    }

    /**
     * Whether a line can carry reactions at all — the chat lines a reaction can name, on a
     * network. Not the same as being able to *send* one: a notice someone else's client reacted
     * to still shows its chips, and so does an encrypted line (see `canSend`).
     */
    fun canCarry(message: Message, networkId: Int?): Boolean =
        message.id != 0L && networkId != null &&
            (message.type == EventType.Message || message.type == EventType.Action || message.type == EventType.Notice)

    /**
     * Whether this client may send a new reaction on `message`: a `message`/`action` the server
     * named with a msgid, in a channel or DM, not end-to-end encrypted, on a network that takes
     * one. The server re-checks all of it (`reactionSendTarget`) and refuses in silence — so this
     * is what keeps a control off a line where it could only do nothing. `support` is
     * `ChatState.tagSupport(networkId:)`.
     */
    fun canSend(message: Message, target: String, support: TagSupport): Boolean =
        canToggle(mine = false, message = message, target = target, support = support)

    /**
     * Whether choosing a value on `message` would do anything: one of ours (`mine`) takes it back,
     * which needs `canRemoveReaction`; anything else adds ours, which needs `canAddReaction`. A
     * network can allow adding one and deny the take-back — irc.so's UnrealIRCd does (lurker#1101) —
     * and the server refuses a take-back there in silence. Every entry point asks this, through
     * `ChatState.canToggleReaction`, so none offers what the send would lose.
     */
    fun canToggle(mine: Boolean, message: Message, target: String, support: TagSupport): Boolean =
        (if (mine) support.canRemoveReaction else support.canAddReaction) && lineTakes(message, target = target)

    /**
     * The line half of `canSend`: whether this line could ever take a reaction from here,
     * whatever the network is doing right now. False for a notice, an encrypted line, a line
     * with no msgid, or one outside a channel or DM — which is what says to the sheet whether
     * to blame the line or the network, and to the row whether to offer an add chip at all.
     */
    fun lineTakes(message: Message, target: String): Boolean =
        message.id != 0L &&
            !message.msgid.isNullOrEmpty() &&
            !message.isE2E &&
            (message.type == EventType.Message || message.type == EventType.Action) &&
            isConversation(target)

    /**
     * What `/react` lands on: the last line someone else said here — and when THAT one can't
     * take a reaction, why not, rather than quietly reaching back to an older line the user
     * never meant (an encrypted run, a trailing notice, a line with no msgid). The web's rule.
     */
    fun commandTarget(messages: List<Message>): Result<Message, CommandRefusal> {
        val line = messages.lastOrNull {
            it.id != 0L && !it.isSelf &&
                (it.type == EventType.Message || it.type == EventType.Action || it.type == EventType.Notice)
        } ?: return Result.Failure(CommandRefusal("nothing here to react to"))
        if (line.type == EventType.Notice) return Result.Failure(CommandRefusal("can't react to a notice"))
        if (line.isE2E) return Result.Failure(CommandRefusal("can't react to an encrypted line"))
        if (line.msgid.isNullOrEmpty()) return Result.Failure(CommandRefusal("can't react to that line (no message id)"))
        return Result.Success(line)
    }

    /**
     * Why `/react` found nothing to land on, in the words the buffer prints.
     *
     * Port note: an `Error` in LurkerKit only so it can ride a `Result`; never thrown, so a
     * plain value here.
     */
    data class CommandRefusal(val text: String)

    /**
     * A channel or a DM — an IRC target a tag can ride to. Not the `:server:` console, the
     * app-scoped system buffer, or a `=nick` DCC chat, none of which a TAGMSG can reach.
     *
     * Port note: reads the first UTF-16 unit, where LurkerKit reads the first `Character` — a
     * `:` or `=` carrying a combining mark is a conversation there and not here.
     */
    fun isConversation(target: String): Boolean =
        target.isNotEmpty() && !target.startsWith(":") && !DccChat.isTarget(target)
}
