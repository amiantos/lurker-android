// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageGrouping
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Run grouping — the part of bubble rendering that keeps a channel readable. */
class MessageGroupingTests {

    private val base = Instant.ofEpochSecond(1_700_000_000)

    /** A `TimeInterval` literal, in seconds. Port-only. */
    private fun seconds(value: Long): Duration = Duration.ofSeconds(value)

    private fun message(
        nick: String,
        text: String = "hi",
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        offset: Duration = Duration.ZERO,
    ): Message =
        Message(
            id = offset.seconds + 1, type = type, nick = nick, text = text,
            isSelf = isSelf, date = base.plus(offset),
        )

    // MARK: - The basics

    @Test
    fun testTheFirstMessageStartsARun() {
        assertFalse(MessageGrouping.continuesRun(message("alice"), previous = null))
    }

    @Test
    fun testSameNickBackToBackContinuesTheRun() {
        val first = message("alice", offset = seconds(0))
        assertTrue(MessageGrouping.continuesRun(message("alice", offset = seconds(10)), previous = first))
    }

    @Test
    fun testADifferentNickBreaksTheRun() {
        val first = message("alice", offset = seconds(0))
        assertFalse(MessageGrouping.continuesRun(message("bob", offset = seconds(10)), previous = first))
    }

    // MARK: - Case folding

    @Test
    fun testNickCaseDoesNotBreakARun() {
        // IRC nicks are case-insensitive and servers send them inconsistently cased, so
        // `Alice` following `alice` is the same person mid-sentence, not a new speaker.
        val first = message("alice", offset = seconds(0))
        assertTrue(MessageGrouping.continuesRun(message("Alice", offset = seconds(5)), previous = first))
    }

    // MARK: - Time

    @Test
    fun testALongGapBreaksTheRunEvenForTheSameNick() {
        val first = message("alice", offset = seconds(0))
        val later = message("alice", offset = MessageGrouping.runGap + seconds(1))
        assertFalse(
            MessageGrouping.continuesRun(later, previous = first),
            "two messages hours apart are not one conversation",
        )
    }

    @Test
    fun testExactlyTheGapStillGroups() {
        val first = message("alice", offset = seconds(0))
        assertTrue(MessageGrouping.continuesRun(message("alice", offset = MessageGrouping.runGap), previous = first))
    }

    @Test
    fun testAMissingTimestampFallsBackToTheAuthorRatherThanSplitting() {
        val undated = Message(id = 2, type = EventType.Message, nick = "alice", text = "hi", date = null)
        assertTrue(MessageGrouping.continuesRun(undated, previous = message("alice", offset = seconds(0))))
    }

    // MARK: - Self

    @Test
    fun testOurOwnMessagesDoNotJoinSomeoneElsesRun() {
        // Same nick, opposite sides of the screen — a run that spanned them would have to
        // render in two places at once.
        val theirs = message("alice", isSelf = false, offset = seconds(0))
        val ours = message("alice", isSelf = true, offset = seconds(1))
        assertFalse(MessageGrouping.continuesRun(ours, previous = theirs))
    }

    // MARK: - Only bubbles group

    @Test
    fun testActionsAndNoticesBreakRuns() {
        // They render as full-width lines, so they can't be inside a bubble run — and an
        // action between two of alice's messages is a real interruption anyway.
        val first = message("alice", offset = seconds(0))
        assertFalse(
            MessageGrouping.continuesRun(message("alice", type = EventType.Action, offset = seconds(1)), previous = first),
            "an action is not a bubble",
        )
        assertFalse(
            MessageGrouping.continuesRun(message("alice", type = EventType.Notice, offset = seconds(1)), previous = first),
            "a notice is not a bubble",
        )
        assertFalse(
            MessageGrouping.continuesRun(
                message("alice", offset = seconds(2)),
                previous = message("alice", type = EventType.Action, offset = seconds(1)),
            ),
            "and a message cannot continue a run an action started",
        )
    }

    /**
     * Everything is a bubble except narration. Sorting lines into bubbles vs. log output
     * by how "conversational" they seemed kept drawing plain conversations as logs — your
     * DM to NickServ bubbling while its reply didn't. An action stays a line because the
     * actor is inside the sentence, so a captioned bubble would name them twice.
     */
    @Test
    fun testEverythingBubblesExceptNarration() {
        assertTrue(EventType.Message.isBubble)
        assertTrue(EventType.Notice.isBubble)
        assertTrue(EventType.System.isBubble)
        assertTrue(EventType.Motd.isBubble)
        assertFalse(EventType.Action.isBubble)
    }

    /**
     * Both are bubbles now, so "both are bubbles" is no longer enough to group them: a bot
     * uses NOTICE precisely to mark a line as not-a-reply, and a run captions once, so
     * grouping a message with a notice would render the rest of them identically and erase
     * the distinction the sender chose.
     */
    @Test
    fun testAMessageAndANoticeFromTheSameNickDoNotGroup() {
        assertFalse(
            MessageGrouping.continuesRun(
                message("nickserv", type = EventType.Notice, offset = seconds(2)),
                previous = message("nickserv", offset = seconds(1)),
            ),
            "a notice must not continue a message's run",
        )
        assertTrue(
            MessageGrouping.continuesRun(
                message("nickserv", type = EventType.Notice, offset = seconds(2)),
                previous = message("nickserv", type = EventType.Notice, offset = seconds(1)),
            ),
            "but notices group with each other",
        )
    }
}
