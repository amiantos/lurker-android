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

    @Test
    fun `shift Enter is left to the field, which makes the newline`() {
        assertEquals(Action.Pass to Action.Pass, press(ComposerKeys(), Event(Key.Enter, down = true, shift = true)))
        assertEquals(
            Action.Pass to Action.Pass,
            press(ComposerKeys(), Event(Key.Enter, down = true, shift = true, hardware = false, enterSends = true)),
        )
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
    fun `nothing is taken while an IME is composing`() {
        for (key in listOf(Key.Enter, Key.Tab, Key.Escape)) {
            val event = Event(key, down = true, composing = true, enterSends = true, replyPending = true)
            assertEquals("$key", Action.Pass to Action.Pass, press(ComposerKeys(), event))
        }
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
