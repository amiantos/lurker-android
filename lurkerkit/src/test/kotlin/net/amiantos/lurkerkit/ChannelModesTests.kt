// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ChannelModeDrafts
import net.amiantos.lurkerkit.model.ChannelModeForm
import net.amiantos.lurkerkit.model.ChannelRank
import net.amiantos.lurkerkit.model.ChannelRefusals
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.PrefixMode
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
 * Channel controls (lurker-ios#187, the iOS half of lurker#727): the mode vocabulary and
 * channel state off the wire, rank gating, the settings form's diff, its drafts, and how an
 * open list stays current.
 */
class ChannelModesTests {

    private val spec = ModeSpec(
        list = "beIq", always = "k", onSet = "lj", flags = "imnstC",
        prefix = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+")),
        maxModes = 4, topicLen = 390,
    )

    // MARK: - Wire

    // MARK: - Live lines

    // MARK: - Store

    // MARK: - Access

    // MARK: - Rank

    @Test
    fun testRankUsesTheNetworksOwnLadder() {
        val odd = listOf(PrefixMode(mode = "Y", symbol = "!"), PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+"))
        assertTrue(ChannelRank.atLeast(listOf("Y"), prefix = odd, letter = "o"))
        assertTrue(ChannelRank.atLeast(listOf("v", "o"), prefix = odd, letter = "o"), "scans by rank, not array order")
        assertFalse(ChannelRank.atLeast(listOf("v"), prefix = odd, letter = "o"))
        assertFalse(ChannelRank.atLeast(emptyList(), prefix = odd, letter = "v"))
        assertEquals(0, ChannelRank.index(listOf("v", "Y"), prefix = odd))
    }

    @Test
    fun testAGateForALetterTheNetworkLacksRoundsUp() {
        val noHalfop = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+"))
        assertTrue(ChannelRank.atLeast(listOf("o"), prefix = noHalfop, letter = "h"))
        assertFalse(ChannelRank.atLeast(listOf("v"), prefix = noHalfop, letter = "h"))
        assertFalse(ChannelRank.atLeast(listOf("o"), prefix = noHalfop, letter = "Z"), "a letter on no ladder gates nobody in")
    }

    // MARK: - Form

    @Test
    fun testRowsComeFromTheSpecNamedFirst() {
        val rows = ChannelModeForm.rows(spec)
        assertEquals(listOf("i", "m", "n", "s", "t", "k", "l", "C", "j"), rows.map { it.letter })
        assertEquals(ChannelModeForm.RowKind.Key, rows.firstOrNull { it.letter == "k" }?.kind)
        assertEquals(ChannelModeForm.RowKind.Param, rows.firstOrNull { it.letter == "j" }?.kind)
        assertNull(rows.firstOrNull { it.letter == "C" }?.name, "no name we can vouch for")
        assertFalse(rows.any { it.letter == "b" }, "lists have their own screens")
        assertEquals(listOf("b", "e", "I", "q"), ChannelModeForm.lists(spec).map { it.letter })
    }

    private fun changes(
        live: ChannelModeForm.Live,
        draft: Map<String, ChannelModeForm.DraftRow>,
    ): Result<List<OutgoingModeChange>, ChannelModeForm.ChangeError> =
        ChannelModeForm.changes(spec = spec, live = live, draft = draft)

    private fun row(on: Boolean, value: String = ""): ChannelModeForm.DraftRow =
        ChannelModeForm.DraftRow(on = on, value = value)

    @Test
    fun testFlagsDiffAgainstTheLiveState() {
        val live = ChannelModeForm.Live(modes = "nt", params = emptyMap())
        assertEquals(
            listOf(
                OutgoingModeChange(sign = '+', letter = "m"),
                OutgoingModeChange(sign = '-', letter = "t"),
            ),
            changes(live, mapOf("m" to row(true), "t" to row(false), "n" to row(true))).get(),
        )
    }

    @Test
    fun testParamModes() {
        val live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "50"))
        assertEquals(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), changes(live, mapOf("l" to row(true, " 60 "))).get())
        assertEquals(emptyList(), changes(live, mapOf("l" to row(true, "50"))).get(), "unchanged")
        assertEquals(listOf(OutgoingModeChange(sign = '-', letter = "l")), changes(live, mapOf("l" to row(false, "50"))).get(), "-l takes no param")
        assertEquals(ChannelModeForm.ChangeError.ValueRequired("j"), changes(live, mapOf("j" to row(true))).get_error())
        assertEquals(ChannelModeForm.ChangeError.Spaces("j"), changes(live, mapOf("j" to row(true, "3 4"))).get_error())
        assertEquals(emptyList(), changes(live, mapOf("j" to row(false, "3 4"))).get(), "a value being turned off needn't be valid")
    }

    @Test
    fun testTheKey() {
        val keyed = ChannelModeForm.Live(modes = "k", params = mapOf("k" to "old"))
        assertEquals(
            listOf(
                OutgoingModeChange(sign = '-', letter = "k"),
                OutgoingModeChange(sign = '+', letter = "k", param = "new"),
            ),
            changes(keyed, mapOf("k" to row(true, "new"))).get(),
            "replacing a key takes the old one off first (467 otherwise)",
        )
        assertEquals(emptyList(), changes(keyed, mapOf("k" to row(true, ""))).get(), "on, and no new key: keep it")
        assertEquals(listOf(OutgoingModeChange(sign = '-', letter = "k")), changes(keyed, mapOf("k" to row(false))).get(), "the server fills -k")

        val unknownKey = ChannelModeForm.Live(modes = "k", params = emptyMap())
        assertEquals(emptyList(), changes(unknownKey, mapOf("k" to row(true, ""))).get(), "Key is set, and left alone")

        val open = ChannelModeForm.Live(modes = "", params = emptyMap())
        assertEquals(ChannelModeForm.ChangeError.KeyRequired, changes(open, mapOf("k" to row(true, ""))).get_error())
        assertEquals(listOf(OutgoingModeChange(sign = '+', letter = "k", param = "s3cret")), changes(open, mapOf("k" to row(true, "s3cret"))).get())
    }

    /** A B-group mode other than the key names its value to unset it — `*` when we never learned it. */
    @Test
    fun testAnAlwaysParamModeNamesItsValueToUnset() {
        val bGroup = ModeSpec(list = "b", always = "kf", onSet = "l", flags = "n", prefix = emptyList(), maxModes = 3, topicLen = null)
        val live = ChannelModeForm.Live(modes = "f", params = emptyMap())
        assertEquals(
            listOf(OutgoingModeChange(sign = '-', letter = "f", param = "*")),
            ChannelModeForm.changes(spec = bGroup, live = live, draft = mapOf("f" to row(false))).get(),
        )
    }

    @Test
    fun testTopicIsBytesAndOneLine() {
        assertEquals(6, ChannelModeForm.topicBytes("héllo"))
        assertEquals("a b c", ChannelModeForm.topicToSend("a\r\nb\nc"))
    }

    // MARK: - Drafts

    @Test
    fun testAnEditDissolvesWhenTheChannelMatchesIt() {
        val live = ChannelModeForm.Live(modes = "nt", params = emptyMap())
        var drafts = ChannelModeDrafts()
        drafts = drafts.setOn("m", true, live = live)
        drafts = drafts.reconcile(live = live, liveTopic = "")
        assertNotNull(drafts.rows["m"], "the channel hasn't answered yet")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "ntm", params = emptyMap()), liveTopic = "")
        assertNull(drafts.rows["m"])
    }

    /**
     * The server may echo a value normalized — `+l 050` comes back as 50. A saved row whose
     * live state MOVED is answered, matching or not.
     */
    @Test
    fun testASavedRowDissolvesWhenItsLiveStateMoves() {
        val live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "50"))
        var drafts = ChannelModeDrafts()
        drafts = drafts.setValue("l", "050", live = live)
        val sent = ChannelModeForm.changes(spec = spec, live = live, draft = drafts.rows).get()
        drafts = drafts.noteSending(drafts.sending(sent, live = live))
        drafts = drafts.reconcile(live = live, liveTopic = "")
        assertNotNull(drafts.rows["l"], "never cleared on the ack — only the channel answers")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "50")), liveTopic = "")
        assertNotNull(drafts.rows["l"], "nothing moved: a refusal leaves the edit standing")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "51")), liveTopic = "")
        assertNull(drafts.rows["l"])
    }

    /**
     * Untick +m again before +m comes back: the echo answers the edit that was SENT, and the
     * newer one is the user's to keep.
     */
    @Test
    fun testANewerEditOutlivesTheEchoOfTheOldOne() {
        val live = ChannelModeForm.Live(modes = "n", params = emptyMap())
        var drafts = ChannelModeDrafts()
        drafts = drafts.setOn("m", true, live = live)
        drafts = drafts.setOn("s", true, live = live)
        drafts = drafts.noteSending(drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live))
        drafts = drafts.setOn("m", false, live = live)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nm", params = emptyMap()), liveTopic = "")
        assertEquals(ChannelModeForm.DraftRow(on = false, value = ""), drafts.rows["m"], "the untick stands")
        assertNotNull(drafts.rows["s"], "a row nobody answered stands")
    }

    @Test
    fun testTheTopicDraft() {
        var drafts = ChannelModeDrafts()
        assertNull(drafts.topicChange(live = "old"), "untouched")
        drafts = drafts.setTopic("new\nline")
        assertEquals("new line", drafts.topicChange(live = "old"))
        drafts = drafts.noteTopicSending("new line", liveTopic = "old")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "", params = emptyMap()), liveTopic = "old")
        assertNotNull(drafts.topic)
        // The server trimmed it: moved, so the saved edit is answered.
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "", params = emptyMap()), liveTopic = "new lin")
        assertNull(drafts.topic)
    }

    /**
     * ⚠⚠ A change that never went out is not the echo's to answer. The topic failed, so the
     * +m behind it never left — and another op's +m then -m must not dissolve it.
     */
    @Test
    fun testAnUnsentChangeIsNotAnsweredBySomeoneElsesMove() {
        val live = ChannelModeForm.Live(modes = "n", params = emptyMap())
        var drafts = ChannelModeDrafts()
        drafts = drafts.setOn("m", true, live = live)
        val sending = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live)
        drafts = drafts.noteSending(sending)
        drafts = drafts.settle(sending, wentOut = false)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nm", params = emptyMap()), liveTopic = "")
        assertNull(drafts.rows["m"], "matching still answers it")

        drafts = drafts.setOn("m", true, live = live)
        val again = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live)
        drafts = drafts.noteSending(again)
        drafts = drafts.settle(again, wentOut = false)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "ns", params = emptyMap()), liveTopic = "")
        assertNotNull(drafts.rows["m"], "but a move it never caused doesn't")
    }

    /**
     * A slow first Save whose failure lands after a second Save must not take back the second's
     * record.
     */
    @Test
    fun testTakingBackAnOldSaveLeavesANewerOne() {
        var drafts = ChannelModeDrafts()
        val fifty = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "50"))
        drafts = drafts.setValue("l", "60", live = fifty)
        val first = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = fifty)
        drafts = drafts.noteSending(first)
        val fiftyFive = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "55"))
        val second = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = fiftyFive)
        drafts = drafts.noteSending(second)
        drafts = drafts.settle(first, wentOut = false)
        // The server normalized: 55 → 56 is the second Save's echo, and answers the edit.
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "56")), liveTopic = "")
        assertNull(drafts.rows["l"])
    }

    /**
     * The echo can come before the answer. If the answer then says the send never went out,
     * the edit the echo dissolved comes back.
     */
    @Test
    fun testAnEditDissolvedWhileItsSendIsOutComesBackIfItNeverWent() {
        val live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "50"))
        var drafts = ChannelModeDrafts()
        drafts = drafts.setValue("l", "60", live = live)
        val sending = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = live)
        drafts = drafts.noteSending(sending)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "51")), liveTopic = "")
        assertNull(drafts.rows["l"], "dissolved by the move, tentatively")
        drafts = drafts.settle(sending, wentOut = false)
        assertEquals("60", drafts.rows["l"]?.value, "never went out: the edit is the user's again")

        // Went out: the dissolve stands.
        var sent = ChannelModeDrafts()
        sent = sent.setValue("l", "60", live = live)
        val out = sent.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = live)
        sent = sent.noteSending(out)
        sent = sent.reconcile(live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "51")), liveTopic = "")
        sent = sent.settle(out, wentOut = true)
        assertNull(sent.rows["l"])
    }

    @Test
    fun testARestoreNeverOverwritesANewerEdit() {
        val live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "50"))
        var drafts = ChannelModeDrafts()
        drafts = drafts.setValue("l", "60", live = live)
        val sending = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = live)
        drafts = drafts.noteSending(sending)
        val moved = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "51"))
        drafts = drafts.reconcile(live = moved, liveTopic = "")
        drafts = drafts.setValue("l", "70", live = moved)
        drafts = drafts.settle(sending, wentOut = false)
        assertEquals("70", drafts.rows["l"]?.value)
    }

    @Test
    fun testATopicDissolvedWhileItsSendIsOutComesBackIfItNeverWent() {
        var drafts = ChannelModeDrafts()
        drafts = drafts.setTopic("mine")
        drafts = drafts.noteTopicSending("mine", liveTopic = "old")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "", params = emptyMap()), liveTopic = "theirs")
        assertNull(drafts.topic, "the channel moved: tentatively answered")
        drafts = drafts.settleTopic("mine", wentOut = false)
        assertEquals("mine", drafts.topic)
        assertEquals("mine", drafts.topicChange(live = "theirs"), "Save offers it again")
    }

    @Test
    fun testOnlyErrorsSoonAfterAChangeAnswerIt() {
        var refusals = ChannelRefusals()
        val start = Instant.ofEpochSecond(1_000)
        refusals = refusals.note("before", now = start)
        assertEquals(emptyList(), refusals.current, "nothing sent yet")
        refusals = refusals.arm(now = start.plusSeconds(1))
        refusals = refusals.note("482 not an op", now = start.plusSeconds(2))
        refusals = refusals.note("much later", now = start.plusSeconds(1).plus(ChannelRefusals.window).plusSeconds(1))
        assertEquals(listOf("482 not an op"), refusals.current)
        refusals = refusals.arm(now = start.plusSeconds(100))
        assertEquals(emptyList(), refusals.current, "a new change starts clean")
    }

    // MARK: - Lists

    private fun modeRow(nick: String, changes: List<ModeChange>): Message =
        Message(id = 1, type = EventType.Mode, nick = nick, text = null, date = Instant.ofEpochSecond(50), modes = changes)

    @Test
    fun testAnOpenListIsPatchedFromLiveRows() {
        val fetched = listOf(ModeListEntry(mask = "*!*@Bad.host", setBy = "op", setAt = null))
        val patched = ChannelModeForm.patch(
            fetched,
            rows = listOf(
                modeRow("op2", listOf(ModeChange(mode = "+b", param = "troll!*@*", kind = ModeChangeKind.List))),
                modeRow("op2", listOf(ModeChange(mode = "-b", param = "*!*@bad.HOST", kind = ModeChangeKind.List))),
                modeRow("op2", listOf(ModeChange(mode = "+b", param = "TROLL!*@*", kind = ModeChangeKind.List))),
                modeRow("op2", listOf(ModeChange(mode = "+e", param = "friend!*@*", kind = ModeChangeKind.List))),
                // Solanum's +q is a list; elsewhere it's an owner, which the server stamps `prefix`.
                modeRow("op2", listOf(ModeChange(mode = "+b", param = "nick", kind = ModeChangeKind.Prefix))),
            ),
            letter = "b",
        )
        assertEquals(listOf("troll!*@*"), patched.map { it.mask }, "case-insensitive both ways, other letters and kinds ignored")
        assertEquals("op2", patched.firstOrNull()?.setBy)
        assertEquals(Instant.ofEpochSecond(50), patched.firstOrNull()?.setAt)
    }

    @Test
    fun testLastKeyChange() {
        assertEquals(ChannelModeForm.KeySighting.None, ChannelModeForm.lastKeyChange(emptyList()))
        assertEquals(
            ChannelModeForm.KeySighting.Set("pw"),
            ChannelModeForm.lastKeyChange(listOf(modeRow("a", listOf(ModeChange(mode = "+k", param = "pw", kind = ModeChangeKind.Chan))))),
        )
        assertEquals(
            ChannelModeForm.KeySighting.Removed,
            ChannelModeForm.lastKeyChange(
                listOf(
                    modeRow("a", listOf(ModeChange(mode = "+k", param = "pw", kind = ModeChangeKind.Chan))),
                    modeRow("a", listOf(ModeChange(mode = "-k", param = "*", kind = ModeChangeKind.Chan))),
                )
            ),
        )
        // A hidden value is still the newest word: an older key must not show through.
        assertEquals(
            ChannelModeForm.KeySighting.SetUnknown,
            ChannelModeForm.lastKeyChange(
                listOf(
                    modeRow("a", listOf(ModeChange(mode = "+k", param = "pw", kind = ModeChangeKind.Chan))),
                    modeRow("a", listOf(ModeChange(mode = "+k", param = "*", kind = ModeChangeKind.Chan))),
                )
            ),
            "`*` is a mask, not a key",
        )
        assertEquals(
            ChannelModeForm.KeySighting.SetUnknown,
            ChannelModeForm.lastKeyChange(listOf(modeRow("a", listOf(ModeChange(mode = "+k", param = null, kind = ModeChangeKind.Chan))))),
        )
    }

    /**
     * The success, or a failed test — Swift's `try result.get()`.
     *
     * Port note: `get()` is the Swift standard library's; `support.Result` carries only the two
     * cases, so the test supplies it, beside the `get_error` LurkerKit's own test file adds.
     */
    private fun <T, E> Result<T, E>.get(): T =
        when (this) {
            is Result.Success -> value
            is Result.Failure -> fail("expected a success, got $error")
        }

    /** The failure, or null — so a test can compare it with `assertEquals`. */
    @Suppress("FunctionName")
    private fun <T, E> Result<T, E>.get_error(): E? =
        when (this) {
            is Result.Success -> null
            is Result.Failure -> error
        }

    // Waiting on FrameParser, ServerFrame: testSnapshotCarriesTheSpecAndEachChannelsModeState,
    // testANullSpecIsUnknown, testModeSpecFrame, testChannelModesFrameNeverBecomesALine,
    // testChannelTopicMetaFollowsKeyPresence (and TopicMeta), testNetworkConfigReadsChannelKeys (and
    // NetworkConfig)
    //
    // Waiting on ChatViewModel, VerbReply (and FrameParser, SessionStore, SettingsCache):
    // testVerbReplyCarriesItsData, testVerbErrorsAreWorded, testLiveLinesAndResyncsReachChannelEvents,
    // testASaveFailureSaysWhetherAnythingCanHaveGoneOut
    //
    // Waiting on LurkerStore, ChatState, ServerFrame (and the private `storeWithChannel` helper and
    // `key`): testSnapshotSeedsSpecAndChannelState,
    // testChannelModesFrameReplacesModesButKeepsTheTopicSetter, testATopicLineNamesItsSetter,
    // testChannelTopicMetaReplacesOnlyWhenStated,
    // testSpecIsForgottenWhenTheLinkDropsAndRestatedByTheFrame, testSpecIsForgottenWhenOurSocketDrops,
    // testChannelStateFollowsARenameAndGoesWithAClose, testAnOpEditsEverything,
    // testPlusTGatesTheTopicOnHalfopAndAVoiceEditsNoModes, testAnUnknownSpecOpensNoRankGate,
    // testNothingIsEditableOutOfTheChannel
}
