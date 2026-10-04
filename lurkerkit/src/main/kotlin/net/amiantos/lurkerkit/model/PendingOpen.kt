// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration
import java.time.Instant

/**
 * A buffer this device asked to open, waiting for its row so the app can go there: a DCC chat
 * opened or accepted (lurker#270), or a DM or query (lurker-ios#201).
 *
 * ⚠ It waits because asking for a buffer doesn't put a row in the store. A DM's `open-buffer` is
 * a write whose answer, the `backlog` that mints the row, comes back later on the socket. A DCC
 * chat's open gets no buffer at all: the server mints the row when it writes the chat's first
 * notice, and `open-buffer` can't make one (it reopens a `=nick` row it has and otherwise does
 * nothing). Going there before the row lands pops straight back: a chat screen whose buffer is
 * absent from a settled roster reads that as a close.
 *
 * A separate, pure type for the reason `PendingJoins` is one: the rule is small, it has a way to
 * be wrong, and a test can drive it here where it can't reach a private frame handler.
 *
 * Port note: immutable in LurkerKit too — two `let`s and no `mutating` method — so a `data class`
 * (PORTING.md, "Structs that mutate", case 1). It is `PendingOpens`, below, that mutates. The
 * Swift struct has the one `init(key:now:)` and no memberwise one, so the primary constructor and
 * `copy` are private here. That init is the companion's `invoke`, not a second constructor: both
 * take a `BufferKey` and an `Instant`, which the JVM can't tell apart.
 */
@ConsistentCopyVisibility
internal data class PendingOpen private constructor(
    val key: BufferKey,
    val deadline: Instant,
) {
    /** Whether this is the wait for `key` — folded, as `BufferKey.id` is. */
    fun isFor(key: BufferKey): Boolean = this.key.id == key.id

    sealed interface Outcome {
        data object Waiting : Outcome

        /** Go there — the stored row's key, in the server's spelling of the name. */
        data class Open(val key: BufferKey) : Outcome

        /** Stop waiting, and go nowhere. */
        data object Expired : Outcome
    }

    /**
     * ⚠ The deadline FIRST. A row that lands after it belongs to a buffer the user has stopped
     * waiting for, and going there would pull them out of whatever they're reading now. Checked
     * after the row, the limit only applied when nothing had arrived — and nothing prunes this
     * between frames, so a row arriving a minute later still navigated.
     *
     * Looked up by `BufferKey.id`, the way the store resolves every buffer, so a DM asked for as
     * `Bob` is satisfied by the server's `bob` row and goes there under that name.
     */
    fun settle(buffers: Map<String, Buffer>, now: Instant): Outcome {
        if (!(now <= deadline)) return Outcome.Expired
        val row = buffers[key.id]
        if (row != null) return Outcome.Open(row.key)
        return Outcome.Waiting
    }

    companion object {
        /**
         * How long to wait. The server answers an open as it acts, so the row is normally there in
         * well under a second; past this, nobody is still watching for it.
         */
        val patience: Duration = Duration.ofSeconds(15)

        operator fun invoke(key: BufferKey, now: Instant): PendingOpen =
            PendingOpen(key = key, deadline = now.plus(patience))
    }
}

/**
 * The buffers this device asked to open, from the request until the app has gone there — the
 * bookkeeping around `PendingOpen`, kept pure so a test can drive the races.
 *
 * Every open takes a number, and only the reply to the LATEST one may install a wait. That one
 * rule settles three races:
 *  - two opens whose replies come back out of order: the earlier request's late reply is stale,
 *    so it can't take the user to the chat they asked for first;
 *  - a reply that outlives the session: `cancel` moves the number on, so an open sent before a
 *    sign-out can't install a wait into whoever signs in next;
 *  - a close that beats an open still in flight: `closing` moves the number on when the open in
 *    flight is for the chat being closed.
 *
 * A DM has no reply to wait for — its `open-buffer` goes out on the socket and the row is the
 * answer — so it takes its number and installs its wait in one step (`waitFor`). Taking the
 * number is still the point: a DCC open still in flight is then stale, because the DM was asked
 * for after it.
 *
 * ⚠ ONE wait, deliberately, shared by DMs and DCC chats. A second open replaces the first: there
 * is one screen to land on, and the buffer asked for last is the one the user is looking for. It
 * replaces it as it is ASKED (`begin`), not when its reply comes back — a wait left standing
 * meanwhile could land, and the newer open's reply would then yank the user on a second time.
 * For the same reason the view model `cancel`s this for a join that opens, and for the user's own
 * move to a buffer.
 *
 * ⚠⚠ A close does its bookkeeping BEFORE its request goes out, not when the reply comes back. The
 * server writes "Cancelled…" into `=nick` as it acts, over the socket, and that frame usually
 * lands before the HTTP reply — so marking on the reply let the notice mint the row and satisfy
 * the wait first, taking the user into the chat they had just ended. A refused close puts the wait
 * back (`closeRefused`).
 *
 * Port note: a value type in LurkerKit, whose methods both mutate it and return a ticket, a mark
 * or the buffer to go to. ⚠ Here it is a plain mutable class (PORTING.md, "Structs that mutate",
 * case 3) with exactly one owner, the view model. It must never go into `ChatState` or travel
 * through a flow: a mutation in place neither publishes nor compares as a change.
 */
