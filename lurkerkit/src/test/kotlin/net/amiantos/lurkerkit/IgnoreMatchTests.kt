// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreInput
import net.amiantos.lurkerkit.model.IgnoreMatch
import net.amiantos.lurkerkit.model.IgnorePatternKind
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreVerdict
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The matcher's behavior, derived from the lurker repo's `server/services/ignoreMatch.test.ts`
 * so the two implementations are held to the same cases.
 *
 * Divergences from that suite are deliberate and marked where they occur; everything else is
 * the same rule with the same expected answer. The point isn't coverage for its own sake —
 * it's that the server stamps `from_ignored` from its copy of this logic while the client
 * filters from this one, so any disagreement between them shows up as a line that's hidden in
 * one place and counted in another.
 */
class IgnoreMatchTests {

    // MARK: - Fixtures

    private fun rule(
        id: Int = 1,
        mask: String? = null,
        channels: List<String>? = null,
        pattern: String? = null,
        patternKind: IgnorePatternKind = IgnorePatternKind.Substr,
        levels: List<String> = listOf("ALL"),
        isExcept: Boolean = false,
        expiresAt: Instant? = null,
    ): IgnoreRule =
        IgnoreRule(
            id = id, mask = mask, channels = channels, pattern = pattern, patternKind = patternKind,
            levels = levels, isExcept = isExcept, expiresAt = expiresAt,
        )

    private fun input(
        nick: String? = "bob",
        userhost: String? = "bob!u@h",
        target: String = "#chan",
        text: String = "hello",
        type: EventType = EventType.Message,
        isDm: Boolean = false,
    ): IgnoreInput =
        IgnoreInput(nick = nick, userhost = userhost, target = target, text = text, type = type, isDm = isDm)

    private fun evaluate(rules: List<IgnoreRule>, input: IgnoreInput): IgnoreVerdict =
        IgnoreMatch.evaluate(IgnoreMatch.compile(rules), input)

    // MARK: - NOHIGHLIGHT

    @Test
    fun testNohighlightKeepsTheMessageVisibleButSuppressesTheHighlight() {
        val rules = listOf(rule(mask = "bob", levels = listOf("NOHIGHLIGHT")))
        assertEquals(
            IgnoreVerdict(hide = false, nohilight = true, nonotify = false),
            evaluate(rules, input(nick = "bob")),
        )
    }

    @Test
    fun testNohighlightDoesNotAffectADifferentSender() {
        val rules = listOf(rule(mask = "bob", levels = listOf("NOHIGHLIGHT")))
        assertEquals(IgnoreVerdict.visible, evaluate(rules, input(nick = "alice")))
    }

    @Test
    fun testNohighlightOnlyAppliesToHighlightableTypes() {
        val rules = listOf(rule(mask = "bob", levels = listOf("NOHIGHLIGHT")))
        assertTrue(evaluate(rules, input(text = "waves", type = EventType.Action)).nohilight)
        assertFalse(evaluate(rules, input(text = "", type = EventType.Join)).nohilight)
    }

    // MARK: - Content patterns

    @Test
    fun testAContentRegexScopedToAChannel() {
        val rules = listOf(
            rule(
                channels = listOf("#chan"), pattern = "(word1|word2)",
                patternKind = IgnorePatternKind.Regex, levels = listOf("PUBLIC"),
            ),
        )
        assertTrue(
            evaluate(rules, input(nick = "anyone", target = "#chan", text = "has word2 in it")).hide,
            "a channel-scoped content rule hides a matching line from anyone",
        )
        assertFalse(evaluate(rules, input(target = "#other", text = "has word1 in it")).hide)
        assertFalse(evaluate(rules, input(target = "#chan", text = "nothing here")).hide)
    }

    /**
     * Not in the server suite: `full` is the third stored `patternKind`, and its whole-word
     * anchoring is the one that can silently misfire — a substring match would hide both of
     * these lines rather than one.
     */
    @Test
    fun testAFullPatternMatchesWholeWordsOnly() {
        val rules = listOf(rule(pattern = "spam", patternKind = IgnorePatternKind.Full, levels = listOf("PUBLIC")))
        assertTrue(evaluate(rules, input(text = "this is Spam!")).hide)
        assertFalse(evaluate(rules, input(text = "spamalot is fine")).hide)
    }

