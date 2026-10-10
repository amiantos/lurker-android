// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.amiantos.lurkerkit.model.BufferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEventsTest {
    private val a = BufferKey(1, "#a")
    private val b = BufferKey(1, "#b")

    @Test
    fun nothingButRenamesIsTakenWithoutAScreen() = runBlocking {
        val events = AppEvents()
        events.send(AppEvent.Notice("refused"))
        events.send(AppEvent.OpenBuffer(a))
        events.send(AppEvent.BufferRenamed(a, b))
        assertTrue(events.notices.value.isEmpty())
        // The rename is the only thing queued: the navigator's saved state still holds the old key.
        assertEquals(AppEvent.BufferRenamed(a, b), events.events.first())
    }

    @Test
    fun aNotificationTapWaitsForTheScreen() = runBlocking {
        // A tap that cold-launches the app lands before MainScaffold attaches; dropping it, as a
        // plain OpenBuffer is, would open the app on the list instead of the conversation.
        val events = AppEvents()
        events.openFromNotification(AppEvent.OpenBuffer(a, jumpTo = 7))
        events.openFromNotification(AppEvent.OpenBuffer(b, jumpTo = 9))
        events.attach()
        // Only the latest tap: the first was superseded before anything could show it.
        assertEquals(AppEvent.OpenBuffer(b, jumpTo = 9), events.events.first())
    }

    @Test
    fun signOutDropsAParkedTap() = runBlocking {
        val events = AppEvents()
        events.openFromNotification(AppEvent.OpenBuffer(a))
        events.drain()
        events.attach()
        events.send(AppEvent.BufferRenamed(a, b))
        // The rename is first out: the parked tap belonged to the previous session.
        assertEquals(AppEvent.BufferRenamed(a, b), events.events.first())
    }

    @Test
    fun anAttachedScreenTakesNavigationInOrder() = runBlocking {
        val events = AppEvents()
        events.attach()
        events.send(AppEvent.OpenBuffer(a))
        events.send(AppEvent.BufferRenamed(a, b))
        assertEquals(
            listOf(AppEvent.OpenBuffer(a), AppEvent.BufferRenamed(a, b)),
            events.events.take(2).toList(),
        )
    }

    @Test
    fun aNoticeLeavesOnlyOnceShownAndOnlyFromTheHead() {
        val events = AppEvents()
        events.attach()
        val first = AppEvent.Notice("one")
        val second = AppEvent.Notice("one")
        events.send(first)
        events.send(second)
        // Equal words, different notices: consuming the second while the first waits removes nothing.
        events.consume(second)
        assertEquals(listOf(first, second), events.notices.value)
        events.consume(first)
        assertEquals(listOf(second), events.notices.value)
    }

    @Test
    fun theLastClaimShowsNotices() {
        val events = AppEvents()
        val scaffold = Any()
        val dialog = Any()
        events.claimNotices(scaffold)
        events.claimNotices(dialog)
        assertEquals(dialog, events.noticeHosts.value.last().host)
        events.releaseNotices(dialog)
        assertEquals(scaffold, events.noticeHosts.value.last().host)
        // Standing outranks order: a conversation's host claimed before the scaffold's still shows over
        // it, and a dialog's over both (lurker#1098).
        val conversation = Any()
        events.claimNotices(conversation, priority = 1)
        events.claimNotices(scaffold)
        assertEquals(conversation, events.noticeHosts.value.last().host)
        events.claimNotices(dialog, priority = 2)
        assertEquals(dialog, events.noticeHosts.value.last().host)
        events.releaseNotices(dialog)
        assertEquals(conversation, events.noticeHosts.value.last().host)
    }

    @Test
    fun detachingTheLastScreenAndDrainingClearNotices() {
        val events = AppEvents()
        val screen = events.attach()
        events.send(AppEvent.Notice("one"))
        events.detach(screen)
        assertTrue(events.notices.value.isEmpty())
        events.attach()
        events.send(AppEvent.Notice("two"))
        events.drain()
        assertTrue(events.notices.value.isEmpty())
    }

    @Test
    fun aRotationsSecondAttachDoesNotOutliveTheScreen() {
        val events = AppEvents()
        events.attach()
        // Rotation: the old scaffold skips detach, the new one attaches again.
        val recreated = events.attach()
        events.detach(recreated)
        events.send(AppEvent.Notice("late"))
        assertTrue(events.notices.value.isEmpty())
    }

    /**
     * A quick sign-out and sign-in: the old session's scaffold is still fading out when the new one
     * attaches, and its detach lands after. Only the current attachment's detach counts, so the new
     * screen keeps taking OpenBuffers and notices — and the ones already waiting for it stay.
     */
    @Test
    fun aStaleDetachLeavesTheCurrentScreenAttached() = runBlocking {
        val events = AppEvents()
        val old = events.attach()
        val current = events.attach()
        events.send(AppEvent.Notice("for the new session"))
        events.detach(old)
        assertEquals(1, events.notices.value.size)
        events.send(AppEvent.OpenBuffer(a))
        events.send(AppEvent.Notice("still heard"))
        assertEquals(2, events.notices.value.size)
        assertEquals(AppEvent.OpenBuffer(a), events.events.first())
        // The current one's own detach still switches everything off.
        events.detach(current)
        events.send(AppEvent.Notice("nobody here"))
        assertTrue(events.notices.value.isEmpty())
    }

    @Test
    fun aRefusalNudgesEveryComposerListeningAndNeverQueues() = runBlocking {
        val events = AppEvents()
        // Nobody listening: dropped, not replayed later — the line waits in the kit's hold instead.
        events.sendRefused(a)
        val heard = async(start = CoroutineStart.UNDISPATCHED) { events.refusals.take(2).toList() }
        events.sendRefused(b)
        events.sendRefused(a)
        assertEquals(listOf(b, a), heard.await())
    }
}
