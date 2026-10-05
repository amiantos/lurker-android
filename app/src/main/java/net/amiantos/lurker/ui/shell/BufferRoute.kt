// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.model.BufferKey
import kotlin.random.Random

/**
 * What the buffer list hands the detail pane: a buffer's address, as the list–detail navigator's
 * content key — and, when the buffer was opened to land on one message, which.
 *
 * `Serializable` because the navigator saves its destination history in a Bundle, so the buffer
 * you were reading survives rotation and process death; a `data class` of primitives needs no
 * Parcelize plugin to get there.
 *
 * Its parts, never a `BufferKey.id`: `id` lower-cases the target, and this is what the
 * conversation is opened from — before any frame has arrived to correct the case, the same reason
 * `UiPreferences.lastOpenBufferKey` stores parts. Compare two routes' buffers by [key]'s `id`:
 * two routes to one buffer with different [jump]s are different routes but the same conversation.
 *
 * [visit] tells one open of a buffer from the next (sweep L11): the conversation's saved state —
 * where the list was, the latched "New messages" divider — is kept per visit, so a rotation or a
 * back to a covered conversation restores it and a fresh open starts fresh. `copy` keeps it: an
 * in-place jump is the same visit. A route saved before the field existed restores with 0.
 */
data class BufferRoute(
    /** Null only for the system buffer. */
    val networkId: Int?,
    val target: String,
    /** The message to open at rather than the bottom (lurker-ios#42), or null for a plain open. */
    val jump: JumpRequest? = null,
    val visit: Long = Random.nextLong(),
) : java.io.Serializable {
    val key: BufferKey get() = BufferKey(networkId = networkId, target = target)

    companion object {
        private const val serialVersionUID = 1L

        fun of(key: BufferKey, jump: JumpRequest? = null): BufferRoute =
            BufferRoute(networkId = key.networkId, target = key.target, jump = jump)
    }
}

/**
 * A request to land on one message — a search hit, a bookmark, a highlight, a notification's
 * message, a reply's quoted line.
 *
 * The [nonce] is what makes it a *request* rather than a value. The conversation consumes each one
 * once and remembers the last nonce it consumed (saved, so a rotation, which recreates the screen
 * with the same route, doesn't jump again); jumping to the same message twice — tap a search hit,
 * scroll away, tap it again — is two requests with two nonces, and lands twice.
 *
 * Random rather than counted: a route restored after process death carries a nonce from the dead
 * process, and a counter restarting at 1 could hand a NEW request the nonce the screen already
 * marked consumed.
 */
data class JumpRequest(val messageId: Long, val nonce: Long) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L

        /** A fresh request to land on [messageId]. */
        fun to(messageId: Long): JumpRequest = JumpRequest(messageId = messageId, nonce = Random.nextLong())
    }
}

/**
 * Which [JumpRequest]s have been consumed, for as long as the navigator's history lives — held by
 * `MainScaffold`, which outlives the panes, and saved with that history.
 *
 * Not the conversation's to remember: the history keeps a route's request for as long as the route
 * is in it, and the conversation leaves composition (taking its saved state) whenever another
 * buffer is pushed over it. Back to it rebuilds it from the same route, and a screen-held "last
 * consumed" would jump again — as would one overwritten by a later in-place jump, once a navigation
 * retired that and the route's own request came back into view.
 */
class JumpLedger(consumed: Collection<Long> = emptyList()) {
    private val consumed = LinkedHashSet(consumed)

    /** True the first time [request] is seen — it's the caller's to act on — and false ever after. */
    fun claim(request: JumpRequest): Boolean = consumed.add(request.nonce)

    /** What to save. */
    fun saved(): LongArray = consumed.toLongArray()
}

/**
 * The conversation visits whose saved state is kept, newest first (sweep L11) — held by
 * `MainScaffold` with a `SaveableStateHolder` keyed by visit.
 *
 * Kept rather than dropped as soon as a visit stops showing, because a covered conversation comes
 * back: a join landing pushes #b over #a, and back rebuilds #a from the same route, which should
 * find #a where it was. The navigator doesn't expose its history, so how deep that can go is
 * guessed at [capacity]; past it a visit's state goes, and a conversation that deep comes back
 * fresh. Without the bound every open in a long session would stay in the saved Bundle.
 */
class RecentVisits(visits: Collection<String> = emptyList(), private val capacity: Int = CAPACITY) {
    private val visits = ArrayList(visits)

    /** [visit] is showing: it's the newest. Returns the visits that fell off the end, to drop. */
    fun touch(visit: String): List<String> {
        if (visits.firstOrNull() == visit) return emptyList()
        visits.remove(visit)
        visits.add(0, visit)
        if (visits.size <= capacity) return emptyList()
        val evicted = visits.subList(capacity, visits.size).toList()
        visits.subList(capacity, visits.size).clear()
        return evicted
    }

    /** What to save. */
    fun saved(): ArrayList<String> = ArrayList(visits)

    companion object {
        const val CAPACITY = 8
    }
}
