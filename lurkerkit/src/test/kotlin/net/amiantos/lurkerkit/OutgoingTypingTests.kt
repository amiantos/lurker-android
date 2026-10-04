// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.OutgoingTyping
import net.amiantos.lurkerkit.model.TypingSignal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The outgoing half of `+typing`: which signals our own draft emits, and when.
 *
 * Three deadlines interact here (refresh, idle, and end), which is exactly the kind of logic
 * that's miserable to check by watching a phone and trivial to check at an exact instant. The
 * clock is a parameter, so every case below is a fixed sequence with no waiting.
 */
class OutgoingTypingTests {

    private val t0 = Instant.ofEpochSecond(2_000_000)

    /**
     * Port note: `Date.addingTimeInterval`, so the instants below read as they do in
     * LurkerKit's suite — fractional seconds, to the millisecond.
     */
    private fun Instant.addingTimeInterval(seconds: Double): Instant = plusMillis(Math.round(seconds * 1000))

    // MARK: - Starting and throttling

    @Test
    fun testFirstKeystrokeAnnouncesActive() {
        val typing = OutgoingTyping()
        assertEquals(TypingSignal.Active, typing.draftChanged("hello", t0))
    }

    /** A tag per character would be a flood. Within the refresh window we stay quiet. */
    @Test
    fun testKeystrokesWithinTheRefreshWindowStayQuiet() {
        val typing = OutgoingTyping()
        typing.draftChanged("h", t0)
        assertNull(typing.draftChanged("he", t0.addingTimeInterval(0.1)))
        assertNull(typing.draftChanged("hel", t0.addingTimeInterval(2.9)))
    }

    @Test
    fun testActiveIsReassertedOnceTheRefreshWindowElapses() {
        val typing = OutgoingTyping()
        typing.draftChanged("h", t0)
        assertEquals(TypingSignal.Active, typing.draftChanged("hello", t0.addingTimeInterval(3.1)))
    }

    // MARK: - Pausing

    @Test
    fun testIdleDowngradesToPaused() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertEquals(TypingSignal.Paused, typing.idled(draft = "hello", now = t0.addingTimeInterval(3.0)))
    }

    @Test
    fun testIdlingTwiceOnlyPausesOnce() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        typing.idled(draft = "hello", now = t0.addingTimeInterval(3.0))
        assertNull(typing.idled(draft = "hello", now = t0.addingTimeInterval(6.0)))
    }

    /**
     * The field can be emptied by something that isn't a keystroke. Announcing `paused` for a
     * draft that no longer exists would park a ghost on the peer for the full 30s lease.
     */
    @Test
    fun testIdleWithAnEmptiedDraftSaysNothing() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertNull(typing.idled(draft = "", now = t0.addingTimeInterval(3.0)))
    }

    @Test
    fun testTypingAgainAfterPausedGoesBackToActive() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        typing.idled(draft = "hello", now = t0.addingTimeInterval(3.0))
        // A state change re-asserts immediately — it doesn't wait out the refresh window.
        assertEquals(TypingSignal.Active, typing.draftChanged("hello!", t0.addingTimeInterval(3.5)))
    }

    // MARK: - Stopping

    @Test
    fun testEmptyingTheDraftSaysDone() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertEquals(TypingSignal.Done, typing.draftChanged("", t0.addingTimeInterval(1.0)))
    }

    @Test
    fun testWhitespaceOnlyDraftCountsAsEmpty() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertEquals(TypingSignal.Done, typing.draftChanged("   \n ", t0.addingTimeInterval(1.0)))
    }

    /**
     * A command isn't a message to the channel. Announcing composing for `/whois` leaks that
     * you're doing something and then never delivers a line to justify it.
     */
    @Test
    fun testCommandDraftNeverAnnouncesTyping() {
        val typing = OutgoingTyping()
        assertNull(typing.draftChanged("/whois al", t0))
        assertFalse(typing.isSignalling)
    }

    @Test
    fun testACommandAfterLeadingWhitespaceIsStillACommand() {
        // lurker-ios#202: the composer trims before sending, so ` /whois al` runs as a command;
        // announcing typing for it would be the same leak as for `/whois al`.
        val typing = OutgoingTyping()
        assertNull(typing.draftChanged(" /whois al", t0))
        assertNull(typing.draftChanged("\n/join #x", t0.addingTimeInterval(1.0)))
        assertFalse(typing.isSignalling)
    }

    @Test
    fun testADoubleSlashEscapeIsAMessageAndIsAnnounced() {
        // `//shrug` goes to the channel as `/shrug`, so it is composing like any other line.
        val typing = OutgoingTyping()
        assertEquals(TypingSignal.Active, typing.draftChanged("//shrug", t0))
    }

    @Test
    fun testTurningAMessageIntoACommandSaysDone() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertEquals(TypingSignal.Done, typing.draftChanged("/me waves", t0.addingTimeInterval(1.0)))
    }

    @Test
    fun testEndedSaysDoneWhenWeWereTyping() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertEquals(TypingSignal.Done, typing.ended())
        assertFalse(typing.isSignalling)
    }

    /**
     * Otherwise switching buffers with an untouched composer would spray `done` at every
     * channel you pass through.
     */
    @Test
    fun testEndedSaysNothingWhenWeWereNotTyping() {
        val typing = OutgoingTyping()
        assertNull(typing.ended())
    }

    @Test
    fun testEndedIsIdempotent() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        typing.ended()
        assertNull(typing.ended())
    }

    /** Emptying the field already said `done`, so the send that follows must not say it twice. */
    @Test
    fun testClearingThenEndingDoesNotDoubleSend() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        assertEquals(TypingSignal.Done, typing.draftChanged("", t0.addingTimeInterval(1.0)))
        assertNull(typing.ended())
    }

    /**
     * After stopping, the next keystroke is a fresh start rather than being throttled against
     * the previous run's timestamp.
     */
    @Test
    fun testResumingAfterDoneAnnouncesImmediately() {
        val typing = OutgoingTyping()
        typing.draftChanged("hello", t0)
        typing.ended()
        assertEquals(TypingSignal.Active, typing.draftChanged("h", t0.addingTimeInterval(0.5)))
    }

    // MARK: - A whole draft, start to finish

    @Test
    fun testATypicalDraftEmitsActivePausedActiveDone() {
        val typing = OutgoingTyping()
        val signals = mutableListOf<TypingSignal>()
        fun note(signal: TypingSignal?) { if (signal != null) signals.add(signal) }

        note(typing.draftChanged("h", t0))
        note(typing.draftChanged("he", t0.addingTimeInterval(0.2)))
        note(typing.draftChanged("hey", t0.addingTimeInterval(0.4)))
        note(typing.idled(draft = "hey", now = t0.addingTimeInterval(3.4)))
        note(typing.draftChanged("hey there", t0.addingTimeInterval(9.0)))
        note(typing.ended())

        assertEquals(
            listOf(TypingSignal.Active, TypingSignal.Paused, TypingSignal.Active, TypingSignal.Done),
            signals,
        )
    }
}
