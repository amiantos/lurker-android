// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.content.Context
import android.text.format.DateUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * When an ignore rule lapses, as a person reads it — the kit's `formatExpiry` (see
 * `IgnoreRule.summary`): local time, short, and a word for the day where there is one
 * ("Tomorrow, 4:15 PM"), else the date.
 *
 * `DateUtils` rather than a `java.time` formatter because it is the one that reads the device's
 * 24-hour setting, which a plain JVM formatter cannot see. It also reads the locale and time zone
 * on every call, so a long-lived instance never goes stale over a region change — the care
 * LurkerKit's `ExpiryText` takes with `autoupdatingCurrent`.
 *
 * The day word is decided on CALENDAR days, as iOS's `doesRelativeDateFormatting` decides it, not
 * on elapsed time: at 09:00, a rule lapsing at 21:00 tomorrow and one lapsing at 05:00 tomorrow
 * both say "Tomorrow". `DateUtils.getRelativeDateTimeString`'s `transitionResolution` is an
 * elapsed window and would have split them. Today and tomorrow get a word (a lapse is never in
 * the past); anything further gets the date, with the year only when it is not this one.
 */
class ExpiryText(context: Context, private val now: () -> Instant = Instant::now) : (Instant) -> String {
    private val context = context.applicationContext

    override fun invoke(instant: Instant): String {
        val zone = ZoneId.systemDefault()
        // `LocalDate.ofInstant` is API 34; minSdk is 28.
        val day = instant.atZone(zone).toLocalDate()
        val today = now().atZone(zone).toLocalDate()
        val millis = instant.toEpochMilli()
        val time = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        return when (ChronoUnit.DAYS.between(today, day)) {
            0L, 1L -> {
                // "Today" / "Tomorrow" in the device's language: the span string at day
                // resolution names the day for these two, and only these two are asked.
                val word = DateUtils.getRelativeTimeSpanString(millis, now().toEpochMilli(), DateUtils.DAY_IN_MILLIS, 0)
                "$word, $time"
            }
            else -> {
                var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
                if (day.year == today.year) flags = flags or DateUtils.FORMAT_NO_YEAR else flags = flags or DateUtils.FORMAT_SHOW_YEAR
                DateUtils.formatDateTime(context, millis, flags)
            }
        }
    }
}
