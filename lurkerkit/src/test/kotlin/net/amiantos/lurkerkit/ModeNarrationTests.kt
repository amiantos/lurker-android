// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ModeNarration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Mode-line narration — ported from the web's `shared/modeNarration.test.ts`, case for case,
 * so the two clients say the same thing about the same row.
 */
class ModeNarrationTests {

    /** The narration as a plain string, for readable assertions. */
    private fun say(modes: List<ModeChange>, rawText: String? = null): String =
        ModeNarration.describe(modes, rawText = rawText).joinToString("") { segment ->
            when (segment) {
                is ModeNarration.Segment.Text -> segment.text
                is ModeNarration.Segment.Arg -> segment.arg
                is ModeNarration.Segment.Nick -> segment.nick
            }
        }

    private fun prefix(mode: String, param: String): ModeChange =
        ModeChange(mode = mode, param = param, kind = ModeChangeKind.Prefix)

    private fun list(mode: String, param: String): ModeChange =
        ModeChange(mode = mode, param = param, kind = ModeChangeKind.List)

    private fun chan(mode: String, param: String? = null): ModeChange =
        ModeChange(mode = mode, param = param, kind = ModeChangeKind.Chan)

    // MARK: - Member status

    @Test
    fun testNarratesGrantsAndRevocations() {
        assertEquals(" gave op to alice", say(listOf(prefix("+o", "alice"))))
        assertEquals(" took op from alice", say(listOf(prefix("-o", "alice"))))
        assertEquals(" gave voice to carol", say(listOf(prefix("+v", "carol"))))
        assertEquals(" took voice from carol", say(listOf(prefix("-v", "carol"))))
        assertEquals(" gave half-op to dave", say(listOf(prefix("+h", "dave"))))
        assertEquals(" gave owner to erin", say(listOf(prefix("+q", "erin"))))
        assertEquals(" gave admin to frank", say(listOf(prefix("+a", "frank"))))
    }

    @Test
    fun testEmitsTheTargetAsANickSegment() {
        assertEquals(
            listOf(ModeNarration.Segment.Text(" gave op to "), ModeNarration.Segment.Nick("alice")),
            ModeNarration.describe(listOf(prefix("+o", "alice"))),
        )
    }

    @Test
    fun testFallsBackToTheTokenForAnUnnamedPrefixLetter() {
        assertEquals(" gave +y to gwen", say(listOf(prefix("+y", "gwen"))))
        assertEquals(" took +y from gwen", say(listOf(prefix("-y", "gwen"))))
    }

    // MARK: - List modes

    @Test
    fun testNarratesBansQuietsAndExceptions() {
        assertEquals(" banned *!*@host", say(listOf(list("+b", "*!*@host"))))
        assertEquals(" unbanned *!*@host", say(listOf(list("-b", "*!*@host"))))
        assertEquals(" quieted *!*@host", say(listOf(list("+q", "*!*@host"))))
        assertEquals(" added a ban exemption for *!*@host", say(listOf(list("+e", "*!*@host"))))
        assertEquals(" removed the invite exception for *!*@host", say(listOf(list("-I", "*!*@host"))))
    }

    @Test
    fun testNamesTheListForAnUnknownLetter() {
        assertEquals(" added mask to the +d list", say(listOf(list("+d", "mask"))))
        assertEquals(" removed mask from the +d list", say(listOf(list("-d", "mask"))))
    }

    @Test
    fun testNeverEmitsAMaskAsANick() {
        // `+b alice` is a mask that happens to look like a nick. Rendering it as one would
        // give it a colour, a nick menu, and a whois — for a ban.
        val segments = ModeNarration.describe(listOf(list("+b", "alice")))
        assertFalse(segments.any { it is ModeNarration.Segment.Nick })
    }

    // MARK: - Channel modes

