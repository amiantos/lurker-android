// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.decodingUtf16
import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines
import kotlin.math.max
import kotlin.math.min

/**
 * Nick completion: the pure logic behind the pill strip the composer floats when the
 * user types `@`, or the first letters of a nick (#57). A faithful port of the web
 * client's `nickCompletion.ts`, so the two clients can't disagree about who leads the list:
 *
 *  - recent speakers first, most recent first — the people you're most likely answering;
 *  - then the rest of the member list alphabetically, so someone who hasn't spoken is
 *    still reachable by typing;
 *  - you are never a candidate (self-mention is noise in your own suggestions);
 *  - in a channel, a speaker who has since left is dropped — completing them would
 *    address nobody;
 *  - an ignored nick is dropped for the same reason it's dropped from the nicklist —
 *    offering to address someone whose replies you won't see is offering a dead end.
 *
 * The token scanner lives here too (not in the composer) so the whole feature is
 * unit-testable: what counts as an active mention, and what a completed one inserts.
 */
object NickCompletion {

    // MARK: - Candidates

    /**
     * Who a nick query offers — after an `@`, or a bare word — best first, capped at
     * `limit`. `speakers` supplies recency — the store's `SpeakerMap`, the web's
     * `buf.speakers`, never a scan of the loaded messages: a bare word asks on most keystrokes,
     * and the map is capped where the history is not. `members` supplies the fallback pool and
     * the still-here check.
     *
     * `ignores`/`networkId` strip ignored candidates. Taken as the shared type rather than an
     * injected predicate: `IgnoreSet` lives in this module, is immutable, and already carries
     * the cheap "no rules on this network" gate — so a lambda would only move that gate to
     * the caller and make every call site restate it. Defaulted to `IgnoreSet.empty`, which
     * answers "nobody is ignored" for the callers that don't care.
     *
     * A member's userhost is reconstructed from the member row when the server sent both
     * halves; a speaker carries only a nick, so a hostmask-only rule can't suppress a speaker
     * who has left (matching the web, which has the same information at the same point).
     *
     * Port note: nicks and the query fold with `lowercase()` and the prefix test is
     * `startsWith`, where LurkerKit folds with `lowercased()` and tests with `hasPrefix`. On
     * ASCII the two are the same. Beyond it they differ at three edges, none of which a real
     * nick reaches:
     *
     * - `lowercase()` applies the final-sigma rule (see `BufferKey.id`), so a nick ending in
     *   `Σ` folds to `…ς` here and `…σ` on iOS: the query `οδοσ` finds `ΟΔΟΣ` there and not
     *   here, and `οδος` the reverse.
     * - `startsWith` compares UTF-16 units, `hasPrefix` grapheme clusters under canonical
     *   equivalence. A query that ends where the nick continues with a combining mark (`e`
     *   against `e` + U+0301 …) matches here and not on iOS; a query written precomposed
     *   against a nick written decomposed (or the reverse) matches on iOS and not here.
     * - The "already decided" set and the member index key on the folded nick by code unit,
     *   where Swift's are by canonical equivalence: two members whose nicks differ only in
     *   normalisation are one person there and two here.
     *
     * And the alphabetical pass compares the folded nicks by UTF-16 unit with a stable sort —
     * the same difference, at the same edges, as `MemberPrefix.sorted`'s.
     */
    fun candidates(
        speakers: SpeakerMap,
        members: List<Member>,
        selfNick: String?,
        query: String,
        isChannel: Boolean,
        limit: Int = 4,
        ignores: IgnoreSet = IgnoreSet.empty,
        networkId: Int? = null,
        channel: String = "",
    ): List<String> {
        val prefix = query.lowercase()
        val seen = mutableSetOf<String>()
        if (selfNick != null) seen.add(selfNick.lowercase())
        // One index over `members`, answering both "are they still here" and "what's their
        // hostmask" — the membership check is just a lookup that found something.
        val memberByNick = mutableMapOf<String, Member>()
        for (member in members) memberByNick[member.nick.lowercase()] = member
        val filtering = !ignores.isEmpty(networkId)
        fun isIgnored(nick: String, userhost: String?): Boolean {
            if (!filtering) return false
            return ignores.isIgnored(
                networkId = networkId, nick = nick, userhost = userhost, channel = channel,
            )
        }
        val out = mutableListOf<String>()

        // Speakers, newest first. Only speech counts, and never our own — the map records
        // message/action from others alone, so a notice bot or a join flood never crowds it.
        for (speaker in speakers.recent) {
            if (out.size >= limit) return out
            val nick = speaker.nick
            val lc = nick.lowercase()
            if (seen.contains(lc) || !lc.startsWith(prefix)) continue
            val member = memberByNick[lc]
            if (isChannel && member == null) continue
            // Marked seen either way: an ignored nick is *decided*, and leaving it unseen would
            // let the member pass below offer the same person the speaker pass just refused.
            seen.add(lc)
            if (isIgnored(nick, member?.userhost)) continue
            out.add(nick)
        }

        // Then everyone else who's here, in case-folded alphabetical order — the same
        // nick tiebreaker MemberPrefix's sort uses (rank doesn't apply here: completion
        // is about who you're addressing, not who has ops). Filtered before the sort: a
        // bare word asks on most keystrokes, and a big channel's whole member list
        // shouldn't be sorted for the handful that match.
        val matching = members.filter { it.nick.lowercase().startsWith(prefix) }
        for (member in matching.sortedWith { lhs, rhs -> lhs.nick.lowercase().compareTo(rhs.nick.lowercase()) }) {
            if (out.size >= limit) return out
            val lc = member.nick.lowercase()
            if (seen.contains(lc)) continue
            seen.add(lc)
            if (isIgnored(member.nick, member.userhost)) continue
            out.add(member.nick)
        }
        return out
    }

