// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The jump-to-latest pill's words — lurker-ios's `JumpToLatestButton.setNewCount`. */
class FloatingControlsTest {

    @Test
    fun `the badge caps at 99+`() {
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    @Test
    fun `TalkBack hears the count as a value, and nothing at zero`() {
        assertNull(newMessagesValue(0))
        assertEquals("1 new message", newMessagesValue(1))
        assertEquals("3 new messages", newMessagesValue(3))
    }
}
