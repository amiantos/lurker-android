// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.LinkPreviewStore
import net.amiantos.lurkerkit.model.ISOTime
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import net.amiantos.lurkerkit.model.PreviewReask
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the client-side preview store, with the resolver stubbed.
 *
 * The failure cases are the point. Two of these are regression guards for findings that made
 * links permanently blank for a whole app session — the kind of bug that reads as "the feature
 * doesn't work" rather than as an error.
 *
 * Port note: a Swift Testing suite in LurkerKit ("LinkPreviewStore"); the method names are kept
 * and each display name is the comment above it. LurkerKit runs it on the main actor against
 * real sleeps; here each case is a `runTest`, the store's scope is its `backgroundScope`, and
 * every sleep — the store's and the test's — is on the test's virtual clock, so the timings
 * below are exact rather than bets on the machine. The store's own clock (`TestClock`) is still
 * separate from that one, as it is in LurkerKit.
 */
class LinkPreviewStoreTests {

    /** Records what was asked for, and answers with whatever the test dictates. */
    class Stub {
        val batches = mutableListOf<List<String>>()
        var answer: (List<String>) -> List<LinkPreview> = { urls ->
            urls.map { LinkPreview(url = it, status = LinkPreview.Status.Ok, kind = PreviewKind.Image, src = "/proxy/$it") }
        }
    }

    private fun TestScope.makeStore(stub: Stub): LinkPreviewStore =
        LinkPreviewStore(scope = backgroundScope) { urls ->
            stub.batches.add(urls)
            stub.answer(urls)
        }

    private fun TestScope.makeStore(stub: Stub, clock: TestClock): LinkPreviewStore =
        LinkPreviewStore(scope = backgroundScope, now = { clock.date }, jitter = { 0.5 }) { urls ->
            stub.batches.add(urls)
            stub.answer(urls)
        }

    /**
     * The store coalesces on a short timer, so tests have to let it fire.
     *
     * ⚠ Right for asserting that something did NOT happen — there is no event to wait for, so a
     * duration is the only thing to wait. For the opposite case use `eventually`: a fixed sleep
     * there is a bet on how fast the machine is, and CI is slower and more contended than the
     * one these were written on.
     */
    private suspend fun settle() {
        delay(120)
    }

