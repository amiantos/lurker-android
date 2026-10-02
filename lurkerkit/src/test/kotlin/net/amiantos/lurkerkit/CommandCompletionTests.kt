// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.ArgKind
import net.amiantos.lurkerkit.commands.CommandCompletion
import net.amiantos.lurkerkit.commands.CommandRegistry
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.support.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the command-completion classifier: what the caret is sitting in (a verb, a channel
 * argument, a nick argument, or nothing the app can suggest for) and the token range a pick
 * replaces. UTF-16 offsets throughout, like `NickCompletion`.
 */
class CommandCompletionTests {

    // MARK: - Command name

    @Test
    fun testBareSlashOffersEveryCommand() {
        assertEquals(
            CommandCompletion.Context.Command(query = "", range = TextRange.of(location = 0, length = 1)),
            CommandCompletion.context("/", caret = 1),
        )
    }

    @Test
    fun testTypingTheVerbFiltersCommands() {
        assertEquals(
            CommandCompletion.Context.Command(query = "jo", range = TextRange.of(location = 0, length = 3)),
            CommandCompletion.context("/jo", caret = 3),
        )
    }

    @Test
    fun testCaretMidVerbSwallowsTheWholeVerbToken() {
        // Caret after "/jo" inside "/join": the range still covers the whole verb, so
        // completing replaces "/join" rather than welding onto "in".
        assertEquals(
            CommandCompletion.Context.Command(query = "jo", range = TextRange.of(location = 0, length = 5)),
            CommandCompletion.context("/join", caret = 3),
        )
    }

    // MARK: - Channel arguments

