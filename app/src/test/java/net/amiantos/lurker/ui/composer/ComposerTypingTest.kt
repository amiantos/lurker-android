// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.model.TypingSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The outgoing typing schedule (lurker-ios#61): what each change puts on the wire, and when the idle timer runs. */
class ComposerTypingTest {
    private val sent = mutableListOf<TypingSignal>()
    private val typing = ComposerTyping { sent += it }
    private val t0 = Instant.parse("2026-10-03T12:00:00Z")

    @Test
    fun `typing says active once per refresh window, and arms the idle timer every time`() {
        assertEquals(true, typing.draftChanged("h", t0))
        assertEquals(true, typing.draftChanged("hi", t0.plusSeconds(1)))
        assertEquals(true, typing.draftChanged("hi ", t0.plusSeconds(4)))
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Active), sent)
    }

    @Test
    fun `going idle with the draft still there says paused, once`() {
        typing.draftChanged("hi", t0)
        typing.idled("hi", t0.plusSeconds(3))
        typing.idled("hi", t0.plusSeconds(6))
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Paused), sent)
    }

    @Test
    fun `emptying the field or starting a command says done and disarms the timer`() {
        typing.draftChanged("hi", t0)
        assertEquals(false, typing.draftChanged("", t0.plusSeconds(1)))
        typing.draftChanged("hi", t0.plusSeconds(2))
        assertEquals(false, typing.draftChanged("/whois bob", t0.plusSeconds(3)))
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Done, TypingSignal.Active, TypingSignal.Done), sent)
    }

    @Test
    fun `a command never claims typing at all`() {
        assertEquals(false, typing.draftChanged("/join #lurker", t0))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the same draft again is no news, and leaves the timer alone`() {
        typing.draftChanged("hi", t0)
        assertNull(typing.draftChanged("hi", t0.plusSeconds(5)))
        assertEquals(listOf(TypingSignal.Active), sent)
    }

    @Test
    fun `once typing ends, the next change is news even if it spells the old draft`() {
        // "hello" typed; a restore from another device sets "hell" (ending the claim); "o" typed again.
        typing.draftChanged("hello", t0)
        typing.ended()
        assertEquals(true, typing.draftChanged("hello", t0.plusSeconds(1)))
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Done, TypingSignal.Active), sent)
    }

    @Test
    fun `a command after leading whitespace is still a command — it runs as one`() {
        assertEquals(false, typing.draftChanged(" /whois bob", t0))
        assertEquals(false, typing.draftChanged("\n/join #lurker", t0.plusSeconds(1)))
        assertTrue(sent.isEmpty())
        // Indented prose is still prose.
        assertEquals(true, typing.draftChanged("  hello", t0.plusSeconds(2)))
        assertEquals(listOf(TypingSignal.Active), sent)
    }

    @Test
    fun `a double-slash escape is a message, so it claims typing`() {
        assertEquals(true, typing.draftChanged("//shrug", t0))
        assertEquals(true, typing.draftChanged(" //shrug", t0.plusSeconds(4)))
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Active), sent)
    }

    @Test
    fun `ending says done only when the network thinks we're typing`() {
        typing.ended()
        assertTrue(sent.isEmpty())
        typing.draftChanged("hi", t0)
        typing.ended()
        typing.ended()
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Done), sent)
    }
}
