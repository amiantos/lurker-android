// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.NickCompletion
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.SpeakerMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks nick completion to the web client's `nickCompletion.ts`: speakers before
 * members, recency order, self excluded, departed speakers dropped in channels — plus
 * the token scanner and the addressing suffix the composer inserts.
 */
class NickCompletionTests {

    /**
     * A speaker map where each nick spoke at its index — so the LAST listed spoke most recently,
     * the way a buffer's history reads.
     */
    private fun spoke(vararg nicks: String): SpeakerMap =
        nicks.foldIndexed(SpeakerMap()) { index, map, nick -> map.record(nick, Instant.ofEpochSecond(index + 1L)) }

    // MARK: - Candidates

    @Test
    fun testRecentSpeakersLeadNewestFirstThenMembersAlphabetically() {
        val candidates = NickCompletion.candidates(
            speakers = spoke("alice", "bob"),
            members = listOf(Member(nick = "zoe"), Member(nick = "alice"), Member(nick = "bob"), Member(nick = "carol")),
            selfNick = "me",
            query = "",
            isChannel = true,
        )
        assertEquals(
            listOf("bob", "alice", "carol", "zoe"), candidates,
            "bob spoke last → first; then alice; members fill the rest A→Z",
        )
    }

    @Test
    fun testFilteringIsCaseInsensitiveAndKeepsRecencyOrder() {
        val candidates = NickCompletion.candidates(
            speakers = spoke("Anna", "arthur"),
            members = listOf(Member(nick = "Anna"), Member(nick = "arthur"), Member(nick = "AXEL"), Member(nick = "bob")),
            selfNick = null,
            query = "a",
            isChannel = true,
        )
        assertEquals(listOf("arthur", "Anna", "AXEL"), candidates)
    }

    @Test
    fun testYouAreNeverACandidate() {
        val candidates = NickCompletion.candidates(
            speakers = spoke("ME", "alice"),
            members = listOf(Member(nick = "me"), Member(nick = "alice")),
            selfNick = "me",
            query = "",
            isChannel = true,
        )
        assertEquals(listOf("alice"), candidates, "self is excluded as speaker and as member, case-folded")
    }

    /**
     * The web filters channel speakers by current membership: completing someone who
     * left addresses nobody. A DM has no member list, so its speakers pass unfiltered.
     */
    @Test
    fun testADepartedSpeakerIsDroppedInChannelsButNotDMs() {
        val speakers = spoke("ghost", "alice")
        val inChannel = NickCompletion.candidates(
            speakers = speakers, members = listOf(Member(nick = "alice")),
            selfNick = null, query = "", isChannel = true,
        )
        assertEquals(listOf("alice"), inChannel)

        val inDM = NickCompletion.candidates(
            speakers = speakers, members = emptyList(),
            selfNick = null, query = "", isChannel = false,
        )
        assertEquals(listOf("alice", "ghost"), inDM)
    }

    @Test
    fun testTheCapHolds() {
        val members = listOf("alice", "bob", "carol", "dave", "erin", "frank").map { Member(nick = it) }
        val candidates = NickCompletion.candidates(
            speakers = spoke("alice", "bob"), members = members, selfNick = null, query = "", isChannel = true,
        )
        assertEquals(listOf("bob", "alice", "carol", "dave"), candidates, "capped at four")
    }

    /** A speaker is offered as they last spelled their nick, not as the map's lowercased key. */
    @Test
    fun testASpeakerKeepsTheirSpelling() {
        assertEquals(
            listOf("Alice"),
            NickCompletion.candidates(speakers = spoke("Alice"), members = emptyList(), selfNick = null, query = "al", isChannel = false),
        )
    }

    // MARK: - Token scanning

    @Test
    fun testAnAtTokenUnderTheCaretIsActive() {
        val token = NickCompletion.activeMention("hey @al", caret = 7)
        assertEquals(NickCompletion.MentionToken(start = 4, end = 7, query = "al"), token)
    }

    @Test
    fun testABareAtOpensAnEmptyQuery() {
        assertEquals("", NickCompletion.activeMention("@", caret = 1)?.query)
    }

