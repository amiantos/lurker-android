// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration
import java.time.Instant

/**
 * A DCC chat this device opened or accepted, waiting for its `=nick` buffer so the app can go
 * there (lurker#270).
 *
 * ⚠ It waits because the server doesn't answer the open with the buffer. It mints the row when it
 * writes the chat's first notice, and `open-buffer` can't make one (it reopens a `=nick` row it
 * has and otherwise does nothing). Going there before the row lands pops straight back: a chat
 * screen whose buffer is absent from a settled roster reads that as a close.
 *
 * A separate, pure type for the reason `PendingJoins` is one: the rule is small, it has a way to
 * be wrong, and a test can drive it here where it can't reach a private frame handler.
 *
 * Port note: immutable in LurkerKit too — two `let`s and no `mutating` method — so a `data class`
 * (PORTING.md, "Structs that mutate", case 1). It is `DccOpens`, below, that mutates. The Swift
 * struct has the one `init(networkId:nick:now:)` and no memberwise one, so the primary
 * constructor and `copy` are private here.
 */
@ConsistentCopyVisibility
internal data class PendingDccOpen private constructor(
    val key: BufferKey,
    val deadline: Instant,
) {
    constructor(networkId: Int, nick: String, now: Instant) : this(
        key = BufferKey(networkId = networkId, target = DccChat.target(nick)),
        deadline = now.plus(patience),
    )

    /** Whether this is the wait for a chat with `nick` on that network — folded, as `BufferKey` is. */
    fun isFor(networkId: Int, nick: String): Boolean =
        key.id == BufferKey(networkId = networkId, target = DccChat.target(nick)).id

    sealed interface Outcome {
        data object Waiting : Outcome

        /** Go there — the stored row's key, in the server's spelling of the name. */
        data class Open(val key: BufferKey) : Outcome

        /** Stop waiting, and go nowhere. */
        data object Expired : Outcome
    }

    /**
     * ⚠ The deadline FIRST. A row that lands after it belongs to a chat the user has stopped
     * waiting for, and going there would pull them out of whatever they're reading now. Checked
     * after the row, the limit only applied when nothing had arrived — and nothing prunes this
     * between frames, so a row arriving a minute later still navigated.
     */
    fun settle(buffers: Map<String, Buffer>, now: Instant): Outcome {
        if (!(now <= deadline)) return Outcome.Expired
        val row = buffers[key.id]
        if (row != null) return Outcome.Open(row.key)
        return Outcome.Waiting
    }

    companion object {
        /**
         * How long to wait. The server writes its first notice as it acts, so the row is normally
         * there in well under a second; past this, nobody is still watching for it.
         */
        val patience: Duration = Duration.ofSeconds(15)
    }
}

/**
 * The DCC chats this device asked to open, from the request until the app has gone there — the
 * bookkeeping around `PendingDccOpen`, kept pure so a test can drive the races.
 *
 * Every open takes a number, and only the reply to the LATEST one may install a wait. That one
 * rule settles three races:
 *  - two opens whose replies come back out of order: the earlier request's late reply is stale,
 *    so it can't take the user to the chat they asked for first;
 *  - a reply that outlives the session: `reset` moves the number on, so an open sent before a
 *    sign-out can't install a wait into whoever signs in next;
 *  - a close that beats an open still in flight: `closing` moves the number on when the open in
 *    flight is for the chat being closed.
 *
 * ⚠ ONE wait, deliberately. A second open replaces the first: there is one screen to land on, and
 * the chat asked for last is the one the user is looking for.
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
internal class DccOpens {
    /**
     * An open request, as the reply to it will present itself.
     *
     * Port note: the three fields are `fileprivate` in LurkerKit — `DccOpens`' to read, in the
     * same file, and nobody else's. A Kotlin class cannot see into a nested class's private
     * members, so they are `internal` here; nothing outside this file should read them, and
     * only `DccOpens` should make one.
     */
    @ConsistentCopyVisibility
    data class Ticket internal constructor(
        internal val number: Int,
        internal val networkId: Int,
        internal val nick: String,
    )

    /**
     * What a close took away, for putting back if the server refuses it.
     *
     * Port note: `fileprivate` fields in LurkerKit, `internal` here — as on [Ticket].
     */
    @ConsistentCopyVisibility
    data class CloseMark internal constructor(
        internal val number: Int,
        internal val waiting: PendingDccOpen?,
    )

    var waiting: PendingDccOpen? = null
        private set

    /**
     * The latest open request's number — or a number no request holds, once something has made
     * every request so far stale.
     */
    private var latest = 0

    /** The chat the latest open request is for, while it's still in flight. */
    private var inFlight: BufferKey? = null

    /** An open is about to go out. */
    fun begin(networkId: Int, nick: String): Ticket {
        latest += 1
        inFlight = key(networkId, nick)
        return Ticket(number = latest, networkId = networkId, nick = nick)
    }

    /** An open succeeded: wait for its row, if nothing has overtaken it. */
    fun opened(ticket: Ticket, now: Instant) {
        if (ticket.number != latest) return
        inFlight = null
        waiting = PendingDccOpen(networkId = ticket.networkId, nick = ticket.nick, now = now)
    }

    /**
     * A close is about to go out: stop waiting for that chat, and make an open for it that is
     * still in flight stale.
     */
    fun closing(networkId: Int, nick: String): CloseMark {
        val key = key(networkId, nick)
        if (inFlight?.id == key.id) {
            latest += 1
            inFlight = null
        }
        val taken = if (waiting?.isFor(networkId = networkId, nick = nick) == true) waiting else null
        if (taken != null) waiting = null
        return CloseMark(number = latest, waiting = taken)
    }

    /**
     * The server refused the close, so the chat is still coming: put back the wait it took —
     * unless the user has asked for something else since.
     */
    fun closeRefused(mark: CloseMark) {
        val taken = mark.waiting
        if (taken == null || waiting != null || latest != mark.number) return
        waiting = taken
    }

    /** Sign-out: nothing asked for in this session may land in the next one. */
    fun reset() {
        latest += 1
        inFlight = null
        waiting = null
    }

    /** The buffer to go to, if the wait just ended in one. Clears the wait either way it ends. */
    fun settle(buffers: Map<String, Buffer>, now: Instant): BufferKey? {
        val pending = waiting ?: return null
        return when (val outcome = pending.settle(buffers = buffers, now = now)) {
            PendingDccOpen.Outcome.Waiting -> null
            PendingDccOpen.Outcome.Expired -> {
                waiting = null
                null
            }
            is PendingDccOpen.Outcome.Open -> {
                waiting = null
                outcome.key
            }
        }
    }

    private companion object {
        fun key(networkId: Int, nick: String): BufferKey =
            BufferKey(networkId = networkId, target = DccChat.target(nick))
    }
}
