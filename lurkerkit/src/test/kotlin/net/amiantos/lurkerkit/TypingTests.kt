// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.TypingActivity
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    private val key = BufferKey(networkId = 1, target = "#chan")
    private val t0: Instant = Instant.ofEpochSecond(1_000_000)

    /**
     * A state holding one open channel — typing is only ever stored against a buffer that
     * already exists, so every case needs one.
     */
    private fun stateWithChannel(target: String = "#chan"): ChatState {
        val key = BufferKey(networkId = 1, target = target)
        return ChatState(buffers = mapOf(key.id to Buffer(networkId = 1, target = target, kind = BufferKind.Channel)))
    }

    private fun typing(
        nick: String,
        activity: String,
        target: String = "#chan",
        userhost: String? = null,
    ): ServerFrame =
        ServerFrame.Typing(
            networkId = 1, target = target, nick = nick,
            activity = TypingActivity.from(activity), userhost = userhost,
        )

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

    @Test
    fun testActiveTypingSurfacesTheTypist() {
        val state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        assertEquals(listOf("alice"), state.typists(key, now = t0))
    }

    @Test
    fun testDoneRemovesTheTypist() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("alice", "done"), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
        // …and leaves no empty shell behind.
        assertNull(state.typing[key.id])
    }

    /**
     * The bug the web had to go back and fix: an unrecognized state used to be ignored, which
     * stranded the prior entry with no timer left to clear it. Here it must delete.
     */
    @Test
    fun testUnrecognizedStateClearsRatherThanStranding() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("alice", "sideways"), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
    }

    /**
     * Case-variant tags from one peer are one typist, not two, and the display keeps the
     * casing the server most recently sent.
     */
    @Test
    fun testCaseVariantTagsCollapseToOneEntry() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("ALICE", "active"), now = t0)
        assertEquals(listOf("ALICE"), state.typists(key, now = t0))
    }

    /**
     * `BufferKey.id` folds the target, so a server that cases the channel differently from
     * how we joined it still lands on our buffer (web lurker#289).
     */
    @Test
    fun testTargetCaseFoldsOntoTheOpenBuffer() {
        val state = LurkerStore.reduce(
            stateWithChannel("#chan"), typing("alice", "active", target = "#CHAN"), now = t0,
        )
        assertEquals(listOf("alice"), state.typists(key, now = t0))
    }

    // MARK: - Store: resolve, never materialize (lurker#292)

    @Test
    fun testTypingForAnUnknownBufferIsDropped() {
        val state = LurkerStore.reduce(ChatState(), typing("stranger", "active", target = "stranger"), now = t0)
        assertTrue(state.buffers.isEmpty(), "a typing tag must never materialize a buffer")
        assertTrue(state.typing.isEmpty())
    }

    // MARK: - Store: a message ends the run (spec clear-condition #1)

    private fun live(nick: String, text: String, id: Long = 1, target: String = "#chan"): ServerFrame =
        ServerFrame.Live(
            networkId = 1, target = target,
            message = Message(id = id, type = EventType.Message, nick = nick, text = text, date = t0),
        )

    /**
     * The spec's FIRST clear condition, and not an optimization: `typing=done` is only sent
     * when the field is cleared *without* sending (`client-tags/typing.md:38`), so a
     * conforming client never announces `done` on send. Without this the line stays pinned
     * under the message they just posted for the rest of the lease.
     */
    @Test
    fun testAMessageFromTheTypistClearsThem() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, live("alice", "hello"), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
    }

    /** A paused entry is the worst case — a 30s lease with nothing to end it. */
    @Test
    fun testAMessageClearsAPausedTypist() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "paused"), now = t0)
        state = LurkerStore.reduce(state, live("alice", "hello"), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
    }

    @Test
    fun testAMessageFromSomeoneElseLeavesTheTypist() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, live("bob", "hi"), now = t0)
        assertEquals(listOf("alice"), state.typists(key, now = t0))
    }

    /** Servers case nicks inconsistently; the message and the tag must still be one person. */
    @Test
    fun testACaseVariantMessageStillClearsTheTypist() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, live("ALICE", "hello"), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
    }

    /** The spec's second clear condition — don't leave a ghost typing in a channel they left. */
    @Test
    fun testAPartFromTheTypistClearsThem() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        val part = ServerFrame.Live(
            networkId = 1, target = "#chan",
            message = Message(id = 2, type = EventType.Part, nick = "alice", text = null, date = t0),
        )
        state = LurkerStore.reduce(state, part, now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
    }

    @Test
    fun testClearingTheLastTypistDropsTheBufferEntry() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, live("alice", "hello"), now = t0)
        assertNull(state.typing[key.id], "an emptied sub-map should not be left behind")
    }

    @Test
    fun testAMessageLeavesTheOtherTypists() {
        var state = stateWithChannel()
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("bob", "active"), now = t0)
        state = LurkerStore.reduce(state, live("alice", "hello"), now = t0)
        assertEquals(listOf("bob"), state.typists(key, now = t0))
    }

    // MARK: - Store: the lease

    @Test
    fun testActiveEntryExpiresAfterItsLease() {
        val state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        assertEquals(listOf("alice"), state.typists(key, now = t0.plusMillis(5_900)))
        // Exclusive of the boundary: a lease that runs out exactly on the tick is over.
        assertEquals(emptyList(), state.typists(key, now = t0.plusSeconds(6)))
    }

    /**
     * `paused` means "stopped, but the draft is still there" and is sent once, so it has to
     * outlive a long pause on its own — five times the `active` lease.
     */
    @Test
    fun testPausedOutlivesActive() {
        val state = LurkerStore.reduce(stateWithChannel(), typing("alice", "paused"), now = t0)
        assertEquals(listOf("alice"), state.typists(key, now = t0.plusSeconds(29)))
        assertEquals(emptyList(), state.typists(key, now = t0.plusSeconds(30)))
    }

    @Test
    fun testRefreshExtendsTheLease() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        val t5 = t0.plusSeconds(5)
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t5)
        // Would have lapsed on the original lease; the refresh carries it past that.
        assertEquals(listOf("alice"), state.typists(key, now = t0.plusSeconds(8)))
        assertEquals(emptyList(), state.typists(key, now = t5.plusSeconds(6)))
    }

    /**
     * Expiry is evaluated at read time and never mutates the map, so asking at a later
     * instant can't corrupt an earlier answer. This is what lets the view tick lazily.
     */
    @Test
    fun testReadTimeExpiryDoesNotMutateState() {
        val state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0.plusSeconds(600)))
        assertEquals(listOf("alice"), state.typists(key, now = t0), "the earlier answer must still hold")
    }

    // MARK: - Store: several typists at once

    @Test
    fun testMultipleTypistsAreOrderedByWhenTheyStarted() {
        var state = stateWithChannel()
        state = LurkerStore.reduce(state, typing("carol", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t0.plusSeconds(1))
        state = LurkerStore.reduce(state, typing("bob", "active"), now = t0.plusSeconds(2))
        assertEquals(
            listOf("carol", "alice", "bob"),
            state.typists(key, now = t0.plusSeconds(3)),
            "longest-running first, not alphabetical and not dictionary order",
        )
    }

    /**
     * The reason `startedAt` is carried across a refresh: without it, ordering on `expiresAt`
     * would reshuffle the list every time somebody's `active` refreshed.
     */
    @Test
    fun testRefreshDoesNotReorderTheList() {
        var state = stateWithChannel()
        state = LurkerStore.reduce(state, typing("carol", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t0.plusSeconds(1))
        // Carol keeps typing — she must stay first even though her lease now ends last.
        state = LurkerStore.reduce(state, typing("carol", "active"), now = t0.plusSeconds(2))
        assertEquals(listOf("carol", "alice"), state.typists(key, now = t0.plusSeconds(3)))
    }

    /**
     * A peer whose lease lapsed and who then types again has started a *new* run, so they go
     * to the back rather than reclaiming their original place.
     */
    @Test
    fun testResumingAfterALapseStartsANewRun() {
        var state = stateWithChannel()
        state = LurkerStore.reduce(state, typing("carol", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t0.plusSeconds(1))
        // Carol lapses (t0+6), then comes back.
        val t10 = t0.plusSeconds(10)
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t10)
        state = LurkerStore.reduce(state, typing("carol", "active"), now = t10.plusSeconds(1))
        assertEquals(listOf("alice", "carol"), state.typists(key, now = t10.plusSeconds(2)))
    }

    @Test
    fun testTypistsExpireIndependently() {
        var state = stateWithChannel()
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("bob", "paused"), now = t0)
        // Alice's short lease is out; Bob's long one is not.
        assertEquals(listOf("bob"), state.typists(key, now = t0.plusSeconds(7)))
    }

    @Test
    fun testOneTypistStoppingLeavesTheOthers() {
        var state = stateWithChannel()
        state = LurkerStore.reduce(state, typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("bob", "active"), now = t0)
        state = LurkerStore.reduce(state, typing("alice", "done"), now = t0)
        assertEquals(listOf("bob"), state.typists(key, now = t0))
    }

    // MARK: - Store: isolation between buffers

    @Test
    fun testTypingIsScopedToItsBuffer() {
        var state = stateWithChannel("#chan")
        val other = BufferKey(networkId = 1, target = "#other")
        state = state.copy(
            buffers = state.buffers + (other.id to Buffer(networkId = 1, target = "#other", kind = BufferKind.Channel)),
        )
        state = LurkerStore.reduce(state, typing("alice", "active", target = "#chan"), now = t0)
        assertEquals(listOf("alice"), state.typists(key, now = t0))
        assertEquals(emptyList(), state.typists(other, now = t0))
    }

    @Test
    fun testTypistsForABufferWithNoEntriesIsEmpty() {
        assertEquals(emptyList(), ChatState().typists(key, now = t0))
    }

    // MARK: - Store: lifecycle

    /**
     * A `paused` entry holds for 30s — long enough to survive a reconnect and show someone
     * composing when we've heard nothing from them since before the drop.
     */
    @Test
    fun testSocketCloseClearsTypists() {
        var state = LurkerStore.reduce(stateWithChannel(), typing("alice", "active"), now = t0)
        state = LurkerStore.reduce(state, ServerFrame.SocketClosed(reason = null, code = null), now = t0)
        assertEquals(emptyList(), state.typists(key, now = t0))
    }
}