    /**
     * Caret mid-word: the query answers what's typed so far, but the token spans the
     * whole word — completion replaces all of it, so `@al|ice` can't become "aliceice".
     */
    @Test
    fun testACaretMidWordFiltersToTheCaretButSpansTheWord() {
        val token = NickCompletion.activeMention("@alice more", caret = 3)
        assertEquals(NickCompletion.MentionToken(start = 0, end = 6, query = "al"), token)
    }

    @Test
    fun testAnEmailShapedWordIsNotAMention() {
        assertNull(
            NickCompletion.activeMention("mail user@host", caret = 14),
            "the @ must open the word — matching the web's startsWith('@')",
        )
    }

    @Test
    fun testACaretOutsideTheTokenDeactivatesIt() {
        assertNull(
            NickCompletion.activeMention("@al done ", caret = 9),
            "past the token's word there is no active mention",
        )
        assertNull(NickCompletion.activeMention("plain text", caret = 0))
    }

    // MARK: - Bare words (#57)

    /**
     * The web's mobile strip: two letters of a nick ask without an `@`, and completion
     * replaces the word from its first letter.
     */
    @Test
    fun testABareWordOfTwoLettersAsks() {
        assertEquals(
            NickCompletion.MentionToken(start = 4, end = 6, query = "al"),
            NickCompletion.activeMention("hey al", caret = 6),
        )
        assertEquals(
            NickCompletion.MentionToken(start = 0, end = 2, query = "al"),
            NickCompletion.activeMention("al", caret = 2),
        )
        assertEquals("al", NickCompletion.activeMention("al more", caret = 2)?.query, "the end of a word, not of the text")
    }

    @Test
    fun testABareWordOfOneCharacterDoesNot() {
        assertNull(NickCompletion.activeMention("hey a", caret = 5), "every \"I\" and \"a\" would float the pills")
        assertNull(
            NickCompletion.activeMention("hey \uD83D\uDC4D", caret = 6),
            "one emoji is one character, not its two UTF-16 units",
        )
    }

    /**
     * A caret placed inside a word is editing it: pills there would float over every typo
     * fix, and a pick would replace the rest of the word ("al|ready" → "alice ").
     */
    @Test
    fun testABareCaretInsideAWordDoesNotAsk() {
        assertNull(NickCompletion.activeMention("I already said", caret = 4))
        assertNull(NickCompletion.activeMention("thanks alice's idea", caret = 9))
    }

    /**
     * Completion replaces the whole word, so a word holding an `@` past its start never
     * asks, in either shape: it would take the `@host` with it.
     */
    @Test
    fun testAWordWithAnAtPastItsStartDoesNotAsk() {
        assertNull(NickCompletion.activeMention("mail user@host", caret = 14))
        assertNull(NickCompletion.activeMention("@alice@host.com", caret = 3), "even after the caret, an @… would lose its tail")
        assertNull(NickCompletion.activeMention("@a@b", caret = 4))
    }

    @Test
    fun testACommandOrChannelWordDoesNotAsk() {
        assertNull(NickCompletion.activeMention("/jo", caret = 3))
        assertNull(NickCompletion.activeMention("//jo", caret = 4), "an escaped command")
        for (sigil in listOf("#", "&", "+", "!")) {
            assertNull(NickCompletion.activeMention("see ${sigil}li", caret = 7), sigil)
        }
    }

    /**
     * A command's arguments are keys, passwords and new nicks: a bare word stays out of them.
     * `/me`'s argument is speech, `//` escapes a command, and an `@` asks anywhere.
     */
    @Test
    fun testACommandLineAsksOnlyForMeOrAnAt() {
        assertNull(NickCompletion.activeMention("/msg NickServ IDENTIFY hu", caret = 25))
        assertNull(
            NickCompletion.activeMention("  /nick al", caret = 10),
            "the composer trims, so leading whitespace is still a command",
        )
        assertEquals("al", NickCompletion.activeMention("/me waves at al", caret = 15)?.query)
        assertEquals("al", NickCompletion.activeMention("/ME waves at al", caret = 15)?.query)
        assertEquals(
            "al", NickCompletion.activeMention("/shrug ask al", caret = 13)?.query,
            "/shrug's argument is speech too",
        )
        assertNull(NickCompletion.activeMention("/meow al", caret = 8), "a verb, not a prefix")
        assertEquals("al", NickCompletion.activeMention("//x al", caret = 6)?.query)
        assertEquals("al", NickCompletion.activeMention("/topic hi @al", caret = 13)?.query)
    }