    /**
     * Also not in the server suite, and the reason the word class is spelled out rather than
     * left to `\w`: an accented letter is a word character, so a keyword must not match
     * inside a longer non-ASCII word.
     */
    @Test
    fun testWholeWordMatchingTreatsNonAsciiLettersAsWordCharacters() {
        val rules = listOf(rule(pattern = "em", patternKind = IgnorePatternKind.Full, levels = listOf("PUBLIC")))
        assertFalse(evaluate(rules, input(text = "zrozumiałem")).hide)
        assertTrue(evaluate(rules, input(text = "ale em jest")).hide)
    }

    /**
     * A rule whose regex doesn't compile goes inert instead of taking the rest of the set
     * down with it — the whole reason `TextMatcher` has a `Never` case.
     */
    @Test
    fun testAnUncompilableRegexDisablesOnlyItsOwnRule() {
        val rules = listOf(
            rule(id = 1, pattern = "([unclosed", patternKind = IgnorePatternKind.Regex, levels = listOf("PUBLIC")),
            rule(id = 2, mask = "bob", levels = listOf("ALL")),
        )
        assertTrue(evaluate(rules, input(nick = "bob")).hide, "the second rule still applies")
        assertFalse(evaluate(listOf(rules[0]), input(nick = "bob")).hide)
    }

    // MARK: - Masks

    @Test
    fun testAnUnmaskedJoinsRuleHidesJoinsFromAnyoneButNotMessages() {
        val rules = listOf(rule(levels = listOf("JOINS")))
        assertTrue(evaluate(rules, input(text = "", type = EventType.Join)).hide)
        assertFalse(evaluate(rules, input()).hide)
    }

    @Test
    fun testAGlobMaskMatchesInsideTheNick() {
        val rules = listOf(rule(mask = "*zzz*", levels = listOf("NICKS")))
        assertTrue(evaluate(rules, input(nick = "fooZZZbar", text = "", type = EventType.Nick)).hide)
        assertFalse(evaluate(rules, input(nick = "foo", text = "", type = EventType.Nick)).hide)
    }

    @Test
    fun testABareNickMaskIsAnchoredAndCaseInsensitive() {
        val rules = listOf(rule(mask = "bozo", levels = listOf("ALL")))
        assertTrue(evaluate(rules, input(nick = "Bozo")).hide)
        assertFalse(evaluate(rules, input(nick = "bozoXYZ")).hide, "anchored, not a substring")
    }

    /**
     * A hostmask rule needs a hostmask to judge. The server doesn't stamp one on every event
     * (synthesized lines carry none), and a rule that constrains the host must not fire on a
     * sender whose host is simply unknown — while one that constrains only the nick still can.
     */
    @Test
    fun testAHostmaskRuleNeedsAHostmaskButANickOnlyOneDoesNot() {
        assertFalse(
            evaluate(listOf(rule(mask = "*!*@spam", levels = listOf("ALL"))), input(userhost = null)).hide,
        )
        assertTrue(
            evaluate(listOf(rule(mask = "bob!*@*", levels = listOf("ALL"))), input(userhost = null)).hide,
        )
    }

    /**
     * The user and host halves are case-SENSITIVE while the nick half isn't — the split the
     * shared matcher makes, and easy to get wrong in a port by folding all three.
     */
    @Test
    fun testTheNickHalfFoldsCaseAndTheHostHalfDoesNot() {
        val rules = listOf(rule(mask = "Bob!*@Host", levels = listOf("ALL")))
        assertTrue(evaluate(rules, input(nick = "bob", userhost = "bob!u@Host")).hide)
        assertFalse(evaluate(rules, input(nick = "bob", userhost = "bob!u@host")).hide)
    }

    /**
     * A mask with no `!` but an `@` names the *user*, not the nick — the third shape
     * `splitMask` has to get right.
     */
    @Test
    fun testAUserAtHostMaskConstrainsTheUserHalf() {
        val rules = listOf(rule(mask = "spammer@evil.example", levels = listOf("ALL")))
        assertTrue(evaluate(rules, input(userhost = "anynick!spammer@evil.example")).hide)
        assertFalse(evaluate(rules, input(userhost = "anynick!other@evil.example")).hide)
    }