    /**
     * Wait until something is true, rather than for long enough that it probably is.
     *
     * ⚠⚠ `failureIsRetryable` failed on CI and passed everywhere else: the store's work is paced
     * by real sleeps (a 24ms coalesce, then a resolve), and the suite ran on the main actor
     * with every other suite in parallel — so the 120ms `settle()` it was betting on ran out
     * before a flush that takes ~24ms on an idle laptop. The assertion was about a rule and it
     * was measuring a stopwatch. Polling costs nothing when the condition already holds, and the
     * timeout is long enough that reaching it means something is genuinely wrong.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // `currentTime`
    private suspend fun TestScope.eventually(
        limit: Duration = Duration.ofSeconds(5),
        condition: () -> Boolean,
    ): Boolean {
        val deadline = currentTime + limit.toMillis()
        while (currentTime < deadline) {
            if (condition()) return true
            delay(5)
        }
        return condition()
    }

    // "resolves what it's asked for and serves it back"
    @Test
    fun resolves() = runTest {
        val stub = Stub()
        val store = makeStore(stub)
        store.request(listOf("https://e.test/a.png"))
        settle()
        assertEquals("/proxy/https://e.test/a.png", store.preview("https://e.test/a.png")?.src)
    }

    // "coalesces a batch into one call rather than one per URL"
    @Test
    fun coalesces() = runTest {
        val stub = Stub()
        val store = makeStore(stub)
        store.request(listOf("https://e.test/1", "https://e.test/2", "https://e.test/3"))
        settle()
        assertEquals(1, stub.batches.size)
        assertEquals(3, stub.batches.firstOrNull()?.size)
    }

    // "asks about a repeated URL only once"
    @Test
    fun dedupes() = runTest {
        val stub = Stub()
        val store = makeStore(stub)
        store.request(listOf("https://e.test/x", "https://e.test/x"))
        settle()
        store.request(listOf("https://e.test/x"))
        settle()
        assertEquals(listOf("https://e.test/x"), stub.batches.flatten())
    }

    // "a failed batch is retried later, not remembered as a verdict — but not instantly"
    @Test
    fun failureIsRetryable() = runTest {
        // ⚠⚠ Regression guard. `asked` used to retain a URL whatever came back, and the client
        // maps any non-2xx (including a 429, which the connect-time backlog burst can provoke on
        // its own) to an empty list. So one throttled batch meant those links were blank for the
        // rest of the app session. A transport failure says nothing about the URL.
        //
        // ⚠ It must not be retried IMMEDIATELY either, which is what this asserted before.
        // `forgetForRetry` takes the URL out of `asked` while arming a deadline, so a priming
        // pass arriving right behind a 429 — a reconnect's backlog replay, a scroll-up page —
        // re-POSTed the whole failed set 24ms after the server said it was overloaded. The
        // backoff has to pace both ways back in, not just the timer.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { emptyList() }
        val store = makeStore(stub, clock)

        store.request(listOf("https://e.test/a", "https://e.test/b"))
        assertTrue(eventually { stub.batches.size == 1 })
        assertNull(store.preview("https://e.test/a"))

        // Server recovers — but the ladder still holds, so priming must NOT ask yet.
        stub.answer = { urls ->
            urls.map { LinkPreview(url = it, status = LinkPreview.Status.Ok, kind = PreviewKind.Page, title = "T") }
        }
        store.request(listOf("https://e.test/a", "https://e.test/b"))
        settle() // a non-event: there is nothing to wait FOR
        assertEquals(1, stub.batches.size, "still inside the backoff")

        // Past the deadline, the next priming pass gets through.
        clock.date = clock.date.plus(PreviewReask.floor).plusSeconds(1)
        store.request(listOf("https://e.test/a", "https://e.test/b"))
        // ⚠ Waits on the VALUE, not on the batch count. The stub records its call before it
        // returns, so `batches.size == 2` is observable a moment before the answer has been
        // written into the cache — which would make the title assertion below the flake instead.
        assertTrue(eventually { store.preview("https://e.test/a")?.title == "T" })
        assertEquals(2, stub.batches.size)
    }

    // "an `unavailable` with no stated expiry is a VERDICT, not an invitation"
    @Test
    fun unavailableWithoutExpiryIsAVerdict() = runTest {
        // ⚠⚠ This asserted only that a second `request()` made no second batch — which passed
        // against a store that had armed a perpetual poller, because its 240ms of sleeps are
        // shorter than the 15s floor. It was measuring the sleep, not the rule.
        //
        // The rule: an absent or unreadable `expiresAt` means nothing was stated, and nothing
        // stated is a verdict. Mapping it to "zero seconds until expiry" sailed through the
        // short-TTL test and armed a ladder `retry` only ever clears on an `ok`, so the URL was
        // re-POSTed forever. `LinkPreview.expiry` documented this as the safe direction and the
        // code did the opposite of its own comment.
        val stub = Stub()
        stub.answer = { urls ->
            urls.map { LinkPreview(url = it, status = LinkPreview.Status.Unavailable, kind = PreviewKind.Page) }
        }
        val store = makeStore(stub)

        store.request(listOf("https://e.test/gone"))
        settle()
        assertEquals(1, stub.batches.size)
        assertNull(store.retry["https://e.test/gone"], "nothing armed, so nothing polls")
        assertFalse(store.runDueReasks(), "and nothing ever comes due")

        store.request(listOf("https://e.test/gone"))
        settle()
        assertEquals(1, stub.batches.size)
    }

    // "splits a batch past the server's per-request cap"
    @Test
    fun splitsLargeBatch() = runTest {
        val stub = Stub()
        val store = makeStore(stub)
        store.request((0..<25).map { "https://e.test/$it" })
        // Batches after the first are paced, so this needs longer than the coalesce window.
        delay(900)
        assertEquals(2, stub.batches.size)
        assertEquals(20, stub.batches[0].size)
        assertEquals(5, stub.batches[1].size)
    }

    // "reset drops everything, so a new account starts clean"
    @Test
    fun resetClears() = runTest {
        val stub = Stub()
        val store = makeStore(stub)
        store.request(listOf("https://e.test/a"))
        settle()
        assertNotNull(store.preview("https://e.test/a"))

        store.reset()
        assertNull(store.preview("https://e.test/a"))

        // And `asked` cleared too, so the next account's server is actually consulted.
        store.request(listOf("https://e.test/a"))
        settle()
        assertEquals(2, stub.batches.size)
    }

    // MARK: - Coming back to an answer that wasn't one

    // "a URL the server never mentions is not left permanently blank"
    @Test
    fun omittedUrlRecovers() = runTest {
        // ⚠⚠ The state nothing could see: `asked` still held the URL so priming skipped it, and
        // no retry entry existed so nothing came back for it. Permanently blank, from a 200 that
        // looked perfectly fine — a truncated response, a batch cap out of step, an error body.
        // Reconciling against what was SENT is what closes it.
        val stub = Stub()
        stub.answer = { urls ->
            urls.filter { it.endsWith("a") }
                .map { LinkPreview(url = it, status = LinkPreview.Status.Ok, kind = PreviewKind.Page, title = "T") }
        }
        val store = makeStore(stub)
        store.request(listOf("https://e.test/a", "https://e.test/ghost"))
        settle()

        assertEquals("T", store.preview("https://e.test/a")?.title)
        assertNull(store.preview("https://e.test/ghost"))
        // Not pending — nothing is coming — so it never stalls a message's reveal...
        assertFalse(store.isPending("https://e.test/ghost"))
        // ...and it is armed to be asked about again rather than forgotten.
        assertNotNull(store.retry["https://e.test/ghost"])
    }

    // "re-asks a SHORT-ttl unavailable once its deadline passes"
    @Test
    fun transientIsReasked() = runTest {
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { urls ->
            urls.map {
                LinkPreview(
                    url = it, status = LinkPreview.Status.Unavailable, kind = PreviewKind.Page,
                    expiresAt = ISOTime.string(date = clock.date.plusSeconds(15)),
                )
            }
        }
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/busy"))
        settle()
        assertEquals(1, stub.batches.size)
        assertNotNull(store.retry["https://e.test/busy"])

        // Not yet due: the floor is 15s and the deadline is jittered around it.
        clock.date = clock.date.plusSeconds(5)
        assertFalse(store.runDueReasks())
        assertEquals(1, stub.batches.size)

        clock.date = clock.date.plusSeconds(60)
        assertTrue(store.runDueReasks())
        settle()
        assertEquals(2, stub.batches.size, "the URL is asked about a second time")
    }

    // "never re-asks a VERDICT, so a dead link is not a perpetual poller"
    @Test
    fun verdictIsNotReasked() = runTest {
        // ⚠⚠ The server answers a real failure with a one-hour TTL and a transient refusal with
        // ~15s. Re-asking both turned 300 dead links scrolled past into 300 outbound fetches an
        // hour — and because the client deadline and the server row TTL start together, each one
        // landed just AFTER the row lapsed: a guaranteed cache miss and a fresh fetch to a
        // known-dead origin, forever.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { urls ->
            urls.map {
                LinkPreview(
                    url = it, status = LinkPreview.Status.Unavailable, kind = PreviewKind.Page,
                    expiresAt = ISOTime.string(date = clock.date.plusSeconds(3600)),
                )
            }
        }
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/dead"))
        settle()
        assertNull(store.retry["https://e.test/dead"], "a verdict arms nothing")

        clock.date = clock.date.plusSeconds(600)
        assertFalse(store.runDueReasks())
        assertEquals(1, stub.batches.size)
    }

    // "a verdict IS asked again by priming, once it has genuinely expired"
    @Test
    fun expiredVerdictIsReopenedByPriming() = runTest {
        // The other half of the rule above: a dead link is not polled, but neither is it
        // remembered forever — the next priming pass after the TTL lapses asks again. Before
        // this, `expiresAt` was carried on the wire and read by nobody.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { urls ->
            urls.map {
                LinkPreview(
                    url = it, status = LinkPreview.Status.Unavailable, kind = PreviewKind.Page,
                    expiresAt = ISOTime.string(date = clock.date.plusSeconds(3600)),
                )
            }
        }
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/dead"))
        settle()

        // Still inside the TTL: priming must NOT re-ask, or the cap achieves nothing.
        clock.date = clock.date.plusSeconds(1800)
        store.request(listOf("https://e.test/dead"))
        settle()
        assertEquals(1, stub.batches.size)

        clock.date = clock.date.plusSeconds(1801)
        store.request(listOf("https://e.test/dead"))
        settle()
        assertEquals(2, stub.batches.size)
    }

    // MARK: - Sign-out, and not repopulating what it cleared

    // "a reset mid-flush is not undone by answers already in the air"
    @Test
    fun resetDuringFlushIsNotRepopulated() = runTest {
        // ⚠⚠ Reachable with no timing luck at all: a 401 makes `resolveLinkPreviews` publish
        // `Unauthorized`, which runs `onAuthLost()` → `reset()` RE-ENTRANTLY while the flush is
        // suspended at `resolve`. The flush then resumed on the store it had just emptied
        // and refilled it — the previous account's metadata back in `cache`, a fresh re-ask
        // timer armed — so the next account served A's previews and POSTed A's URLs under B's
        // bearer token. Exactly what the sign-out comment claims to have closed.
        val stub = Stub()
        // LurkerKit makes, and then discards, a plain store here too; nothing reads it.
        makeStore(stub)
        var storeRef: LinkPreviewStore? = null
        val resetting = LinkPreviewStore(scope = backgroundScope) { urls ->
            stub.batches.add(urls)
            // Stand in for the 401 → onAuthLost → reset re-entrancy, at the exact moment the
            // real one happens: while the answer is in flight.
            storeRef?.reset()
            urls.map { LinkPreview(url = it, status = LinkPreview.Status.Ok, kind = PreviewKind.Page, title = "A") }
        }
        storeRef = resetting

        resetting.request(listOf("https://e.test/a"))
        settle()

        assertNull(resetting.preview("https://e.test/a"), "the cleared store must stay cleared")
        assertTrue(resetting.retry.isEmpty(), "and no timer may survive into the next account")
    }

    // "one flush at a time, so pacing is not multiplied by the number of priming passes"
    @Test
    fun flushesDoNotOverlap() = runTest {
        // ⚠⚠ `flushTask` was released BEFORE the flush body ran, so the guard suppressing a
        // second flush stopped suppressing anything for the whole duration of one. A connect
        // burst is a continuous stream of priming passes, and each one landing mid-flight
        // spawned another loop — every loop granting itself an unpaced first batch, because the
        // 600ms delay only ever applies from index 1. N loops, N times the request rate, into
        // the account's 120/min limit whose 429s then put every URL on the ladder.
        val stub = Stub()
        var inFlight = 0
        var peak = 0
        val store = LinkPreviewStore(scope = backgroundScope) { urls ->
            stub.batches.add(urls)
            inFlight += 1
            peak = maxOf(peak, inFlight)
            delay(60)
            inFlight -= 1
            urls.map { LinkPreview(url = it, status = LinkPreview.Status.Ok, kind = PreviewKind.Image) }
        }

        // Five priming passes arriving while the first flush is in the air.
        for (pass in 0..<5) {
            store.request((0..<30).map { "https://e.test/p$pass-$it" })
            delay(30)
        }
        delay(3_000)

        assertEquals(1, peak, "never more than one resolve in flight; saw $peak")
    }

    // "repaints after every batch, not only when the whole flush drains"
    @Test
    fun repaintsPerBatch() = runTest {
        // 200 URLs is 10 batches paced 600ms apart, so deferring the callback to the end held
        // the first batch's images unpainted for the entire drain — and at the scale this class
        // is built for ("a connect burst can prime thousands of URLs") that is about a minute of
        // resolved previews sitting in the cache with nothing told to draw them.
        val stub = Stub()
        val store = makeStore(stub)
        var updates = 0
        store.onUpdate = { updates += 1 }

        store.request((0..<45).map { "https://e.test/$it" })
        // Long enough for two of the three batches, not all three.
        delay(800)
        assertTrue(updates >= 2, "each completed batch paints; saw $updates")
    }

    // "says WHICH urls moved, so a consumer can tell whether it is affected"
    @Test
    fun reportsTheUrlsThatMoved() = runTest {
        // Without this the only thing a list could learn is "something, somewhere, changed" —
        // so links resolving for one buffer rebuilt every visible cell of whichever buffer the
        // reader was actually looking at, once per batch.
        val stub = Stub()
        val store = makeStore(stub)
        val reported = mutableListOf<Set<String>>()
        store.onUpdate = { reported.add(it) }

        store.request(listOf("https://e.test/a", "https://e.test/b"))
        assertTrue(eventually { reported.isNotEmpty() })

        assertEquals(1, reported.size)
        assertEquals(setOf("https://e.test/a", "https://e.test/b"), reported.firstOrNull())
    }

    // "a repeat failure for a url already on the ladder tells the list nothing"
    @Test
    fun repeatFailureIsSilent() = runTest {
        // ⚠ The other half of the re-ask being silent. Its FLUSH still runs, and reporting the
        // whole chunk there put the wasted reload straight back: the URL is mentioned by a
        // visible row, so the filter lets it through, and a dead link redraws the row it sits in
        // once per rung. The first omission is the event — it takes the URL out of `asked` and
        // settles its message's gate. The second says nothing new.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { emptyList() }
        val store = makeStore(stub, clock)

        val reported = mutableListOf<Set<String>>()
        store.onUpdate = { reported.add(it) }
        store.request(listOf("https://e.test/dead"))
        assertTrue(eventually { reported.isNotEmpty() })
        assertEquals(listOf(setOf("https://e.test/dead")), reported, "the first omission settles the gate")

        // The ladder comes due and the server fails it again.
        clock.date = clock.date.plus(PreviewReask.floor).plusSeconds(1)
        store.request(listOf("https://e.test/dead"))
        assertTrue(eventually { stub.batches.size == 2 }, "asked a second time")
        // ⚠ A beat AFTER the batch lands, because the stub records its call before the flush
        // decides whether to say anything — asserting the silence on the batch alone would be
        // asserting it a moment too early, which is a test that passes for the wrong reason.
        settle()
        assertEquals(1, reported.size, "and the second answer says nothing new")
    }

    // "a re-ask tells the list nothing, because it changes nothing on screen"
    @Test
    fun reaskIsSilent() = runTest {
        // ⚠⚠ A re-ask re-queues a URL but KEEPS its retry entry (parked), and `isPending` reads
        // anything holding one as settled — so the message's gate was settled before and is
        // settled after, and no row can draw differently. Announcing it spent a full reload of
        // every visible cell per rung of the ladder: six of them for a link the server can't
        // resolve, each one discarding and rebuilding the screen to redraw nothing. The ANSWER
        // is what gets announced, from the flush that follows.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { emptyList() } // a transport failure: arms the ladder
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/dead"))
        // The ladder has to be armed before the clock is advanced past it, or there is no rung
        // to come due and this tests nothing.
        assertTrue(eventually { store.retry["https://e.test/dead"] != null })

        val reported = mutableListOf<Set<String>>()
        store.onUpdate = { reported.add(it) }
        clock.date = clock.date.plus(PreviewReask.floor).plusSeconds(1)
        assertTrue(store.runDueReasks(), "the rung is due")
        assertTrue(reported.isEmpty(), "and it is nobody's business but the store's")
    }

    // "a url the server never mentioned still counts as moved"
    @Test
    fun reportsOmittedUrlsToo() = runTest {
        // ⚠ The set is what MOVED, not what was answered. An omission goes onto the retry
        // ladder, and `allSettled` reads that as settled — which can be the event that completes
        // a message's reveal gate. Reporting only the answers would leave that message blank
        // until something unrelated redrew it.
        val stub = Stub()
        stub.answer = { urls ->
            urls.filter { it != "https://e.test/b" }
                .map { LinkPreview(url = it, status = LinkPreview.Status.Ok, kind = PreviewKind.Image, src = "/proxy/$it") }
        }
        val store = makeStore(stub)
        val reported = mutableListOf<Set<String>>()
        store.onUpdate = { reported.add(it) }

        store.request(listOf("https://e.test/a", "https://e.test/b"))
        assertTrue(eventually { reported.isNotEmpty() })

        assertEquals(true, reported.firstOrNull()?.contains("https://e.test/b"))
    }

    // "batches follow priming order, so the visible buffer is not sent to the back"
    @Test
    fun batchesFollowPrimingOrder() = runTest {
        // ⚠ Reading `pending` as a list gave an order seeded per launch on iOS, so which batch a
        // URL landed in was random — and on a large burst the buffer actually on screen could
        // sit behind twenty paced batches for the better part of a minute, unreproducibly.
        // Priming runs outward from the frame the reader is looking at, so insertion order is
        // the useful order.
        val stub = Stub()
        val store = makeStore(stub)
        val urls = (0..<40).map { "https://e.test/$it" }
        store.request(urls)
        delay(800)

        assertEquals(urls.take(20), stub.batches.firstOrNull())
    }

    // "an expiry already in the PAST is a verdict, not an invitation"
    @Test
    fun lapsedExpiryIsAVerdict() = runTest {
        // ⚠⚠ The first fix closed only the null half of its own documented bug. A stamp already
        // behind us yields a NEGATIVE untilExpiry, which sails through the short-TTL test and
        // arms the ladder — so a device whose clock runs an hour fast reads every one-hour
        // failure TTL as lapsed and turns all 300 dead links a reader scrolls past into pollers.
        // That is the scenario the comment claimed to have closed. A lapsed answer is re-opened
        // by priming, which does not need a timer as well.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { urls ->
            urls.map {
                LinkPreview(
                    url = it, status = LinkPreview.Status.Unavailable, kind = PreviewKind.Page,
                    expiresAt = ISOTime.string(date = clock.date.minusSeconds(3600)),
                )
            }
        }
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/skewed"))
        settle()
        assertNull(store.retry["https://e.test/skewed"])
        assertFalse(store.runDueReasks())
    }

    // "a URL nobody ever answers is chased a bounded number of times, then let go"
    @Test
    fun unansweredRetriesAreBounded() = runTest {
        // ⚠⚠ This path can never reach a verdict on its own: nothing was ANSWERED, so `delay`
        // sees a zero expiry and always says come back. PreviewReask justifies having no attempt
        // cap on the grounds that a dead URL arrives with a one-hour TTL — true of an answer,
        // false of silence. So a 502, or the operator turning the feature off mid-session, put
        // every primed URL on a permanent five-minute poll for the life of the session.
        //
        // `asked` stays clear, so a later priming pass can still try — driven by new messages
        // rather than by a timer, which is the difference between recovering and hammering.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { emptyList() }
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/silent"))
        settle()

        var rounds = 0
        while (store.retry["https://e.test/silent"] != null && rounds < 20) {
            clock.date = clock.date.plusSeconds(600)
            store.runDueReasks()
            settle()
            rounds += 1
        }
        assertTrue(rounds < 20, "it gave up rather than polling forever")
        assertNull(store.retry["https://e.test/silent"])
    }

    // MARK: - Is an answer still coming?

    // "a URL nobody ever asked about is settled, not pending"
    @Test
    fun neverAskedIsSettled() = runTest {
        // ⚠⚠ Three states, not two. Conflating "in flight" with "nobody ever asked" blanks a
        // message forever: the gate withholds the whole block waiting for an answer that was
        // never requested.
        val store = makeStore(Stub())
        assertFalse(store.isPending("https://e.test/nobody-primed-this"))
        assertTrue(store.allSettled(listOf("https://e.test/nobody-primed-this")))
    }

    // "a URL waiting on a re-ask is SETTLED, so one 502 cannot blank a screen"
    @Test
    fun awaitingReaskIsSettled() = runTest {
        // ⚠⚠ The branch that matters most. Counting a retry as pending hid the block for the
        // whole 15s→5min ladder, and indefinitely while a server kept failing — one 502 during a
        // deploy blanked the attachments of every message that shared a batch with it, siblings
        // that had resolved perfectly well included.
        val stub = Stub()
        stub.answer = { emptyList() }
        val store = makeStore(stub)
        store.request(listOf("https://e.test/a", "https://e.test/b"))
        settle()

        assertNotNull(store.retry["https://e.test/a"])
        assertFalse(store.isPending("https://e.test/a"))
        assertTrue(store.allSettled(listOf("https://e.test/a", "https://e.test/b")))
    }

    // "a URL being re-asked RIGHT NOW is still settled, so recovery never re-hides a block"
    @Test
    fun reaskInFlightIsSettled() = runTest {
        // ⚠⚠ The case the `retry`-before-`pending` ordering exists for, and it took two attempts
        // to write a test that reaches it. After a plain failure the URL sits in neither `asked`
        // nor `pending`, so the ordering is unobservable; and after an `unavailable` there is a
        // cached VALUE, so `isPending` returns on its first line and never consults `retry` at
        // all. The branch is live in exactly one state: no value (a transport failure), and
        // re-queued by `runDueReasks`. There the URL is pending in the plain sense and must
        // still read as settled, or a recovery attempt re-hides a block already on screen.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { emptyList() }
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/busy"))
        settle()
        assertNull(store.preview("https://e.test/busy"), "no value to short-circuit on")

        clock.date = clock.date.plusSeconds(60)
        assertTrue(store.runDueReasks(), "the URL is now queued for a second ask")
        assertNotNull(store.retry["https://e.test/busy"], "and still carries its retry entry")
        assertFalse(store.isPending("https://e.test/busy"))
        assertTrue(store.allSettled(listOf("https://e.test/busy")))
    }

    // "the backoff ladder survives a re-ask, so it genuinely doubles"
    @Test
    fun ladderPersistsAcrossReasks() = runTest {
        // ⚠⚠ `tries` lives in the retry entry, so deleting that entry when re-queueing re-armed
        // at tries = 1 every single time — a "backoff" that was a flat 15-second poll aimed at a
        // server already saying it was overloaded. Parking the entry at `distantFuture` is what
        // keeps the count.
        val clock = TestClock()
        val stub = Stub()
        stub.answer = { emptyList() } // transport failure, every time
        val store = makeStore(stub, clock)
        store.request(listOf("https://e.test/flaky"))
        settle()
        assertEquals(1, store.retry["https://e.test/flaky"]?.tries)
        // jitter at its midpoint is a multiplier of exactly 1, so the first gap is the floor.
        assertEquals(
            PreviewReask.floor,
            store.retry["https://e.test/flaky"]?.at?.let { Duration.between(clock.date, it) },
        )

        clock.date = clock.date.plusSeconds(60)
        assertTrue(store.runDueReasks())
        settle()

        assertEquals(2, store.retry["https://e.test/flaky"]?.tries, "the count carried across")
        assertEquals(
            PreviewReask.floor.multipliedBy(2),
            store.retry["https://e.test/flaky"]?.at?.let { Duration.between(clock.date, it) },
            "so the second gap is twice the first, not the floor again",
        )
    }

    // "a URL in flight IS pending, which is what the gate is for"
    @Test
    fun inFlightIsPending() = runTest {
        val store = makeStore(Stub())
        store.request(listOf("https://e.test/slow"))
        // Deliberately not settled: the coalesce window has not fired, so no answer can exist.
        assertTrue(store.isPending("https://e.test/slow"))
        assertFalse(store.allSettled(listOf("https://e.test/slow")))
        settle()
        assertFalse(store.isPending("https://e.test/slow"))
    }
}

/** A hand-wound clock, so the backoff can be tested as arithmetic rather than through a sleep. */
class TestClock {
    var date: Instant = Instant.ofEpochSecond(1_750_000_000)
}

/**
 * The re-ask rule on its own, away from the store's timers.
 *
 * Port note: a Swift Testing suite in LurkerKit, where each case carries a display name. The
 * method names are kept and the display name is the comment above each.
 */
class PreviewReaskTests {