    @Test
    fun testAnAtStillAsksFromItsFirstKeystrokeAnywhereInTheWord() {
        assertEquals("a", NickCompletion.activeMention("hey @a", caret = 6)?.query, "the bare threshold never applies to an @")
        assertEquals("al", NickCompletion.activeMention("@alice", caret = 3)?.query)
    }

    // MARK: - Addressing suffix

    private fun suffix(start: Int, text: String, punctuation: String = ":"): String =
        NickCompletion.addressingSuffix(beforeTokenAt = start, text = text, punctuation = punctuation)

    @Test
    fun testLineStartAddressesWithColonMidSentenceWithSpace() {
        assertEquals(": ", suffix(0, "@al"))
        // Any leading whitespace still counts as line start (web: /(^|\n)\s*$/)…
        assertEquals(": ", suffix(2, "  @al"))
        assertEquals(": ", suffix(1, "\t@al"))
        // …and so does the start of a wrapped line.
        assertEquals(": ", suffix(6, "hello\n@al"))
        assertEquals(" ", suffix(4, "cc: @al"))
    }

    // MARK: - The suffix is a setting (lurker-ios#133, lurker#835)
    //
    // Ported from the web's `MessageInput.completion.test.ts` (the lurker#835 block under
    // `describe('nicks')`). The web exercises the four paths that seed a line-start
    // session — picker, in-place Tab, strip, Reply; iOS has two (the @ picker and
    // Reply), and both read through the same pair of helpers tested here.

    private fun settings(value: String?): Settings =
        Settings(
            registry = mapOf(
                "input.completion.nick_suffix" to SettingOption(
                    key = "input.completion.nick_suffix", label = "Nick completion suffix",
                    description = "", type = SettingType.String, default = SettingValue.String(":"),
                ),
            ),
            values = value?.let { mapOf("input.completion.nick_suffix" to SettingValue.String(it)) } ?: emptyMap(),
        )

    @Test
    fun testAddressingPunctuationComesFromTheSetting() {
        assertEquals(",", NickCompletion.addressPunctuation(settings(",")))
        assertEquals(", ", suffix(0, "@al", NickCompletion.addressPunctuation(settings(","))))
        // Mid-line is a bare space whatever the setting says — the setting only touches
        // the line-start form.
        assertEquals(" ", suffix(4, "cc: @al", ","))
    }

    @Test
    fun testAnEmptyPunctuationStillAddressesWithASpace() {
        // The path most likely to be handed "" and drop the space with it.
        assertEquals("", NickCompletion.addressPunctuation(settings("")))
        assertEquals(" ", suffix(0, "@al", ""))
    }

    @Test
    fun testAnUnsetOrUnknownKeyFallsBackToTheRegistryDefault() {
        assertEquals(
            ":", NickCompletion.addressPunctuation(settings(null)),
            "no stored value — the registry default",
        )
        assertEquals(
            ":", NickCompletion.addressPunctuation(Settings()),
            "a server too old to know the key, or the window before bootstrap",
        )
    }

    @Test
    fun testTheStoredValueNormalisesTheSameWayForAControlAsForTheCompletion() {
        // The settings pull-down matches the stored value against the forms it offers with
        // this overload, so a `", "` written from the web checks the `","` row rather than
        // showing up as a custom value beside an identical-looking one.
        assertEquals(",", NickCompletion.addressPunctuation(", "))
        assertEquals("", NickCompletion.addressPunctuation(" "))
        assertEquals("->", NickCompletion.addressPunctuation("->"))
    }

    @Test
    fun testTrailingWhitespaceInTheSettingIsDroppedNotDoubled() {
        // The description shows the form as `nick: `, so typing exactly that in is the
        // natural mistake; and a quoted " " is the natural way to ask for "space only".
        assertEquals(",", NickCompletion.addressPunctuation(settings(", ")))
        assertEquals(", ", suffix(0, "@al", NickCompletion.addressPunctuation(settings(", "))))
        assertEquals("", NickCompletion.addressPunctuation(settings(" ")))
        assertEquals(" ", suffix(0, "@al", NickCompletion.addressPunctuation(settings(" "))))
    }