    /**
     * A channel scope with a wildcard takes the regex path while a plain name takes the
     * literal one, and the two have to agree — the literal case exists purely to keep the regex
     * engine off the render path, so it must not also change what matches.
     */
    @Test
    fun testAChannelScopeMatchesLiterallyAndByGlobAlike() {
        val literal = listOf(rule(channels = listOf("#chan"), levels = listOf("PUBLIC")))
        assertTrue(evaluate(literal, input(target = "#chan")).hide)
        assertTrue(evaluate(literal, input(target = "#CHAN")).hide, "targets fold case")
        assertFalse(evaluate(literal, input(target = "#chan2")).hide, "anchored, not a prefix")

        val glob = listOf(rule(channels = listOf("#chan*"), levels = listOf("PUBLIC")))
        assertTrue(evaluate(glob, input(target = "#chan")).hide)
        assertTrue(evaluate(glob, input(target = "#chan2")).hide)
        assertFalse(evaluate(glob, input(target = "#other")).hide)
    }

    /**
     * A literal mask that happens to contain regex metacharacters must stay a literal —
     * `[` and `.` are legal in some networks' nicks, and the fast path must not treat them
     * as syntax where the regex path escapes them.
     */
    @Test
    fun testAMaskWithRegexMetacharactersIsMatchedLiterally() {
        val rules = listOf(rule(mask = "a.b[c]", levels = listOf("ALL")))
        assertTrue(evaluate(rules, input(nick = "a.b[c]")).hide)
        assertFalse(evaluate(rules, input(nick = "axbc")).hide)
    }

    // MARK: - Levels

    @Test
    fun testPublicMatchesChannelMessagesOnlyAndMsgsDmsOnly() {
        val publicRule = listOf(rule(mask = "bob", levels = listOf("PUBLIC")))
        assertTrue(evaluate(publicRule, input(isDm = false)).hide)
        assertFalse(evaluate(publicRule, input(target = "bob", isDm = true)).hide)

        val msgsRule = listOf(rule(mask = "bob", levels = listOf("MSGS")))
        assertTrue(evaluate(msgsRule, input(target = "bob", isDm = true)).hide)
        assertFalse(evaluate(msgsRule, input(isDm = false)).hide)
    }

    @Test
    fun testAllHidesEveryIgnorableTypeAndNoOthers() {
        val rules = listOf(rule(mask = "bob", levels = listOf("ALL")))
        val ignorable: List<EventType> = listOf(
            EventType.Message, EventType.Action, EventType.Notice, EventType.Join, EventType.Part,
            EventType.Quit, EventType.Nick, EventType.Kick, EventType.Mode, EventType.Topic,
            EventType.Chghost,
        )
        for (type in ignorable) {
            assertTrue(evaluate(rules, input(text = "x", type = type)).hide, "$type should hide")
        }
        // System and self-scoped rows have no sender to ignore. `Other` stands in for the
        // unmodeled state events (usermode, names, lag) this client folds together.
        for (type in listOf(EventType.Motd, EventType.Error, EventType.System, EventType.Other)) {
            assertFalse(evaluate(rules, input(type = type)).hide, "$type must stay visible")
        }
    }

    /** A chghost rides the QUITS level rather than having one of its own (lurker #591). */
    @Test
    fun testQuitsAlsoCoversChghost() {
        val rules = listOf(rule(mask = "bob", levels = listOf("QUITS")))
        assertTrue(evaluate(rules, input(text = "", type = EventType.Quit)).hide)
        assertTrue(evaluate(rules, input(text = "", type = EventType.Chghost)).hide)
        assertFalse(evaluate(rules, input(text = "", type = EventType.Part)).hide)
    }

    /**
     * A level token this client doesn't know is dropped rather than matching everything —
     * the compile-time equivalent of the server's `if (!def) continue`.
     */
    @Test
    fun testAnUnknownLevelTokenHidesNothing() {
        assertFalse(evaluate(listOf(rule(mask = "bob", levels = listOf("INVENTED"))), input()).hide)
    }

