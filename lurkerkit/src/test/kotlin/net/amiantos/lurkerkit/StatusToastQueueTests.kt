// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.model.StatusToast
import net.amiantos.lurkerkit.model.StatusToastQueue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The order toasts take a surface in: one at a time, a burst from one source updating in place,
 * a notice first.
 */
class StatusToastQueueTests {
    private fun toast(nick: String, target: String = "#a", kind: StatusNotification.Kind = StatusNotification.Kind.Highlight): StatusToast =
        StatusToast.Notification(
            StatusNotification(
                kind = kind, key = BufferKey(networkId = 1, target = target), nick = nick, text = "hi",
                messageId = 1, date = Instant.now(),
            ),
        )

    @Test
    fun testOneAtATimeInArrivalOrder() {
        val queue = StatusToastQueue()
        val alice = toast("alice")
        assertEquals(StatusToastQueue.Offer.Queued, queue.offer(alice))
        assertEquals(StatusToastQueue.Offer.Queued, queue.offer(toast("bob")))
        val first = queue.presentNext()
        assertEquals(alice, first?.toast)
        // Someone waits behind it, so it holds for less.
        assertEquals(StatusToastQueue.holdBusy, first?.hold)
        assertNull(queue.presentNext(), "nothing new while one is showing")
        queue.endActive()
        val second = queue.presentNext()
        assertEquals(StatusToastQueue.hold, second?.hold)
    }

    @Test
    fun testTheSameSourceUpdatesInPlace() {
        val queue = StatusToastQueue()
        queue.offer(toast("alice"))
        queue.presentNext()
        // Same person, buffer and kind, whatever the case of the nick or target.
        val newer = toast("Alice", target = "#A")
        assertEquals(StatusToastQueue.Offer.UpdatedActive, queue.offer(newer))
        assertEquals(newer, queue.active)
        assertTrue(queue.waiting.isEmpty())

        queue.offer(toast("bob"))
        val bobAgain = toast("bob")
        assertEquals(StatusToastQueue.Offer.UpdatedWaiting, queue.offer(bobAgain))
        assertEquals(listOf(bobAgain), queue.waiting)
        // Another kind from the same person is its own toast.
        assertEquals(StatusToastQueue.Offer.Queued, queue.offer(toast("bob", kind = StatusNotification.Kind.AlwaysNotify)))
    }

    @Test
    fun testOnlyTheNewestFewWait() {
        val queue = StatusToastQueue()
        val toasts = listOf("a", "b", "c", "d", "e").map { toast(it) }
        for (t in toasts) queue.offer(t)
        assertEquals(toasts.takeLast(3), queue.waiting)
    }

    @Test
    fun testANoticeGoesFirstAndTakesOverFromANotification() {
        val queue = StatusToastQueue()
        queue.offer(toast("alice"))
        queue.offer(toast("bob"))
        queue.presentNext()
        assertEquals(StatusToastQueue.Offer.PreemptedActive, queue.offer(StatusToast.Notice("Not connected")))
        assertNull(queue.active)
        assertEquals(StatusToast.Notice("Not connected"), queue.presentNext()?.toast)
        // A notice showing isn't pushed aside by the next one; it waits at the front.
        assertEquals(StatusToastQueue.Offer.Queued, queue.offer(StatusToast.Notice("Other")))
        assertEquals(StatusToast.Notice("Other"), queue.waiting.first())
    }

    @Test
    fun testRequeueAndDrop() {
        val queue = StatusToastQueue()
        val alice = toast("alice")
        val bob = toast("bob")
        queue.offer(alice)
        queue.offer(bob)
        queue.presentNext()
        queue.requeueActive()
        assertNull(queue.active)
        assertEquals(listOf(alice, bob), queue.waiting)
        queue.dropWaiting()
        assertNull(queue.presentNext())
    }
}