    @Test
    fun testChannelArgumentUnderCaret() {
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "join", index = 0, kind = ArgKind.Channel, query = "#li",
                range = TextRange.of(location = 6, length = 3),
            ),
            CommandCompletion.context("/join #li", caret = 9),
        )
    }

    @Test
    fun testEmptyChannelSlotAfterTheSpace() {
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "join", index = 0, kind = ArgKind.Channel, query = "",
                range = TextRange.of(location = 6, length = 0),
            ),
            CommandCompletion.context("/join ", caret = 6),
        )
    }

    @Test
    fun testChannelArgumentMidWordSwallowsTheTail() {
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "join", index = 0, kind = ArgKind.Channel, query = "#li",
                range = TextRange.of(location = 6, length = 6),
            ),
            CommandCompletion.context("/join #linux", caret = 9),
        )
    }

    // MARK: - Nick arguments

    @Test
    fun testNickArgumentUnderCaret() {
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "msg", index = 0, kind = ArgKind.Nick, query = "al",
                range = TextRange.of(location = 5, length = 2),
            ),
            CommandCompletion.context("/msg al", caret = 7),
        )
    }

    @Test
    fun testInviteSecondArgumentIsAChannel() {
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "invite", index = 1, kind = ArgKind.Channel, query = "#",
                range = TextRange.of(location = 12, length = 1),
            ),
            CommandCompletion.context("/invite bob #", caret = 13),
        )
    }

    /**
     * Completion reads the typed words now rather than counting them. For every single-form
     * command that must come to the same thing — including a trailing `rest` slot, which keeps
     * answering past its end.
     */
    @Test
    fun testARestSlotKeepsAnsweringPastTheEnd() {
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "op", index = 2, kind = ArgKind.Nick, query = "c",
                range = TextRange.of(location = 8, length = 1),
            ),
            CommandCompletion.context("/op a b c", caret = 9),
        )
    }

    // MARK: - No completion

    @Test
    fun testFreeTextSlotYieldsNothing() {
        // `/me`'s argument is free text — no chips here (the composer falls through to
        // @-mention detection).
        assertNull(CommandCompletion.context("/me hello", caret = 9))
    }

    @Test
    fun testChannelKeySlotYieldsNothing() {
        // The second `/join` argument is an opaque key.
        assertNull(CommandCompletion.context("/join #x k", caret = 10))
    }

    @Test
    fun testNewNickSlotYieldsNothing() {
        assertNull(CommandCompletion.context("/nick bo", caret = 8))
    }

    @Test
    fun testUnknownVerbYieldsNothing() {
        assertNull(CommandCompletion.context("/frob x", caret = 7))
    }

    @Test
    fun testEscapeYieldsNothing() {
        assertNull(CommandCompletion.context("//slap", caret = 6))
    }

    @Test
    fun testPlainTextYieldsNothing() {
        assertNull(CommandCompletion.context("hello", caret = 5))
    }

    // MARK: - Registry

    @Test
    fun testMatchingIsPrefixOnCanonicalNames() {
        assertEquals(listOf("join"), CommandRegistry.matching("j").map { it.name })
    }

    @Test
    fun testMatchingEmptyReturnsTheFeaturedStarterSet() {
        // A bare `/` shows a cross-category starter set, not just the first table block.
        val names = CommandRegistry.matching("").map { it.name }
        assertEquals(CommandRegistry.featured, names)
        assertTrue(names.contains("join"), "discovery must surface /join, not only Messaging")
    }

    @Test
    fun testAliasesResolveToOneSpec() {
        // `/query` is an alias of `/msg`; the chips shouldn't offer both.
        assertEquals("msg", CommandRegistry.spec("query")?.name)
        assertFalse(CommandRegistry.matching("q").any { it.name == "query" })
    }

    // MARK: - ChannelName

    @Test
    fun testChannelFoldMatchesRegardlessOfSigil() {
        // The `#` a user hasn't typed yet shouldn't hide a channel: `li` and `#li` both fold
        // to the same needle that prefix-matches `#linux`.
        assertEquals("linux", ChannelName.fold("#Linux"))
        assertTrue(ChannelName.fold("#linux").startsWith(ChannelName.fold("li")))
        assertTrue(ChannelName.fold("#linux").startsWith(ChannelName.fold("#li")))
    }

    @Test
    fun testChannelEnsurePrefix() {
        assertEquals("#linux", ChannelName.ensurePrefix("linux"))
        assertEquals("&local", ChannelName.ensurePrefix("&local"))
        assertEquals("+nomodes", ChannelName.ensurePrefix("+nomodes"))
        assertEquals("!12345safe", ChannelName.ensurePrefix("!12345safe"))
    }

    /**
     * The one classification both tiers mirror (`shared/channels.ts:isChannelTarget`).
     * Asserted directly, not only through its callers, because a `#`-only twin of it is the
     * bug that keeps recurring (lurker#724, lurker-ios#98).
     */
    @Test
    fun testChannelTargetCountsAllFourSigils() {
        for (target in listOf("#chan", "&local", "+nomodes", "!12345safe")) {
            assertTrue(ChannelName.isChannelTarget(target), target)
            assertEquals(BufferKind.Channel, BufferKind.of(networkId = 1, target = target), target)
        }
        for (target in listOf("", "alice", ":server:1", "chan#notleading")) {
            assertFalse(ChannelName.isChannelTarget(target), target)
        }
    }

    /**
     * The sort/display strip, the web's `stripChannelPrefix`. The buffer list's sort key
     * hand-wrote half the set (`#&`), so `+`/`!` channels sorted under their sigil — above
     * every named channel — while the web sorted them by name (lurker-ios#98).
     */
    @Test
    fun testStripSigilsTakesEveryLeadingSigil() {
        assertEquals("linux", ChannelName.stripSigils("#linux"))
        assertEquals("local", ChannelName.stripSigils("&local"))
        assertEquals("nomodes", ChannelName.stripSigils("+nomodes"))
        assertEquals("12345safe", ChannelName.stripSigils("!12345safe"))
        // Every LEADING one — `##anime` sorts as "anime", not "#anime".
        assertEquals("anime", ChannelName.stripSigils("##anime"))
        // Interior sigils are part of the name; a bare nick is untouched.
        assertEquals("chan#notleading", ChannelName.stripSigils("chan#notleading"))
        assertEquals("alice", ChannelName.stripSigils("alice"))
        assertEquals("", ChannelName.stripSigils(""))
    }

    // Port-only: answers taken from the Swift.

    @Test
    fun testACaretInsideASurrogatePairLeavesAReplacementCharacterNotHalfAnEmoji() {
        // LurkerKit decodes the slice up to the caret, which repairs the lone high surrogate.
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "msg", index = 0, kind = ArgKind.Nick, query = "\uFFFD",
                range = TextRange.of(location = 5, length = 4),
            ),
            CommandCompletion.context("/msg 👍al", caret = 6),
        )
    }

    @Test
    fun testTheCompleterSplitsOnFoundationsWhitespace() {
        // `CharacterSet.whitespacesAndNewlines` here, not `Character.isWhitespace` as in the
        // parser: a zero-width space and a no-break space both separate.
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "msg", index = 0, kind = ArgKind.Nick, query = "al",
                range = TextRange.of(location = 6, length = 2),
            ),
            CommandCompletion.context("\u200B/msg al", caret = 8),
        )
        assertEquals(
            CommandCompletion.Context.Argument(
                verb = "msg", index = 0, kind = ArgKind.Nick, query = "al",
                range = TextRange.of(location = 5, length = 2),
            ),
            CommandCompletion.context("/msg\u00A0al", caret = 7),
        )
    }
}