    @Test
    fun testNarratesTheCommonFlags() {
        assertEquals(" locked the topic", say(listOf(chan("+t"))))
        assertEquals(" unlocked the topic", say(listOf(chan("-t"))))
        assertEquals(" made the channel moderated", say(listOf(chan("+m"))))
        assertEquals(" removed moderation", say(listOf(chan("-m"))))
        assertEquals(" made the channel invite-only", say(listOf(chan("+i"))))
        assertEquals(" made the channel secret", say(listOf(chan("+s"))))
        assertEquals(" made the channel private", say(listOf(chan("+p"))))
    }

    @Test
    fun testGetsPlusNTheRightWayRound() {
        // +n BLOCKS messages from outside the channel. gamja narrates it as "allowed external
        // messages", which is inverted; this pins ours.
        assertEquals(" blocked outside messages", say(listOf(chan("+n"))))
        assertEquals(" allowed outside messages", say(listOf(chan("-n"))))
    }

    @Test
    fun testNarratesTheUserLimitWithItsValue() {
        assertEquals(" set the user limit to 50", say(listOf(chan("+l", "50"))))
        assertEquals(" removed the user limit", say(listOf(chan("-l"))))
    }

    @Test
    fun testNeverPrintsTheChannelKey() {
        assertEquals(" set a channel key", say(listOf(chan("+k", "hunter2"))))
        assertFalse(say(listOf(chan("+k", "hunter2"))).contains("hunter2"))
        assertEquals(" removed the channel key", say(listOf(chan("-k", "hunter2"))))
    }

    @Test
    fun testFallsBackToTheTokenForAnUnknownChannelLetter() {
        assertEquals(" set +C", say(listOf(chan("+C"))))
        assertEquals(" unset +C", say(listOf(chan("-C"))))
        assertEquals(" set +j to 5:1", say(listOf(chan("+j", "5:1"))))
    }

    // MARK: - Fallbacks

    @Test
    fun testShowsAModeStringForSeveralChanges() {
        assertEquals(
            " set +o alice -b *!*@host",
            say(listOf(prefix("+o", "alice"), list("-b", "*!*@host"))),
        )
    }

    @Test
    fun testWithholdsTheKeyInTheMultiChangeFormToo() {
        val said = say(listOf(chan("+k", "hunter2"), chan("+m")))
        assertEquals(" set +k +m", said)
        assertFalse(said.contains("hunter2"))
    }

    @Test
    fun testDoesNotNarrateAnUnstampedChange() {
        // Without `kind`, `+q alice` might grant ownership or quiet a mask. Guessing is the
        // bug the stamp exists to prevent.
        assertEquals(" set +q alice", say(listOf(ModeChange(mode = "+q", param = "alice"))))
        assertEquals(" set +o alice", say(listOf(ModeChange(mode = "+o", param = "alice"))))
    }

    @Test
    fun testUsesTheRowTextWhenThereIsNoParsedList() {
        assertEquals(" set +o alice", say(emptyList(), rawText = "+o alice"))
        assertEquals(" set +nt", say(emptyList(), rawText = "+nt"))
    }

    @Test
    fun testWithholdsTheKeyFromTheRawTextPathToo() {
        // Reachable, not theoretical: rows written before `modes` was persisted take this
        // path, and their text is the wire form.
        assertEquals(" set +k", say(emptyList(), rawText = "+k hunter2"))
        assertEquals(" set +ok", say(emptyList(), rawText = "+ok alice hunter2"))
        assertFalse(say(emptyList(), rawText = "+k hunter2").contains("hunter2"))
    }

    @Test
    fun testLeavesAKeylessRawTextIntact() {
        assertEquals(" set +b *!*@host", say(emptyList(), rawText = "+b *!*@host"))
        assertEquals(" set +l 50", say(emptyList(), rawText = "+l 50"))
    }

    @Test
    fun testStillSaysSomethingWhenTheRowHasNothingUsable() {
        assertEquals(" changed the channel modes", say(emptyList(), rawText = ""))
        assertEquals(" changed the channel modes", say(emptyList(), rawText = null))
    }

    @Test
    fun testIgnoresMalformedEntries() {
        assertEquals(
            " gave op to alice",
            say(listOf(ModeChange(mode = "", param = "x"), prefix("+o", "alice"))),
        )
    }
}
