// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.model.IgnoreLevels
import net.amiantos.lurkerkit.model.IgnorePatternKind
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.support.Result
import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespaces
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.unicodeRegex
import java.time.Instant

/**
 * The `/ignore` command line, parsed — a port of `shared/parseIgnore.ts` (lurker #301,
 * lurker#350), which is irssi's grammar:
 *
 *     /ignore [-regexp|-full] [-pattern <text>] [-except] [-time <dur>] [-network]
 *             [<mask>|<#channel>] [LEVELS...]
 *
 * Pure, and with no clock beyond the injected `now`, so the whole grammar is testable without
 * a socket or a wall clock — the same shape the matcher ported in (`IgnoreMatch`). The server
 * re-validates everything this produces (`parseIgnoreInput` → `ignoreRulesService.add`), so a
 * rule that gets past here can still be refused; what this buys is that the common mistakes —
 * a typo'd flag, a duration that isn't one — are answered in the buffer the user typed in
 * rather than by silence.
 *
 * Port note: the line is walked a UTF-16 unit at a time, as the JS reference walks it, where
 * Swift walks grapheme clusters. The two part only when a combining mark directly follows a
 * character the grammar reads — a space, a quote, a paren, a leading `-`, a digit of a
 * duration: Swift sees one fused `Character` that is not that character (and a space carrying
 * a mark is still whitespace there, so the mark is swallowed with it), and this sees the plain
 * character and then the mark. A tab or a newline never fuses, in either.
 */
object IgnoreArgs {

    /** A command line that named a rule, plus the one thing about it that isn't the rule. */
    data class Parsed(
        /** The rule to send. Its `id` is 0 — identity is the server's to assign. */
        val rule: IgnoreRule,
        /**
         * Whether `-network` scoped it to the issuing connection. False — global, every
         * network — is the default, and the opposite of what irssi does (lurker#350). The rule
         * payload itself is scope-agnostic; the caller maps this to a `networkId`.
         */
        val scopeNetwork: Boolean,
    )

    /**
     * Why a command line named no rule. Carries the same wording as the web's parser, since
     * it's printed straight back to the user.
     */
    data class Failure(val message: String)

    // MARK: - Durations

    private val multipliers: Map<String, Double> = mapOf(
        "ms" to 1.0,
        "s" to 1000.0, "sec" to 1000.0, "secs" to 1000.0,
        "m" to 60_000.0, "min" to 60_000.0, "mins" to 60_000.0,
        "h" to 3_600_000.0, "hr" to 3_600_000.0, "hrs" to 3_600_000.0, "hour" to 3_600_000.0, "hours" to 3_600_000.0,
        "d" to 86_400_000.0, "day" to 86_400_000.0, "days" to 86_400_000.0,
        "w" to 604_800_000.0, "week" to 604_800_000.0, "weeks" to 604_800_000.0,
    )

    /**
     * ~100 years. Past this, `now + duration` leaves the range a date can carry to the
     * server as an ISO string, and a value that large is a typo rather than an intent — so it
     * fails rather than being silently capped. A permanent ignore is `-time` omitted.
     */
    private const val maxDurationMillis: Double = 100.0 * 365 * 24 * 60 * 60 * 1000

    /**
     * `"7 days"`, `"30m"`, `"300"` → milliseconds; null if it isn't a duration.
     *
     * Hand-rolled rather than regex'd, because the reference's `\d` is ASCII-only and ICU's
     * is not: `٧days` would parse here and not there. Held in a `Double` for the same reason
     * the reference holds a JS number — a 20-digit count of days has to be *rejected*, and an
     * integer type would have overflowed to null before the range check could say so.
     */
    internal fun duration(raw: String?): Double? {
        if (raw == null) return null
        // Whitespace *and newlines*, not whitespace alone: the reference's `\s` matches a
        // newline and the narrower set doesn't, so a pasted `-time "5\ndays"` would have died
        // here on a duration the web accepts. The tokenizer only lets interior whitespace
        // through inside a quoted token, so this is exactly the case that reaches it.
        val text = raw.trimmingWhitespacesAndNewlines()
        val digits = text.takeWhile { it in '0'..'9' }
        if (digits.isEmpty()) return null
        val count = digits.toDoubleOrNull() ?: return null
        val unit = text.substring(digits.length).trimmingWhitespacesAndNewlines().lowercase()
        val multiplier = multipliers[unit.ifEmpty { "s" }] ?: return null
        val millis = count * multiplier
        // A zero duration is rejected rather than taken literally: `-time 0` would expire the
        // rule at the instant it was created — reported as added, never matching, never listed,
        // and (having no index) removable only by mask until the server's sweep notices.
        if (!(millis > 0 && millis.isFinite() && millis <= maxDurationMillis)) return null
        return millis
    }

