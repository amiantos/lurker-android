// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Run grouping — the part of bubble rendering that keeps a channel readable. */
class MessageGroupingTests {

    // MARK: - The basics

    // MARK: - Case folding

    // MARK: - Time

    // MARK: - Self

    // MARK: - Only bubbles group

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

    // Waiting on MessageGrouping (and the private `message` helper and `base`):
    // testTheFirstMessageStartsARun, testSameNickBackToBackContinuesTheRun,
    // testADifferentNickBreaksTheRun, testNickCaseDoesNotBreakARun,
    // testALongGapBreaksTheRunEvenForTheSameNick, testExactlyTheGapStillGroups,
    // testAMissingTimestampFallsBackToTheAuthorRatherThanSplitting,
    // testOurOwnMessagesDoNotJoinSomeoneElsesRun, testActionsAndNoticesBreakRuns,
    // testAMessageAndANoticeFromTheSameNickDoNotGroup
}
