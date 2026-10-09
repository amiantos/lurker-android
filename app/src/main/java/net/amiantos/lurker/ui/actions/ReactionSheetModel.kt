// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * What the reaction sheet draws for one line, and nothing else — so the sheet's stream dedupes on it
 * rather than recomposing on every frame of every buffer (the rule: never collect `ChatState` raw).
 * iOS dedupes on `reactionsRevision` + `tagSupport`; this compares what those stand for directly.
 */
data class ReactionSheetInputs(
    /** Who reacted with what, first-reacted first. */
    val groups: List<ReactionGroup>,
    /**
     * Whether a NEW reaction can go out on this line right now — what the quick picks and the field
     * are for (`Reactions.canToggle`, not ours).
     */
    val canAdd: Boolean,
    /**
     * Whether one of OURS can come back off it right now (`Reactions.canToggle`, ours). A network can
     * take a reaction and refuse the take-back: irc.so does (lurker#1101).
     */
    val canRemove: Boolean,
) {
    /** The values that are ours — what lights a quick pick, and what makes choosing one a take-back. */
    val mine: Set<String> get() = groups.filter { it.mine }.map { it.value }.toSet()

    /**
     * Whether choosing [value] would go out: ours takes it back, anything else adds ours — the kit's
     * `Reactions.canToggle`, resolved once for the whole sheet (iOS's `works`).
     */
    fun works(value: String): Boolean = if (value in mine) canRemove else canAdd

    /**
     * Whether to say, under the standing reactions, that ours can't be taken back here: the sheet
     * offers adding one, one of ours is standing, and the network won't take it back.
     */
    val noTakeBack: Boolean get() = canAdd && !canRemove && mine.isNotEmpty()

    companion object {
        fun of(state: ChatState, message: Message, key: BufferKey): ReactionSheetInputs {
            // Resolved once for everything the sheet draws together, as `tagSupport`'s doc asks.
            val support = state.tagSupport(networkId = key.networkId)
            return ReactionSheetInputs(
                groups = state.reactionGroups(message.id),
                canAdd = Reactions.canToggle(mine = false, message = message, target = key.target, support = support),
                canRemove = Reactions.canToggle(mine = true, message = message, target = key.target, support = support),
            )
        }
    }
}

/**
 * The field's verdict on what's typed: the value to send, whether it's too long, whether it's one of
 * ours the network won't take back, and so whether React is live.
 */
data class TypedReaction(val value: String, val tooLong: Boolean, val refused: Boolean = false) {
    val canSubmit: Boolean get() = value.isNotEmpty() && !tooLong && !refused

    /** What to say under the field about the value itself, or null when there's nothing wrong with it. */
    val problem: String?
        get() = when {
            tooLong -> ReactionSheetModel.TOO_LONG
            refused -> ReactionSheetModel.NO_TAKE_BACK
            else -> null
        }

    companion object {
        /**
         * Trimmed (the kit's whitespace, never Kotlin's `trim`), then checked against the server's
         * limit — an emoji, or plain text like "lol", which the spec allows and IRC people use — and,
         * given the sheet's [inputs], against what the network takes: a typed value that matches one
         * of ours is a take-back, which the network may refuse. Said at the field, where the keyboard
         * can't hide it, rather than only in the footnote under the standing list (iOS's `updateField`).
         */
        fun of(text: String, inputs: ReactionSheetInputs? = null): TypedReaction {
            val value = text.trimmingWhitespacesAndNewlines()
            val valid = Reactions.isValidValue(value)
            return TypedReaction(
                value,
                tooLong = value.isNotEmpty() && !valid,
                refused = valid && inputs != null && !inputs.works(value),
            )
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

    /**
     * The line itself under the title, codes stripped and spoilers kept hidden — what you see on
     * screen, so you know which line, and never the secret a box is hiding (`SpoilerSafeText`).
     */
    fun quote(message: Message): SpoilerSafeText? = message.text?.let(SpoilerSafeText::of)

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

    /**
     * One of ours, on a network that takes a reaction but not a take-back (irc.so, lurker#1101): said
     * under the standing list and at the field, and why our own values are greyed out.
     */
    const val NO_TAKE_BACK = "This network can't take a reaction back."

    /**
     * A choice on the sheet, re-checked against [state] at the tap rather than trusted from whenever
     * the buttons were drawn — the network may have dropped since, and a take-back the network
     * refuses must never go out (`ChatState.canToggleReaction`, the kit's one rule). [toggle] sends
     * it: true when it went to a socket.
     */
    fun choose(state: ChatState, message: Message, key: BufferKey, value: String, toggle: () -> Boolean): ReactionChoice =
        when {
            !Reactions.isValidValue(value) ||
                !state.canToggleReaction(value, message = message, target = key.target, networkId = key.networkId) ->
                ReactionChoice.Refused
            toggle() -> ReactionChoice.Sent
            else -> ReactionChoice.NotConnected
        }

    /** A toggle went nowhere — no socket. The sheet says so and stays, rather than closing on nothing. */
    const val NOT_CONNECTED = "Not connected — try again in a moment."

    /** "👍, 2: alice, bob" — what TalkBack reads for a standing reaction. */
    fun spoken(group: ReactionGroup): String = "${group.value}, ${group.nicks.size}: ${group.nicks.joinToString(", ")}"

    /** The nicks after a standing reaction's value. */
    fun names(group: ReactionGroup): String = group.nicks.joinToString(", ")
}
