// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewReask
import net.amiantos.lurkerkit.support.task
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.time.toKotlinDuration

/**
 * Instance feature flags from the public `/api/config`.
 *
 * Absent means off: a server that doesn't advertise a flag doesn't have the feature. Defaults
 * are deliberately the OFF value, so a failed fetch can't conjure a feature the server may not
 * have — the settings rows would appear and then every resolve would 404.
 */
data class InstanceFeatures(val linkPreviews: Boolean = false)

/**
 * The client half of link previews.
 *
 * Everything expensive is on the server — the fetch, the HTML parse, the cache, the byte
 * proxy. What's left here is the thing a client is uniquely placed to do: notice that a
 * screenful of scrollback contains the same eight URLs forty times, and turn that into one
 * request.
 *
 * Two layers of coalescing, catching different things:
 *
 *   - `cache` dedupes across TIME. Scroll past a link and back, and the second pass is free.
 *   - `pending` dedupes across a TICK. A buffer opening lays out a screenful of rows at
 *     once; they all want previews, and they become one POST rather than twenty.
 *
 * Without the second, opening a link-heavy channel would fire a request per row and walk
 * straight into the server's per-user rate limit.
 *
 * Confined to the main thread: every method, and [scope]'s dispatcher, run there.
 *
 * Port note: LurkerKit's `Task { … }`s are launched in [scope], which the app passes on
 * `Dispatchers.Main.immediate` (tests pass a `TestScope`); the kit makes no scope of its own.
 * Each one yields before its body runs — see [task] — so, as a Swift `Task`, none of it runs
 * before the statement that started it returns. LurkerKit's `[weak self]` on both tasks is
 * dropped: a launched coroutine holds the store strongly, and what ends its work instead is
 * [scope] — cancelled, it runs nothing more. A store dropped while its scope lives is kept for
 * at most one coalesce window or one re-ask delay.
 */
