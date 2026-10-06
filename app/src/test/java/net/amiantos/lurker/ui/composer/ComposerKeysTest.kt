// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurker.ui.composer.ComposerKeys.Action
import net.amiantos.lurker.ui.composer.ComposerKeys.Event
import net.amiantos.lurker.ui.composer.ComposerKeys.Key
import org.junit.Assert.assertEquals
import org.junit.Test

/** The composer's keys (lurker-android#63, #64): what a press does, and that its release goes with it. */
class ComposerKeysTest {

    private fun press(keys: ComposerKeys, event: Event): Pair<Action, Action> =
        keys.onKey(event) to keys.onKey(event.copy(down = false))

    // MARK: - Enter

    @Test
    fun `a hardware Enter sends whatever the setting says and whatever else is held`() {
        assertEquals(Action.Send, ComposerKeys().onKey(Event(Key.Enter, down = true)))
        assertEquals(Action.Send, ComposerKeys().onKey(Event(Key.Enter, down = true, enterSends = false)))
        assertEquals(Action.Send, ComposerKeys().onKey(Event(Key.Enter, down = true, enterSends = true)))
    }

    /** Compose's field types nothing for a shifted Enter, so the composer inserts the newline itself. */
    @Test
    fun `shift Enter is a newline, from either keyboard`() {
        assertEquals(Action.Newline to Action.Swallow, press(ComposerKeys(), Event(Key.Enter, down = true, shift = true)))
        assertEquals(
            Action.Newline to Action.Swallow,
            press(ComposerKeys(), Event(Key.Enter, down = true, shift = true, hardware = false, enterSends = true)),
        )
    }

    /** A held Enter repeats its press; a second send would send whatever the field held next. */
    @Test
    fun `a held Enter sends once`() {
        val keys = ComposerKeys()
        assertEquals(Action.Send, keys.onKey(Event(Key.Enter, down = true)))
        assertEquals(Action.Swallow, keys.onKey(Event(Key.Enter, down = true)))
        assertEquals(Action.Swallow, keys.onKey(Event(Key.Enter, down = false)))
        // Released, the next press sends again; and a held Tab keeps cycling.
        assertEquals(Action.Send, keys.onKey(Event(Key.Enter, down = true)))
        assertEquals(Action.Complete, keys.onKey(Event(Key.Tab, down = true)))
        assertEquals(Action.Complete, keys.onKey(Event(Key.Tab, down = true)))
    }

    @Test
    fun `an on-screen keyboard's Enter key event follows Enter to send`() {
        assertEquals(Action.Pass, ComposerKeys().onKey(Event(Key.Enter, down = true, hardware = false, enterSends = false)))
        assertEquals(Action.Send, ComposerKeys().onKey(Event(Key.Enter, down = true, hardware = false, enterSends = true)))
    }

    @Test
    fun `the release of a taken press is taken too, and only that one`() {
        assertEquals(Action.Send to Action.Swallow, press(ComposerKeys(), Event(Key.Enter, down = true)))
        val keys = ComposerKeys()
        keys.onKey(Event(Key.Enter, down = true))
        keys.onKey(Event(Key.Enter, down = false))
        assertEquals("a second release has no press of ours behind it", Action.Pass, keys.onKey(Event(Key.Enter, down = false)))
        assertEquals("a release never seen pressed", Action.Pass, ComposerKeys().onKey(Event(Key.Tab, down = false)))
    }

    // MARK: - Tab

    @Test
    fun `Tab completes and Shift Tab completes backward, release and all`() {
        assertEquals(Action.Complete to Action.Swallow, press(ComposerKeys(), Event(Key.Tab, down = true)))
        assertEquals(Action.CompleteBackward to Action.Swallow, press(ComposerKeys(), Event(Key.Tab, down = true, shift = true)))
        // From the on-screen kind too: only Enter has a setting to tell them apart for.
        assertEquals(Action.Complete, ComposerKeys().onKey(Event(Key.Tab, down = true, hardware = false)))
    }

    // MARK: - Escape

    @Test
    fun `Escape cancels a pending reply and is otherwise left alone`() {
        assertEquals(Action.CancelReply to Action.Swallow, press(ComposerKeys(), Event(Key.Escape, down = true, replyPending = true)))
        assertEquals(Action.Pass to Action.Pass, press(ComposerKeys(), Event(Key.Escape, down = true, replyPending = false)))
    }

    // MARK: - Composition

    @Test
    fun `nothing from the on-screen keyboard acts while an IME is composing`() {
        for (key in listOf(Key.Enter, Key.Escape)) {
            val event = Event(key, down = true, hardware = false, composing = true, enterSends = true, replyPending = true)
            assertEquals("$key", Action.Pass to Action.Pass, press(ComposerKeys(), event))
        }
        // Tab is swallowed instead — passed, the multi-line field would type a tab character.
        val tab = Event(Key.Tab, down = true, hardware = false, composing = true)
        assertEquals(Action.Swallow to Action.Swallow, press(ComposerKeys(), tab))
    }

    /** Ctrl+Tab and the like belong to the system (ChromeOS, DeX): never a completion. */
    @Test
    fun `tab with ctrl alt or meta is passed on`() {
        assertEquals(Action.Pass to Action.Pass, press(ComposerKeys(), Event(Key.Tab, down = true, otherModifier = true)))
        assertEquals(Action.Pass to Action.Pass, press(ComposerKeys(), Event(Key.Tab, down = true, shift = true, otherModifier = true)))
        // Enter doesn't care: Ctrl+Enter sends, as on the web.
        assertEquals(Action.Send, ComposerKeys().onKey(Event(Key.Enter, down = true, otherModifier = true)))
    }

    /**
     * A physical key reaches the composer only after the IME passed on it, so a composing region —
     * which Gboard keeps on the word being typed, Latin included — doesn't stop it.
     */
    @Test
    fun `a hardware key acts over a composing region`() {
        assertEquals(Action.Send, ComposerKeys().onKey(Event(Key.Enter, down = true, composing = true)))
        assertEquals(Action.Complete, ComposerKeys().onKey(Event(Key.Tab, down = true, composing = true)))
        assertEquals(Action.Newline, ComposerKeys().onKey(Event(Key.Enter, down = true, shift = true, composing = true)))
    }

    @Test
    fun `a press taken before a composition began still takes its release`() {
        val keys = ComposerKeys()
        keys.onKey(Event(Key.Tab, down = true))
        assertEquals(Action.Swallow, keys.onKey(Event(Key.Tab, down = false, composing = true)))
    }

    @Test
    fun `other keys are never taken`() {
        assertEquals(Action.Pass to Action.Pass, press(ComposerKeys(), Event(Key.Other, down = true, replyPending = true, enterSends = true)))
    }
}
