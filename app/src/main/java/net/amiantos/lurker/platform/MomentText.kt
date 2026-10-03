// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.content.Context
import android.text.format.DateUtils
import java.time.Instant

/**
 * A moment as the profile and channel pages print it — iOS's `dateStyle = .medium, timeStyle =
 * .short` ("Sep 1, 2026, 10:00 AM"), and its long date alone ("September 1, 2026") for a channel's
 * creation.
 *
 * `DateUtils`, as [ExpiryText] uses, because it is the formatter that reads the device's 24-hour
 * setting — a plain `java.time` one would say 3:00 PM beside every other time on screen saying
 * 15:00. It reads the locale and zone on every call, so a long-lived instance never goes stale.
 */
class MomentText(context: Context) {
    private val context = context.applicationContext

    /** Medium date and short time. */
    fun dateTime(instant: Instant): String =
        DateUtils.formatDateTime(
            context,
            instant.toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )

    /** The long date alone. */
    fun longDate(instant: Instant): String =
        DateUtils.formatDateTime(context, instant.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
}