    // MARK: - Token

    /**
     * An in-progress nick under the caret — an `@…`, or a bare word long enough to ask.
     * Offsets are UTF-16 (a Kotlin `String`'s own indices, so the composer can hand its
     * selection straight in).
     */
    data class MentionToken(
        /**
         * Offset of the token's first character: the `@`, or a bare word's first letter.
         * Completion replaces from here, so the `@` goes and the nick stands alone.
         */
        val start: Int,
        /**
         * One past the token's last character — the end of the whitespace-delimited
         * word, which runs *past* the caret when the caret sits mid-word. Completion
         * replaces `start..<end`: swallowing the tail is what keeps `@al|ice` from
         * completing to "aliceice".
         */
        val end: Int,
        /**
         * What's been typed of the nick, up to the caret — after the `@`, or from a bare
         * word's start. The filter query. Deliberately not the whole word: the list should
         * answer what's been typed so far.
         */
        val query: String,
    )

    /**
     * How much of a bare word must be typed before it asks for nicks — the web's mobile
     * strip threshold, so a one-letter word ("I", "a") never floats the pills. Counted in
     * code points (LurkerKit: Unicode scalars), so one emoji is one, not its two UTF-16 units.
     */
    const val BARE_WORD_MINIMUM = 2

    /**
     * The nick being typed at `caret`, or null. A token is the whitespace-delimited run the
     * caret sits in, and it asks in one of two shapes:
     *
     *  - `@…`, explicit, so it asks from the first keystroke — a lone `@` lists everyone —
     *    on any line, and with the caret anywhere in the word;
     *  - a bare word of at least [BARE_WORD_MINIMUM] characters, the web's mobile suggestion
     *    strip (#57), so a nick can be finished without the `@`. Narrower than the `@`,
     *    because it fires on words the user never meant as nicks: only with the caret at the
     *    word's END (a caret placed inside a word is editing it, and a pick would replace the
     *    rest of it), never in a word opening with `/` or a channel sigil, and never on a
     *    command line but `/me` — a command's arguments are keys, passwords and new nicks,
     *    where a nick pick is only ever a mistake.
     *
     * An `@` anywhere but the word's start disqualifies the word in both shapes: `user@host`
     * is an email-shaped word, not a mention, exactly as the web treats it — and completion
     * replaces the whole word, so it would take an `@host` after the caret with it.
     *
     * Port note: `caret`, `start` and `end` are UTF-16 offsets on both sides, so they carry
     * over unchanged. The query is the units up to the caret, decoded the way LurkerKit
     * decodes them (`String(decoding:as: UTF16.self)`): half a surrogate pair — a caret that
     * has landed inside an emoji — reads as U+FFFD rather than as a lone surrogate.
     */
    fun activeMention(text: String, caret: Int): MentionToken? {
        if (caret < 0 || caret > text.length) return null
        var start = caret
        while (start > 0 && !isWhitespace(text[start - 1])) start -= 1
        var end = caret
        while (end < text.length && !isWhitespace(text[end])) end += 1
        if (start >= caret || (start + 1 until end).any { text[it] == '@' }) return null

        if (text[start] == '@') {
            return MentionToken(start = start, end = end, query = decoding(text, start + 1, caret))
        }

        if (caret != end || isCommandLine(text)) return null
        val word = decoding(text, start, end)
        if (word.codePointCount(0, word.length) < BARE_WORD_MINIMUM) return null
        if (word.startsWith("/") || ChannelName.isChannelTarget(word)) return null
        return MentionToken(start = start, end = end, query = word)
    }

