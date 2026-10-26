// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.TimestampFormat
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The web's timestamp tokens (`vue_client/src/utils/timestamp.test.ts`, ported), and the compact
 * layout's time with its date on older lines (lurker#1179, lurker#1237).
 */
class TimestampFormatTests {
    /** Fixed to UTC so the hours below don't move with the machine running the tests. */
    private val utc = ZoneOffset.UTC

    private fun at(text: String): Instant = Instant.parse(text + "Z")

    private fun format(text: String, fmt: String): String = TimestampFormat.string(at(text), fmt, utc)

    // MARK: - 24-hour tokens

    @Test fun testPaddedTwentyFourHourClock() {
        assertEquals("09:07:03", format("2026-05-21T09:07:03", "HH:mm:ss"))
    }

    @Test fun testUnpaddedTwentyFourHourToken() {
        assertEquals("9:07", format("2026-05-21T09:07:03", "H:mm"))
        assertEquals("13:07", format("2026-05-21T13:07:03", "H:mm"))
    }

    @Test fun testDateTokens() {
        assertEquals("2026-05-21", format("2026-05-21T09:07:03", "YYYY-MM-DD"))
    }

    // MARK: - 12-hour tokens

    @Test fun testAfternoonIsTwelveHourWithPm() {
        assertEquals("01:05 pm", format("2026-05-21T13:05:00", "hh:mm a"))
        assertEquals("1:05 PM", format("2026-05-21T13:05:00", "h:mm A"))
    }

    @Test fun testMorningIsTwelveHourWithAm() {
        assertEquals("9:05 am", format("2026-05-21T09:05:00", "h:mm a"))
    }

    @Test fun testMidnightIsTwelveAmNotZero() {
        assertEquals("12:30 am", format("2026-05-21T00:30:00", "hh:mm a"))
        assertEquals("12:30 AM", format("2026-05-21T00:30:00", "h:mm A"))
    }

    @Test fun testNoonIsTwelvePmNotZero() {
        assertEquals("12:00 pm", format("2026-05-21T12:00:00", "hh:mm a"))
    }

    @Test fun testLateEveningWrapsToSingleDigits() {
        assertEquals("11:45 PM", format("2026-05-21T23:45:00", "h:mm A"))
    }

    // MARK: - Guards

    @Test fun testEmptyFormatIsEmpty() {
        assertEquals("", format("2026-05-21T09:07:03", ""))
    }

    @Test fun testNonTokenCharactersPassThrough() {
        assertEquals("[1:05]", format("2026-05-21T13:05:00", "[h:mm]"))
    }

    @Test fun testTokenLettersAreSubstitutedInsideWords() {
        // No literal escaping, as on the web: the "a" of "at" is am/pm.
        assertEquals("pmt 1:05", format("2026-05-21T13:05:00", "at h:mm"))
    }

    @Test fun testTheDefaultCalendarIsGregorian() {
        // Port note: `java.time` is always the ISO calendar, so there's no calendar to choose. What
        // this pins is that the default zone path prints the Gregorian year, which a Buddhist calendar
        // would print as 2569. Mid-year, so no zone on earth moves it across a new year.
        assertEquals("2026", TimestampFormat.string(at("2026-06-15T12:00:00"), "YYYY"))
    }

    // MARK: - The compact layout's time

    private fun settings(values: Map<String, SettingValue> = emptyMap()) = Settings(registry = emptyMap(), values = values)

    private fun compact(
        text: String,
        now: String = "2026-05-21T18:00:00",
        withDate: Boolean = true,
        values: Map<String, SettingValue> = emptyMap(),
    ): String? = TimestampFormat.compact(at(text), settings(values), withDate = withDate, now = at(now), zone = utc)

    @Test fun testTodayIsTheTimeAlone() {
        assertEquals("09:07", compact("2026-05-21T09:07:03"))
    }

    @Test fun testAnotherDayPutsTheDateFirst() {
        assertEquals("2026-05-20 23:59", compact("2026-05-20T23:59:00"))
    }

    @Test fun testTheTimeFollowsItsSetting() {
        assertEquals("1:05 pm", compact("2026-05-21T13:05:00", values = mapOf(TimestampFormat.compactTimeKey to SettingValue.String("h:mm a"))))
    }

    @Test fun testTheDateFollowsItsSetting() {
        assertEquals("05/20 13:05", compact("2026-05-20T13:05:00", values = mapOf(TimestampFormat.compactDateKey to SettingValue.String("MM/DD"))))
    }

    @Test fun testAnEmptyDateFormatNeverShowsOne() {
        assertEquals("13:05", compact("2026-05-20T13:05:00", values = mapOf(TimestampFormat.compactDateKey to SettingValue.String(""))))
    }

    @Test fun testAnEmptyTimeFormatHidesTheTimeAndTheDate() {
        assertNull(compact("2026-05-20T13:05:00", values = mapOf(TimestampFormat.compactTimeKey to SettingValue.String(""))))
    }

    @Test fun testWithoutTheDateAnotherDayIsTheTimeAlone() {
        assertEquals("13:05", compact("2026-05-20T13:05:00", withDate = false))
    }
}
