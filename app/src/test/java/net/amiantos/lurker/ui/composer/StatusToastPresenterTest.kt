// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.model.StatusToast
import net.amiantos.lurkerkit.model.StatusToastQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** One surface's toasts in time: held for their turn, waiting on the chips, dropped when nobody can see them. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusToastPresenterTest {

    private fun toast(nick: String): StatusToast = StatusToast.Notification(
        StatusNotification(
            kind = StatusNotification.Kind.Highlight, key = BufferKey(1, "#a"), nick = nick, text = "hi",
            messageId = 1, date = Instant.EPOCH,
        ),
    )

    private class Surface(scope: TestScope) {
        val presenter = StatusToastPresenter(scope.backgroundScope)
        val drawn = mutableListOf<StatusToast?>()
        val shown = mutableListOf<Pair<StatusToast, Boolean>>()
        var ready = true
        var visible = true

        init {
            presenter.isReady = { ready }
            presenter.isVisible = { visible }
            presenter.onChange = { drawn += presenter.active }
            presenter.onShow = { toast, isNew -> shown += toast to isNew }
        }
    }

    @Test
    fun aToastHoldsForItsTimeThenTheNextComesOn() = runTest {
        val surface = Surface(this)
        val alice = toast("alice")
        val bob = toast("bob")
        surface.presenter.show(alice)
        surface.presenter.show(bob)
        assertEquals(alice, surface.presenter.active)
        assertEquals(listOf(alice to true), surface.shown)
        // Alice went up alone, so she holds the full time; bob waits his turn behind her.
        advanceTimeBy(StatusToastQueue.hold.toMillis() - 1)
        runCurrent()
        assertEquals(alice, surface.presenter.active)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(bob, surface.presenter.active)
        assertEquals(bob to true, surface.shown.last())
        advanceTimeBy(StatusToastQueue.hold.toMillis() + 1)
        runCurrent()
        assertNull(surface.presenter.active)
        // Redrawn at each change: alice up, down, bob up, down.
        assertEquals(listOf(alice, null, bob, null), surface.drawn)
    }

    @Test
    fun aToastWithOthersWaitingHoldsForLess() = runTest {
        val surface = Surface(this)
        surface.ready = false
        surface.presenter.show(toast("alice"))
        surface.presenter.show(toast("bob"))
        surface.ready = true
        surface.presenter.presentNext()
        // Someone waits behind alice, so she holds for less: a burst drains rather than backing up.
        advanceTimeBy(StatusToastQueue.holdBusy.toMillis() + 1)
        runCurrent()
        assertEquals(toast("bob").sameSource(surface.presenter.active!!), true)
    }

    @Test
    fun theSameSourceUpdatesInPlaceWithoutMoreTime() = runTest {
        val surface = Surface(this)
        surface.presenter.show(toast("alice"))
        advanceTimeBy(StatusToastQueue.hold.toMillis() - 500)
        runCurrent()
        val newer = toast("alice")
        surface.presenter.show(newer)
        assertEquals(newer, surface.presenter.active)
        assertEquals(newer to false, surface.shown.last())
        advanceTimeBy(600)
        runCurrent()
        assertNull("the update bought no more time", surface.presenter.active)
    }

    @Test
    fun theChipsHoldTheQueueAndTakeBackAToastShowing() = runTest {
        val surface = Surface(this)
        val alice = toast("alice")
        surface.presenter.show(alice)
        // The chips come up: the toast goes back to the front, to be shown in full once they close.
        surface.ready = false
        surface.presenter.requeueActive()
        assertNull(surface.presenter.active)
        advanceTimeBy(StatusToastQueue.hold.toMillis() * 2)
        runCurrent()
        surface.presenter.show(toast("bob"))
        assertNull("nothing goes up while the chips are up", surface.presenter.active)
        surface.ready = true
        surface.presenter.presentNext()
        assertEquals(alice, surface.presenter.active)
        assertEquals(alice to true, surface.shown.last())
    }

    @Test
    fun aQueueNobodyCanSeeIsDropped() = runTest {
        val surface = Surface(this)
        surface.visible = false
        surface.presenter.show(toast("alice"))
        surface.presenter.show(toast("bob"))
        assertNull(surface.presenter.active)
        surface.visible = true
        surface.presenter.presentNext()
        assertNull("passing news, gone stale while nobody could see it", surface.presenter.active)
    }

    @Test
    fun aNoticeThatNoLongerFitsGoesElsewhereAndTheNextIsTried() = runTest {
        val surface = Surface(this)
        val floated = mutableListOf<String>()
        surface.presenter.shouldPresent = { toast ->
            if (toast is StatusToast.Notice && toast.message.length > 10) {
                floated += toast.message
                false
            } else {
                true
            }
        }
        val bob = toast("bob")
        val carol = toast("carol")
        surface.presenter.show(bob)
        surface.presenter.show(carol)
        surface.presenter.show(StatusToast.Notice("far too long for the row"))
        // The notice took over from bob (who is gone with it — the queue's rule), then floated; the
        // next in line comes on.
        assertEquals(listOf("far too long for the row"), floated)
        assertEquals(carol, surface.presenter.active)
        surface.presenter.show(StatusToast.Notice("short"))
        assertEquals(StatusToast.Notice("short"), surface.presenter.active)
        assertEquals(listOf("far too long for the row"), floated)
    }

    @Test
    fun aTapTakesTheToastDownAndTheCallerBringsOnTheNext() = runTest {
        val surface = Surface(this)
        val alice = toast("alice")
        val bob = toast("bob")
        surface.presenter.show(alice)
        surface.presenter.show(bob)
        assertEquals(alice, surface.presenter.takeActive())
        assertNull(surface.presenter.active)
        surface.presenter.presentNext()
        assertEquals(bob, surface.presenter.active)
        surface.presenter.clear()
        assertNull(surface.presenter.active)
        advanceTimeBy(StatusToastQueue.hold.toMillis() * 2)
        runCurrent()
        assertNull(surface.presenter.active)
    }
}
