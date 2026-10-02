// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.pow

/**
 * When to ask the server about a URL again — the whole rule, as arithmetic.
 *
 * Pure and separate from `LinkPreviewStore` so it can be exercised directly: the store owns a
 * timer and a task, and a rule buried behind those is a rule tested through a sleep. Ported
 * from the web client's `armReask`, including the three defects that shaped it — each of the
 * ⚠⚠ notes below was a live bug there before it was a comment here.
 */
object PreviewReask {

    /**
     * The longest stated TTL that still means "come back" rather than "this is the answer".
     *
     * ⚠⚠ Without this test every dead link becomes a perpetual poller. The server answers a
     * real failure with a one-hour TTL and a transient refusal with ~15 seconds; re-asking both
     * turned 300 dead links scrolled past into 300 outbound fetches an hour, for as long as the
     * app stayed open. Worse, the client's deadline and the server's row TTL start at the same
     * instant, so the re-ask landed *just after* the row lapsed — a guaranteed cache miss and a
     * fresh fetch to a known-dead origin, every single time. **Only a SHORT TTL means "come
     * back."** A verdict is re-asked only by a new priming pass, once it has genuinely expired.
     */
    val verdictTTL: Duration = Duration.ofSeconds(60)

    /**
     * Floor on the gap between re-asks, doubling per consecutive failure up to `ceiling`.
     *
     * ⚠ A FLOOR, not the delay itself — the delay is whatever the server's own `expiresAt` asks
     * for, and this only raises it. That distinction is what keeps the steady state cheap
     * without needing an attempt cap: a genuinely dead URL is answered with the one-hour failure
     * TTL, so it is a verdict and never reaches here at all. The floor binds only on the ~15s
     * transient answer — which means the instance is saturated, i.e. precisely when backing off
     * is the right thing to do rather than re-asking every 15 seconds forever.
     */
    val floor: Duration = Duration.ofSeconds(15)
    val ceiling: Duration = Duration.ofSeconds(300)

    /**
     * How long to wait before asking again, or **null for "this is the answer, don't"**.
     *
     * - `untilExpiry`: time until the server's stated `expiresAt`. Pass a non-positive value
     *   for "already lapsed", and null-expiry callers should pass zero — an answer with no
     *   stated expiry, which is what a transport failure produces, backs off on the floor alone.
     * - `tries`: how many times this URL has already come back unanswered, 1 for the first.
     * - `jitter`: a value in 0..<1, supplied by the caller so the rule stays pure.
     *
     * ⚠⚠ The jitter is not cosmetic, and `max(untilExpiry, floor)` without it was a real bug.
     * The server jitters its transient TTL specifically so that the losers of one saturation
     * event don't all come back as a single wave; taking the max against a fixed floor throws
     * that away and re-synchronises every client onto the same millisecond — a thundering herd
     * aimed at a server that has just finished saying it is overloaded. ±25%, matching the
     * server's own spread.
     *
     * Port note: the arithmetic is done in floating-point seconds, exactly as LurkerKit does it
     * on `TimeInterval`, and only the result becomes a [Duration] — rounded to the nanosecond.
     * (The doubling overflows a `Duration` long before `ceiling` clamps it; a `Double` just
     * grows.)
     */
    fun delay(untilExpiry: Duration, tries: Int, jitter: Double): Duration? {
        if (untilExpiry > verdictTTL) return null
        val doubled = floor.inSeconds * 2.0.pow(max(0, tries - 1))
        val base = max(untilExpiry.inSeconds, min(ceiling.inSeconds, doubled))
        val seconds = base * (0.75 + jitter.clamped(0.0..<1.0) * 0.5)
        return Duration.ofNanos(Math.round(seconds * 1_000_000_000))
    }
}

private fun Double.clamped(range: OpenEndRange<Double>): Double =
    min(max(this, range.start), range.endExclusive.nextDown())

/** Port note: `TimeInterval` is already this — a `Duration` as floating-point seconds. */
private val Duration.inSeconds: Double get() = seconds + nano / 1_000_000_000.0