    /**
     * The types NOT in `IgnoreLevels.all`, stated explicitly. `Invite`, `E2e` and `Ctcp`
     * sat in neither list, so an `ALL` rule visibly leaving invites standing was untested in
     * both directions.
     */
    @Test
    fun testAllLeavesInvitesAndClientSideLinesStanding() {
        val rules = listOf(rule(mask = "bob", levels = listOf("ALL")))
        for (type in listOf(EventType.Invite, EventType.E2e, EventType.Ctcp)) {
            assertFalse(
                evaluate(rules, input(text = "x", type = type)).hide,
                "ALL should not cover $type — it is outside IgnoreLevels.all",
            )
        }
    }

    /**
     * The literal fast paths must never disagree with the glob path they exist to avoid.
     * The earlier version of this suite asserted exactly that and only tested ASCII, where
     * the split is invisible — on iOS a `lowercased()` comparison answered differently from ICU
     * for `WEIß` and `ς`, so an optimization was quietly changing verdicts.
     */
    @Test
    fun testTheLiteralAndGlobPathsAgreeOnNonAsciiToo() {
        // Each pair is (mask, nick) chosen so the two paths could diverge: German sharp s
        // (full vs simple case folding) and Greek final sigma.
        for ((mask, nick) in listOf("weiss" to "WEIß", "weiß" to "WEISS", "σ" to "ς", "Straße" to "STRASSE")) {
            // `mask` has no wildcard, so it takes the literal path; `mask + "*"` forces the
            // regex path against the same nick with a suffix that matches nothing extra.
            val literal = evaluate(listOf(rule(mask = mask, levels = listOf("ALL"))), input(nick = nick)).hide
            val glob = evaluate(listOf(rule(mask = "$mask*", levels = listOf("ALL"))), input(nick = nick)).hide
            assertEquals(literal, glob, "literal and glob disagree for mask=$mask nick=$nick")
        }
    }

    /** Same requirement for channel scopes, which have their own literal path. */
    @Test
    fun testTheLiteralAndGlobChannelPathsAgreeOnNonAsciiToo() {
        for ((scope, target) in listOf("#weiss" to "#WEIß", "#σ" to "#ς")) {
            val literal =
                evaluate(listOf(rule(channels = listOf(scope), levels = listOf("PUBLIC"))), input(target = target)).hide
            val glob =
                evaluate(listOf(rule(channels = listOf("$scope*"), levels = listOf("PUBLIC"))), input(target = target)).hide
            assertEquals(literal, glob, "literal and glob disagree for scope=$scope target=$target")
        }
    }

    /**
     * A substring pattern compares code units, like the JS `includes` it ports — NOT
     * canonical equivalence (Swift's default), which would hide a decomposed line the server
     * counted.
     */
    @Test
    fun testASubstringPatternDoesNotMatchAcrossCanonicalEquivalence() {
        val rules = listOf(rule(pattern = "caf\u00e9", levels = listOf("PUBLIC"))) // precomposed é
        assertTrue(evaluate(rules, input(text = "caf\u00e9 time")).hide)
        assertFalse(
            evaluate(rules, input(text = "cafe\u0301 time")).hide,
            "decomposed text is a different string to the reference engine, so it must be here",
        )
    }

    /**
     * The compile failure fallbacks match NOTHING. The tempting alternative — "matches
     * anything" — silently promotes `hide bob` into `hide everyone`, which for an `ALL` rule
     * blanks the message list, the nicklist and completion at once, and widens a channel
     * scope from one buffer to the whole network.
     *
     * Asserted on the cases themselves because `globToRegex` escapes everything a user can
     * type, so the branch that selects them isn't reachable today. That is exactly why the
     * direction is worth pinning now: an unreachable wrong default stays invisible right up
     * until the escaping changes.
     */
    @Test
    fun testTheCompileFailureFallbacksMatchNothing() {
        assertFalse(IgnoreMatch.MaskMatcher.Nobody.matches(nick = "bob", userhost = "bob!u@h"))
        assertFalse(IgnoreMatch.MaskMatcher.Nobody.matches(nick = null, userhost = null))
        assertFalse(IgnoreMatch.ChannelMatcher.None.matches("#chan"))
        // The sibling that already got this right, for contrast.
        assertFalse(IgnoreMatch.TextMatcher.Never.matches("anything", lowered = "anything"))
    }