    @Test
    fun testThePunctuationIsNamedForVoiceOver() {
        // The settings pull-down's labels are samples of the form — `nick:`, `nick,` — which
        // differ only by a trailing mark, and VoiceOver reads none of them at its default
        // verbosity. Names are what it reads instead.
        assertEquals("Colon", NickCompletion.spokenPunctuation(":"))
        assertEquals("Comma", NickCompletion.spokenPunctuation(","))
        assertEquals("Semicolon", NickCompletion.spokenPunctuation(";"))
        assertEquals("Space only", NickCompletion.spokenPunctuation(""))
    }

    @Test
    fun testAFreeFormPunctuationIsNamedByTheSameRule() {
        // The value is free-form on the web, and a mark the phone doesn't offer is exactly the
        // one a VoiceOver user can't discover any other way — naming only the four would leave
        // this one mute, or announced as "custom", which says nothing about what is in force.
        assertEquals("Semicolon p", NickCompletion.spokenPunctuation(";p"))
        assertEquals("Hyphen-minus greater-than sign", NickCompletion.spokenPunctuation("->"))
        assertEquals("Exclamation mark", NickCompletion.spokenPunctuation("!"))
        // A letter or a digit already reads aloud; "latin small letter p" is not an
        // improvement. Nor is sentence case, which would announce a capital P — a different
        // suffix from the one that is set.
        assertEquals("p", NickCompletion.spokenPunctuation("p"))
        assertEquals("2", NickCompletion.spokenPunctuation("2"))
        assertEquals("p semicolon", NickCompletion.spokenPunctuation("p;"))
    }

    // MARK: - Reply's already-addressed test

    @Test
    fun testReplyRecognisesADraftAddressedUnderTheConfiguredForm() {
        assertTrue(
            NickCompletion.isAddressed("bob, sure", nick = "bob", punctuation = ","),
            "a second Reply must not stack a second `bob, `",
        )
    }

    @Test
    fun testReplyRecognisesADraftAddressedUnderAnotherForm() {
        // The draft can predate a settings change, or come from a client with its own form
        // — drafts sync — so this must not become `bob, bob: sure`.
        assertTrue(NickCompletion.isAddressed("bob: sure", nick = "bob", punctuation = ","))
        assertTrue(
            NickCompletion.isAddressed("bob!! sure", nick = "bob", punctuation = ","),
            "any run of punctuation counts, not just one mark",
        )
    }

    @Test
    fun testReplyStillAddressesADraftThatMerelyOpensWithTheNickAsAWord() {
        // "will" is a nick and a word. Under any non-empty suffix the bare `will ` form is
        // NOT an address — the check demands punctuation, not just the nick.
        assertFalse(NickCompletion.isAddressed("will you come?", nick = "will", punctuation = ":"))
    }

    @Test
    fun testUnderAnEmptyPunctuationTheBareNickFormCountsAsAddressed() {
        // With "space only" the addressed form and the nick-as-a-word form are the same
        // text; that ambiguity is the convention's, and Reply follows it rather than
        // producing `bob bob is wrong`.
        assertTrue(NickCompletion.isAddressed("bob is wrong", nick = "bob", punctuation = ""))
    }

    @Test
    fun testReplyDoesNotMistakeALongerNickForTheAddressedOne() {
        // `bob_` is bob's ghost and `bobł` is someone else. The mark run has to exclude
        // nick characters — Unicode letters and the RFC 2812 specials — not just ASCII `\w`.
        assertFalse(NickCompletion.isAddressed("bob_: hi", nick = "bob", punctuation = ":"))
        assertFalse(NickCompletion.isAddressed("bobł hi", nick = "bob", punctuation = ":"))
        assertFalse(
            NickCompletion.isAddressed("bobł hi", nick = "bob", punctuation = ""),
            "and an empty setting must not let a letter pass as the space either",
        )
        assertFalse(NickCompletion.isAddressed("bob2: hi", nick = "bob", punctuation = ":"))
    }

