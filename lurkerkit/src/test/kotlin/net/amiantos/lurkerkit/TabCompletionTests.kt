// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.TabCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-place Tab completion (lurker-android#63), pinned against the web composer's Tab handler. */
class TabCompletionTests {
    private val roster = listOf("alice", "alfred", "bob")

    private fun nicks(query: String): List<String> =
        roster.filter { it.lowercase().startsWith(query.lowercase()) }

    private fun begin(text: String, caret: Int? = null, channels: List<String> = emptyList(), punctuation: String = ":"): TabCompletion? =
        TabCompletion.begin(
            text = text, caret = caret ?: text.length, nicks = ::nicks, channels = { channels }, punctuation = punctuation,
        )

    @Test
    fun testANickOpeningTheLineIsAddressed() {
        val edit = begin("al")?.edit
        assertEquals("alice: ", edit?.text)
        assertEquals(7, edit?.caret)
    }

    @Test
    fun testANickMidSentenceTakesNothing() {
        val edit = begin("thanks al")?.edit
        assertEquals("thanks alice", edit?.text)
        assertEquals(12, edit?.caret)
    }

    @Test
    fun testTheSuffixFollowsTheSetting() {
        assertEquals("alice, ", begin("al", punctuation = ",")?.edit?.text)
        // "Space only" is the empty punctuation.
        assertEquals("alice ", begin("al", punctuation = "")?.edit?.text)
    }

    @Test
    fun testALineStartAfterANewlineCounts() {
        assertEquals("hi all\n  alice: ", begin("hi all\n  al")?.edit?.text)
    }

    @Test
    fun testAnAtIsDroppedFromTheMatchAndTheInsertion() {
        assertEquals("hey alice", begin("hey @al")?.edit?.text)
        assertNull(begin("@"))
    }

    @Test
    fun testTheWholeWordIsReplacedNotJustUpToTheCaret() {
        // al|xyz: the token runs past the caret, so its tail goes too.
        val edit = begin("alxyz tail", caret = 2)?.edit
        assertNull(edit, "the word is alxyz, and nobody's nick starts that way")
        assertEquals("alice: ", begin("al", caret = 1)?.edit?.text)
    }

    @Test
    fun testTabCyclesAndShiftTabGoesBack() {
        val completion = assertNotNull(begin("al"))
        assertEquals("alice: ", completion.edit.text)
        assertEquals("alfred: ", completion.cycle(backward = false).text)
        assertEquals("alice: ", completion.cycle(backward = false).text)
        assertEquals("alfred: ", completion.cycle(backward = true).text)
    }

    @Test
    fun testACompletionContinuesOnlyWhereItLeftTheCaret() {
        val completion = assertNotNull(begin("hi al"))
        val edit = completion.edit
        assertTrue(completion.continues(text = edit.text, caret = edit.caret))
        assertFalse(completion.continues(text = edit.text, caret = 0))
        assertFalse(completion.continues(text = edit.text + "x", caret = edit.caret + 1))
    }

    @Test
    fun testTheTextAfterTheWordStays() {
        val edit = begin("al is here", caret = 2)?.edit
        assertEquals("alice:  is here", edit?.text)
        assertEquals(7, edit?.caret)
    }

    @Test
    fun testAHashCompletesAChannelInTheOrderGiven() {
        val channels = listOf("#lurker", "#linux", "#Lounge", "&local")
        val completion = assertNotNull(begin("join #l", channels = channels))
        assertEquals("join #lurker", completion.edit.text)
        assertEquals("join #linux", completion.cycle(backward = false).text)
        // Case-insensitive, and never a suffix — even opening the line.
        assertEquals("join #Lounge", completion.cycle(backward = false).text)
        assertEquals("#lurker", begin("#lu", channels = channels)?.edit?.text)
    }

    @Test
    fun testOnlyAHashCompletesChannels() {
        // `&lo` is prose as far as completion goes: it asks for nicks, and none start that way.
        assertNull(begin("&lo", channels = listOf("&local")))
    }

    @Test
    fun testNothingUnderTheCaretOrNoMatchIsNil() {
        assertNull(begin(""))
        assertNull(begin("hi ", caret = 3))
        assertNull(begin("zz"))
        assertNull(begin("#zz", channels = listOf("#lurker")))
    }

    @Test
    fun testChannelsAreAskedOnlyForAHash() {
        // Most Tabs complete a nick; the network's channels are only worth gathering for a `#`.
        var asked = 0
        val channels = { asked += 1; listOf("#lurker") }
        TabCompletion.begin(text = "al", caret = 2, nicks = ::nicks, channels = channels, punctuation = ":")
        assertEquals(0, asked)
        TabCompletion.begin(text = "#lu", caret = 3, nicks = ::nicks, channels = channels, punctuation = ":")
        assertEquals(1, asked)
    }

    @Test
    fun testOffsetsAreUTF16() {
        // An astral emoji before the word is two UTF-16 units.
        val edit = begin("😀 al")?.edit
        assertEquals("😀 alice", edit?.text)
        assertEquals("😀 alice".length, edit?.caret)
    }
}