    /**
     * Longest-mask-wins is decided against the server's answer for the same rules, and the
     * server counts JS `String.length` — UTF-16 units, where a non-BMP character counts 2.
     */
    @Test
    fun testMaskLengthIsCountedInUtf16UnitsLikeTheReference() {
        val compiled = IgnoreMatch.compile(listOf(rule(mask = "🙂🙂", levels = listOf("ALL"))))
        assertEquals(
            4, compiled.rules[0].maskLength,
            "two non-BMP characters are 4 UTF-16 units, not 2 graphemes",
        )
    }

    // MARK: - Except

    @Test
    fun testTheLongerExceptMaskWins() {
        val rules = listOf(
            rule(id = 1, mask = "*!*@spam", levels = listOf("ALL")),
            rule(id = 2, mask = "bob!*@spam", levels = listOf("ALL"), isExcept = true),
        )
        assertFalse(evaluate(rules, input(nick = "bob", userhost = "bob!u@spam")).hide)
        assertTrue(
            evaluate(rules, input(nick = "eve", userhost = "eve!u@spam")).hide,
            "others on the host are still hidden",
        )
    }

    // MARK: - Expiry

    @Test
    fun testALapsedRuleNeverMatchesAndAFutureOneStillDoes() {
        val past = Instant.ofEpochSecond(946_684_800) // 2000-01-01
        val future = Instant.ofEpochSecond(32_503_680_000) // 2999-01-01
        assertFalse(evaluate(listOf(rule(mask = "bob", expiresAt = past)), input()).hide)
        assertTrue(evaluate(listOf(rule(mask = "bob", expiresAt = future)), input()).hide)
    }

    // MARK: - Text normalization

    @Test
    fun testAPatternDoesNotMatchAWordThatAppearsOnlyInsideAURL() {
        val rules = listOf(rule(pattern = "spam", levels = listOf("PUBLIC")))
        assertFalse(evaluate(rules, input(text = "see https://spam.example here")).hide)
        assertTrue(evaluate(rules, input(text = "this is spam")).hide)
    }

    /**
     * mIRC color codes have to come off before matching, or the digits fuse to the front of
     * the word and a whole-word rule misses the line it was written for.
     */
    @Test
    fun testFormattingCodesAreStrippedBeforeMatching() {
        val rules = listOf(rule(pattern = "QUACK", patternKind = IgnorePatternKind.Full, levels = listOf("PUBLIC")))
        assertTrue(evaluate(rules, input(text = "\u000304QUACK\u0003")).hide)
    }

    // MARK: - Member visibility

    @Test
    fun testOnlyAWholeIdentityAllRuleHidesAMember() {
        fun hidden(rules: List<IgnoreRule>, channel: String = "#chan"): Boolean =
            IgnoreMatch.isMemberHidden(
                IgnoreMatch.compile(rules), nick = "bob", userhost = "bob!u@h", channel = channel,
            )
        assertTrue(hidden(listOf(rule(mask = "bob", levels = listOf("ALL")))))
        assertTrue(hidden(listOf(rule(mask = "bob", channels = listOf("#chan"), levels = listOf("ALL")))))
        assertFalse(
            hidden(listOf(rule(mask = "bob", channels = listOf("#other"), levels = listOf("ALL")))),
            "a rule scoped to another channel doesn't reach this nicklist",
        )
        assertFalse(
            hidden(listOf(rule(mask = "bob", levels = listOf("PUBLIC")))),
            "a level-scoped rule leaves them listed — they're still in the room",
        )
        assertFalse(hidden(listOf(rule(mask = "bob", levels = listOf("NOHIGHLIGHT")))))
        assertFalse(
            hidden(listOf(rule(mask = "bob", pattern = "x", levels = listOf("ALL")))),
            "a content rule can't be judged without a message",
        )
        assertFalse(hidden(listOf(rule(mask = "bob", levels = listOf("ALL"), isExcept = true))))
    }

    // MARK: - Mute modifiers (lurker#359)

    @Test
    fun testANonotifyRuleMutesNotificationsButKeepsTheMessageVisible() {
        val verdict = evaluate(
            listOf(rule(channels = listOf("#chan"), levels = listOf("NOUNREAD", "NONOTIFY"))),
            input(target = "#chan"),
        )
        assertEquals(IgnoreVerdict(hide = false, nohilight = false, nonotify = true), verdict)
    }