    @Test
    fun testTheNickCharacterSetIsExactlyTheWebs() {
        // `\p{L}\p{N}` and nothing else. A COMBINING mark is not `\p{L}`, so the web reads
        // `bob` + punctuation here and this must agree — no real draft opens this way, which
        // is precisely why a quiet divergence would never be found again.
        assertTrue(NickCompletion.isAddressed("bob\u0301 hi", nick = "bob", punctuation = ":"))
        // `Ⅳ` is `Nl` and `²` is `No` — digits to `\p{N}`, but not to `.decimalDigits`.
        assertFalse(NickCompletion.isAddressed("bob\u2163 hi", nick = "bob", punctuation = ":"))
        assertFalse(NickCompletion.isAddressed("bob\u00B2 hi", nick = "bob", punctuation = ":"))
    }

    @Test
    fun testAMultiCharacterMarkIsRecognisedVerbatim() {
        // `->` ends in a nick special, so the punctuation-run arm can't see it; the
        // configured mark counts on its own, whatever it is.
        assertTrue(NickCompletion.isAddressed("bob-> sure", nick = "bob", punctuation = "->"))
        assertFalse(NickCompletion.isAddressed("bob-x sure", nick = "bob", punctuation = "->"))
    }

    @Test
    fun testAddressedTestIsCaseInsensitiveAndNeedsMoreThanTheNick() {
        assertTrue(NickCompletion.isAddressed("BOB: sure", nick = "bob", punctuation = ":"))
        assertTrue(NickCompletion.isAddressed("bob: sure", nick = "BOB", punctuation = ":"))
        assertFalse(
            NickCompletion.isAddressed("bob:", nick = "bob", punctuation = ":"),
            "the form is `nick: ` — a draft that is only the mark isn't addressed yet",
        )
        assertFalse(NickCompletion.isAddressed("bob", nick = "bob", punctuation = ""))
        assertFalse(NickCompletion.isAddressed("", nick = "bob", punctuation = ":"))
        assertFalse(NickCompletion.isAddressed("bob: hi", nick = "", punctuation = ":"))
    }

    // Port-only: where a Kotlin `String` and a Swift one could part ways — offsets counted in
    // UTF-16 units against a walk by scalar, Foundation's whitespace against Kotlin's, the
    // Unicode names and folds read through the JDK. Every answer is the Swift's own, taken from
    // LurkerKit compiled on a Mac.

    @Test
    fun testACaretInsideAnEmojiReadsTheHalfAsAReplacementCharacter() {
        val text = "@\uD83D\uDE00\u3000"
        assertNull(NickCompletion.activeMention(text, caret = 0))
        assertEquals(NickCompletion.MentionToken(start = 0, end = 3, query = ""), NickCompletion.activeMention(text, caret = 1))
        assertEquals(NickCompletion.MentionToken(start = 0, end = 3, query = "\uFFFD"), NickCompletion.activeMention(text, caret = 2))
        assertEquals(NickCompletion.MentionToken(start = 0, end = 3, query = "\uD83D\uDE00"), NickCompletion.activeMention(text, caret = 3))
        assertNull(NickCompletion.activeMention(text, caret = 4), "the ideographic space ended the word")
    }

    @Test
    fun testAWordEndsAtFoundationsWhitespaceNotKotlins() {
        // A zero-width space, a no-break space and NEL are whitespace to Foundation; `trim()` and
        // `isWhitespace()` know only the second. U+001C is whitespace to those two and to
        // nothing in Foundation.
        val token = NickCompletion.MentionToken(start = 2, end = 5, query = "al")
        assertEquals(token, NickCompletion.activeMention("a\u200B@al", caret = 5))
        assertEquals(token, NickCompletion.activeMention("a\u00A0@al", caret = 5))
        assertEquals(token, NickCompletion.activeMention("a\u0085@al x", caret = 5))
        assertNull(NickCompletion.activeMention("a\u001C@al", caret = 5))

        assertEquals(": ", suffix(1, "\u200B@al"))
        assertEquals(" ", suffix(1, "\u001C@al"))
        // Only a line feed starts a line; any other break is whitespace to look past.
        assertEquals(" ", suffix(3, "hi\u2028@al"))
        assertEquals(" ", suffix(3, "x\u0085 @al"))

        assertEquals(":", NickCompletion.addressPunctuation(":\u200B"))
        assertEquals(":", NickCompletion.addressPunctuation(":\u0085"))
        assertEquals(":\u001C", NickCompletion.addressPunctuation(":\u001C"))
    }

