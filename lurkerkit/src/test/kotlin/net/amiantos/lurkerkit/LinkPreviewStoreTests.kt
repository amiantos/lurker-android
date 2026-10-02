// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.PreviewReask
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Waiting on LinkPreviewStore: the whole `LinkPreviewStoreTests` suite and its `TestClock`.
// LurkerKit keeps the `PreviewReask` suite in the same file, and it needs nothing else.

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