    @Test
    fun testNounreadAloneProducesNoPerMessageVerdict() {
        // Its only effect is the whole-buffer badge downgrade, tested below.
        assertEquals(
            IgnoreVerdict.visible,
            evaluate(listOf(rule(channels = listOf("#chan"), levels = listOf("NOUNREAD"))), input(target = "#chan")),
        )
    }

    @Test
    fun testNonotifyVetoesNonHighlightableTypesToo() {
        val rules = listOf(rule(channels = listOf("#chan"), levels = listOf("NONOTIFY")))
        assertTrue(evaluate(rules, input(target = "#chan", type = EventType.Notice)).nonotify)
        assertTrue(evaluate(rules, input(target = "#chan", text = "", type = EventType.Join)).nonotify)
        assertTrue(evaluate(rules, input(target = "#chan", text = "waves", type = EventType.Action)).nonotify)
        assertFalse(evaluate(rules, input(target = "#other")).nonotify, "channel scope holds")
    }

    @Test
    fun testAScopeMuteVetoesANicklessEventButASenderMuteDoesNot() {
        assertTrue(
            evaluate(
                listOf(rule(channels = listOf("#chan"), levels = listOf("NONOTIFY"))),
                input(nick = null, userhost = null, target = "#chan", type = EventType.Notice),
            ).nonotify,
        )
        assertFalse(
            evaluate(listOf(rule(mask = "bob", levels = listOf("NONOTIFY"))), input(nick = null, userhost = null))
                .nonotify,
        )
    }

    @Test
    fun testNonotifyHonorsExcept() {
        val rules = listOf(
            rule(id = 1, channels = listOf("#chan"), levels = listOf("NONOTIFY")),
            rule(id = 2, mask = "boss", channels = listOf("#chan"), levels = listOf("NONOTIFY"), isExcept = true),
        )
        assertTrue(evaluate(rules, input(nick = "rando", target = "#chan")).nonotify)
        assertFalse(evaluate(rules, input(nick = "boss", target = "#chan")).nonotify)
    }

    // MARK: - channelMutesUnread (lurker#359)

    @Test
    fun testChannelMutesUnread() {
        fun mutes(rules: List<IgnoreRule>, channel: String): Boolean =
            IgnoreMatch.channelMutesUnread(IgnoreMatch.compile(rules), channel = channel)
        assertTrue(mutes(listOf(rule(channels = listOf("#chan"), levels = listOf("NOUNREAD"))), "#chan"))
        assertFalse(mutes(listOf(rule(channels = listOf("#chan"), levels = listOf("NOUNREAD"))), "#other"))

        // A network-wide rule (no channel scope) covers every buffer under it, DMs included.
        val networkWide = listOf(rule(levels = listOf("NOUNREAD")))
        assertTrue(mutes(networkWide, "#anything"))
        assertTrue(mutes(networkWide, "somenick"))

        assertFalse(
            mutes(listOf(rule(mask = "bob", channels = listOf("#chan"), levels = listOf("NOUNREAD"))), "#chan"),
            "a per-sender mute can't speak for the whole buffer's badge",
        )
        assertFalse(mutes(listOf(rule(channels = listOf("#chan"), levels = listOf("NONOTIFY"))), "#chan"))
        assertFalse(
            mutes(listOf(rule(channels = listOf("#chan"), levels = listOf("NOUNREAD"), isExcept = true)), "#chan"),
        )
    }

    // Port-only:

