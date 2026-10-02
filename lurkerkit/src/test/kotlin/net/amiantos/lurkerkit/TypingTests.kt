// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.TypingActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The `+typing` path, end to end: the parser reads the real wire frame, the store folds it,
 * and `typists(in:now:)` applies the lease.
 *
 * The multi-typist and expiry cases are the whole reason this is a value-typed, read-time
 * lease rather than the web's timer-driven map — every one of them is exercised here at an
 * exact instant, with no sleeping and nothing to go flaky. `reduce` takes `now`, so "six
 * seconds later" is a parameter, not a wait.
 */
class TypingTests {

    // MARK: - Parser

    @Test
    fun testTypingFrameParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","type":"typing","networkId":1,"target":"#chan","nick":"alice","state":"active","userhost":"alice!u@h"}""",
        )
        if (frame !is ServerFrame.Typing) fail("expected typing, got $frame")
        val (networkId, target, nick, activity, userhost) = frame
        assertEquals(1, networkId)
        assertEquals("#chan", target)
        assertEquals("alice", nick)
        assertEquals(TypingActivity.Active, activity)
        assertEquals("alice!u@h", userhost)
    }

    /**
     * `done` is the absence of typing, not a kind of it — so it parses to null activity and
     * the store reads that as "remove them".
     */
    @Test
    fun testDoneAndUnknownStatesParseToNoActivity() {
        for (raw in listOf("done", "wat", "")) {
            val frame = FrameParser.parseWs(
                """{"kind":"irc","type":"typing","networkId":1,"target":"#chan","nick":"alice","state":"$raw"}""",
            )
            if (frame !is ServerFrame.Typing) fail("expected typing for state $raw, got $frame")
            assertNull(frame.activity, "state $raw should carry no activity")
        }
    }

    /** A typing frame with nobody to attribute it to would key an entry on the empty string. */
    @Test
    fun testTypingWithoutNickIsIgnored() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","type":"typing","networkId":1,"target":"#chan","nick":"","state":"active"}""",
        )
        assertEquals(ServerFrame.Ignored, frame)
    }

    /**
     * A typing frame must not be mistaken for a renderable event — it carries no id and its
     * payload is in `state`, so falling through to `parseEvent` would append a junk line.
     */
    @Test
    fun testTypingIsNotParsedAsALiveMessage() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","type":"typing","networkId":1,"target":"#chan","nick":"alice","state":"active"}""",
        )
        if (frame is ServerFrame.Live) fail("typing must not parse as a live message")
    }

    // MARK: - Store: the basics
    // MARK: - Store: resolve, never materialize (lurker#292)
    // MARK: - Store: a message ends the run (spec clear-condition #1)
    // MARK: - Store: the lease
    // MARK: - Store: several typists at once
    // MARK: - Store: isolation between buffers
    // MARK: - Store: lifecycle

    // Waiting on LurkerStore, ChatState (and the private `key`, `t0`, `stateWithChannel`, `typing` and
    // `live` helpers): testActiveTypingSurfacesTheTypist, testDoneRemovesTheTypist,
    // testUnrecognizedStateClearsRatherThanStranding, testCaseVariantTagsCollapseToOneEntry,
    // testTargetCaseFoldsOntoTheOpenBuffer, testTypingForAnUnknownBufferIsDropped,
    // testAMessageFromTheTypistClearsThem, testAMessageClearsAPausedTypist,
    // testAMessageFromSomeoneElseLeavesTheTypist, testACaseVariantMessageStillClearsTheTypist,
    // testAPartFromTheTypistClearsThem, testClearingTheLastTypistDropsTheBufferEntry,
    // testAMessageLeavesTheOtherTypists, testActiveEntryExpiresAfterItsLease, testPausedOutlivesActive,
    // testRefreshExtendsTheLease, testReadTimeExpiryDoesNotMutateState,
    // testMultipleTypistsAreOrderedByWhenTheyStarted, testRefreshDoesNotReorderTheList,
    // testResumingAfterALapseStartsANewRun, testTypistsExpireIndependently,
    // testOneTypistStoppingLeavesTheOthers, testTypingIsScopedToItsBuffer,
    // testTypistsForABufferWithNoEntriesIsEmpty, testSocketCloseClearsTypists
}
