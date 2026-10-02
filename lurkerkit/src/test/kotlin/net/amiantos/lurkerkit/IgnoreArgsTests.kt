// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.IgnoreArgs
import net.amiantos.lurkerkit.model.ISOTime
import net.amiantos.lurkerkit.model.IgnorePatternKind
import net.amiantos.lurkerkit.support.Result
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Derived from the reference suite for the same grammar (`server/services/parseIgnore.test.ts`),
 * case for case, because the two parsers write rows into the same table: a level spelled
 * `nohilight` here and dropped there would store a rule that means something different
 * depending on which client typed it.
 *
 * Two deliberate divergences from the reference file:
 *  - `durationToExpiry` isn't ported. It exists on the web for the ignore settings pane's
 *    duration field; this client has no such pane (lurker-ios#86 is slash commands only), and
 *    the duration grammar it shares with `-time` is covered through `-time` below.
 *  - The reference compares `expiresAt` as an ISO string; here it's an `Instant`, parsed at the
 *    wire boundary. The instants asserted are the same ones.
 */
class IgnoreArgsTests {

    private val now = ISOTime.parse("2026-06-18T00:00:00.000Z")!!

    /**
     * Port note: `IgnoreRule.summary` takes the expiry's formatter here, where LurkerKit
     * formats it itself; the summary tests hand it this one.
     */
    private val formatted: (Instant) -> String = { it.toString() }

    /** The rule a command line names (failing the test if it named none). */
    private fun parse(line: String): IgnoreArgs.Parsed =
        when (val result = IgnoreArgs.parse(line, now = now)) {
            is Result.Success -> result.value
            is Result.Failure -> fail("expected a rule from $line, got: ${result.error.message}")
        }

    /** The reason a command line named no rule (failing the test if it named one). */
    private fun error(line: String): String =
        when (val result = IgnoreArgs.parse(line, now = now)) {
            is Result.Success -> fail("expected $line to fail")
            is Result.Failure -> result.error.message
        }

    // MARK: - Masks & levels

    @Test
    fun testBareNickDefaultsToAll() {
        val rule = parse("bob").rule
        assertEquals("bob", rule.mask)
        assertEquals(listOf("ALL"), rule.levels)
        assertNull(rule.channels)
        assertNull(rule.pattern)
    }

    @Test
    fun testNohighlightAcceptsLurkerAndIrssiSpellings() {
        assertEquals(listOf("NOHIGHLIGHT"), parse("bob NOHIGHLIGHT").rule.levels)
        assertEquals(listOf("NOHIGHLIGHT"), parse("bob NOHIGHLIGHTS").rule.levels)
        assertEquals(listOf("NOHIGHLIGHT"), parse("bob NOHILIGHT").rule.levels)
    }

    @Test
    fun testStarMaskNormalizesToAnyone() {
        val parsed = parse("* JOINS")
        assertNull(parsed.rule.mask)
        assertEquals(listOf("JOINS"), parsed.rule.levels)
    }

    @Test
    fun testKeepsAGlobMaskVerbatim() {
        val parsed = parse("*zzz* NICKS")
        assertEquals("*zzz*", parsed.rule.mask)
        assertEquals(listOf("NICKS"), parsed.rule.levels)
    }

    @Test
    fun testAcceptsSingularAndPluralLevelAliases() {
        assertEquals(listOf("PUBLIC"), parse("bob publics").rule.levels)
        assertEquals(listOf("JOINS", "PARTS"), parse("bob join part").rule.levels)
    }

    @Test
    fun testMuteModifiersAndTheirIrssiAliases() {
        // A channel-scoped mute carries no mask — the whole channel is muted.
        val parsed = parse("#idlerpg NOUNREAD")
        assertNull(parsed.rule.mask)
        assertEquals(listOf("#idlerpg"), parsed.rule.channels)
        assertEquals(listOf("NOUNREAD"), parsed.rule.levels)
        // Canonical order, not the order they were typed.
        assertEquals(listOf("NOUNREAD", "NONOTIFY"), parse("#idlerpg NONOTIFY NOUNREAD").rule.levels)
        assertEquals(listOf("NOUNREAD"), parse("#idlerpg no_act").rule.levels)
        assertEquals(listOf("NOUNREAD"), parse("#idlerpg noact").rule.levels)
    }

    @Test
    fun testABareTokenStaysASenderMaskRatherThanAChannel() {
        val parsed = parse("spambot NONOTIFY")
        assertEquals("spambot", parsed.rule.mask)
        assertNull(parsed.rule.channels)
        assertEquals(listOf("NONOTIFY"), parsed.rule.levels)
    }

    @Test
    fun testAllDoesNotExpandToIncludeTheModifierLevels() {
        // The modifiers aren't in `defs`, so expanding ALL can't pull them in — a rule that
        // hid everything would otherwise also silence the badge and the notification.
        val levels = parse("bob ALL -PUBLIC").rule.levels
        assertFalse(levels.contains("NOUNREAD"))
        assertFalse(levels.contains("NONOTIFY"))
        assertFalse(levels.contains("NOHIGHLIGHT"))
    }

    // MARK: - Content patterns & channels

    @Test
    fun testRegexpPatternGroupAndChannel() {
        val parsed = parse("-regexp -pattern (word1|word2) #channel")
        assertNull(parsed.rule.mask)
        assertEquals(listOf("#channel"), parsed.rule.channels)
        assertEquals("(word1|word2)", parsed.rule.pattern)
        assertEquals(IgnorePatternKind.Regex, parsed.rule.patternKind)
        assertEquals(listOf("ALL"), parsed.rule.levels)
    }

    @Test
    fun testFullWithAQuotedMultiWordPattern() {
        val parsed = parse("-full -pattern \"two words\" PUBLIC")
        assertEquals("two words", parsed.rule.pattern)
        assertEquals(IgnorePatternKind.Full, parsed.rule.patternKind)
        assertEquals(listOf("PUBLIC"), parsed.rule.levels)
    }

    @Test
    fun testDefaultPatternKindIsSubstring() {
        assertEquals(IgnorePatternKind.Substr, parse("-pattern spam").rule.patternKind)
    }

    @Test
    fun testChannelScopeIsLowercased() {
        // Channel names are case-insensitive on the wire and the matcher compares them that
        // way, but the stored value is what the settings pane and the listing show.
        assertEquals(listOf("#loudchannel"), parse("bob #LoudChannel").rule.channels)
    }

    // MARK: - Subtractive levels

    @Test
    fun testAllMinusLevelsExpandsThenRemoves() {
        val rule = parse("#irssi ALL -PUBLIC -ACTIONS").rule
        assertEquals(listOf("#irssi"), rule.channels)
        // Pinned exactly, not by `contains`: the stored CSV is what the server's dedupe
        // compares as a string, so both the membership AND the canonical order are the
        // contract. A drift in either writes a duplicate rule rather than matching an
        // existing one.
        assertEquals(
            listOf("MSGS", "NOTICES", "JOINS", "PARTS", "QUITS", "NICKS", "KICKS", "MODES", "TOPICS", "CTCPS"),
            rule.levels,
        )
    }

    @Test
    fun testSubtractingEveryLevelIsAnError() {
        // Not a rule with no levels — that would store something that matches nothing and sit
        // in the listing looking like it works.
        assertEquals("no levels remain", error("bob PUBLIC -PUBLIC"))
    }

    // MARK: - Flags & errors

    @Test
    fun testExceptSetsTheWhitelistFlag() {
        val parsed = parse("-except *!*@*.irssi.org CTCPS")
        assertEquals("*!*@*.irssi.org", parsed.rule.mask)
        assertEquals(true, parsed.rule.isExcept)
        assertEquals(listOf("CTCPS"), parsed.rule.levels)
    }

    @Test
    fun testTimeComputesExpiryFromNow() {
        val parsed = parse("-time 5days christmas PUBLICS")
        assertEquals("christmas", parsed.rule.mask)
        assertEquals(listOf("PUBLIC"), parsed.rule.levels)
        assertEquals(ISOTime.parse("2026-06-23T00:00:00.000Z"), parsed.rule.expiresAt)
        // A bare number is seconds.
        assertEquals(ISOTime.parse("2026-06-18T00:05:00.000Z"), parse("-time 300 mike").rule.expiresAt)
        assertEquals(ISOTime.parse("2026-06-18T00:30:00.000Z"), parse("-time 30m bob").rule.expiresAt)
        // A space between the count and the unit is joined, quoted or not. The reference reads
        // ONE token, so `-time 7 days` there is a seven-SECOND rule whose mask is the word
        // "days" — and with no mask of its own it lapses seconds later leaving no trace.
        assertEquals(ISOTime.parse("2026-06-25T00:00:00.000Z"), parse("-time \"7 days\" bob").rule.expiresAt)
        assertEquals(ISOTime.parse("2026-06-25T00:00:00.000Z"), parse("-time 7 days bob").rule.expiresAt)
        assertEquals("bob", parse("-time 7 days bob").rule.mask)
        // Only a bare count followed by a unit word is joined; a mask still reads as a mask.
        assertEquals("bob", parse("-time 30 bob").rule.mask)
    }

    @Test
    fun testTimeRefusesADurationOfZero() {
        // `-time 0` would expire the rule at the instant it was created: reported as added,
        // never matching, and with no index, removable only by mask until the server sweeps.
        for (input in listOf("-time 0 bob", "-time 0m bob", "-time 000 bob")) {
            assertEquals(true, error(input).contains("invalid -time"), input)
        }
    }

    @Test
    fun testRejectsAnAbsurdTimeRatherThanOverflowingTheDate() {
        assertEquals(true, error("-time 99999999999999999999 bob").contains("time"))
    }

    @Test
    fun testRejectsATimeThatIsNotADuration() {
        assertEquals(true, error("-time soon bob").contains("soon"))
        assertEquals(true, error("-time").contains("(missing)"))
    }

    @Test
    fun testRejectsRepliesAsUnsupported() {
        assertEquals(true, error("-replies *!*@*.irssi.org ALL").contains("replies"))
    }

    @Test
    fun testRejectsAnUnknownFlagAndAMissingPatternValue() {
        assertEquals(true, error("-bogus bob").contains("unknown flag"))
        assertEquals(true, error("bob -pattern").contains("pattern"))
    }

    @Test
    fun testRejectsASecondMask() {
        // Two bare tokens can't both be the sender; saying so beats silently ignoring one.
        assertEquals(true, error("bob alice").contains("unexpected argument"))
    }

    // MARK: - Scope (lurker #350)

    @Test
    fun testDefaultsToGlobal() {
        assertEquals(false, parse("bob").scopeNetwork)
    }

    @Test
    fun testNetworkFlagsScopeToTheCurrentNetwork() {
        assertEquals(true, parse("-network bob").scopeNetwork)
        assertEquals(true, parse("-net bob NOHIGHLIGHT").scopeNetwork)
    }

    @Test
    fun testGlobalFlagIsTheExplicitDefault() {
        assertEquals(false, parse("-global bob").scopeNetwork)
    }

    @Test
    fun testScopeFlagsDoNotLeakIntoTheOtherDimensions() {
        val parsed = parse("-network bob JOINS")
        assertEquals("bob", parsed.rule.mask)
        assertEquals(listOf("JOINS"), parsed.rule.levels)
        assertEquals(true, parsed.scopeNetwork)
    }

    // MARK: - Tokenizer

    @Test
    fun testTokenizerKeepsGroupsAndQuotedStringsWhole() {
        assertEquals(listOf("-pattern", "(a|b c)", "bob"), IgnoreArgs.tokenize("-pattern (a|b c) bob"))
        assertEquals(listOf("-pattern", "two words", "bob"), IgnoreArgs.tokenize("-pattern \"two words\" bob"))
        assertEquals(listOf("-pattern", "two words"), IgnoreArgs.tokenize("-pattern 'two words'"))
        // Nested groups close at the matching paren, not the first one.
        assertEquals(listOf("-pattern", "((a|b)|c)", "x"), IgnoreArgs.tokenize("-pattern ((a|b)|c) x"))
        // An unbalanced group runs to the end of the line rather than eating the parser.
        assertEquals(listOf("-pattern", "(a|b"), IgnoreArgs.tokenize("-pattern (a|b"))
        assertEquals(emptyList(), IgnoreArgs.tokenize("   "))
    }

    @Test
    fun testDurationRejectsNonAsciiDigits() {
        // The reference's `\d` is ASCII-only. ICU's is not, so a regex here would have
        // accepted `٧days` and stored an expiry the server's parser would have refused.
        assertNull(IgnoreArgs.duration("٧days"))
        assertNull(IgnoreArgs.duration("days"))
        assertNull(IgnoreArgs.duration("5 fortnights"))
        assertEquals(5000.0, IgnoreArgs.duration("5"))
    }

    // MARK: - The rule as the listing shows it

    @Test
    fun testSummaryNamesEveryDimensionTheRuleConstrains() {
        val rule = parse("-except -regexp -pattern (spam|ham) *zzz* #chan NICKS").rule
        val summary = rule.summary(global = true, formatted = formatted)
        assertTrue(summary.contains("*zzz*"), summary)
        assertTrue(summary.contains("[global]"), summary)
        assertTrue(summary.contains("NICKS"), summary)
        assertTrue(summary.contains("#chan"), summary)
        assertTrue(summary.contains("/(spam|ham)/"), summary)
        assertTrue(summary.contains("[except]"), summary)
        // A network-scoped rule doesn't claim to be global.
        assertFalse(rule.summary(global = false, formatted = formatted).contains("[global]"))
    }

    @Test
    fun testSummaryQuotesANonRegexPatternAndNotesAnExpiry() {
        val rule = parse("-pattern spam -time 1day bob").rule
        val summary = rule.summary(global = false, now = now, formatted = formatted)
        assertTrue(summary.contains("\"spam\""), summary)
        // Past tense once it has run out: a lapsed rule keeps its place in the listing (its
        // row is still on the server) but has stopped hiding anything.
        assertTrue(summary.contains("(expires "), summary)
        val later = rule.summary(global = false, now = now.plusSeconds(86_401), formatted = formatted)
        assertTrue(later.contains("(expired "), later)
        // `contains("expires")` alone would pass on an empty formatter result — the word is
        // this file's own literal. Assert the stamp itself is there, without pinning a format
        // the reader's locale and calendar decide.
        val marker = summary.indexOf("(expires ")
        if (marker < 0) fail("expected an expiry in: $summary")
        val stamp = summary.substring(marker + "(expires ".length).takeWhile { it != ')' }
        assertTrue(stamp.any { it.isDigit() }, "expected a formatted stamp in: $summary")
    }

    @Test
    fun testSummaryOfAMasklessRuleSaysAnyone() {
        assertTrue(parse("#idlerpg NOUNREAD").rule.summary(global = false, formatted = formatted).startsWith("*"))
    }

    // MARK: - Flag hygiene

    @Test
    fun testPatternRefusesToSwallowAFlagAsItsValue() {
        // The reference takes the next token whatever it is, so this stores a rule matching
        // the literal text "-network" and silently drops the scope — making global the rule
        // the user scoped to one connection. Refused here instead.
        assertEquals(true, error("bob -pattern -network").contains("got the flag"))
        assertEquals(true, error("-pattern -regexp foo").contains("got the flag"))
        // Only the known flags are refused: a pattern may still start with a dash.
        assertEquals("-_-", parse("bob -pattern -_-").rule.pattern)
    }

    @Test
    fun testAnUncompilableRegexIsRefusedHereRatherThanInSilence() {
        // The server drops a failed validation without answering, so an unclosed bracket would
        // otherwise be confirmed as added and never exist.
        assertEquals(true, error("-regexp -pattern [ bob").contains("invalid regex"))
        // A pattern that isn't a regex isn't compiled — `[` is a fine substring to look for.
        assertEquals("[", parse("-pattern [ bob").rule.pattern)
    }

    @Test
    fun testAnEmptyOrBlankMaskIsAnyoneRatherThanABlankSubject() {
        // `/ignore ""` and `/ignore " "` — the server's `strOrNull` and this client's matcher
        // both read those as "anyone", so keeping them as a mask would list a hide-everyone
        // rule under a blank subject. Both are explicit, so both are allowed through.
        for (input in listOf("\"\" JOINS", "\" \" JOINS")) {
            assertNull(parse(input).rule.mask, input)
            assertEquals(true, parse(input).rule.summary(global = true, formatted = formatted).startsWith("*  [global]"))
        }
    }

    @Test
    fun testABlankPatternIsDroppedRatherThanWideningTheRule() {
        // The server nulls a whitespace-only pattern, and a null pattern turns "hide what they
        // say about X" into "hide everything they say".
        assertNull(parse("bob -pattern \" \"").rule.pattern)
        assertEquals(true, error("-pattern \" \"").contains("names nobody"))
    }

    @Test
    fun testARuleThatNamesNobodyIsRefusedUnlessAnyoneWasAskedForExplicitly() {
        // Each of these parses cleanly into "hide everything from everyone" on the reference.
        for (input in listOf("-network", "-global", "-time 1d", "", "   ", "Quit", "all")) {
            assertEquals(true, error(input).contains("names nobody"), "expected a refusal from: $input")
        }
        // `*` said out loud is the escape hatch, and still works.
        assertNull(parse("* JOINS").rule.mask)
        assertEquals(listOf("JOINS"), parse("* JOINS").rule.levels)
        // As is any other dimension: a channel scope or a content pattern names a subject too.
        assertEquals(listOf("#idlerpg"), parse("#idlerpg NOUNREAD").rule.channels)
        assertEquals("spam", parse("-pattern spam").rule.pattern)
    }

    @Test
    fun testSubtractingAllIsRefusedRatherThanInverted() {
        // The reference resolves `-ALL` to the MAXIMAL hide set: the base expands to the
        // concrete levels first, so the removal then finds no `ALL` to take off.
        assertEquals(true, error("bob -ALL").contains("-ALL"))
        assertEquals(true, error("bob -all").contains("-ALL"))
    }

    @Test
    fun testAnOverLongPatternIsRefusedHereRatherThanInSilence() {
        val long = "x".repeat(IgnoreArgs.maxPatternLength + 1)
        assertEquals(true, error("bob -pattern $long").contains("exceeds"))
        assertNotNull(parse("bob -pattern ${long.dropLast(1)}"))
    }

    // MARK: - The wire

    // Port-only:

    /**
     * The limit being mirrored is the server's `pattern.length`, which counts UTF-16 units.
     * LurkerKit counts `Character`s, so 257 emoji pass there and are then dropped by the server
     * in silence — the very thing the check exists to prevent.
     */
    @Test
    fun testThePatternLimitCountsUtf16UnitsAsTheServerDoes() {
        val emoji = "🙂"
        assertEquals(2, emoji.length)
        assertEquals(true, error("bob -pattern ${emoji.repeat(IgnoreArgs.maxPatternLength / 2 + 1)}").contains("exceeds"))
        assertNotNull(parse("bob -pattern ${emoji.repeat(IgnoreArgs.maxPatternLength / 2)}").rule.pattern)
    }

    /** An expiry is `now` plus a whole number of milliseconds, exactly. */
    @Test
    fun testAnExpiryIsExactToTheMillisecond() {
        assertEquals(now.plusMillis(5), parse("-time 5ms bob").rule.expiresAt)
        assertEquals(ISOTime.parse("2126-05-25T00:00:00.000Z"), parse("-time 36500d bob").rule.expiresAt)
        assertEquals(true, error("-time 36501d bob").contains("invalid -time"))
    }

    // Waiting on LurkerClient (`ruleJSON`): testTheEncodedRuleRoundTripsThroughTheFrameDecoder,
    // testTheEncoderOmitsUnsetDimensionsRatherThanSendingNulls
}
