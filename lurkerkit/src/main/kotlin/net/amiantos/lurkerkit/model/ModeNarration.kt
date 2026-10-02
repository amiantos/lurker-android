// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespaces

/**
 * Turning a MODE row into a sentence — a port of the web's `shared/modeNarration.ts`.
 *
 * The line used to render as `ChanServ set +o alice`: the wire form with a verb bolted on,
 * which asks the reader to know IRC mode syntax in order to find out that somebody got
 * opped. gamja narrates these instead and it reads enormously better; this is that idea,
 * built on the server's `kind` stamp so it can tell `+o alice` (a nick) from `+b alice` (a
 * mask that happens to look like one).
 *
 * Output is a SEGMENT LIST rather than a string so the renderer can draw the affected nick
 * as a real nick — coloured, with the nick menu on it — which the raw form never offered.
 *
 * Only a SINGLE-change message is narrated, matching gamja's own gate. A services burst
 * reads better as `+o-b alice *!*@host` than as a run-on sentence.
 */
object ModeNarration {

    /** A piece of a narrated mode line. */
    sealed interface Segment {
        /** Narration prose. Rendered as-is. */
        data class Text(val text: String) : Segment

        /**
         * A nick the mode acted on. Only ever emitted for a `prefix` change, so it is a real
         * member and never a mask — render it as a nick.
         */
        data class Nick(val nick: String) : Segment

        /**
         * A literal argument: a ban mask, a limit, a raw mode string. Never a nick, so it
         * must never be rendered as one.
         */
        data class Arg(val arg: String) : Segment
    }

    /**
     * What a member-prefix letter grants, for the "gave X to" phrasing. Only a phrasing
     * table — whether a letter IS a prefix mode was settled server-side and is on `kind`.
     */
    private val prefixNames: Map<String, String> = mapOf(
        "q" to "owner", "a" to "admin", "o" to "op", "h" to "half-op", "v" to "voice",
    )

    /**
     * Narrate a MODE row: the segments that follow the actor's nick. Every list starts with
     * a leading space, so a caller renders the actor and then these with no separator.
     */
    fun describe(modes: List<ModeChange>, rawText: String? = null): List<Segment> {
        val list = modes.filter { it.mode.isNotEmpty() }

        if (list.isEmpty()) {
            // Backlog old enough to predate `modes` being persisted at all; its raw text is
            // the only description it has.
            val text = withoutKeyParam((rawText ?: "").trimmingWhitespaces())
            if (text.isEmpty()) return listOf(Segment.Text(" changed the channel modes"))
            return listOf(Segment.Text(" set "), Segment.Arg(text))
        }

        if (list.size > 1) return rawSegments(list)

        val only = list[0]
        // An unstamped row can't be narrated: without `kind` there is no way to know whether
        // `+q alice` grants ownership or quiets a mask, and guessing is exactly the bug the
        // stamp exists to prevent.
        return when (only.kind) {
            ModeChangeKind.Prefix -> {
                val param = only.param
                if (param == null || param.isEmpty()) return rawSegments(list)
                prefixSegments(only.isGrant, only.letter, param)
            }
            ModeChangeKind.List -> {
                val param = only.param
                if (param == null || param.isEmpty()) return rawSegments(list)
                listSegments(only.isGrant, only.letter, param)
            }
            ModeChangeKind.Chan ->
                chanSegments(only.isGrant, only.letter, only.param)
            null ->
                rawSegments(list)
        }
    }

    private fun prefixSegments(grant: Boolean, letter: String, param: String): List<Segment> {
        val name = prefixNames[letter] ?: "+$letter"
        return listOf(Segment.Text(if (grant) " gave $name to " else " took $name from "), Segment.Nick(param))
    }