    /**
     * Whether the draft is a command whose arguments a bare word must stay out of: it opens
     * (after any whitespace, which the composer trims before sending) with `/` and a verb
     * other than `me`. `//` escapes a command, so that line is text.
     *
     * Port note: the verb is folded with `lowercase()` where LurkerKit uses `lowercased()`;
     * the one case they differ (a final sigma) can't spell `me`.
     */
    private fun isCommandLine(text: String): Boolean {
        var index = 0
        while (index < text.length && isWhitespace(text[index])) index += 1
        if (index >= text.length || text[index] != '/') return false
        var verbEnd = index + 1
        while (verbEnd < text.length && !isWhitespace(text[verbEnd])) verbEnd += 1
        val verb = decoding(text, index + 1, verbEnd)
        if (verb.startsWith("/")) return false
        return verb.lowercase() != "me"
    }

    /**
     * What a completed nick carries after it: the addressing form when the mention opens
     * the line, and a plain space mid-sentence. Same rule as the web's `isAtLineStart`
     * (`/(^|\n)\s*$/`): any run of whitespace between the line's start and the token still
     * counts as the start of the line.
     *
     * `punctuation` is the user's `input.completion.nick_suffix` — pass
     * `addressPunctuation(settings)`. Not defaulted: the literal `":"` used to be baked in
     * here, and a default would let a call site keep it silently.
     *
     * Port note: `beforeTokenAt` is a UTF-16 offset, as in LurkerKit.
     */
    fun addressingSuffix(beforeTokenAt: Int, text: String, punctuation: String): String {
        var index = min(max(0, beforeTokenAt), text.length) - 1
        while (index >= 0) {
            val unit = text[index]
            if (unit == '\n') return "$punctuation "
            if (!isWhitespace(unit)) return " "
            index -= 1
        }
        return "$punctuation "
    }

    // MARK: - The addressing suffix (lurker-ios#133 / lurker#835)

    /**
     * The punctuation a nick takes when it opens the line, per
     * `input.completion.nick_suffix`. The setting stores the mark *alone* — the space is
     * always the client's to add — so "space only" is the empty string, and the registry
     * default is `":"`.
     *
     * Trailing whitespace is dropped rather than doubled, matching the web's `addressPunct`:
     * the setting's description shows the form as `nick: `, so typing exactly that into the
     * field is the natural mistake, and it lets `/set … " "` land on "space only" too. Note
     * this happens at *apply* time, not write time — a value written by the web keeps its
     * spaces on the server, and both clients trim on the way out.
     */
    fun addressPunctuation(settings: Settings): String =
        addressPunctuation(settings.string("input.completion.nick_suffix", default = ":"))

