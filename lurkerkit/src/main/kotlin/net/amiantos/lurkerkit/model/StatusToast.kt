// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration

/**
 * What a toast surface can flash for a few seconds: the chat screen's status row, or the capsule
 * over the buffer list.
 */
sealed interface StatusToast {
    /** Something in another conversation; a tap goes there. */
    data class Notification(val notification: StatusNotification) : StatusToast

    /**
     * Something you just did that didn't work: "Not connected — try again when you're back
     * online". A tap just clears it.
     */
    data class Notice(val message: String) : StatusToast

    val isNotification: Boolean get() = this is Notification

    /**
     * The same person, buffer and kind — or the same notice. What a newer toast updates in
     * place rather than queueing behind.
     */
    fun sameSource(other: StatusToast): Boolean = when {
        this is Notification && other is Notification ->
            notification.key.id == other.notification.key.id && notification.kind == other.notification.kind &&
                notification.nick?.lowercase() == other.notification.nick?.lowercase()
        this is Notice && other is Notice -> message == other.message
        else -> false
    }
}

/**
 * The toast showing now, and the ones waiting their turn, oldest first. One surface shows one
 * at a time; the surface owns the timing and asks this what comes next.
 *
 * - A newer line from the same person in the same buffer updates their toast where it is,
 *   showing or waiting, rather than queueing behind it or being dropped: a burst (ChanServ
 *   answering /HELP) is one toast that reads its latest line. It doesn't buy more time.
 * - A notice is about something you just did, so it goes first, and a notification showing
 *   gives way to it.
 *
 * Port note: a struct whose mutators return values (`offer`, `presentNext`), so a plain mutable
 * class with one owner — the surface's presenter — never published, never in `ChatState`
 * (PORTING.md, structs that mutate, case 3).
 */
class StatusToastQueue {
    /** What [offer] did, and so what the surface has to do about it. */
    enum class Offer {
        /** The toast showing was replaced in place: redraw and announce it, keep its timer. */
        UpdatedActive,

        /** A waiting toast was replaced in place: nothing to do yet. */
        UpdatedWaiting,

        /** A notice took over from the notification showing: stop its timer, then present. */
        PreemptedActive,

        /** Added to the queue: present, if the surface is free. */
        Queued,
    }

    /**
     * What [presentNext] made active, and how long it should hold.
     *
     * Port note: the Swift returns a named tuple, `(toast: StatusToast, hold: TimeInterval)`.
     */
    data class Presented(val toast: StatusToast, val hold: Duration)

    var active: StatusToast? = null
        private set
    var waiting: List<StatusToast> = emptyList()
        private set

    fun offer(toast: StatusToast): Offer {
        val active = active
        if (active != null && active.sameSource(toast)) {
            this.active = toast
            return Offer.UpdatedActive
        }
        val index = waiting.indexOfFirst { it.sameSource(toast) }
        if (index >= 0) {
            waiting = waiting.toMutableList().also { it[index] = toast }
            return Offer.UpdatedWaiting
        }
        if (toast is StatusToast.Notice) {
            waiting = listOf(toast) + waiting
            if (active is StatusToast.Notification) {
                this.active = null
                return Offer.PreemptedActive
            }
            return Offer.Queued
        }
        val next = waiting.toMutableList()
        next.add(toast)
        while (next.count { it.isNotification } > cap) {
            val oldest = next.indexOfFirst { it.isNotification }
            if (oldest < 0) break
            next.removeAt(oldest)
        }
        waiting = next
        return Offer.Queued
    }

    /**
     * Make the next waiting toast the active one, with how long it should hold. Null while one
     * is already showing or nothing waits.
     */
    fun presentNext(): Presented? {
        if (active != null || waiting.isEmpty()) return null
        val next = waiting.first()
        waiting = waiting.drop(1)
        active = next
        return Presented(next, if (waiting.isEmpty()) hold else holdBusy)
    }

    /** The active toast ran out its time, or was tapped. */
    fun endActive() {
        active = null
    }

    /**
     * Put the active toast back at the front, to be shown in full later — the completion chips
     * took the row before it had its time.
     */
    fun requeueActive() {
        val active = active ?: return
        this.active = null
        waiting = listOf(active) + waiting
    }

    /** Drop whatever waits: passing news, gone stale while nobody could see it. */
    fun dropWaiting() {
        waiting = emptyList()
    }

    companion object {
        /**
         * How many notifications may wait. Past it the oldest goes: its line is still in its
         * buffer, and the highlight counts still show it.
         */
        const val cap = 3

        /** How long a toast holds the surface. */
        val hold: Duration = Duration.ofSeconds(4)

        /** Shorter while others wait, so a burst drains rather than backing up. */
        val holdBusy: Duration = Duration.ofMillis(2_500)
    }
}