    /**
     * LurkerKit's literal path is Foundation's `caseInsensitiveCompare`, which the JVM has no
     * equal of; here it is ASCII compared as ASCII and anything else handed to the regex the
     * glob path would have built. So the two paths agree by construction, on any engine — which
     * is what this sweeps, over the characters whose folding the engines disagree about. What
     * each pair's answer *is* depends on the engine, and is deliberately not asserted.
     */
    @Test
    fun testTheLiteralPathIsTheGlobPathsAnswerOnWhicheverEngineThisIs() {
        // (plain, the spelling an engine may or may not fold it with)
        val folds = listOf(
            "k" to "\u212A", // Kelvin sign
            "s" to "\u017F", // long s
            "i" to "\u0130", // dotted capital I
            "I" to "\u0131", // dotless i
            "fi" to "\uFB01", // the fi ligature
            "ss" to "\u00DF", // sharp s
            "SS" to "\u1E9E", // capital sharp s
            "\u00E9" to "e\u0301", // precomposed and decomposed e-acute
            "dz" to "\u01C5", // the titlecase digraph
            "\u03C3" to "\u03C2", // sigma and final sigma
            "bob" to "BOB",
        )
        for ((plain, folded) in folds) {
            for ((mask, nick) in listOf("x${plain}y" to "X${folded}Y", "x${folded}y" to "X${plain}Y")) {
                val literal = evaluate(listOf(rule(mask = mask)), input(nick = nick)).hide
                val glob = evaluate(listOf(rule(mask = "$mask*")), input(nick = nick)).hide
                assertEquals(glob, literal, "literal and glob disagree for mask=$mask nick=$nick")

                val scoped = evaluate(listOf(rule(channels = listOf("#$mask"))), input(target = "#$nick")).hide
                val scopedGlob = evaluate(listOf(rule(channels = listOf("#$mask*"))), input(target = "#$nick")).hide
                assertEquals(scopedGlob, scoped, "literal and glob disagree for scope=#$mask target=#$nick")
            }
        }
    }

    /** ASCII is the one place every engine agrees, so there the answer itself is pinned. */
    @Test
    fun testAnAsciiLiteralFoldsAsciiCaseAndNothingElse() {
        val rules = listOf(rule(mask = "[Bob]^_`"))
        assertTrue(evaluate(rules, input(nick = "[bOB]^_`")).hide)
        // RFC 1459's own folding (`{}|~` for `[]\^`) is not this matcher's: neither engine does it.
        assertFalse(evaluate(rules, input(nick = "{bob}~_`")).hide)
        // A compare, not a search: `$` in the regex it stands in for would also match before a
        // final newline, and LurkerKit's literal path does not.
        assertFalse(evaluate(rules, input(nick = "[Bob]^_`\n")).hide)
        assertFalse(evaluate(rules, input(nick = "x[Bob]^_`")).hide)
    }

    /**
     * A whole-word pattern is escaped the way `NSRegularExpression.escapedPattern(for:)` escapes
     * it — a backslash before each of `$ ( ) * + . / ? [ \ ^ { | }`, and not before `]`. Every
     * answer here is the Swift's, taken from the differential run.
     */
    @Test
    fun testAWholeWordPatternIsMatchedLiterallyWhateverItIsMadeOf() {
        val patterns = listOf(
            "a.b", "c++", "[tag]", "\$5", "sl/ash", "q?", "st*r", "{b}", "a|b", "back\\slash", "^caret", "(paren)", "a]b", "x#y",
            "a b", "-_-", "50%",
        )
        for (pattern in patterns) {
            val rules = listOf(rule(pattern = pattern, patternKind = IgnorePatternKind.Full, levels = listOf("PUBLIC")))
            assertTrue(evaluate(rules, input(text = pattern)).hide, "$pattern alone")
            assertTrue(evaluate(rules, input(text = "see ($pattern) here")).hide, "$pattern in brackets")
            assertFalse(evaluate(rules, input(text = "x$pattern")).hide, "$pattern after a letter")
            assertFalse(evaluate(rules, input(text = "${pattern}x")).hide, "$pattern before a letter")
            assertFalse(evaluate(rules, input(text = "${pattern}_")).hide, "$pattern before an underscore")
        }
        // The metacharacters are literal: `a.b` is not "a, anything, b".
        val dotted = listOf(rule(pattern = "a.b", patternKind = IgnorePatternKind.Full, levels = listOf("PUBLIC")))
        assertFalse(evaluate(dotted, input(text = "axb")).hide)
    }

    /**
     * A user's regex is *searched for*. Kotlin's own `Regex.matches` means the whole input, and
     * would have quietly anchored every pattern at both ends.
     */
    @Test
    fun testAUserRegexIsFoundAnywhereInTheLine() {
        val rules = listOf(rule(pattern = "sp[a4]m", patternKind = IgnorePatternKind.Regex, levels = listOf("PUBLIC")))
        assertTrue(evaluate(rules, input(text = "buy SP4M now")).hide)
        assertFalse(evaluate(rules, input(text = "buy ham now")).hide)
    }
}