    /**
     * The applied form of an already-read `input.completion.nick_suffix` — the trim above,
     * on its own. Split out so a control that OFFERS values can match the stored one against
     * them the same way the completion matches it: a value the web wrote as `", "` is the
     * `","` choice, and a picker that couldn't see that would show the row as "custom".
     */
    fun addressPunctuation(stored: String): String {
        var end = stored.length
        while (end > 0 && isWhitespace(stored[end - 1])) end -= 1
        return stored.substring(0, end)
    }

    /**
     * How the addressing punctuation should be READ ALOUD — for a settings control, whose
     * visible label is a sample of the form (`nick:`) and so is nearly all punctuation.
     *
     * On iOS, VoiceOver does not speak trailing punctuation at its default verbosity, so left
     * alone every choice announces as "nick" and the row's value never changes however it is
     * set.
     *
     * Derived from the Unicode names rather than a table of the marks we happen to offer: the
     * value is free-form on the web, so a phone that could only name the four would be mute
     * on exactly the value it can't otherwise explain — the setting a user would most need
     * read back. A letter or digit is left as itself, because those already read aloud and
     * "latin small letter p" is not an improvement on "p".
     *
     * Port note: "a letter or digit" is LurkerKit's `CharacterSet.alphanumerics` — the general
     * categories `L*`, `M*` and `N*` — spelled out here. The names come from
     * `Character.getName`, with the scalars the Unicode Character Database gives no name
     * (controls, private use, lone surrogates, unassigned) left as themselves, as they are on
     * iOS; the JDK would otherwise hand back an alias or a block name for them (`BEL`,
     * `PRIVATE USE AREA E000`). ⚠ Which scalars are assigned, and so named, is the Unicode
     * version of whatever runs this — the host JDK, the device and iOS carry three. A mark
     * newer than the oldest of them is read by name on one and left as itself on another.
     */
    fun spokenPunctuation(punctuation: String): String {
        if (punctuation.isEmpty()) return "Space only"
        val spoken = punctuation.codePoints().toArray().map { scalar ->
            val asItself = String(Character.toChars(scalar))
            if (isAlphanumeric(scalar)) return@map Spoken(asItself, isName = false)
            Spoken(unicodeName(scalar)?.lowercase() ?: asItself, isName = true)
        }.toMutableList()
        // Sentence case, but only when the first piece is a NAME. Capitalising a character
        // kept as itself would change it: `p;` reads as a capital P, which is a different
        // suffix from the one that is set.
        //
        // Port note: the first code point, where the Swift takes the first `Character`. A
        // name is ASCII and a piece kept as itself is one scalar, so they are the same thing.
        val first = spoken.firstOrNull()
        if (first != null && first.isName) {
            val head = first.text.offsetByCodePoints(0, 1)
            spoken[0] = Spoken(first.text.substring(0, head).uppercase() + first.text.substring(head), isName = true)
        }
        return spoken.joinToString(" ") { it.text }
    }

    /** Port note: the Swift builds these as a named tuple, `(text: String, isName: Bool)`. */
    private data class Spoken(val text: String, val isName: Boolean)

    /**
     * Whether `draft` already opens by addressing `nick`, so Reply is idempotent. A port of
     * the web's `isAddressedTo` (`MessageInput.vue`), and it deliberately accepts more than
     * the configured form:
     *
     *  - a draft can carry an *older* setting's punctuation, or one another client wrote —
     *    drafts sync, and the web writes whatever its own setting says — so any run of
     *    punctuation after the nick counts, not just today's mark;
     *  - the configured mark counts verbatim whatever it is, since a multi-character or
     *    nick-shaped mark (`->`) wouldn't survive the punctuation-run test;
     *  - the bare `nick ` form counts *only* when it IS the configured form. Otherwise a
     *    draft that merely opens with a nick that is also a word ("will you come?") would
     *    swallow the Reply. Under an empty setting the two are the same text — that is the
     *    ambiguity of the convention itself, not something to second-guess.
     *
     * The punctuation run may not contain a character that could *continue* a nick, or
     * `bob_: hi` would read as addressing bob — and `bob_` is every ghost's nick.
     */
    fun isAddressed(draft: String, nick: String, punctuation: String): Boolean =
        addressLength(draft, nick = nick, punctuation = punctuation) != null

