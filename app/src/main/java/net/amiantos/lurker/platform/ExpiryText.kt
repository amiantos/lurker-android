// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.content.Context
import android.text.format.DateUtils
import java.time.Instant

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
 * Words only within a day either side (`transitionResolution` of a day): iOS's relative formatting
 * names today, tomorrow and yesterday and dates the rest, where a longer transition here would say
 * "In 3 days".
 */
class ExpiryText(context: Context) : (Instant) -> String {
    private val context = context.applicationContext

    override fun invoke(instant: Instant): String =
        DateUtils.getRelativeDateTimeString(
            context,
            instant.toEpochMilli(),
            DateUtils.DAY_IN_MILLIS,
            DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        ).toString()
}
