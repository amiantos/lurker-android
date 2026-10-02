// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus

/**
 * Keeps the app-icon badge equal to `ChatState.totalHighlights` (lurker#490).
 *
 * The icon has two writers: this, and the OS itself painting a push's badge (on iOS,
 * `aps.badge`). Each is right in a different situation, and the job here is to write only when
 * ours is.
 *
 * A push's number is the server's count at SEND time. While the app is closed it is the
 * only truth there is. But it can also land late — after the socket has delivered the
 * read-state that emptied it — and then it paints a number our count already disagrees
 * with. Publishing only the count's *transitions* can never repair that (lurker-ios#134): the
 * count didn't move, so the write that would correct the icon is exactly the one a
 * dedupe swallows, and the icon reads a stale number until something moves the real one.
 *
 * So the current count is also re-asserted, dedupe or no — but only a count the app can
 * vouch for. Writing a count we merely *hold* is the opposite failure: a pre-background
 * leftover, or an empty store at cold launch, painting a too-LOW number over a push's
 * true one, with a real highlight now hidden. The rules:
 *
 *  - Nothing is written until a snapshot has landed this session (`burstGeneration`).
 *    Before that the store's count is "nothing yet", not zero, and the push's paint
 *    stands. A drop back to nothing is sign-out, and that clears the icon.
 *  - A change of the count is written.
 *  - The end of a snapshot burst is written even when the count didn't change: the
 *    roster has just been re-stated in full, so the count is as fresh as it gets. Only
 *    the settling edge — the burst's START re-states nothing (highlights arrive on the
 *    per-buffer frames that follow), and writing there would paint the leftover. Note
 *    this leg only exists on servers that send the `backlog-complete` terminator; an
 *    older self-hosted server never settles the roster, and there `reassert` is the
 *    whole lurker-ios#134 repair.
 *  - `reassert` writes on demand — the scene delegate asks on activation, when the user
 *    has just been looking at the icon — and refuses when the count can't be vouched
 *    for: no snapshot yet, no network path, or a socket known to be down. The caller
 *    holds the other half of that judgement (whether the reconnect it just made is about
 *    to replace the count) and asks only when it isn't.
 *
 * Only the app's `write` closure ever touches the OS: same shape as reachability and
 * push, the app does the platform's notification call and this decides the number.
 *
 * Confined to the main thread.
 *
 * Port note: `follow` takes a `Flow` and the `CoroutineScope` to collect it in, where LurkerKit
 * takes a Combine publisher; the collecting `Job` stands in for the `AnyCancellable`. Collecting
 * in `scope` is what ties the subscription's life to the caller's: LurkerKit's `[weak self]`
 * has no counterpart, because a coroutine's lambda holds `this` strongly until `scope` ends.
 */
class AppBadge(private val write: (Int) -> Unit) {
    private var cancellable: Job? = null

    /**
     * The count and settledness at the last state seen, once the server has spoken this
     * session. `null` is "nothing known": before the first snapshot, and again after
     * sign-out.
     */
    private var seen: Seen? = null

    /** LurkerKit's `(count: Int, settled: Bool)` tuple. */
    private data class Seen(val count: Int, val settled: Boolean)

    /**
     * Follow `states` for the app's lifetime. `statePublisher` replays the current state
     * on subscribe; on a cold launch that's the empty store, which writes nothing.
     *
     * Port note: as assigning a new `AnyCancellable` ends the old subscription in LurkerKit,
     * following again cancels the previous collection.
     */
    fun follow(states: Flow<ChatState>, scope: CoroutineScope) {
        cancellable?.cancel()
        cancellable = scope.launch { states.collect { observe(it) } }
    }

    internal fun observe(state: ChatState) {
        if (!hasServerSpoken(state)) {
            // A session that HAD a count and now has none was reset: sign-out. The icon
            // is that account's; clear it. Nothing-to-nothing (cold launch) says nothing.
            if (seen != null) {
                seen = null
                write(0)
            }
            return
        }
        val now = Seen(count = state.totalHighlights, settled = state.rosterSettled)
        try {
            val seen = this.seen
            if (seen == null) {
                write(now.count)
                return
            }
            if (now.count != seen.count || (now.settled && !seen.settled)) {
                write(now.count)
            }
        } finally {
            seen = now
        }
    }

    /**
     * Write the current count whether or not it changed — if it's one the app can vouch
     * for. See the type doc for what disqualifies it.
     */
    fun reassert(state: ChatState) {
        if (!(hasServerSpoken(state) && state.reachable && state.connection == SocketStatus.Connected)) return
        seen = Seen(count = state.totalHighlights, settled = state.rosterSettled)
        write(state.totalHighlights)
    }

    private companion object {
        /**
         * Whether a snapshot has landed this session. `burstGeneration` is bumped by every
         * `snapshot` frame — the one burst frame every server version sends — and zeroed by
         * `reset()`, so it is exactly "the store's counts come from somewhere".
         */
        fun hasServerSpoken(state: ChatState): Boolean = state.burstGeneration > 0
    }
}
