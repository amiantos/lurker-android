// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.model.TypingSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(typing.draftChanged("h", t0))
        assertTrue(typing.draftChanged("hi", t0.plusSeconds(1)))
        assertTrue(typing.draftChanged("hi ", t0.plusSeconds(4)))
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
        assertFalse(typing.draftChanged("", t0.plusSeconds(1)))
        typing.draftChanged("hi", t0.plusSeconds(2))
        assertFalse(typing.draftChanged("/whois bob", t0.plusSeconds(3)))
        assertEquals(listOf(TypingSignal.Active, TypingSignal.Done, TypingSignal.Active, TypingSignal.Done), sent)
    }

    @Test
    fun `a command never claims typing at all`() {
        assertFalse(typing.draftChanged("/join #lurker", t0))
        assertTrue(sent.isEmpty())
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
