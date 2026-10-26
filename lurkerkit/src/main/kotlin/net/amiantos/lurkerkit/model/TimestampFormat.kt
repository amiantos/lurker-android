// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant
import java.time.ZoneId

/**
 * The web's timestamp formats (`vue_client/src/utils/timestamp.ts`, `formatTimestamp`), ported token
 * for token so a format set in a browser renders the same string here (lurker#1179).
 *
 * Not mapped onto a `DateTimeFormatter` pattern: the two token languages disagree (`DD` is the day of
 * the year there, `a` is always `AM`), and a translation would drift the first time either side grew
 * a token.
 *
 * The tokens: `YYYY` year, `MM` month, `DD` day, `HH` / `H` 24-hour padded / unpadded, `hh` / `h`
 * 12-hour, `mm` minutes, `ss` seconds, `a` am/pm, `A` AM/PM. Everything else passes through, with no
 * escape syntax — a letter that is a token is substituted wherever it stands, as on the web ("at h:mm"
 * reads "pmt 1:05").
 *
 * Port note: LurkerKit's `calendar` is [ZoneId] `zone` here, as in `MessageRows`. `java.time` is
 * always the ISO (Gregorian) calendar, which is the guarantee LurkerKit builds a Gregorian `Calendar`
 * for, and a default of `ZoneId.systemDefault()` is read per call, so it follows a time zone change
 * as LurkerKit's autoupdating zone does.
 */
object TimestampFormat {
    /**
     * Longest first within each prefix, so `HH` wins over `H` and `hh` over `h` — the order of the
     * web's alternation, which takes the first alternative that matches at each position.
     */
    private val tokens = listOf("YYYY", "MM", "DD", "HH", "H", "hh", "h", "mm", "ss", "a", "A")

    /** [date] in [format], or "" for an empty format. */
    fun string(date: Instant, format: String, zone: ZoneId = ZoneId.systemDefault()): String {
        if (format.isEmpty()) return ""
        val parts = date.atZone(zone)
        val h24 = parts.hour
        // 0 and 12 both read 12; 13–23 wrap to 1–11.
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        fun padded(value: Int): String = if (value < 10) "0$value" else "$value"
        fun value(token: String): String =
            when (token) {
                "YYYY" -> "${parts.year}"
                "MM" -> padded(parts.monthValue)
                "DD" -> padded(parts.dayOfMonth)
                "HH" -> padded(h24)
                "H" -> "$h24"
                "hh" -> padded(h12)
                "h" -> "$h12"
                "mm" -> padded(parts.minute)
                "ss" -> padded(parts.second)
                "a" -> if (h24 < 12) "am" else "pm"
                else -> if (h24 < 12) "AM" else "PM"
            }
        // By UTF-16 unit, as the web's regex walks them: the tokens are ASCII, and anything else
        // (a surrogate pair included) passes through unit by unit, intact.
        val out = StringBuilder()
        var index = 0
        while (index < format.length) {
            val token = tokens.firstOrNull { format.startsWith(it, index) }
            if (token != null) {
                out.append(value(token))
                index += token.length
            } else {
                out.append(format[index])
                index += 1
            }
        }
        return out.toString()
    }

    const val compactTimeKey = "look.buffer.time_format_compact"
    const val compactDateKey = "look.buffer.date_format_compact"

    /**
     * A compact-layout line's time: `look.buffer.time_format_compact`, with
     * `look.buffer.date_format_compact` before it on a line from a day other than today (lurker#1237),
     * as the web's `MessageList` builds it. Null when the time format is empty, which hides the time —
     * and the date with it.
     *
     * `withDate = false` for a screen that already says the day, such as the highlights feed under its
     * day headers.
     */
    fun compact(
        date: Instant,
        settings: Settings,
        withDate: Boolean = true,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val time = string(date, settings.string(compactTimeKey, default = "HH:mm"), zone)
        if (time.isEmpty()) return null
        val dateFormat = if (withDate) settings.string(compactDateKey, default = "YYYY-MM-DD") else ""
        if (dateFormat.isEmpty() || date.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) return time
        return "${string(date, dateFormat, zone)} $time"
    }
}
