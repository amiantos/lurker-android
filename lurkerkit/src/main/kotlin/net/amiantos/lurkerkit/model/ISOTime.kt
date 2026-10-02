// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * The server's ISO-8601 `time` field → an [Instant].
 *
 * `ISO_OFFSET_DATE_TIME` reads the server's two ISO shapes on its own — it emits fractional
 * seconds on some paths and not others — so the pair of formatters LurkerKit needs for that
 * collapses to one here. `java.time` formatters are immutable and thread-safe, so there is no
 * lock either.
 */
object ISOTime {
    /**
     * SQLite's `datetime('now')` — `"2026-08-29 12:00:00"`, a space instead of the `T` and no
     * zone at all — which no ISO parser accepts.
     *
     * ⚠⚠ Some server columns are declared `DEFAULT (datetime('now'))` and some
     * `DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))`, and the values reach clients
     * **verbatim**. So which shape a timestamp arrives in is a property of the column it came
     * from, not of the wire, and a reader that handles only the ISO one silently returns null
     * for the others — a blank "Updated …" row rather than an error. That is exactly what
     * `user_nick_notes.updated_at` did (lurker-ios#12).
     *
     * ⚠ It is UTC — `datetime('now')` always is — so the zone is fixed in [parse] rather than
     * left to the device's. Reading it as local time is the JS-side bug the web works around
     * in `parseServerTimestamp`, and is not worth porting.
     *
     * ⚠ `uuuu` and `Locale.ROOT`, because a fixed format string parsed under the user's own
     * locale can pick up a non-Gregorian calendar and misread the year.
     */
    private val sqliteDateTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)

    /**
     * Fractional seconds and `Z`, which is what JS `toISOString()` produces. Spelled out
     * because `Instant.toString()` drops the fraction when it is zero and widens it to
     * nanoseconds when it isn't.
     */
    private val withFraction: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /**
     * Parse, or null if absent/unparseable. Never throws — an unreadable timestamp costs
     * a rendered clock, not a dropped message.
     *
     * Ordered by how often each shape actually arrives: nearly every timestamp on the wire is
     * an event `time`, which is ISO.
     */
    fun parse(iso: String?): Instant? {
        if (iso == null) return null
        return try {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(iso, sqliteDateTime).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * The other direction, for the one field this client sends as a timestamp: an ignore
     * rule's `expiresAt` (lurker-ios#86). Fractional seconds and `Z`, which is what every
     * other row in that table was written with.
     */
    fun string(date: Instant): String = withFraction.format(date)
}