    /**
     * `draft` with the address `isAddressed` recognizes taken off its front — what cancelling a
     * pending reply undoes (lurker-ios#184), the web's `stripAddress`. One pattern for both, so
     * what counts as an address and what a cancel takes back can't drift apart. Anything else in
     * the draft stays; a draft that doesn't open with the address comes back as it was.
     *
     * Port note: the address is measured in scalars (`addressLength`), so the count is turned
     * into a UTF-16 offset before the cut — a nick with an emoji in it is fewer scalars than
     * units.
     */
    fun removingAddress(draft: String, nick: String, punctuation: String): String {
        val length = addressLength(draft, nick = nick, punctuation = punctuation) ?: return draft
        return draft.substring(draft.offsetByCodePoints(0, length))
    }

    /**
     * A reply's text without the `nick: ` it opens with (lurker-ios#184) — the web's
     * `stripReplyAddress`. Stricter than `isAddressed` in one way and looser in another, both the
     * web's: the nick must be followed by at least one punctuation mark (so a reply to `will`
     * saying "will you come?" keeps its first word, whatever the setting), and every space after
     * it goes. Never strips to nothing. Same scalar walk as `isAddressed`, so what the composer
     * writes and what the timeline hides are one definition.
     */
    fun removingReplyAddress(text: String, nick: String): String {
        if (nick.isEmpty()) return text
        val scalars = text.codePoints().toArray()
        val name = nick.codePoints().toArray()
        if (scalars.size <= name.size) return text
        for ((index, scalar) in name.withIndex()) {
            if (asciiLower(scalars[index]) != asciiLower(scalar)) return text
        }
        var index = name.size
        while (index < scalars.size && isMarkScalar(scalars[index])) index += 1
        if (index <= name.size || index >= scalars.size || !isWhitespace(scalars[index])) return text
        while (index < scalars.size && isWhitespace(scalars[index])) index += 1
        if (index >= scalars.size) return text
        return String(scalars, index, scalars.size - index)
    }

    /**
     * Whether two nicks are the same person's, folded the way IRC folds them — ASCII only, as
     * the rest of the client compares nicks and targets.
     */
    fun sameNick(a: String, b: String): Boolean {
        val left = a.codePoints().toArray()
        val right = b.codePoints().toArray()
        if (left.size != right.size) return false
        return left.indices.all { asciiLower(left[it]) == asciiLower(right[it]) }
    }

    /**
     * How many scalars the address at the head of `draft` spans — nick, mark, and the one
     * whitespace after it — or null when it doesn't open with one.
     *
     * Port note: a scalar is a code point here. This, `removingReplyAddress` and `sameNick`
     * walk `codePoints()` where the Swift walks `unicodeScalars`, so the three agree with it
     * scalar for scalar; nothing in them counts UTF-16 units or grapheme clusters.
     */
    private fun addressLength(draft: String, nick: String, punctuation: String): Int? {
        if (nick.isEmpty()) return null
        val text = draft.codePoints().toArray()
        val name = nick.codePoints().toArray()
        if (text.size <= name.size) return null
        // ASCII folding, the same rule the rest of the client uses for IRC targets: a nick's
        // case-insensitivity is the protocol's, not the locale's.
        for ((index, scalar) in name.withIndex()) {
            if (asciiLower(text[index]) != asciiLower(scalar)) return null
        }
        // Port note: `rest` is the Swift's slice of `text` from `name.count` on, which keeps
        // `text`'s indices; here it is the offset `restStart` into the same array.
        val restStart = name.size
        val restCount = text.size - restStart

        // The configured mark, verbatim, then whitespace.
        val mark = punctuation.codePoints().toArray()
        if (mark.isNotEmpty() && restCount > mark.size &&
            mark.indices.all { text[restStart + it] == mark[it] } &&
            isWhitespace(text[restStart + mark.size])
        ) {
            return name.size + mark.size + 1
        }

        // Else a run of punctuation, then whitespace. Greedy with no backtracking is exact
        // here: the run excludes whitespace, so stopping short would only leave a non-space.
        var index = restStart
        while (index < text.size && isMarkScalar(text[index])) index += 1
        val ranAtLeastOne = index > restStart
        // A bare `nick ` is an address only under the empty setting (see the doc comment).
        if (!ranAtLeastOne && mark.isNotEmpty()) return null
        if (index >= text.size || !isWhitespace(text[index])) return null
        return index + 1
    }

