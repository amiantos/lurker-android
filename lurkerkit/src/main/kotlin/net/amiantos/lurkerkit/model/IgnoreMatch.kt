// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.URLMatcher
import net.amiantos.lurkerkit.support.unicodeRegex
import java.time.Instant
import java.util.regex.PatternSyntaxException

/**
 * The irssi-style ignore matcher (lurker #301), ported from `shared/ignoreMatch.ts`.
 *
 * The server evaluates the same rules at insert time — it stamps `from_ignored` and drops a
 * highlight a `NOHIGHLIGHT` rule covers — but that stamp is frozen at the moment the line
 * arrived. Filtering *again* at render time is what makes a rule retroactive in both
 * directions: adding one hides backlog the server had already sent, and removing one brings
 * those lines back without a refetch. That's why this exists client-side at all, and why it
 * has to agree with the server's copy exactly rather than approximate it.
 *
 * ## Known divergences, all confined to non-ASCII rules
 *
 * The reference runs on V8; this runs on ICU (on a device — Android's `java.util.regex` is ICU
 * underneath, as `NSRegularExpression` is on iOS). The two do not agree everywhere, and no
 * amount of care in this file closes the gap — it would take shipping a case-folding table and
 * a regex engine. What IS held is internal consistency, which is testable: the literal and glob
 * paths below answer identically for every input, so an optimization can never change a
 * verdict. The residue, for anyone chasing a line hidden on one client and not the other:
 *
 *  - **Case folding.** V8's `/…/i` uses *simple* folding, ICU's uses full. `/^weiss$/i`
 *    does not match `WEIß` on the server but does here; `σ`/`ς` fold together in both. Only
 *    reachable via a non-ASCII mask or channel scope, which RFC-1459 nicks can't be.
 *  - **Regex shorthands.** A user's `-pattern regex` is compiled by V8 without the `u` flag,
 *    where `\d`/`\w`/`\s`/`\b` are ASCII-only; ICU makes them Unicode-aware. So
 *    `-pattern regex \w+bank` matches `Ünterbank` here and not on the server.
 *
 * These are documented rather than fixed because each is a whole engine's worth of work to
 * close, and every one needs a non-ASCII rule to reach. If a report ever lands that fits one,
 * this is the list to check it against.
 *
 * Port note: this port adds a third engine, and differences of its own from the Swift — every
 * one of them also confined to non-ASCII rules or lines:
 *
 *  - **The host JVM.** The tests run on OpenJDK's regex engine, which folds case one
 *    character at a time: `/^weiss$/i` does not match `WEIß` there and does on a device, and
 *    — the other way — `i` matches `İ` and `I` matches `ı` there, which neither ICU nor V8
 *    allows. It also reads a user's `-pattern regex` in its own dialect: measured against
 *    ICU, it refuses `\y`, `\i` and `(?#comment)`, accepts `\1` with no group, a bare `}`,
 *    `(?<=a+)b` and `(?U)`, and has no `[[:alpha:]]`. And it does not count a vertical tab or
 *    a form feed as a line terminator, which ICU does — so `.`, and so a glob's `*` and `?`,
 *    match one there and not on a device. The suite therefore pins the non-ASCII cases only
 *    as "the literal and glob paths agree"; what they agree *on* wants an instrumented test.
 *  - **Canonical equivalence on the literal path.** LurkerKit compares a wildcard-free mask or
 *    scope with Foundation's `caseInsensitiveCompare`, which also calls a precomposed `é` and
 *    `e` + U+0301 the same; no regex engine does, so iOS's own two paths split there. Here the
 *    literal path is the glob path's answer by construction (see [FoldedLiteral]), which is
 *    also the server's: differently-normalised spellings do not match.
 *  - **Lowercasing a substring pattern.** `lowercase()` writes a word-final `Σ` as `ς`, as JS
 *    `toLowerCase()` does and Swift's `lowercased()` does not. So `-pattern οδοσ` does not hide
 *    `ΟΔΟΣ` here or on the server, and does on iOS.
 *  - **Stepping by UTF-16 unit.** A mask, and a sender's `nick!user@host`, are walked a unit
 *    at a time, as the JS reference walks them, where Swift walks grapheme clusters. They part
 *    only when a combining mark, a joiner or a variation selector directly follows a `*`, `?`,
 *    `!` or `@`: Swift sees one fused `Character` that is none of those (so `*` + U+0301 is
 *    not a wildcard on iOS, and `!` + U+20E3 does not end a nick), and this sees the plain
 *    character and then the mark.
 */
