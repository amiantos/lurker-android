// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.time.Instant
import java.time.ZoneId

/**
 * What the composer's away strip says (lurker-ios#135): "Away" in bold, then
 * " since 2:32 PM · lunch".
 *
 * Built only from an away that is `active`. `since` and `message` outlive `/back` on purpose,
 * so the dividers can draw the finished pair (see `AwayState`) — an indicator that read them
 * directly would stay up after you came back.
 */
data class AwayStrip(
    /**
     * "Away", or "Auto-away" when the server set it from idle — the case where you'd least
     * know why you're marked away.
     */
    val lead: String,
    /** When, and the reason if there is one: " since 2:32 PM · lunch". */
    val detail: String,
) {
    /**
     * How much of the date the strip's time carries. Port-only: LurkerKit picks one of three
     * `DateFormatter` templates and formats on the spot; here the choice is the kit's and the
     * formatting is the app's.
     *
     * `skeleton` is that template — an ICU skeleton, which Android's
     * `DateFormat.getBestDateTimePattern` reads as iOS's `setLocalizedDateFormatFromTemplate`
     * does. (`j` is "the locale's hour"; the app decides what the device's 24-hour setting
     * makes of it.)
     */
    enum class Since(val skeleton: String) {
        /** Today: the time alone. */
        Time("jmm"),

        /** Another day this year: the date too. */
        DateAndTime("MMMdjmm"),

        /** Another year: the year too. */
        DateYearAndTime("yMMMdjmm"),
    }

    companion object {
        /**
         * The strip for `away`, or null when there's nothing to show.
         *
         * ⚠ No reason is still away. The web's `awayLabel` returns nothing in that case, which
         * would hide the strip for the most common spelling of `/away`.
         *
         * Port note: `formatted` writes the time, given how much of the date to include — the
         * kit never formats a date for display (PORTING.md). LurkerKit takes a `calendar` and a
         * `locale` for its own `DateFormatter`; the calendar is `zone` here and the locale is
         * the app's business.
         *
         * Port note: ⚠ "this year" is the ISO year. LurkerKit asks the user's own calendar, so
         * on a device set to the Persian or an Islamic calendar the two disagree for an away
         * that spans that calendar's new year, or the Gregorian one: the year is shown, or left
         * out, on the other side of it.
         */
        fun make(
            away: AwayState?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            formatted: (Instant, Since) -> String,
        ): AwayStrip? {
            if (away == null || !away.active) return null
            var detail = " since " + formatted(away.since, since(away.since, now = now, zone = zone))
            // Plain text in a plain label: a reason coloured from another client would otherwise
            // show its control bytes as "04lunch", to the eye and to the screen reader.
            val reason = IRCFormatting.strip(away.message ?: "").trimmingWhitespacesAndNewlines()
            if (reason.isNotEmpty()) detail += " · $reason"
            return AwayStrip(lead = if (away.autoSet) "Auto-away" else "Away", detail = detail)
        }

        /**
         * The time alone for today, the date too for any other day, and the year too for any
         * other year: "since 2:32 PM" from a week ago would read as this afternoon.
         */
        internal fun since(date: Instant, now: Instant, zone: ZoneId): Since {
            val day = date.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            return when {
                day == today -> Since.Time
                day.year == today.year -> Since.DateAndTime
                else -> Since.DateYearAndTime
            }
        }
    }
}
