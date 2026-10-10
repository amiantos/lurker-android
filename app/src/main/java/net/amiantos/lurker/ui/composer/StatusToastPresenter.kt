// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.amiantos.lurkerkit.model.StatusToast
import net.amiantos.lurkerkit.model.StatusToastQueue

/**
 * Runs a `StatusToastQueue` for one surface — the chat screen's status row, or the capsule over
 * the buffer list: holds each toast for its time, then brings on the next. The surface draws;
 * this decides what it draws and when. lurker-ios's `StatusToastPresenter`, its `Timer` a coroutine
 * on [scope] — the screen's, so a surface that leaves takes its timer with it.
 */
internal class StatusToastPresenter(private val scope: CoroutineScope) {
    private val queue = StatusToastQueue()
    private var timer: Job? = null

    /** The toast showing now. */
    val active: StatusToast? get() = queue.active

    /**
     * Whether the surface is free for the next toast. False holds the queue until [presentNext]
     * is called again — the completion chips own the status row while they're up.
     */
    var isReady: () -> Boolean = { true }

    /**
     * Whether the surface can be seen at all, asked before each toast. False drops whatever
     * waits: one shown under a dialog, or after the screen has gone, would expire unseen while
     * its announcement spoke over somewhere else.
     */
    var isVisible: () -> Boolean = { true }

    /**
     * A toast about to go up. False means it went somewhere else instead — a notice too long for
     * the one-line row floats where it can wrap — and the next one is tried.
     */
    var shouldPresent: (StatusToast) -> Boolean = { true }

    /** The toast showing changed, or went: draw what's current. */
    var onChange: () -> Unit = {}

    /** A toast went up (`isNew`), or the one up was updated in place. */
    var onShow: (toast: StatusToast, isNew: Boolean) -> Unit = { _, _ -> }

    fun show(toast: StatusToast) {
        when (queue.offer(toast)) {
            StatusToastQueue.Offer.UpdatedActive -> {
                onChange()
                onShow(toast, false)
            }
            StatusToastQueue.Offer.UpdatedWaiting -> Unit
            StatusToastQueue.Offer.PreemptedActive -> {
                stopTimer()
                onChange()
                presentNext()
            }
            StatusToastQueue.Offer.Queued -> presentNext()
        }
    }

    /** Bring on the next toast, if the surface is free and one waits. */
    fun presentNext() {
        if (!isReady() || active != null || queue.waiting.isEmpty()) return
        if (!isVisible()) {
            // Passing news, gone stale while nobody could see it; the counts still have the rest.
            queue.dropWaiting()
            return
        }
        val (next, hold) = queue.presentNext() ?: return
        if (!shouldPresent(next)) {
            queue.endActive()
            return presentNext()
        }
        timer = scope.launch {
            delay(hold.toMillis())
            timer = null
            queue.endActive()
            onChange()
            presentNext()
        }
        onChange()
        onShow(next, true)
    }

    /**
     * The toast showing was tapped: take it down and return it. The caller acts on it, then
     * calls [presentNext] — after, so a tap that leaves the screen doesn't start the next toast
     * on the way out.
     */
    fun takeActive(): StatusToast? {
        val toast = active ?: return null
        stopTimer()
        queue.endActive()
        onChange()
        return toast
    }

    /**
     * Put the toast showing back at the front of the queue, to be shown in full once the
     * surface is free again.
     */
    fun requeueActive() {
        if (active == null) return
        stopTimer()
        queue.requeueActive()
        onChange()
    }

    /** Take everything down: the surface is going away. */
    fun clear() {
        stopTimer()
        queue.endActive()
        queue.dropWaiting()
        onChange()
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }
}
