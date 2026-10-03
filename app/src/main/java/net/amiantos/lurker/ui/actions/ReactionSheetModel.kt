// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * What the reaction sheet draws for one line, and nothing else — so the sheet's stream dedupes on it
 * rather than recomposing on every frame of every buffer (the rule: never collect `ChatState` raw).
 * iOS dedupes on `reactionsRevision` + `canReact`; this compares what those stand for directly.
 */
data class ReactionSheetInputs(
    /** Who reacted with what, first-reacted first. */
    val groups: List<ReactionGroup>,
    /** Whether a reaction can go out on this line right now (`Reactions.canSend`). */
    val canReact: Boolean,
) {
    /** The values that are ours — what lights a quick pick. */
    val mine: Set<String> get() = groups.filter { it.mine }.map { it.value }.toSet()

    companion object {
        fun of(state: ChatState, message: Message, key: BufferKey): ReactionSheetInputs =
            ReactionSheetInputs(
                groups = state.reactionGroups(message.id),
                canReact = Reactions.canSend(message, target = key.target, networkCanReact = state.canReact(key.networkId)),
            )
    }
}

/** The field's verdict on what's typed: the value to send, whether it's too long, whether React is live. */
data class TypedReaction(val value: String, val tooLong: Boolean) {
    val canSubmit: Boolean get() = value.isNotEmpty() && !tooLong

    companion object {
        /**
         * Trimmed (the kit's whitespace, never Kotlin's `trim`), then checked against the server's
         * limit — an emoji, or plain text like "lol", which the spec allows and IRC people use.
         */
        fun of(text: String): TypedReaction {
            val value = text.trimmingWhitespacesAndNewlines()
            return TypedReaction(value, tooLong = value.isNotEmpty() && !Reactions.isValidValue(value))
        }
    }
}

/**
 * The reaction sheet (lurker-ios#183), decided without drawing it — lurker-ios's
 * `ReactionSheetViewController`: who reacted with what (the only place a touch screen can see that),
 * a grid of quick picks, and a field for anything else. Every choice toggles.
 */
object ReactionSheetModel {

    /** "React to alice", "React to your message", or plain "React" for a line with no nick. */
    fun title(message: Message): String {
        val nick = message.nick ?: ""
        return when {
            nick.isEmpty() -> "React"
            message.isSelf -> "React to your message"
            else -> "React to $nick"
        }
    }

    /** The line itself under the title, codes stripped — what you see on screen, so you know which line. */
    fun quote(message: Message): String? = message.text?.let(IRCFormatting::strip)

    /**
     * The quick picks as two rows of four, not one of eight: eight across a compact phone left each
     * pick under the 48dp a thumb needs, and clipped the emoji at large font scales.
     */
    fun quickRows(picks: List<String> = Reactions.quickPicks): List<List<String>> {
        val half = (picks.size + 1) / 2
        return listOf(picks.take(half), picks.drop(half)).filter { it.isNotEmpty() }
    }

    /**
     * Why the picker is hidden, blaming the right thing: a notice or an encrypted line can't take one
     * on any network, and sending someone off to look for a connection problem there would be a wild
     * goose chase.
     */
    fun offline(message: Message, target: String): String =
        if (Reactions.lineTakes(message, target = target)) {
            "This network can't carry reactions right now."
        } else {
            "This line can't take reactions."
        }

    const val TOO_LONG = "That's longer than a reaction can be."

    /** A toggle went nowhere — no socket. The sheet says so and stays, rather than closing on nothing. */
    const val NOT_CONNECTED = "Not connected — try again in a moment."

    /** "👍, 2: alice, bob" — what TalkBack reads for a standing reaction. */
    fun spoken(group: ReactionGroup): String = "${group.value}, ${group.nicks.size}: ${group.nicks.joinToString(", ")}"

    /** The nicks after a standing reaction's value. */
    fun names(group: ReactionGroup): String = group.nicks.joinToString(", ")
}