    /** Whether a token is a bare unit word (`days`, `mins`) — what `-time 7 days` splits into. */
    internal fun isDurationUnit(token: String): Boolean = multipliers[token.lowercase()] != null

    // MARK: - Tokenizer

    /**
     * Split on whitespace, but keep a balanced `(…)` group or a `"quoted"` string whole, so
     * `-pattern (a|b c)` and `-pattern "two words"` survive as one token each.
     *
     * Whitespace is Swift's (`Character.isWhitespace`, the Unicode White_Space property —
     * `isSwiftWhitespace` here), which is what the rest of `CommandParser` splits on. It is
     * not quite the reference's `/\s/`: ECMAScript also counts U+FEFF, which Unicode stopped
     * calling whitespace in 4.0.1. A pasted `bob<U+FEFF>JOINS` is therefore one token here and
     * two on the web — stored as an unmatchable mask rather than a mask plus a level. Left
     * alone deliberately: following ECMA's list would make this the one place in the app that
     * disagrees with every other command about what a space is, to rescue a rule nobody can
     * type on purpose.
     */
    internal fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val characters = line
        var index = 0
        while (index < characters.length) {
            while (index < characters.length && characters[index].isSwiftWhitespace()) index += 1
            if (index >= characters.length) break
            val first = characters[index]
            if (first == '"' || first == '\'') {
                index += 1
                val buffer = StringBuilder()
                while (index < characters.length && characters[index] != first) {
                    buffer.append(characters[index])
                    index += 1
                }
                if (index < characters.length) index += 1 // closing quote
                tokens.add(buffer.toString())
            } else if (first == '(') {
                // Depth-counted, so a nested group closes at the right paren. An unbalanced
                // one runs to the end of the line — the same shape it has on the web, and the
                // regex it becomes will fail to compile there too.
                var depth = 0
                val buffer = StringBuilder()
                while (index < characters.length) {
                    val character = characters[index]
                    if (character == '(') depth += 1 else if (character == ')') depth -= 1
                    buffer.append(character)
                    index += 1
                    if (depth == 0) break
                }
                tokens.add(buffer.toString())
            } else {
                val buffer = StringBuilder()
                while (index < characters.length && !characters[index].isSwiftWhitespace()) {
                    buffer.append(characters[index])
                    index += 1
                }
                tokens.add(buffer.toString())
            }
        }
        return tokens
    }

    /**
     * The server's `MAX_PATTERN_LENGTH` (`ignoreRulesService.ts`), mirrored so an over-long
     * pattern is refused where it was typed rather than dropped in silence. Duplicated
     * deliberately — the alternative is asking the server and getting no answer.
     */
    internal const val maxPatternLength = 512

    /**
     * The flags this grammar knows, lowercased — what `-pattern` refuses to swallow as its
     * value. Kept as one set so a flag added above can't quietly become a pattern.
     *
     * Checked after the tokenizer has stripped quotes, so it can't tell `-pattern -net` from
     * `-pattern "-net"` and refuses both. That costs a rule whose content pattern is exactly a
     * flag spelling, which is a fair trade for a clean refusal over the silent scope loss.
     */
    private val flags: Set<String> = setOf(
        "-regexp", "-regex", "-full", "-word", "-except", "-network", "-net", "-global",
        "-replies", "-pattern", "-time",
    )

    // MARK: - Parse

    /**
     * Parse the arguments of an `/ignore` line (everything after the verb).
     *
     * `now` is injected rather than read here so `-time` is testable, and so the expiry a
     * command computes is the same instant the rest of the line was parsed at.
     */
    fun parse(argLine: String, now: Instant = Instant.now()): Result<Parsed, Failure> {
        fun fail(message: String): Result<Parsed, Failure> = Result.Failure(Failure(message = message))

        var mask: String? = null
        // Whether an explicit `*` (or an empty token) claimed the mask slot — "anyone", said
        // on purpose, as against never naming a subject at all.
        var sawAnyone = false
        val channels = mutableListOf<String>()
        var patternText: String? = null
        var expiresAt: Instant? = null
        var isExcept = false
        var scopeNetwork = false
        var sawRegexp = false
        var sawFull = false
        val addLevels = mutableListOf<String>()
        val subLevels = mutableListOf<String>()

        val tokens = tokenize(argLine.trimmingWhitespaces())
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            index += 1
            val lower = token.lowercase()

            when (lower) {
                "-regexp", "-regex" -> {
                    sawRegexp = true
                    continue
                }
                "-full", "-word" -> {
                    sawFull = true
                    continue
                }
                "-except" -> {
                    isExcept = true
                    continue
                }
                // Scope flags (lurker#350): global is the default, `-network` opts into the
                // current connection, and `-global` is the explicit opposite — a no-op that
                // exists so the default is discoverable from the command line rather than only
                // from docs.
                "-network", "-net" -> {
                    scopeNetwork = true
                    continue
                }
                "-global" -> {
                    scopeNetwork = false
                    continue
                }
                "-replies" -> return fail("-replies is not supported")
                "-pattern" -> {
                    if (index >= tokens.size) return fail("-pattern needs a value")
                    // A flag is never the pattern. The reference takes the next token whatever it
                    // is, so `/ignore bob -pattern -network` there stores a rule matching the
                    // literal text "-network" AND drops the scope the user asked for — silently
                    // making global the rule they scoped to one connection. A deliberate
                    // divergence: a pattern that merely *starts* with `-` (say `-_-`) still works,
                    // because only the known flags are refused.
                    val value = tokens[index]
                    if (flags.contains(value.lowercase())) {
                        return fail("-pattern needs a value (got the flag $value)")
                    }
                    patternText = value
                    index += 1
                    continue
                }
                "-time" -> {
                    var value: String? = if (index < tokens.size) tokens[index] else null
                    index += 1
                    // `7 days` typed without quotes arrives as two tokens. The reference takes only
                    // the first, so `/ignore -time 7 days` there is a SEVEN-SECOND rule whose mask
                    // is the word "days" — which then lapses and leaves no trace of what happened.
                    // Joining them is a deliberate divergence: it reads the line the way it was
                    // meant, and the pair is unambiguous (a bare count followed by a unit word).
                    val count = value
                    if (count != null && index < tokens.size &&
                        count.all { it in '0'..'9' } &&
                        isDurationUnit(tokens[index])
                    ) {
                        value = "$count ${tokens[index]}"
                        index += 1
                    }
                    val millis = duration(value)
                        ?: return fail("invalid -time value: ${value ?: "(missing)"}")
                    // Port note: `millis` is a whole number of at most ~3.2e12 — a count of
                    // ASCII digits times a whole multiplier, range-checked — so the `Long` is
                    // exact, and so is the `Instant`. LurkerKit adds `millis / 1000` seconds to
                    // a `Date`, a `Double`, and lands on the same instant to the millisecond.
                    expiresAt = now.plusMillis(millis.toLong())
                    continue
                }
                else -> {}
            }

            // A subtractive level — `ALL -PUBLIC`. Only a known level token qualifies;
            // anything else beginning with `-` is a flag nobody implements, and saying so
            // beats silently reading `-regex` (a typo of `-regexp`) as a mask.
            if (token.startsWith('-') && token.length > 1) {
                val level = IgnoreLevels.canonical(token.substring(1))
                    ?: return fail("unknown flag: $token")
                // `-ALL` reads as "everything except everything" and the reference resolves it
                // to the MAXIMUM hide set: the base expands `ALL` to its concrete members
                // first, and the removal loop then looks for a token that is no longer in the
                // set. Refused rather than inverted — `bob PUBLIC -PUBLIC` already fails with
                // "no levels remain", and this is the same request spelled shorter.
                if (level == "ALL") {
                    return fail("-ALL isn't a level to subtract — name what to keep, or drop the rule")
                }
                subLevels.add(level)
                continue
            }

            if (ChannelName.isChannelTarget(token)) {
                // Port note: `lowercase()` writes a word-final `Σ` as `ς`, as the web's
                // `toLowerCase()` does and Swift's `lowercased()` does not — so `#ΣΟΣ` is
                // stored as `#σος` from here and the web, and as `#σοσ` from iOS. The matcher
                // folds the two together; only the stored spelling differs.
                channels.add(token.lowercase())
                continue
            }

            val level = IgnoreLevels.canonical(token)
            if (level != null) {
                addLevels.add(level)
                continue
            }

            if (mask == null && !sawAnyone) {
                // `*` is "anyone", which the matcher spells as no mask at all — and so are an
                // empty quoted token (`/ignore ""`) and a whitespace-only one (`/ignore " "`).
                // All three normalize to null, the way the server's `strOrNull` and
                // `IgnoreMatch.maskMatcher` already read them. Left alone, `" "` reached the
                // server, was nulled there, and became a rule hiding everyone — while the
                // receipt showed a blank subject.
                val trimmed = token.trimmingWhitespacesAndNewlines()
                if (trimmed == "*" || trimmed.isEmpty()) {
                    // Remembered, because "the user asked for everyone" and "the user named
                    // nobody" are different requests that both leave `mask` null — and only the
                    // second one is a mistake. See the guard below.
                    sawAnyone = true
                } else {
                    mask = trimmed
                }
                continue
            }
            return fail("unexpected argument: $token")
        }

        // Resolve the level set: the additive tokens are the base, or `ALL` when none was
        // named. A subtractive token expands `ALL` to its concrete members first and then
        // removes — irssi's `ALL -PUBLIC -ACTIONS`.
        val levelSet = (if (addLevels.isEmpty()) listOf("ALL") else addLevels).toMutableSet()
        if (subLevels.isNotEmpty()) {
            if (levelSet.remove("ALL")) levelSet.addAll(IgnoreLevels.concrete)
            for (level in subLevels) levelSet.remove(level)
        }
        if (levelSet.isEmpty()) return fail("no levels remain")

        // A rule that names no subject — no mask, no channel, no content — hides EVERY message
        // from everyone, on every network if it's global. The server allows it (irssi does
        // too) and it is occasionally what someone means, so the escape hatch is to say so:
        // `/ignore * JOINS` is explicit and passes. What's refused is arriving there by
        // accident, which several ordinary inputs do — `/ignore -network` sent early by a
        // stray Return, or `/ignore Quit`, where a nick that happens to spell a level token is
        // consumed as the level (the reference reads levels before masks, and this client
        // matches it) leaving the rule with no subject at all.
        val pattern = patternText?.trimmingWhitespacesAndNewlines()
        if (mask == null && channels.isEmpty() && pattern.isNullOrEmpty() && !sawAnyone) {
            return fail("that names nobody to ignore — try /ignore <nick>, or /ignore * <levels> to mean everyone")
        }

        // The server's own `add` checks, ported so the answer lands in the buffer the command
        // was typed in. Its rejection arrives as silence — `wsHub`'s `add-ignore` drops a
        // failed validation with a bare `break` and sends nothing back — so anything caught
        // there and not here is confirmed as added and simply never exists.
        //
        // Port note: `length` — UTF-16 units — where LurkerKit counts `Character`s. The limit
        // being mirrored is the server's `pattern.length`, which is units too, so a pattern of
        // emoji or combining marks that iOS lets through at 512 graphemes (and the server then
        // drops in silence, the very thing this check is for) is refused here.
        if (pattern != null && pattern.length > maxPatternLength) {
            return fail("pattern exceeds $maxPatternLength chars")
        }
        // The regex check is the one that can't be exact: the server compiles with V8 and this
        // is ICU. Measured divergences, both directions — ICU accepts inline `(?i)spam` which
        // V8 rejects (so that one still reaches the server and dies quietly); V8 accepts `[]`,
        // `a{,3}` and `free{` under Annex B where ICU refuses, so those few patterns are
        // creatable in a browser and refused here. What it reliably catches is the unbalanced
        // bracket or paren, which is the mistake that actually gets typed.
        //
        // Port note: "this is ICU" on a device. The host tests compile with OpenJDK's engine,
        // a third dialect, which agrees on the unbalanced bracket and paren and not on every
        // pattern beyond them.
        if (sawRegexp && pattern != null && pattern.isNotEmpty() && !compiles(pattern)) {
            return fail("invalid regex: $pattern")
        }

        return Result.Success(
            Parsed(
                rule = IgnoreRule(
                    mask = mask,
                    channels = channels.ifEmpty { null },
                    // Trimmed, and empty means absent: the server's `strOrNull` nulls a
                    // whitespace-only pattern, and a null pattern WIDENS the rule from "hide
                    // what they say about X" to "hide everything they say".
                    pattern = if (pattern.isNullOrEmpty()) null else pattern,
                    patternKind = if (sawRegexp) {
                        IgnorePatternKind.Regex
                    } else if (sawFull) {
                        IgnorePatternKind.Full
                    } else {
                        IgnorePatternKind.Substr
                    },
                    levels = IgnoreLevels.canonicalize(levelSet.toList()),
                    isExcept = isExcept,
                    expiresAt = expiresAt,
                ),
                scopeNetwork = scopeNetwork,
            ),
        )
    }

    /** Whether `pattern` compiles as a regex — the Swift's `try? NSRegularExpression(…) != nil`. */
    private fun compiles(pattern: String): Boolean =
        try {
            unicodeRegex(pattern)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