    private fun seconds(value: Long): Duration = Duration.ofSeconds(value)

    // "only a SHORT ttl means come back"
    @Test
    fun verdictBoundary() {
        assertNotNull(PreviewReask.delay(untilExpiry = seconds(15), tries = 1, jitter = 0.5))
        assertNotNull(PreviewReask.delay(untilExpiry = seconds(60), tries = 1, jitter = 0.5))
        assertNull(PreviewReask.delay(untilExpiry = seconds(61), tries = 1, jitter = 0.5))
        assertNull(PreviewReask.delay(untilExpiry = seconds(3600), tries = 1, jitter = 0.5))
    }

    // "the floor RAISES a short deadline and never lowers a longer one"
    @Test
    fun floorIsAFloor() {
        // ⚠ A floor, not the delay. With jitter at its midpoint the multiplier is exactly 1, so
        // these read as the base value.
        assertEquals(seconds(15), PreviewReask.delay(untilExpiry = seconds(2), tries = 1, jitter = 0.5))
        assertEquals(seconds(40), PreviewReask.delay(untilExpiry = seconds(40), tries = 1, jitter = 0.5))
    }

    // "doubles per consecutive failure, up to a ceiling"
    @Test
    fun backoffLadder() {
        assertEquals(seconds(15), PreviewReask.delay(untilExpiry = seconds(0), tries = 1, jitter = 0.5))
        assertEquals(seconds(30), PreviewReask.delay(untilExpiry = seconds(0), tries = 2, jitter = 0.5))
        assertEquals(seconds(60), PreviewReask.delay(untilExpiry = seconds(0), tries = 3, jitter = 0.5))
        assertEquals(seconds(300), PreviewReask.delay(untilExpiry = seconds(0), tries = 99, jitter = 0.5))
    }

    // "spreads the return by ±25%, so the losers of one stall don't come back together"
    @Test
    fun jitterSpread() {
        // ⚠⚠ Not cosmetic. The server jitters its transient TTL precisely so a saturation event
        // doesn't produce a single returning wave; taking max(untilExpiry, floor) against a
        // fixed floor threw that away and re-synchronised every client onto the same
        // millisecond — a thundering herd aimed at a server that had just said it was overloaded.
        val low = assertNotNull(PreviewReask.delay(untilExpiry = seconds(0), tries = 1, jitter = 0.0))
        val high = assertNotNull(PreviewReask.delay(untilExpiry = seconds(0), tries = 1, jitter = 0.999_999))
        // 15 * 0.75
        assertEquals(Duration.ofMillis(11_250), low)
        // 15 * 1.24 < high <= 15 * 1.25
        assertTrue(high > Duration.ofMillis(18_600) && high <= Duration.ofMillis(18_750))
        assertTrue(low < high)
    }
}
