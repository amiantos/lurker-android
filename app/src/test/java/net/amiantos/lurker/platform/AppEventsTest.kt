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
        assertEquals(dialog, events.noticeHosts.value.last())
        events.releaseNotices(dialog)
        assertEquals(scaffold, events.noticeHosts.value.last())
    }

    @Test
    fun detachingTheLastScreenAndDrainingClearNotices() {
        val events = AppEvents()
        events.attach()
        events.send(AppEvent.Notice("one"))
        events.detach()
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
        events.attach()
        events.detach()
        events.send(AppEvent.Notice("late"))
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