class LinkPreviewStore(
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Instant.now() },
    private val jitter: () -> Double = { Random.nextDouble() },
    private val resolve: suspend (List<String>) -> List<LinkPreview>,
) {
    // `now` and `jitter` are injected so the re-ask rule can be tested as arithmetic rather
    // than through a sleep — production passes the real clock and a real random.

    /** One entry of [retry]: LurkerKit's `(at: Date, tries: Int)` tuple. */
    internal data class Retry(val at: Instant, val tries: Int)

    private val cache = mutableMapOf<String, LinkPreview>()

    /**
     * URLs already asked about, so a redraw never re-requests one.
     *
     * ⚠ A URL is REMOVED from this on transport failure. Keeping it meant any batch that
     * failed — a 429 in particular, which the connect-time backlog burst can provoke by
     * itself once scrollback spans enough channels — was remembered as a permanent verdict,
     * and those links stayed blank for the rest of the app session. A failure says nothing
     * about the URL.
     */
    private val asked = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()

    /**
     * Insertion order for `pending`, so batching follows priming order rather than a Set's
     * per-launch hash seed. See `flush`.
     */
    private val pendingOrder = mutableListOf<String>()
    private var flushGeneration = 0
    private var flushTask: Job? = null

    /**
     * URLs due to be asked about again, and how many times they already have been.
     *
     * ⚠⚠ An entry here means SETTLED, not pending — see `isPending`. It holds two different
     * situations: a short-TTL `unavailable` (which has a value) and a URL put back in play by
     * `forgetForRetry` after a transport failure or an omitted answer (which does not). Both
     * are "we will come back to this on our own schedule", and neither is "an answer is on its
     * way right now".
     *
     * ⚠ `tries` is the only record of how many times a URL has failed, so it survives
     * `dropIfExpired`. Discarding it reset the ladder to zero on every priming pass, which in a
     * channel where a bot reposts a failing link meant the backoff never accumulated at all.
     */
    internal val retry: MutableMap<String, Retry> = mutableMapOf()
    private var reaskTask: Job? = null

    /**
     * Called when previews arrive, so the list can re-lay-out the rows that now have one.
     * The list reloads rather than mutating a cell, because a preview changes a row's height.
     *
     * ⚠⚠ Carries WHICH URLs moved, and that is the whole point of the parameter. Without it a
     * consumer can only answer "something changed somewhere", so a batch resolving links posted
     * in `##videogames` rebuilt every visible cell of `#lurker` — on iOS, every `CompactCell` and
     * every `MessageAttachmentsView` subtree discarded and rebuilt, a touch in progress on a link
     * or an image cancelled, and the reader re-pinned to the bottom. A burst is `ceil(N / 20)` of
     * those, 600ms apart.
     *
     * ⚠ The set is every URL whose state MOVED, not only the ones that got a value. A URL the
     * server omitted moves a message's reveal gate exactly as an answer does: `forgetForRetry`
     * arms its ladder, and `isPending` reads anything holding a retry entry as settled — so the
     * omission can be the event that completes a gate. That is why this fires for the whole
     * batch that was sent.
     *
     * ⚠⚠ A RE-ASK is not such an event and does not come through here — see `runDueReasks`.
     */
    var onUpdate: ((Set<String>) -> Unit)? = null

    /**
     * The preview for a URL, if we already have one.
     *
     * Synchronous and non-committal by design: a cell asks during layout and draws whatever
     * is there. Null means "nothing to draw", whether that's "not asked yet", "still in
     * flight" or "came back unusable" — three states a row has no business distinguishing.
     */
    fun preview(url: String): LinkPreview? = cache[url]

    /**
     * Note that a URL is on screen and should be resolved if it hasn't been.
     *
     * Cheap and idempotent: safe to call while binding every visible row (on iOS,
     * `cellForRowAt`), for every URL, on every reload, which is exactly how it's used.
     */
    fun request(url: String) {
        dropIfExpired(url)
        if (asked.contains(url)) return
        // ⚠⚠ The backoff paces BOTH ways back in, not just the timer. `forgetForRetry` takes a
        // URL out of `asked` while arming a deadline, so without this test the very next priming
        // pass — a reconnect's backlog replay, a scroll-up history page, the settings re-walk —
        // sailed through and re-POSTed the whole failed set 24ms after a 429. `tries` kept
        // climbing while the delay it computed was enforced on one path only, which is a
        // backoff that backs off exactly when nothing is happening.
        val deadline = retry[url]?.at
        if (deadline != null && deadline.isAfter(now())) return
        asked.add(url)
        enqueue(url)
        scheduleFlush()
    }

    /** Add to the pending set, remembering the order it arrived in. */
    private fun enqueue(url: String) {
        if (!pending.add(url)) return
        pendingOrder.add(url)
    }

    fun request(urls: List<String>) {
        for (url in urls) request(url)
    }

    private fun scheduleFlush() {
        if (flushTask != null) return
        flushGeneration += 1
        val generation = flushGeneration
        flushTask = scope.task {
            delay(coalesceDelay.toKotlinDuration())
            // ⚠ On iOS, `try?` swallows the cancellation error, so a cancelled task RESUMES
            // here, and a guard has to stop it clearing `flushTask` — clobbering the handle of
            // any newer task scheduled since the reset — and then flushing with no coalescing
            // window, leaving two flushes racing.
            //
            // Port note: a cancelled `delay` throws instead, so a cancelled coroutine never
            // reaches this line and the guard has nothing left to test.
            flush(generation)
            // ⚠⚠ Released AFTER the flush, not before it, and the difference is two bugs.
            //
            // `reset()` cancels through this handle, so clearing it up front left a sign-out
            // with nothing to cancel — and `flush()` is suspended at `resolve` for the
            // whole round trip. A 401 gets there re-entrantly (`resolveLinkPreviews` publishes
            // `Unauthorized`, which runs `onAuthLost()` → `reset()`), so the flush resumed on a
            // store that had just been emptied and refilled it: the previous account's metadata
            // back in `cache`, a fresh re-ask timer armed, and the next account POSTing account
            // A's URLs under account B's token.
            //
            // It also un-suppressed the guard above for the entire duration of a flush, so every
            // priming pass arriving mid-flight — and a connect burst is a continuous stream of
            // them — spawned another flush loop that granted itself an unpaced first batch. The
            // pacing only ever applied WITHIN one loop, so N loops meant N times the request
            // rate, straight into the account rate limit whose 429s then put every URL on the
            // retry ladder: the client manufacturing the storm the pacing exists to prevent.
            //
            // ⚠ Guarded by GENERATION, not by a null check. `reset()` bumps it, so a resumed task
            // cannot clear — or chain onto — a handle belonging to a newer one.
            if (flushGeneration != generation) return@task
            flushTask = null
            // Anything primed while this flush was in the air is still waiting for its turn.
            if (pending.isNotEmpty()) scheduleFlush()
        }
    }

    private suspend fun flush(generation: Int) {
        // ⚠ Sorted, not `pending` read as a list. A `Set`'s iteration order is seeded per launch
        // on iOS, so the batch a URL landed in was random — and with a large burst the buffer
        // actually on screen could sit behind twenty paced batches for the better part of a
        // minute, for no reason anyone could reproduce. Insertion order is the useful order:
        // priming runs from the frame the reader is looking at outwards.
        val urls = pendingOrder.filter { pending.contains(it) }
        pending.clear()
        pendingOrder.clear()
        if (urls.isEmpty()) return

        val batches = urls.chunked(maxBatch)
        for ((index, chunk) in batches.withIndex()) {
            // Port note: a cancelled `delay` throws, where LurkerKit's `try?` carries on to the
            // generation test below. Only `reset()` cancels, and it bumps the generation, so
            // both end the flush here.
            if (index > 0) delay(interBatchDelay.toKotlinDuration())
            // ⚠⚠ Checked on BOTH sides of the suspension. A sign-out that lands mid-flight must
            // not have its cleared state refilled by answers already in the air — `reset()` bumps
            // the generation, so this is the one test that covers a cancel arriving at any point
            // in a multi-batch flush.
            if (flushGeneration != generation) return
            val previews = resolve(chunk)
            if (flushGeneration != generation) return

            val answered = mutableSetOf<String>()
            for (preview in previews) {
                cache[preview.url] = preview
                answered.add(preview.url)
                if (preview.status == LinkPreview.Status.Ok) {
                    retry.remove(preview.url)
                } else {
                    noteUnusableAnswer(preview.url, expiry = preview.expiry)
                }
            }

            // ⚠⚠ Reconciled against what was SENT, not merely iterated over what came back. A
            // URL the server omits — a truncated response, a batch cap drifting out of step with
            // `maxBatch`, a 200 carrying an error body — otherwise reached a state no recovery
            // path could see: `asked` still held it so priming skipped it, and no `retry` entry
            // existed so nothing came back for it. Permanently blank, from a response that
            // looked perfectly fine. A whole-batch failure (offline, 401, 429) is the same case
            // with an empty answer, so it needs no branch of its own any more.
            //
            // ⚠⚠ And a URL only MOVED if it wasn't already on the ladder. Second time round, it
            // was settled before this (`isPending` reads a retry entry as settled) and it is
            // settled after, with no value either way — so nothing about it can draw differently,
            // and telling the list otherwise spends a reload per rung: six for a link the server
            // can't resolve, each rebuilding a row to redraw the same thing. The FIRST omission
            // is the one that matters, because that is the one that moves the URL out of `asked`
            // and completes its message's gate.
            val moved = answered.toMutableSet()
            for (url in chunk) {
                if (answered.contains(url)) continue
                val wasAlreadyOnTheLadder = retry[url] != null
                forgetForRetry(url)
                if (!wasAlreadyOnTheLadder) moved.add(url)
            }

            // ⚠⚠ Per BATCH, not once per flush.
            //
            // ⚠ It is NOT "whenever the batch finishes", which is what this said while it fired
            // unconditionally. That was itself a correction of an older rule — "only if a value
            // landed" — which missed that an `unavailable`, and a URL the server never mentioned
            // at all, are very often the answer that finishes a message's reveal gate. Both
            // versions were reaching for the same question and neither asked it: what fires this
            // is a URL whose state MOVED, which is what `moved` above computes. A batch of
            // nothing but repeat failures for URLs already on the ladder moves nothing.
            //
            // Per batch, because batches after the first are paced 600ms apart. Deferring to the
            // end meant a 200-URL flush (10 batches) held batch 1's images unpainted for the
            // whole drain, and at the scale this class is built for — "a connect burst can prime
            // thousands of URLs" — a 2,000-URL flush is 100 batches and roughly a minute of
            // resolved previews sitting in the cache with nothing told to draw them. The re-ask
            // timer was deferred identically, so every entry that came due during the drain was
            // re-queued in one sweep the moment it finished: precisely the synchronised wave
            // PreviewReask's jitter exists to break up.
            scheduleReask()
            // Silent when nothing moved — a batch of nothing but repeat failures for URLs
            // already on the ladder is a round trip the reader's screen has no stake in.
            if (moved.isNotEmpty()) onUpdate?.invoke(moved)
        }
    }

    // MARK: - Coming back to an answer that wasn't one

    /**
     * Arm (or clear) the re-ask for a URL the server did not answer usefully.
     *
     * `expiry` null means nothing was stated, which is what a transport failure produces — the
     * backoff floor alone then decides.
     * The server ANSWERED, and the answer was not usable.
     *
     * ⚠⚠ An absent or unreadable `expiresAt` is a VERDICT here, not an invitation. Mapping it
     * to "zero seconds until expiry" made it sail through the short-TTL test and arm a poller
     * that `retry` only ever clears on an `ok` — so an `Unavailable` with no stated expiry was
     * re-POSTed forever on the 15s→300s ladder, and PreviewReask has no attempt cap by design.
     * Clock skew was the same trigger from the other direction: a device an hour fast computes
     * a negative `untilExpiry` for every one-hour failure TTL, turning all 300 dead links a
     * reader scrolls past into pollers. `LinkPreview.expiry` already documented this as the
     * safe direction — "the alternative turns one bad field into an unbounded re-ask loop" —
     * and the code did the opposite of its own comment.
     */
    private fun noteUnusableAnswer(url: String, expiry: Instant?) {
        if (expiry == null) {
            retry.remove(url)
            return
        }
        // ⚠⚠ An expiry already in the PAST is a verdict too, and the first version of this only
        // closed the null half of its own documented bug. A lapsed stamp yields a negative
        // `untilExpiry`, which sails through the short-TTL test and arms the ladder — so a device
        // whose clock runs an hour fast reads every one-hour failure TTL as expired and turns all
        // 300 dead links a reader scrolls past into pollers, which is the exact scenario the
        // comment claimed to have closed. A lapsed answer is re-opened by `dropIfExpired` on the
        // next priming pass; it does not need a timer as well.
        val untilExpiry = Duration.between(now(), expiry)
        if (untilExpiry.isNegative || untilExpiry.isZero) {
            retry.remove(url)
            return
        }
        arm(url, untilExpiry = untilExpiry)
    }

    /**
     * Put a URL back in play after NO answer at all — a transport failure, or a URL the server
     * omitted from a response it did send.
     *
     * Distinct from `noteUnusableAnswer` precisely because nothing was stated: there is no
     * verdict to respect, so the backoff floor alone decides when to come back.
     *
     * ⚠ The cached VALUE is deliberately left alone where one exists — a stale card merely due
     * a refresh keeps rendering rather than blanking. What changes is `asked`, which re-opens
     * the URL to priming.
     */
    private fun forgetForRetry(url: String) {
        asked.remove(url)
        // ⚠⚠ Bounded, because this path can never reach a verdict on its own. `PreviewReask`
        // justifies having no attempt cap on the grounds that "a genuinely dead URL is answered
        // with the one-hour failure TTL, so it is a verdict and never reaches here" — true of
        // `noteUnusableAnswer`, false of this. Nothing was ever ANSWERED here, so `delay` sees a
        // zero expiry and always says come back. Two live triggers: the resolve endpoint 502s
        // or the operator turns the feature off mid-session, in which case every URL of every
        // chunk lands here — a connect burst having primed thousands — and polls for the life of
        // the session; or `decodeEach` (LurkerKit's `FailableDecodable`) drops a descriptor this
        // build can't read, which fails identically on every retry, forever.
        //
        // After the ladder reaches its ceiling we stop. `asked` is already clear, so the next
        // priming pass that mentions the URL asks again — driven by new messages arriving rather
        // than by a timer, which is the difference between recovering and hammering.
        val tries = (retry[url]?.tries ?: 0) + 1
        if (tries > maxUnansweredRetries) {
            retry.remove(url)
            return
        }
        arm(url, untilExpiry = Duration.ZERO)
    }

    private fun arm(url: String, untilExpiry: Duration) {
        val tries = (retry[url]?.tries ?: 0) + 1
        val delay = PreviewReask.delay(untilExpiry = untilExpiry, tries = tries, jitter = jitter())
        if (delay == null) {
            // A verdict. Re-asked only by a priming pass once it has genuinely expired, which is
            // what `dropIfExpired` is for.
            retry.remove(url)
            return
        }
        retry[url] = Retry(at = now().plus(delay), tries = tries)
    }

    /**
     * Drop the "already asked" mark once the server's stated TTL has lapsed, so the next
     * priming pass asks again.
     *
     * ⚠ Neither the cached value nor the backoff ladder is discarded — see `retry`.
     */
    private fun dropIfExpired(url: String) {
        val expiry = cache[url]?.expiry ?: return
        if (expiry.isAfter(now())) return
        asked.remove(url)
    }

    /**
     * Re-queue everything whose re-ask has come due. Returns whether anything was queued.
     *
     * ⚠⚠ It notifies NOBODY, and that is not an oversight. A re-ask changes no rendering: the
     * `retry` entry stays (parked at `distantFuture` below), and `isPending` answers `false`
     * for anything holding one — so the message's gate was settled before this ran and is
     * settled after. What the reader eventually sees is the ANSWER, which comes back through
     * `flush` and is announced there. Telling the list about the re-queue instead spent a full
     * visible-rows reload per rung of the ladder — six of them for a link the server can't
     * resolve, each rebuilding every cell on screen to redraw nothing.
     *
     * Internal rather than private so a test can drive it directly: the decision is the part
     * worth asserting, and reaching it through `reaskTask` would mean sleeping out a real
     * backoff to see it.
     */
    internal fun runDueReasks(): Boolean {
        val moment = now()
        var queued = false
        // Port note: over a copy of the entries, as Swift iterates a copy of the dictionary.
        for ((url, state) in retry.entries.toList()) {
            if (state.at.isAfter(moment)) continue
            // Parked as in-flight rather than deleted, and the difference IS the backoff: the
            // `tries` count lives in this entry, so removing it re-armed at tries = 1 every time
            // and the floor never doubled — a "backoff" that was a flat 15-second poll.
            // Whatever comes back re-arms it, or clears it on an `ok`.
            retry[url] = Retry(at = distantFuture, tries = state.tries)
            // Marked `asked` as well as queued, for state consistency rather than as a guard:
            // the URL genuinely has been asked about, and that is what the set means.
            //
            // ⚠ It does NOT carry the duplicate-resolve protection, and the drill says so — the
            // parked `distantFuture` deadline above is what `request()` rejects, so removing
            // this line reddens nothing. Left in because a set called `asked` that excludes a
            // request currently in flight is a trap for the next reader, not because it defends
            // anything today.
            asked.add(url)
            enqueue(url)
            queued = true
        }
        if (queued) scheduleFlush()
        return queued
    }

    /** One task for the whole map, set to the earliest deadline — not a task per URL. */
    private fun scheduleReask() {
        reaskTask?.cancel()
        reaskTask = null
        val earliest = retry.values.minOfOrNull { it.at } ?: return
        if (earliest == distantFuture) return
        val delay = Duration.between(now(), earliest).let { if (it.isNegative) Duration.ZERO else it }
        reaskTask = scope.task {
            // Rounded UP to the millisecond, so the timer never wakes before the deadline it
            // was set for and finds nothing due.
            delay(delay.toKotlinDuration())
            reaskTask = null
            runDueReasks()
            scheduleReask()
        }
    }

    // MARK: - Is an answer still coming?

    /**
     * Whether an answer is still expected for `url`.
     *
     * ⚠⚠ FAILS OPEN in every branch, deliberately. Its consumer withholds a whole message's
     * attachment block while this answers true, so every "not sure" turns a partial failure
     * into a blank message — strictly worse than the layout shift the gate exists to prevent.
     * Only a request actively in flight counts as pending:
     *
     *   - carries a value       → settled, whatever the value says
     *   - nobody ever asked     → settled. A URL nothing primed must render as nothing NOW
     *                             rather than stall its message forever.
     *   - waiting on a re-ask   → SETTLED, and this is the branch that matters most. Counting
     *                             it as pending hid the block for the whole 15s→5min ladder,
     *                             and indefinitely while a server kept failing: one 502 during
     *                             a deploy would blank the attachments of every message sharing
     *                             a batch with it, resolved siblings included.
     *   - queued or in flight   → pending.
     */
    fun isPending(url: String): Boolean {
        if (cache[url] != null) return false
        // Before the `pending`/`asked` test, not after: `runDueReasks` re-queues a URL it is
        // retrying, so the retry state has to win or a recovery attempt would re-hide a block
        // that is already on screen.
        if (retry[url] != null) return false
        return pending.contains(url) || asked.contains(url)
    }

    /**
     * Whether every URL in a message has settled — the atomic-reveal gate.
     *
     * ⚠⚠ ASKED, never timed. The first version of this revealed on a 1500ms deadline, on the
     * reasoning that a message's URLs all land together "~24ms after ingest" — which was
     * `coalesceDelay`, the debounce before the POST is sent, mistaken for the round trip. The
     * server allows 10s of queue plus a 30s resolve deadline PER URL, so the timer fired
     * routinely on any cold link and revealed a partial set, re-creating the exact arrangement
     * flip the gate exists to remove.
     */
    fun allSettled(urls: List<String>): Boolean = !urls.any { isPending(it) }

    /** Drop everything. For sign-out, and for tests. */
    fun reset() {
        cache.clear()
        asked.clear()
        pending.clear()
        pendingOrder.clear()
        retry.clear()
        // ⚠⚠ Bumped, not just cancelled. A flush suspended at `resolve` resumes whatever
        // the handle says, and this is what tells it the store it is about to write into is no
        // longer the store it read from.
        flushGeneration += 1
        flushTask?.cancel()
        flushTask = null
        reaskTask?.cancel()
        reaskTask = null
    }

    private companion object {
        /** Server cap per request. More than this just means a second POST. */
        const val maxBatch = 20

        /**
         * One frame-ish: long enough that everything laid out in the same pass lands in one
         * batch, short enough that a preview never visibly lags the scroll.
         */
        val coalesceDelay: Duration = Duration.ofMillis(24)

        /**
         * Pause between batches once there's more than one.
         *
         * The server allows 120 resolve requests/min per account and takes 20 URLs each. A
         * connect burst can prime thousands of URLs across every buffer, which sails past that —
         * so the client paces itself rather than discovering the limit. ~100 requests/min,
         * comfortably under. Only the first batch is immediate, so the buffer you're looking at
         * isn't delayed.
         */
        val interBatchDelay: Duration = Duration.ofMillis(600)

        /**
         * How many times a URL nobody answered is chased before we let it go.
         *
         * Six lands on the 15s→300s ladder's ceiling, so the last wait is already five minutes.
         * Past that a timer is not recovery, it is a background process.
         */
        const val maxUnansweredRetries = 6

        /**
         * Port note: Foundation's `Date.distantFuture` (the first instant of the year 4001), the
         * deadline a re-ask in flight is parked at.
         */
        val distantFuture: Instant = Instant.parse("4001-01-01T00:00:00Z")
    }
}