    /**
     * A scalar that cannot continue a nick, which is what "punctuation after the nick" has to
     * mean above: not a letter or digit, not whitespace, and not one of the RFC 2812 nick
     * specials. Mirrors the web's `NOT_NICK_CHAR`, whose `\p{L}\p{N}` is Unicode rather than
     * ASCII `\w` — or `bobł` would parse as bob plus a mark, and `bobł` is somebody else.
     *
     * The general categories are spelled out rather than reached through a ready-made set (on
     * iOS, a `CharacterSet`), because none of them is the same set: `.alphanumerics` is
     * `L* ∪ M* ∪ N*` (a combining mark would read as part of the nick where the web reads it as
     * punctuation) and `.decimalDigits` is `Nd` alone (dropping `Nl`/`No`). Both divergences are
     * unreachable in a real draft, which is exactly why they'd never be found again once
     * written.
     *
     * Port note: the ready-made test on this side, `Character.isLetterOrDigit`, is `L* ∪ Nd` —
     * a third set that is not this one either. And a scalar's category is the Unicode version's
     * of whatever runs this, as with `spokenPunctuation`: a letter newer than the tables at
     * hand is unassigned to them, and so reads as a mark.
     */
    private fun isMarkScalar(scalar: Int): Boolean {
        if (isWhitespace(scalar)) return false
        return when (Character.getType(scalar).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            -> false
            else -> scalar !in nickSpecials
        }
    }

    private val nickSpecials: Set<Int> = "_[]\\`^{|}-".codePoints().toArray().toSet()

    private fun asciiLower(scalar: Int): Int =
        if (scalar in 65..90) scalar + 32 else scalar

    /**
     * Port note: Foundation's `CharacterSet.whitespacesAndNewlines`, through `support/`. Half a
     * surrogate pair is no scalar and so no whitespace, there as here.
     */
    private fun isWhitespace(unit: Char): Boolean = unit.isInWhitespacesAndNewlines()

    /** Port note: every member of that set is in the BMP, so nothing past it is whitespace. */
    private fun isWhitespace(scalar: Int): Boolean =
        scalar <= Char.MAX_VALUE.code && scalar.toChar().isInWhitespacesAndNewlines()

    /**
     * Port-only. `CharacterSet.alphanumerics`: the general categories `L*`, `M*` and `N*`.
     */
    private fun isAlphanumeric(scalar: Int): Boolean =
        when (Character.getType(scalar).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            -> true
            else -> false
        }

    /**
     * Port-only. `Unicode.Scalar.Properties.name`: the scalar's published name, or null for
     * one the Unicode Character Database gives none.
     */
    private fun unicodeName(scalar: Int): String? =
        when (Character.getType(scalar).toByte()) {
            Character.CONTROL, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> null
            else -> Character.getName(scalar)
        }

    /**
     * Port-only. `String(decoding:as: UTF16.self)` over `text[start, end)`: the units as a
     * string, with half a surrogate pair repaired to U+FFFD.
     */
    private fun decoding(text: String, start: Int, end: Int): String = text.decodingUtf16(start, end)
}
