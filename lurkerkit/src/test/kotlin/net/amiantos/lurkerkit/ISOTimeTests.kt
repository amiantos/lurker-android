// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ISOTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire's timestamp shapes. There are three, and which one a field arrives in is a
 * property of the **column it came from**, not of the wire — so a reader that handles only
 * the common one fails silently on whole features rather than loudly anywhere.
 */
class ISOTimeTests {

    /** 2026-08-29T12:00:00Z, the instant all three spellings below denote. */
    private val instant = Instant.ofEpochSecond(1_788_004_800)

    @Test
    fun testParsesTheEventTimeShapeWithFractionalSeconds() {
        // What almost every timestamp on the wire looks like: an event `time`.
        assertEquals(instant, ISOTime.parse("2026-08-29T12:00:00.000Z"))
    }

    @Test
    fun testParsesIsoWithoutFractionalSeconds() {
        assertEquals(instant, ISOTime.parse("2026-08-29T12:00:00Z"))
    }

    @Test
    fun testParsesSqliteDatetimeNow() {
        // ⚠⚠ A space instead of the `T`, and no zone at all. This is what a column declared
        // `DEFAULT (datetime('now'))` holds, and the server echoes such columns verbatim —
        // `user_nick_notes.updated_at` is one (lurker-ios#12). No ISO parser accepts it, so
        // before this shape was handled on iOS the value silently became nil and the row it
        // fed simply never drew.
        assertEquals(instant, ISOTime.parse("2026-08-29 12:00:00"))
    }

    @Test
    fun testSqliteDatetimeIsReadAsUtcNotAsDeviceLocalTime() {
        // `datetime('now')` is always UTC despite carrying no zone. Reading it as local time
        // is the JS-side bug the web works around in `parseServerTimestamp`, and it would put
        // a note's "Updated…" row hours out for most of the world.
        //
        // Asserted against the ISO spelling of the same instant rather than a fixed offset, so
        // this test says the same thing in every timezone it runs in.
        assertEquals(ISOTime.parse("2026-08-29T12:00:00Z"), ISOTime.parse("2026-08-29 12:00:00"))
    }

    @Test
    fun testAnUnreadableTimestampIsNilRatherThanAThrowOrTheEpoch() {
        for (bad in listOf("", "not a date", "2026-08-29", "29/08/2026 12:00:00")) {
            assertNull(ISOTime.parse(bad), bad)
        }
        assertNull(ISOTime.parse(null))
    }

    @Test
    fun testRoundTripsTheOneShapeThisClientSends() {
        // An ignore rule's `expiresAt` (lurker-ios#86) is the only timestamp this client writes.
        assertEquals(instant, ISOTime.parse(ISOTime.string(instant)))
    }

    // Not in LurkerKit's suite: `ISO8601DateFormatter` fixes the output shape by construction,
    // where this port spells the pattern out by hand.
    @Test
    fun testWritesMillisecondsAndZTheWayToISOStringDoes() {
        assertEquals("2026-08-29T12:00:00.000Z", ISOTime.string(instant))
        assertEquals("2026-08-29T12:00:00.250Z", ISOTime.string(instant.plusMillis(250)))
    }
}