    @Test
    fun testAMarkWithNoPublishedNameIsReadAsItself() {
        assertEquals("Rightwards arrow", NickCompletion.spokenPunctuation("\u2192"))
        assertEquals("Grinning face", NickCompletion.spokenPunctuation("\uD83D\uDE00"))
        assertEquals("Colon zero width space", NickCompletion.spokenPunctuation(":\u200B"))
        // A control character and a private-use one have no name in the Unicode Character
        // Database. The JDK would offer `BEL` and `PRIVATE USE AREA E000`.
        assertEquals("\u0007", NickCompletion.spokenPunctuation("\u0007"))
        assertEquals("\uE000", NickCompletion.spokenPunctuation("\uE000"))
        assertEquals("Colon \u0085", NickCompletion.spokenPunctuation(":\u0085"))
        // A letter beyond ASCII is still a letter, and a combining mark counts as one.
        assertEquals("\u00E9 semicolon", NickCompletion.spokenPunctuation("\u00E9;"))
        assertEquals("e \u0301", NickCompletion.spokenPunctuation("e\u0301"))
    }

    @Test
    fun testTheQueryFoldsBeyondAsciiButANickIsStillMatchedLiterally() {
        fun candidates(query: String): List<String> = NickCompletion.candidates(
            speakers = spoke("\u00D1u"),
            members = listOf("\u00C9mile", "\u00D1u", "bob", "\uD83D\uDE00bob", "\u00E9va").map { Member(nick = it) },
            selfNick = null, query = query, isChannel = true,
        )
        assertEquals(listOf("\u00C9mile", "\u00E9va"), candidates("\u00E9"))
        assertEquals(listOf("\u00C9mile", "\u00E9va"), candidates("\u00C9"))
        assertEquals(listOf("\u00C9mile"), candidates("\u00C9M"))
        assertEquals(listOf("\u00D1u"), candidates("\u00F1"))
        assertEquals(emptyList(), candidates("e"), "an accent is not a case")
    }

    @Test
    fun testAnAddressIsMeasuredInScalarsAndCutInUtf16() {
        // The address's length is counted in scalars; an emoji in the nick is one scalar and two
        // UTF-16 units, so cutting by the count would leave half of what follows it.
        val nick = "\uD83D\uDE00bob"
        assertEquals("hi", NickCompletion.removingAddress("$nick: hi", nick = nick, punctuation = ":"))
        assertEquals("hi", NickCompletion.removingReplyAddress("$nick: hi", nick = nick))
        val shouted = "\uD83D\uDE00BOB\uD83D\uDE00  hi"
        assertEquals(" hi", NickCompletion.removingAddress(shouted, nick = nick, punctuation = ","), "the one whitespace after the mark")
        assertEquals("hi", NickCompletion.removingReplyAddress(shouted, nick = nick), "every one of them")
        // The nick folds ASCII only, so the capital is somebody else…
        assertFalse(NickCompletion.isAddressed("\u00C9mile: hi", nick = "\u00E9mile", punctuation = ":"))
        assertTrue(NickCompletion.isAddressed("\u00E9mile: hi", nick = "\u00E9mile", punctuation = ":"))
        // …and the whitespace after the mark is Foundation's.
        assertEquals("hi", NickCompletion.removingAddress("bob\u200Bhi", nick = "bob", punctuation = ""))
        assertEquals("bob\u200Bhi", NickCompletion.removingReplyAddress("bob\u200Bhi", nick = "bob"), "a mark is still required")
        assertEquals("\u3000hi", NickCompletion.removingAddress("bob:\u00A0\u3000hi", nick = "bob", punctuation = ":"))
        assertEquals("hi", NickCompletion.removingReplyAddress("bob:\u00A0\u3000hi", nick = "bob"))
    }
}