internal class PendingOpens {
    /**
     * An open request, as the reply to it will present itself.
     *
     * Port note: the two fields are `fileprivate` in LurkerKit — `PendingOpens`' to read, in the
     * same file, and nobody else's. A Kotlin class cannot see into a nested class's private
     * members, so they are `internal` here; nothing outside this file should read them, and
     * only `PendingOpens` should make one.
     */
    @ConsistentCopyVisibility
    data class Ticket internal constructor(
        internal val number: Int,
        internal val key: BufferKey,
    )

    /**
     * What a close took away, for putting back if the server refuses it.
     *
     * Port note: `fileprivate` fields in LurkerKit, `internal` here — as on [Ticket].
     */
    @ConsistentCopyVisibility
    data class CloseMark internal constructor(
        internal val number: Int,
        internal val waiting: PendingOpen?,
    )

    var waiting: PendingOpen? = null
        private set

    /**
     * The latest open request's number — or a number no request holds, once something has made
     * every request so far stale.
     */
    private var latest = 0

    /** The buffer the latest open request is for, while it's still in flight. */
    private var inFlight: BufferKey? = null

    /** What it takes to open `key` and go there. */
    enum class Plan {
        /**
         * Nothing: we're already waiting for it. A re-tap must not send another `open-buffer`,
         * which every other device is told about, nor restart the clock.
         */
        AlreadyWaiting,

        /**
         * No write — the row is here, and `open-buffer` is a write the server refuses outright
         * for a paused account. Just go.
         */
        Show,

        /** Ask for it, then wait for the row. */
        Write,
    }

    /** See `Plan`. `held` is whether the store has the row; a wait past its deadline is no wait. */
    fun plan(key: BufferKey, held: Boolean, now: Instant): Plan {
        val waiting = waiting
        if (waiting != null && waiting.isFor(key) && now <= waiting.deadline) return Plan.AlreadyWaiting
        return if (held) Plan.Show else Plan.Write
    }

    /** An open is about to go out. It replaces any wait still standing — see the type's note. */
    fun begin(key: BufferKey): Ticket {
        latest += 1
        inFlight = key
        waiting = null
        return Ticket(number = latest, key = key)
    }

    /** An open succeeded: wait for its row, if nothing has overtaken it. */
    fun opened(ticket: Ticket, now: Instant) {
        if (ticket.number != latest) return
        inFlight = null
        waiting = PendingOpen(key = ticket.key, now = now)
    }

    /**
     * An open that is answered by its row alone — a DM's `open-buffer` (lurker-ios#201): wait for
     * it now, as the latest request.
     */
    fun waitFor(key: BufferKey, now: Instant) {
        opened(begin(key), now = now)
    }

    /**
     * A close is about to go out: stop waiting for that buffer, and make an open for it that is
     * still in flight stale.
     */
    fun closing(key: BufferKey): CloseMark {
        if (inFlight?.id == key.id) {
            latest += 1
            inFlight = null
        }
        val taken = if (waiting?.isFor(key) == true) waiting else null
        if (taken != null) waiting = null
        return CloseMark(number = latest, waiting = taken)
    }

    /**
     * The server refused the close, so the buffer is still coming: put back the wait it took —
     * unless the user has asked for something else since.
     */
    fun closeRefused(mark: CloseMark) {
        val taken = mark.waiting
        if (taken == null || waiting != null || latest != mark.number) return
        waiting = taken
    }

    /**
     * Nothing asked for so far may land: something newer has the user's attention — a join that
     * opens, a buffer they went to themselves — or they signed out, and nothing from this session
     * may land in the next.
     */
    fun cancel() {
        latest += 1
        inFlight = null
        waiting = null
    }

    /** The buffer to go to, if the wait just ended in one. Clears the wait either way it ends. */
    fun settle(buffers: Map<String, Buffer>, now: Instant): BufferKey? {
        val pending = waiting ?: return null
        return when (val outcome = pending.settle(buffers = buffers, now = now)) {
            PendingOpen.Outcome.Waiting -> null
            PendingOpen.Outcome.Expired -> {
                waiting = null
                null
            }
            is PendingOpen.Outcome.Open -> {
                waiting = null
                outcome.key
            }
        }
    }
}
