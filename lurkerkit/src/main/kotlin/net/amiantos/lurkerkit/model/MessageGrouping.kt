// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration

/**
 * Which messages collapse into one visual run.
 *
 * Load-bearing, not a polish detail. A 1:1 messenger can give every message its own nick header
 * and full spacing because there are only two participants and they alternate. An IRC channel has
 * dozens, so without runs you get a header per line and the list roughly doubles in height for no
 * added information.
 */
object MessageGrouping {
    /**
     * A gap longer than this breaks a run even for the same author. Without it, two
     * messages from the same nick three hours apart would render as one conversation.
     */
    val runGap: Duration = Duration.ofSeconds(5 * 60)

    /**
     * Whether `message` continues the run that `previous` is part of.
     *
     * Only lines with a separate author group (`EventType.isBubble`): an action names its own
     * actor, so it breaks any run it lands in — which is correct, since "* nick waves" between
     * two of nick's messages is a real interruption.
     *
     * The types must match, not merely both be headed. A message and a notice from the
     * same nick are different kinds of utterance — NOTICE is what a bot uses precisely to
     * say "this is not a reply to talk back to" — and grouping them would caption the run
     * once and render the rest identically, erasing the distinction the sender chose.
     */
    fun continuesRun(message: Message, previous: Message?): Boolean {
        if (previous == null ||
            !message.type.isBubble || !previous.type.isBubble ||
            message.type != previous.type ||
            message.isSelf != previous.isSelf ||
            !sameAuthor(message, previous)
        ) {
            return false
        }
        // No clock on one side → fall back to author alone rather than splitting a run
        // that probably belongs together.
        val earlier = previous.date ?: return true
        val later = message.date ?: return true
        return Duration.between(earlier, later).abs() <= runGap
    }

    /**
     * IRC nicks are case-insensitive and servers send them inconsistently cased, so a
     * run must not break just because `Brad` said something after `brad`. House style is
     * an ASCII lowercase fold, matching `BufferKey`.
     *
     * ⚠ A nick alone is not an identity once relay re-attribution is in play (lurker#277). The
     * `alice` on a Discord bridge and the `alice` in the channel are different people who happen
     * to share a name — anyone on the bridged platform can pick a nick that matches an IRC
     * regular — and folding them into one run renders the bridged line headerless under the real
     * alice's name, dropping the very tag that says otherwise. So the bridge is part of the
     * author: the bot it came through, and which platform it came from, since one bot can carry
     * several.
     *
     * A local speaker has neither, which is exactly what tells them apart from their namesake.
     * The source is compared as sent — it's a platform tag a bot writes the same way every time,
     * not a nick — so a bot that did vary its casing costs a run break and nothing worse.
     *
     * Port note: the fold is LurkerKit's `lowercased()`, as `BufferKey.id`'s is, and differs
     * from it in the same one place — `lowercase()` applies the final-sigma rule and Swift does
     * not, so `ΟΔΟΣ` after `οδοσ` continues a run on iOS and breaks one here (and after `οδος`,
     * the reverse). The comparisons are by code unit where Swift's are by canonical
     * equivalence (PORTING.md, Strings).
     */
    private fun sameAuthor(lhs: Message, rhs: Message): Boolean =
        (lhs.nick ?: "").lowercase() == (rhs.nick ?: "").lowercase() &&
            (lhs.relayBot ?: "").lowercase() == (rhs.relayBot ?: "").lowercase() &&
            lhs.relaySource == rhs.relaySource
}

/**
 * Where a message sits in its run.
 *
 * `isFirst` is what decides whether a message opens an author block, and so whether it draws a
 * header. `isLast` is computed for symmetry and currently read by nothing — the list works out
 * where a block *ends* from the following row, because a block can also be ended by a minute
 * change, which a run position knows nothing about.
 */
data class RunPosition(
    val isFirst: Boolean,
    val isLast: Boolean,
) {
    companion object {
        /** A message that is its own whole run. */
        val solo = RunPosition(isFirst = true, isLast = true)
    }
}
