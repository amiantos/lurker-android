// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespaces
import java.time.Duration
import java.time.Instant

/**
 * The joins this device asked for and hasn't heard back about (lurker-ios#57).
 *
 * A JOIN is a request the server can refuse, forward, or never answer, and every reply is a frame
 * naming a channel: `channel-joined`, `join-error`, `channel-parted`. Which of those answers
 * something the user did has to be remembered here. A separate, pure type for the reason
 * `UnsentCorrelator` is one: the rules are small, each has a way to be wrong, and a test can drive
 * them here where it can't reach a private frame handler.
 *
 * Keyed by `BufferKey.id`, which folds case, so the server's `#Lurker` answers a typed `#lurker`.
 *
 * Port note: a value type in LurkerKit, whose methods both mutate it and return what became of
 * the join. ⚠ Here it is a plain mutable class (PORTING.md, "Structs that mutate", case 3) with
 * exactly one owner, the view model. It must never go into `ChatState` or travel through a flow:
 * a mutation in place neither publishes nor compares as a change.
 */
internal class PendingJoins {

    /** What became of a join, for the view model to act on. */
    sealed interface Outcome {
        /** We're in. `opens` is whether the asker wanted to be taken there. */
        data class Joined(val key: BufferKey, val opens: Boolean) : Outcome

        /** The server said no, and why. */
        data class Refused(val key: BufferKey, val reason: String) : Outcome

        /** Nothing answered in time. */
        data class TimedOut(val key: BufferKey) : Outcome
    }

    private data class Request(
        val key: BufferKey,
        val opens: Boolean,
        val deadline: Instant,
    )

    /**
     * Port note: a map that keeps the order its keys first went in, where a Swift dictionary
     * keeps none. Only `expire` can tell — see there.
     */
    private val requests = mutableMapOf<String, Request>()

    /** Whether anything is waiting on an answer. Test seam. */
    val pendingCount: Int get() = requests.size

    /**
     * Remember a join that was just sent.
     *
     * Asking again for the same channel restarts the clock, and opens if either request wanted to:
     * tapping Join on a parted row and then typing `/join` for it still takes you there.
     */
    fun request(key: BufferKey, opens: Boolean, now: Instant) {
        val deadline = now.plus(timeout)
        val existing = requests[key.id]
        if (existing != null) {
            requests[key.id] = existing.copy(opens = existing.opens || opens, deadline = deadline)
        } else {
            requests[key.id] = Request(key = key, opens = opens, deadline = deadline)
        }
    }

    /**
     * A `channel-joined`. Null for a join nobody here asked for — a reconnect's rejoin, or one made
     * on another device — which must not move the user anywhere.
     */
    fun joined(key: BufferKey): Outcome? {
        val request = requests.remove(key.id) ?: return null
        return Outcome.Joined(key, opens = request.opens)
    }

    /**
     * A `join-error`. Null for a join nobody here asked for: a rejoin refused on reconnect already
     * shows as a parted row, and another device's refusal is that device's to report.
     */
    fun refused(key: BufferKey, reason: String): Outcome? {
        val request = requests.remove(key.id) ?: return null
        return Outcome.Refused(request.key, reason = reason)
    }

    /**
     * A `channel-parted` for a name we asked to join: a 470 forward. The join WAS answered, under a
     * name whose own `channel-joined` arrives separately, so this is dropped without a word rather
     * than left to time out into a "No response" that isn't true.
     */
    fun parted(key: BufferKey) {
        requests.remove(key.id)
    }

    /**
     * The joins whose deadline has passed, oldest first, forgotten as they're returned.
     *
     * Port note: two joins with the SAME deadline come back in the order they were first asked
     * for — the map keeps that order and the sort is stable. In LurkerKit their order is
     * whatever the dictionary's happens to be.
     */
    fun expire(now: Instant): List<Outcome> {
        val expired = requests.filter { it.value.deadline <= now }
        for (id in expired.keys) requests.remove(id)
        return expired.values
            .sortedWith { lhs, rhs -> lhs.deadline.compareTo(rhs.deadline) }
            .map { Outcome.TimedOut(it.key) }
    }

    /** Forget everything: the socket died or the account signed out, so no answer is coming. */
    fun removeAll() {
        requests.clear()
    }

    companion object {
        /** How long a join may go unanswered before the user hears "No response". The web's figure. */
        val timeout: Duration = Duration.ofSeconds(10)

        /**
         * The channels a join names, one per entry. `/join #a,#b` is one JOIN on the wire, but the
         * server answers each channel on its own, so waiting on the list as typed waited on a name
         * nothing ever answers: a "No response" toast for two joins that both worked. (A channel name
         * can't contain a comma, so there's nothing to escape.)
         *
         * Port note: splits at every `,` UTF-16 unit. LurkerKit splits at the `,` *character*, so a
         * comma with a combining mark (or a zero-width joiner) on it is not a separator there and
         * is one here.
         */
        fun channels(list: String): List<String> =
            list.split(",")
                .filter { it.isNotEmpty() }
                .map { it.trimmingWhitespaces() }
                .filter { it.isNotEmpty() }
    }
}

/**
 * A join this device asked for that didn't happen, to tell the user in passing (lurker-ios#57).
 * The app shows it as a toast.
 */
sealed interface JoinNotice {
    /** The server refused. `reason` is its own sentence, such as "This channel is invite-only." */
    data class Refused(val channel: String, val reason: String) : JoinNotice

    /** Nothing came back within `PendingJoins.timeout`. */
    data class NoResponse(val channel: String) : JoinNotice

    /** Never sent: the network isn't connected, or there was no socket to carry the JOIN. */
    data class NotConnected(val channel: String, val network: String) : JoinNotice

    /** What the toast says. */
    val message: String
        get() = when (this) {
            is Refused -> "Couldn't join $channel: $reason"
            is NoResponse -> "No response joining $channel"
            is NotConnected -> "Can't join $channel while $network is offline"
        }
}
