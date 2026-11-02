// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swipe-to-reply rule (lurker#1248), pinned: the buffer list's swipe-to-part was lost to an
 * angle rule nobody could check (lurker#1102).
 */
class SwipeToReplyModelTest {

    /** Left in a left-to-right layout. */
    private val left = -1f

    @Test
    fun aDragTowardTheTrailingEdgeIsASwipe() {
        assertTrue(SwipeToReplyModel.isSwipe(dx = -20f, dy = 0f, sign = left))
        assertTrue(SwipeToReplyModel.isSwipe(dx = -30f, dy = 20f, sign = left))
        assertTrue(SwipeToReplyModel.isSwipe(dx = -30f, dy = -20f, sign = left))
    }

    /** Three across to two down is the line; shallower than that is a scroll that drifted. */
    @Test
    fun aDiagonalDragIsNot() {
        assertFalse(SwipeToReplyModel.isSwipe(dx = -20f, dy = 20f, sign = left))
        assertFalse(SwipeToReplyModel.isSwipe(dx = -29f, dy = 20f, sign = left))
        assertFalse(SwipeToReplyModel.isSwipe(dx = -5f, dy = 30f, sign = left))
    }

    @Test
    fun aDragTheOtherWayIsNot() {
        assertFalse(SwipeToReplyModel.isSwipe(dx = 20f, dy = 0f, sign = left))
        assertFalse(SwipeToReplyModel.isSwipe(dx = 0f, dy = 0f, sign = left))
    }

    /** In a right-to-left layout the trailing edge is on the left, and the swipe goes right. */
    @Test
    fun theSignFlipsTheDirection() {
        assertTrue(SwipeToReplyModel.isSwipe(dx = 20f, dy = 0f, sign = 1f))
        assertFalse(SwipeToReplyModel.isSwipe(dx = -20f, dy = 0f, sign = 1f))
    }

    @Test
    fun theRowFollowsTheFingerToTheReachAndAThirdOfItPast() {
        assertEquals(0f, SwipeToReplyModel.offsetFor(toward = -10f, reach = 64f), 0f)
        assertEquals(30f, SwipeToReplyModel.offsetFor(toward = 30f, reach = 64f), 0f)
        assertEquals(64f, SwipeToReplyModel.offsetFor(toward = 64f, reach = 64f), 0f)
        assertEquals(74f, SwipeToReplyModel.offsetFor(toward = 94f, reach = 64f), 0f)
    }
}