    private fun listSegments(grant: Boolean, letter: String, param: String): List<Segment> {
        val known: Map<String, Pair<String, String>> = mapOf(
            "b" to Pair(" banned ", " unbanned "),
            "q" to Pair(" quieted ", " unquieted "),
            "e" to Pair(" added a ban exemption for ", " removed the ban exemption for "),
            "I" to Pair(" added an invite exception for ", " removed the invite exception for "),
        )
        val phrase = known[letter]
        if (phrase != null) {
            return listOf(Segment.Text(if (grant) phrase.first else phrase.second), Segment.Arg(param))
        }
        // An unknown list mode still reads correctly said plainly, and says which list it
        // was — better than inventing a verb for a letter we don't know.
        return listOf(
            Segment.Text(if (grant) " added " else " removed "),
            Segment.Arg(param),
            Segment.Text(if (grant) " to the +$letter list" else " from the +$letter list"),
        )
    }

    private fun chanSegments(grant: Boolean, letter: String, param: String?): List<Segment> {
        // ⚠ The channel key is never printed. Every member of the channel saw the MODE that
        // set it, so it is no secret from them — but it lands in scrollback, and Lurker
        // already keeps it out of the channel-mode display for that reason (lurker#476).
        return when (letter) {
            "k" ->
                listOf(Segment.Text(if (grant) " set a channel key" else " removed the channel key"))
            "l" -> {
                if (!grant) return listOf(Segment.Text(" removed the user limit"))
                if (param == null || param.isEmpty()) return listOf(Segment.Text(" set a user limit"))
                listOf(Segment.Text(" set the user limit to "), Segment.Arg(param))
            }
            "t" ->
                listOf(Segment.Text(if (grant) " locked the topic" else " unlocked the topic"))
            "n" ->
                // ⚠ +n BLOCKS messages from outside the channel; it does not allow them. gamja
                // has this pair inverted — narrate the mode, not the reference.
                listOf(Segment.Text(if (grant) " blocked outside messages" else " allowed outside messages"))
            "i" ->
                listOf(Segment.Text(if (grant) " made the channel invite-only" else " removed invite-only"))
            "m" ->
                listOf(Segment.Text(if (grant) " made the channel moderated" else " removed moderation"))
            "s" ->
                listOf(Segment.Text(if (grant) " made the channel secret" else " removed secret"))
            "p" ->
                listOf(Segment.Text(if (grant) " made the channel private" else " removed private"))
            else -> {
                // Unknown letter. With a value it reads as an assignment; without one, as a flag.
                if (grant && param != null && param.isNotEmpty()) {
                    return listOf(Segment.Text(" set +$letter to "), Segment.Arg(param))
                }
                listOf(Segment.Text(if (grant) " set +$letter" else " unset +$letter"))
            }
        }
    }

    /**
     * A compact mode string for a message carrying more than one change, and for anything
     * else this can't narrate.
     *
     * Rebuilt from the parsed list rather than reusing the row's raw `text`, because `text`
     * is the wire form INCLUDING a `+k` key. Reconstructing is what lets the key be dropped
     * here the way it already is everywhere else.
     */
    private fun rawSegments(modes: List<ModeChange>): List<Segment> {
        val parts = modes.map { change ->
            // The one param that is withheld; the letter still shows, so the reader knows a
            // key was set.
            if (change.letter == "k") return@map change.mode
            val param = change.param
            if (param == null || param.isEmpty()) return@map change.mode
            "${change.mode} $param"
        }
        return listOf(Segment.Text(" set "), Segment.Arg(parts.joinToString(" ")))
    }

    /**
     * A mode message's wire text with its parameters dropped when a `k` is among the
     * letters.
     *
     * `text` is `raw_modes` followed by every parameter, so it carries the channel key — and
     * this is the one path that would show it. Which parameter is the key can't be worked
     * out here: mapping parameters to letters needs CHANMODES, and a row with no parsed list
     * has no classification either. So when a key may be present, keep the letters and drop
     * every parameter.
     *
     * Port note: the split and the search are by UTF-16 unit, where LurkerKit's are by
     * `Character`. A space or a `k` carrying a combining mark is some other character there
     * (no separator; no key) and is itself here — so on such a row this drops the parameters
     * where iOS shows them, which is the direction that cannot print a key. No mode string
     * the server sends is written that way.
     */
    private fun withoutKeyParam(text: String): String {
        val modeToken = text.split(" ").firstOrNull { it.isNotEmpty() } ?: return text
        return if (modeToken.contains("k")) modeToken else text
    }
}
