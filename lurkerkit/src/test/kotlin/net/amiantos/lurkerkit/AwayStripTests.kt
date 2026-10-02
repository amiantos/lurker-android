// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.AwayStrip
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The composer's away strip (lurker-ios#135). */
class AwayStripTests {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun date(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    /**
     * The expected time, as the strip's formatter is asked for it — so these pin which form it
     * picks (time, date, year) without pinning any formatter's spacing and joiners.
     *
     * Port note: in LurkerKit this is a `DateFormatter` given the template, and the strip
     * formats with one of its own. Here the strip is handed the formatter, so this stands in
     * for both: it writes down what it was asked.
     */
    private fun formatted(date: Instant, form: AwayStrip.Since): String = "${form.skeleton}@$date"

    private fun strip(away: AwayState?, now: Instant? = null): AwayStrip? =
        AwayStrip.make(away, now = now ?: date(2026, 10, 1, 18, 0), zone = zone, formatted = ::formatted)

    @Test
    fun testNothingWhenNeverAway() {
        assertNull(strip(null))
    }

    @Test
    fun testNothingOnceBackEvenThoughSinceAndReasonSurvive() {
        // ⚠ `since`/`message` outlive `/back` so the dividers can draw the pair. Reading them
        // instead of `active` would leave the strip up after you came back.
        val back = AwayState(
            active = false, message = "lunch", since = date(2026, 10, 1, 12, 0), backAt = date(2026, 10, 1, 13, 0),
        )
        assertNull(strip(back))
    }

    @Test
    fun testAwayWithAReason() {
        val since = date(2026, 10, 1, 14, 32)
        val away = AwayState(active = true, message = "lunch", since = since)
        assertEquals(
            AwayStrip(lead = "Away", detail = " since ${formatted(since, AwayStrip.Since.Time)} · lunch"),
            strip(away),
        )
    }

    @Test
    fun testAwayWithNoReasonIsStillAway() {
        // Unlike the web's `awayLabel`, which shows nothing for the most common `/away`.
        val since = date(2026, 10, 1, 14, 32)
        val away = AwayState(active = true, message = "  ", since = since)
        assertEquals(
            AwayStrip(lead = "Away", detail = " since ${formatted(since, AwayStrip.Since.Time)}"),
            strip(away),
        )
        assertEquals(
            " since ${formatted(since, AwayStrip.Since.Time)}",
            strip(AwayState(active = true, since = since))?.detail,
        )
    }

    @Test
    fun testAColouredReasonReadsAsItsText() {
        val away = AwayState(
            active = true, message = "\u000304lunch\u0003 \u0002soon\u0002", since = date(2026, 10, 1, 14, 32),
        )
        assertEquals(true, strip(away)?.detail?.endsWith(" · lunch soon"))
    }

    @Test
    fun testTheServerIdlingYouOutSaysSo() {
        val away = AwayState(active = true, message = "afk", since = date(2026, 10, 1, 14, 32), autoSet = true)
        assertEquals("Auto-away", strip(away)?.lead)
    }

    @Test
    fun testAnotherDayCarriesTheDate() {
        // "since 2:32 PM" from last week would read as this afternoon.
        val since = date(2026, 9, 28, 14, 32)
        assertEquals(
            " since ${formatted(since, AwayStrip.Since.DateAndTime)}",
            strip(AwayState(active = true, since = since))?.detail,
        )
    }

    @Test
    fun testAnotherYearCarriesTheYear() {
        val since = date(2025, 12, 31, 23, 5)
        val detail = strip(AwayState(active = true, since = since), now = date(2026, 1, 1, 9, 0))?.detail
        assertEquals(" since ${formatted(since, AwayStrip.Since.DateYearAndTime)}", detail)
    }

    // Not ported: three assertions about what `DateFormatter` writes for a template ("Oct" is
    // absent from the time-only form, "Sep 28" and "2025" present in the longer ones). The kit
    // formats nothing here; they belong with the app's formatter, on a device.

    // Port-only:

    @Test
    fun testTheDayIsTheZonesNotUTCs() {
        // 23:30 in Los Angeles on the 1st is already the 2nd in UTC. It is still "today" at
        // 23:45 there, and "another day" a quarter of an hour into the 2nd.
        val since = date(2026, 10, 1, 23, 30)
        assertEquals(AwayStrip.Since.Time, AwayStrip.since(since, now = date(2026, 10, 1, 23, 45), zone = zone))
        assertEquals(AwayStrip.Since.DateAndTime, AwayStrip.since(since, now = date(2026, 10, 2, 0, 15), zone = zone))
    }
}