object IgnoreMatch {

    // MARK: - Glob

    /**
     * Translate a glob into an anchored regex, mirroring the shared matcher's translation
     * exactly: `*` and `?` are the only wildcards, every other regex metacharacter is escaped.
     */
    internal fun globToRegex(pattern: String, caseInsensitive: Boolean): Regex? {
        val source = StringBuilder("^")
        for (character in pattern) {
            when (character) {
                '*' -> source.append(".*")
                '?' -> source.append(".")
                '.', '+', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\' ->
                    source.append('\\').append(character)
                else -> source.append(character)
            }
        }
        source.append("$")
        return try {
            unicodeRegex(source.toString(), ignoreCase = caseInsensitive)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    /** What [splitMask] returns. Port note: a named tuple in LurkerKit, `(nick:user:host:)`. */
    internal data class MaskParts(val nick: String, val user: String, val host: String)

    /**
     * Split a mask into its three globbable parts, defaulting each missing one to `*`.
     *
     * The three shapes a mask can take, and what each leaves unconstrained:
     * `bob` (nick only), `*@host` / `user@host` (no `!`, so the part before `@` is the
     * *user*), and the full `nick!user@host`.
     */
    internal fun splitMask(mask: String): MaskParts {
        var nick = "*"
        var user = "*"
        var host = "*"
        var pre = mask
        val at = mask.indexOf('@')
        if (at >= 0) {
            pre = mask.substring(0, at)
            val rest = mask.substring(at + 1)
            host = rest.ifEmpty { "*" }
        }
        val bang = pre.indexOf('!')
        if (bang >= 0) {
            val head = pre.substring(0, bang)
            val tail = pre.substring(bang + 1)
            nick = head.ifEmpty { "*" }
            user = tail.ifEmpty { "*" }
        } else if (at >= 0) {
            user = pre.ifEmpty { "*" }
        } else {
            nick = pre.ifEmpty { "*" }
        }
        return MaskParts(nick, user, host)
    }

    /**
     * A wildcard-free mask or channel scope, compared case-insensitively. Port-only: the
     * stand-in for Foundation's `caseInsensitiveCompare(_:) == .orderedSame`, which the JVM
     * has no equal of.
     *
     * ⚠⚠ The obvious translations — `equals(ignoreCase = true)`, or `lowercase() ==` — are
     * exactly the trap `MaskMatcher.LiteralNick` warns about, moved to this platform. Both fold
     * one character at a time, so neither can see `ß` as `ss`; the regex path on a device (ICU)
     * can. `/ignore weiss` would hide `WEIß` when written `weiss*` and not when written
     * `weiss`, and the host tests — whose regex engine folds simply too — would stay green
     * while it did.
     *
     * So the rule is: two ASCII strings are compared as ASCII, where every engine and every
     * fold agrees and the cost is the string compare the fast path exists for; anything else
     * is handed to the very regex the glob path would have built for the same text. The two
     * paths then agree by construction, on whichever engine this is running, rather than by
     * two tables happening to match.
     *
     * The regex is asked for a whole-input match, not found-anywhere: `$` also matches
     * before a final line terminator, and a compare does not. (That one edge is LurkerKit's
     * too: `bob` does not match a nick of `bob` plus a newline, and `bob*` does.)
     */
    internal class FoldedLiteral(val literal: String) {
        private val literalIsAscii = isAscii(literal)
        private val regex: Regex? by lazy { globToRegex(literal, caseInsensitive = true) }

        fun matches(text: String): Boolean {
            if (literalIsAscii && isAscii(text)) return text.equals(literal, ignoreCase = true)
            // A literal that somehow would not compile matches nothing — closed, as
            // `MaskMatcher.Nobody` is.
            val regex = regex ?: return false
            return regex.matches(text)
        }

        private companion object {
            fun isAscii(text: String): Boolean = text.all { it.code < 0x80 }
        }
    }

    /**
     * How a compiled rule decides whether a sender is the one it's about.
     *
     * The nick half is always case-insensitive (IRC nicks are); the user and host halves are
     * not, matching the shared matcher — a hostmask is closer to a literal address.
     */
    internal sealed interface MaskMatcher {
        /** No mask, or `*`: anyone. */
        data object Anyone : MaskMatcher

        /**
         * A mask that wouldn't compile — matches nobody, so the rule is inert.
         *
         * Failing CLOSED is the whole point. The obvious fallback, `Anyone`, silently
         * promotes "hide bob" into "hide everyone": an `ALL` rule in that state blanks the
         * message list, the nicklist and completion at once. Today's escaping makes the
         * failure hard to reach, which is exactly why the wrong default would go unnoticed
         * until it wasn't. Same choice `TextMatcher.Never` makes for the same class of
         * failure.
         */
        data object Nobody : MaskMatcher

        /**
         * A bare token with no wildcards in it — `/ignore bob`, far and away the commonest
         * rule there is. Compared as a case-insensitive literal so the per-row cost is a
         * string compare rather than a regex execution; the glob cases below are what
         * the wildcards are for.
         *
         * **Not** `lowercase() ==`, which is the obvious form and answers differently from
         * the regex path it stands in for: `lowercase()` maps `ß` to itself while ICU's
         * case-insensitive matching folds it to `ss`, so on iOS `/ignore weiss` stopped
         * matching `WEIß` the moment this optimization landed. An optimization that changes
         * verdicts isn't one — see `testTheLiteralAndGlobPathsAgreeOnNonAsciiToo`.
         *
         * Port note: LurkerKit compares with `caseInsensitiveCompare`; see [FoldedLiteral]
         * for what stands in for it here.
         */
        class LiteralNick(val literal: String) : MaskMatcher {
            internal val folded = FoldedLiteral(literal)
        }

        /** A bare token — a nick glob, and nothing said about the hostmask. */
        class Nick(val regex: Regex) : MaskMatcher

        /** A `nick!user@host` form, each part globbed independently. */
        class Identity(
            val nick: Regex,
            val user: Regex,
            val host: Regex,
            /**
             * Whether the user and host halves were both `*`. That's what decides the
             * hostmask-less case: a rule that constrains only the nick still matches a
             * sender whose mask the server never sent, but one that names a user or host
             * cannot be judged without one.
             */
            val hostmaskOptional: Boolean,
        ) : MaskMatcher

        fun matches(nick: String?, userhost: String?): Boolean {
            when (this) {
                Anyone -> return true
                Nobody -> return false
                is LiteralNick -> {
                    if (nick == null) return false
                    return folded.matches(nick)
                }
                is Nick -> {
                    if (nick == null) return false
                    return regex.matchesAnywhere(nick)
                }
                is Identity -> {
                    if (nick == null || !this.nick.matchesAnywhere(nick)) return false
                    if (userhost == null) return hostmaskOptional
                    // The wire form is `nick!user@host`; the `@` is looked for *after* the `!` so
                    // an `@` inside a nick can't split it in the wrong place.
                    val bang = userhost.indexOf('!')
                    if (bang < 0) return hostmaskOptional
                    val afterBang = bang + 1
                    val at = userhost.indexOf('@', startIndex = afterBang)
                    if (at < 0) return hostmaskOptional
                    val user = userhost.substring(afterBang, at)
                    val host = userhost.substring(at + 1)
                    return this.user.matchesAnywhere(user) && this.host.matchesAnywhere(host)
                }
            }
        }
    }

    /** A rule's buffer scope: every buffer, or a set of case-insensitive target globs. */
    internal sealed interface ChannelMatcher {
        data object Any : ChannelMatcher

        /**
         * Wildcard-free targets — the shape every mute rule and most `-channels` scopes take.
         * Compared case-insensitively, so scoping a rule to a channel costs a string compare
         * per buffer rather than a regex execution.
         */
        class Literals(val literals: List<String>) : ChannelMatcher {
            internal val folded = literals.map(::FoldedLiteral)
        }

        class Globs(val regexes: List<Regex>) : ChannelMatcher

        /**
         * Every glob in the scope failed to compile — matches nothing, so the rule is inert.
         * Fails closed for the same reason `MaskMatcher.Nobody` does: the `Any` fallback
         * would widen a rule from one channel to every buffer on the network.
         */
        data object None : ChannelMatcher

        fun matches(target: String): Boolean {
            when (this) {
                Any -> return true
                None -> return false
                is Literals -> {
                    if (target.isEmpty()) return false
                    return folded.any { it.matches(target) }
                }
                is Globs -> {
                    if (target.isEmpty()) return false
                    return regexes.any { it.matchesAnywhere(target) }
                }
            }
        }
    }

    /** A rule's content pattern. */
    internal sealed interface TextMatcher {
        /** No pattern: any body. */
        data object Any : TextMatcher

        /** Case-insensitive substring, holding the already-lowercased needle. */
        class Substring(val needle: String) : TextMatcher

        class Regex(val regex: kotlin.text.Regex) : TextMatcher

        /**
         * A pattern that wouldn't compile. It matches nothing, so the rule is inert — which
         * drops one broken rule rather than throwing away the whole set.
         */
        data object Never : TextMatcher

        /**
         * `lowered` is `text` case-folded, computed once per event by the caller rather than
         * once per rule here — it's a full Unicode-folding allocation, and a message matched
         * against several substring rules would otherwise pay for it several times over.
         */
        fun matches(text: String, lowered: String): Boolean =
            when (this) {
                Any -> true
                // Code-unit comparison, NOT canonical equivalence. Swift's `String.contains`
                // treats a decomposed `cafe\u0301` and a precomposed `café` as equal (which is
                // why LurkerKit has to ask for a `.literal` search); the reference is JS
                // `includes`, which does not, and neither does Kotlin's `contains`. Without
                // this a `-pattern café` rule hides a decomposed line the server counted.
                is Substring -> lowered.contains(needle)
                is Regex -> regex.matchesAnywhere(text)
                Never -> false
            }
    }

    /**
     * One rule with its globs and patterns compiled. Built once per list change, then read on
     * every rendered row.
     *
     * Safe to share across threads: a compiled `Regex` is immutable, and every property here
     * is a `val` fixed at compile time.
     */
    internal class CompiledRule(
        val isExcept: Boolean,
        /**
         * The mask's length, which is the whole of "longest mask wins": a more specific mask
         * is a longer string, so an `-except` only beats a hide it's more specific than.
         *
         * Counted in UTF-16 units, not graphemes, because the reference is JS `String.length`
         * and this number is *compared against the server's answer for the same rules*. An
         * emoji or other non-BMP character in a mask counts 2 there and would count 1 as a
         * grapheme, which is enough to flip which of two rules wins and hide a line
         * the server showed.
         */
        val maskLength: Int,
        val expiresAt: Instant?,
        /** Whether the rule carries any hide levels at all (a modifier-only rule doesn't). */
        val hides: Boolean,
        val nohilight: Boolean,
        val nounread: Boolean,
        val nonotify: Boolean,
        val hasPattern: Boolean,
        /**
         * Whether the mask matches everyone (absent or `*`) — what `channelMutesUnread`
         * requires, since a per-sender mute can't speak for a whole buffer's badge.
         */
        val anyNick: Boolean,
        val hasAll: Boolean,
        /**
         * The non-modifier levels, resolved to the types they cover. Unknown tokens are
         * dropped at compile time rather than checked for on every row.
         */
        val hideDefs: List<IgnoreLevels.Def>,
        val mask: MaskMatcher,
        val channels: ChannelMatcher,
        val text: TextMatcher,
    ) {
        /**
         * Whether this rule hides an event of `type`. `ALL` covers the whole ignorable set;
         * otherwise each level names its types, and `PUBLIC`/`MSGS` additionally split on
         * channel-vs-DM.
         */
        fun hidesLevel(type: EventType, isDm: Boolean): Boolean {
            if (hasAll) return IgnoreLevels.all.contains(type)
            for (def in hideDefs) {
                if (!def.types.contains(type)) continue
                if (def.dm == null || def.dm == isDm) return true
            }
            return false
        }

        fun isLive(now: Instant): Boolean {
            val expiresAt = expiresAt ?: return true
            return expiresAt > now
        }
    }

    /** A compiled rule list plus the one fact the evaluator needs about it as a whole. */
    internal class CompiledSet(
        val rules: List<CompiledRule>,
        /**
         * Whether any rule carries a content pattern. When none does, the URL/formatting
         * strip is skipped entirely — which is most accounts, on every rendered row.
         */
        val hasPattern: Boolean,
    ) {
        val isEmpty: Boolean get() = rules.isEmpty()

        companion object {
            /**
             * Concatenate two sets — how a network's effective rules are formed from the global
             * bucket and its own. The short-circuit is for the common account that has only
             * network rules or only global ones, and keeps the union free of a copy there.
             */
            fun merged(first: CompiledSet, second: CompiledSet): CompiledSet {
                if (first.isEmpty) return second
                return CompiledSet(
                    rules = first.rules + second.rules,
                    hasPattern = first.hasPattern || second.hasPattern,
                )
            }
        }
    }

    // MARK: - Compile

    internal fun compile(rules: List<IgnoreRule>): CompiledSet {
        val compiled = mutableListOf<CompiledRule>()
        var hasPattern = false
        for (rule in rules) {
            val hideLevels = rule.levels.filter { !IgnoreLevels.modifiers.contains(it) }
            val pattern = rule.pattern?.takeIf { it.isNotEmpty() }
            if (pattern != null) hasPattern = true
            compiled.add(
                CompiledRule(
                    isExcept = rule.isExcept,
                    maskLength = rule.mask?.length ?: 0,
                    expiresAt = rule.expiresAt,
                    hides = hideLevels.isNotEmpty(),
                    nohilight = rule.levels.contains("NOHIGHLIGHT"),
                    nounread = rule.levels.contains("NOUNREAD"),
                    nonotify = rule.levels.contains("NONOTIFY"),
                    hasPattern = pattern != null,
                    anyNick = rule.mask == null || rule.mask == "*",
                    hasAll = hideLevels.contains("ALL"),
                    hideDefs = hideLevels.mapNotNull { IgnoreLevels.defs[it] },
                    mask = maskMatcher(rule.mask),
                    channels = channelMatcher(rule.channels),
                    text = textMatcher(pattern, kind = rule.patternKind),
                ),
            )
        }
        return CompiledSet(rules = compiled, hasPattern = hasPattern)
    }

    /**
     * Whether a glob has any wildcard in it at all. A pattern without one is a literal, and
     * compiling it to `^literal$` only to run the regex engine over it per row is the single
     * most avoidable cost on this path — `/ignore bob` is the rule people actually write.
     */
    private fun hasWildcard(pattern: String): Boolean = pattern.contains('*') || pattern.contains('?')

    internal fun maskMatcher(mask: String?): MaskMatcher {
        if (mask == null || mask.isEmpty() || mask == "*") return MaskMatcher.Anyone
        if (!(mask.contains('!') || mask.contains('@'))) {
            if (!hasWildcard(mask)) return MaskMatcher.LiteralNick(mask)
            val regex = globToRegex(mask, caseInsensitive = true) ?: return MaskMatcher.Nobody
            return MaskMatcher.Nick(regex)
        }
        val parts = splitMask(mask)
        val nick = globToRegex(parts.nick, caseInsensitive = true)
        val user = globToRegex(parts.user, caseInsensitive = false)
        val host = globToRegex(parts.host, caseInsensitive = false)
        if (nick == null || user == null || host == null) return MaskMatcher.Nobody
        return MaskMatcher.Identity(
            nick = nick,
            user = user,
            host = host,
            hostmaskOptional = parts.user == "*" && parts.host == "*",
        )
    }

    internal fun channelMatcher(channels: List<String>?): ChannelMatcher {
        if (channels == null || channels.isEmpty()) return ChannelMatcher.Any
        if (!channels.any(::hasWildcard)) return ChannelMatcher.Literals(channels)
        val regexes = channels.mapNotNull { globToRegex(it, caseInsensitive = true) }
        return if (regexes.isEmpty()) ChannelMatcher.None else ChannelMatcher.Globs(regexes)
    }

    /**
     * A word character for whole-word matching: any Unicode letter or number, or underscore.
     *
     * Deliberately not `\w`, which is ASCII-only in the web's engine and would treat `ł` as a
     * boundary — making the keyword `em` match inside `zrozumiałem`. ICU's `\w` is
     * Unicode-aware by default, so the two engines would *disagree* if each used its own
     * shorthand; spelling the class out is what keeps them identical.
     */
    private const val wordCharacter = "[\\p{L}\\p{N}_]"
    private const val nonWordCharacter = "[^\\p{L}\\p{N}_]"

    /**
     * `NSRegularExpression.escapedPattern(for:)`: `text` as a pattern that matches it literally.
     *
     * Port-only, and enumerated from Foundation on a Mac rather than left to `Regex.escape`:
     * a backslash goes in front of each of `$ ( ) * + . / ? [ \ ^ { | }` and nothing else is
     * touched. (Note what is *not* in it: `]`, which is a literal outside a class in both
     * engines.) `Regex.escape` would match the same text — it wraps the lot in `\Q…\E` — but
     * the source it builds is a different string from the one iOS compiles, and this is the
     * matcher that has to answer as iOS does.
     */
    private fun escapedPattern(text: String): String {
        val out = StringBuilder()
        for (character in text) {
            when (character) {
                '$', '(', ')', '*', '+', '.', '/', '?', '[', '\\', '^', '{', '|', '}' ->
                    out.append('\\').append(character)
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    internal fun textMatcher(pattern: String?, kind: IgnorePatternKind): TextMatcher {
        if (pattern == null || pattern.isEmpty()) return TextMatcher.Any
        return when (kind) {
            IgnorePatternKind.Substr -> TextMatcher.Substring(pattern.lowercase())
            IgnorePatternKind.Regex -> {
                // A user-authored regex compiles as written. One that doesn't compile makes its
                // rule inert rather than taking the rest of the set down with it.
                val regex = compileOrNull(pattern) ?: return TextMatcher.Never
                TextMatcher.Regex(regex)
            }
            IgnorePatternKind.Full -> {
                // The leading boundary is a *consuming* negated class rather than a lookbehind,
                // matching the web (whose engine has to run on Safari < 16.4). Consuming it is
                // harmless here — nothing reads the match's position, only whether there was one.
                val body = escapedPattern(pattern)
                val source = "(?:^|$nonWordCharacter)(?:$body)(?!$wordCharacter)"
                val regex = compileOrNull(source) ?: return TextMatcher.Never
                TextMatcher.Regex(regex)
            }
        }
    }

    /**
     * A case-insensitive regex, or null where the Swift's `try? NSRegularExpression(…)` is nil.
     *
     * Port note: catches `IllegalArgumentException`, the parent of `PatternSyntaxException` —
     * what reaches here is a pattern a person typed, and a refusal of any shape has to make
     * the rule inert rather than escape onto the render path.
     */
    private fun compileOrNull(pattern: String): Regex? =
        try {
            unicodeRegex(pattern, ignoreCase = true)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Normalize a body for pattern matching: drop IRC formatting codes, then blank out URLs.
     *
     * The formatting strip is not cosmetic. A colored word arrives as `\u000304QUACK`, which
     * leaves the digit `4` glued to the front of QUACK — enough to break a whole-word match
     * and let a rule silently miss the line it was written for.
     */
    internal fun cleanForMatch(text: String): String = URLMatcher.blanked(IRCFormatting.strip(text))

    // MARK: - Evaluate

    /**
     * Evaluate `input` against a compiled set.
     *
     * Every verdict is decided the same way: the longest matching mask wins, and an
     * `-except` at that length or longer vetoes it. The three are tracked independently
     * because a rule can carry hide levels and modifiers at once, and an `-except` written
     * against one shouldn't quietly lift the others.
     *
     * `now` is a parameter rather than a clock read inside so expiry is testable without
     * waiting for it.
     */
    internal fun evaluate(
        compiled: CompiledSet,
        input: IgnoreInput,
        now: Instant = Instant.now(),
    ): IgnoreVerdict {
        if (compiled.isEmpty) return IgnoreVerdict.visible
        // Only pay for the strip when some rule actually reads the text — which, for most
        // accounts, is never: a content pattern is the rarest thing a rule carries.
        val text = if (compiled.hasPattern && input.text.isNotEmpty()) cleanForMatch(input.text) else ""
        val lowered = if (text.isEmpty()) "" else text.lowercase()

        var bestHide = -1
        var bestHideExcept = -1
        var bestNohilight = -1
        var bestNohilightExcept = -1
        var bestNonotify = -1
        var bestNonotifyExcept = -1

        for (rule in compiled.rules) {
            if (!rule.isLive(now)) continue
            // Computed once and shared by all three verdicts below: they ask the same
            // question of the same rule, and it's a set lookup per level token.
            val levelHit = rule.hides && rule.hidesLevel(input.type, isDm = input.isDm)
            val hideApplies = levelHit
            // NOHIGHLIGHT only sensibly applies to the types a highlight can land on; when the
            // rule also carries hide levels it's bounded to those as well.
            val nohilightApplies = rule.nohilight &&
                IgnoreLevels.highlightable.contains(input.type) &&
                (if (rule.hides) levelHit else true)
            // NONOTIFY is NOT bounded to highlightable types. A channel's notify-always can
            // fire a notification for any event — a notice, a join — so muting a channel or
            // network has to veto those too ("quietest wins", lurker #359). Bounded only by
            // the rule's own hide levels, if it has any.
            val nonotifyApplies = rule.nonotify && (if (rule.hides) levelHit else true)
            if (!(hideApplies || nohilightApplies || nonotifyApplies)) continue
            if (!rule.mask.matches(nick = input.nick, userhost = input.userhost)) continue
            if (!rule.channels.matches(input.target)) continue
            if (!rule.text.matches(text, lowered = lowered)) continue

            if (hideApplies) {
                if (rule.isExcept) {
                    bestHideExcept = maxOf(bestHideExcept, rule.maskLength)
                } else {
                    bestHide = maxOf(bestHide, rule.maskLength)
                }
            }
            if (nohilightApplies) {
                if (rule.isExcept) {
                    bestNohilightExcept = maxOf(bestNohilightExcept, rule.maskLength)
                } else {
                    bestNohilight = maxOf(bestNohilight, rule.maskLength)
                }
            }
            if (nonotifyApplies) {
                if (rule.isExcept) {
                    bestNonotifyExcept = maxOf(bestNonotifyExcept, rule.maskLength)
                } else {
                    bestNonotify = maxOf(bestNonotify, rule.maskLength)
                }
            }
        }

        return IgnoreVerdict(
            hide = bestHide >= 0 && bestHideExcept < bestHide,
            nohilight = bestNohilight >= 0 && bestNohilightExcept < bestNohilight,
            nonotify = bestNonotify >= 0 && bestNonotifyExcept < bestNonotify,
        )
    }

    /**
     * Whether a rule would erase someone's whole presence — the only thing that may drop a
     * row from the nicklist, or a name from completion.
     *
     * A nicklist row carries a nick and a hostmask and nothing else: no body, no event type.
     * So only a rule that needs neither counts — a non-except, pattern-free `ALL` rule scoped
     * to every buffer or to this one. A `NOHIGHLIGHT`, content-pattern, or single-level rule
     * deliberately does NOT remove anybody: they're still in the room and still talking, and
     * vanishing them from the member list would misreport who is there.
     */
    internal fun isMemberHidden(
        compiled: CompiledSet,
        nick: String,
        userhost: String?,
        channel: String,
        now: Instant = Instant.now(),
    ): Boolean {
        for (rule in compiled.rules) {
            if (rule.isExcept || rule.hasPattern || !rule.hasAll) continue
            if (!rule.isLive(now)) continue
            if (!rule.channels.matches(channel)) continue
            if (rule.mask.matches(nick = nick, userhost = userhost)) return true
        }
        return false
    }

    /**
     * Whether a whole buffer's plain-unread signal is muted (lurker #359).
     *
     * True when a non-except, pattern-free, everyone-mask rule carrying `NOUNREAD` covers
     * this buffer — including a network-wide rule (no channel scope), which is what makes
     * muting a network downgrade every buffer under it.
     *
     * A *masked* NOUNREAD rule deliberately doesn't count: silencing one person's traffic in
     * a busy channel would need per-sender unread accounting, which is the server's to do and
     * isn't done. Muting the whole badge on the strength of one ignored member would hide
     * everybody else's messages from the list too.
     */
    internal fun channelMutesUnread(
        compiled: CompiledSet,
        channel: String,
        now: Instant = Instant.now(),
    ): Boolean {
        for (rule in compiled.rules) {
            if (rule.isExcept || rule.hasPattern || !rule.nounread || !rule.anyNick) continue
            if (!rule.isLive(now)) continue
            if (rule.channels.matches(channel)) return true
        }
        return false
    }
}

/** One event, in the terms a rule is written against. */
data class IgnoreInput(
    val nick: String?,
    /**
     * The sender's `nick!user@host`, when the server had one. A rule that names a user or
     * host can't match without it.
     */
    val userhost: String?,
    /** The buffer the event landed in — what a rule's channel scope is matched against. */
    val target: String,
    val text: String,
    val type: EventType,
    /** Whether `target` is a DM rather than a channel — the whole of the `PUBLIC`/`MSGS` split. */
    val isDm: Boolean,
)

/** What the rules say about one event. */
data class IgnoreVerdict(
    /** Don't render the row at all. */
    val hide: Boolean,
    /** Render it, but never as a highlight — the mention wash comes off. */
    val nohilight: Boolean,
    /**
     * Render and count it, but don't make a sound about it.
     *
     * Nothing on this client reads it yet, and by design: whether a notification fires is the
     * server's call (it folds `hide || nonotify` into the `notify` flag it decides push on),
     * so a second opinion here could only disagree with it. It's part of the verdict because
     * it's part of what a rule *says*, and computing two thirds of a verdict would make the
     * matcher's agreement with the server's copy partial rather than checkable.
     */
    val nonotify: Boolean,
) {
    companion object {
        /** The verdict when no rule has anything to say. */
        val visible = IgnoreVerdict(hide = false, nohilight = false, nonotify = false)
    }
}

/**
 * Whether the pattern matches anywhere in `text`. Anchoring is the pattern's own business
 * — `globToRegex` builds `^…$`-anchored sources, a user's `-pattern regex` is matched as
 * written — so there is one method rather than two names for the same call.
 *
 * Port note: `matches` in LurkerKit, an extension on `NSRegularExpression`. Renamed
 * because Kotlin's `Regex` already has a `matches`, and it means the *whole* input — the
 * member would win over an extension of the same name and quietly anchor every user
 * pattern at both ends.
 *
 * Port note: a search the engine gives up on is no match. `NSRegularExpression` answers
 * nil when ICU runs out of backtracking stack on a pathological pattern; `java.util.regex`
 * throws `StackOverflowError` instead, and this runs on the render path with patterns a
 * person typed.
 */
private fun Regex.matchesAnywhere(text: String): Boolean =
    try {
        containsMatchIn(text)
    } catch (_: StackOverflowError) {
        false
    }
