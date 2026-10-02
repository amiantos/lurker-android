// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.HighlightDay
import net.amiantos.lurkerkit.model.HighlightGrouping
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.Message
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the channel+day run grouping the recent-highlights list renders: a new run on a
 * buffer or day change, case-folded targets, interleaved channels repeating in order, and
 * the Today/Yesterday/date/undated classification.
 */
class HighlightGroupingTests {
    private val zone = ZoneId.systemDefault()

    // Noon today, so day-boundary math never lands on midnight and flakes.
    private val now: Instant by lazy { startOfDay(Instant.now()).plusSeconds(12 * 3600) }

    /** `calendar.startOfDay(for:)`. Port-only. */
    private fun startOfDay(date: Instant): Instant =
        date.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    private fun item(id: Long, networkId: Int?, target: String, date: Instant?): HighlightItem =
        HighlightItem(
            message = Message(id = id, type = EventType.Message, nick = "a", text = "hi", date = date),
            networkId = networkId, target = target, networkName = null,
        )

    @Test
    fun testConsecutiveSameChannelSameDayIsOneGroup() {
        val items = listOf(
            item(3, networkId = 1, "#a", date = now),
            item(2, networkId = 1, "#a", date = now.plusSeconds(-60)),
        )
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
        assertEquals(HighlightDay.Today, groups[0].day)
        assertEquals(0, groups[0].offset)
    }

    @Test
    fun testDifferentChannelSameDaySplits() {
        val items = listOf(item(3, networkId = 1, "#a", date = now), item(2, networkId = 1, "#b", date = now))
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(listOf("#a", "#b"), groups.map { it.target })
        assertEquals(listOf(0, 1), groups.map { it.offset })
    }

    @Test
    fun testInterleavedChannelsRepeatInOrder() {
        // Order is preserved and a channel repeats when it comes back — iMessage's behavior.
        val items = listOf(
            item(3, networkId = 1, "#a", date = now),
            item(2, networkId = 1, "#b", date = now),
            item(1, networkId = 1, "#a", date = now),
        )
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(listOf("#a", "#b", "#a"), groups.map { it.target })
        assertEquals(listOf(1, 1, 1), groups.map { it.items.size })
        assertEquals(listOf(0, 1, 2), groups.map { it.offset })
    }

    @Test
    fun testSameChannelDifferentDaySplits() {
        val yesterday = now.plusSeconds(-24 * 3600)
        val items = listOf(item(3, networkId = 1, "#a", date = now), item(2, networkId = 1, "#a", date = yesterday))
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(2, groups.size)
        assertEquals(HighlightDay.Today, groups[0].day)
        assertEquals(HighlightDay.Yesterday, groups[1].day)
    }

    @Test
    fun testTargetCaseFoldedIntoOneRun() {
        val items = listOf(item(2, networkId = 1, "#Chan", date = now), item(1, networkId = 1, "#chan", date = now))
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(1, groups.size)
        assertEquals("#Chan", groups[0].target, "the display target keeps the first row's casing")
    }

    @Test
    fun testSameTargetDifferentNetworkSplits() {
        val items = listOf(item(2, networkId = 1, "#a", date = now), item(1, networkId = 2, "#a", date = now))
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(listOf<Int?>(1, 2), groups.map { it.networkId })
    }

    @Test
    fun testUndatedRowsGroupAsUndated() {
        val items = listOf(item(2, networkId = 1, "#a", date = null), item(1, networkId = 1, "#a", date = null))
        val groups = HighlightGrouping.group(items, now = now, zone = zone)
        assertEquals(1, groups.size)
        assertEquals(HighlightDay.Undated, groups[0].day)
    }

    @Test
    fun testOlderDayIsOnStartOfDay() {
        val fiveDaysAgo = now.plusSeconds(-5 * 24 * 3600)
        val groups = HighlightGrouping.group(listOf(item(1, networkId = 1, "#a", date = fiveDaysAgo)), now = now, zone = zone)
        assertEquals(HighlightDay.On(startOfDay(fiveDaysAgo)), groups[0].day)
    }

    @Test
    fun testEmptyInputIsNoGroups() {
        assertTrue(HighlightGrouping.group(emptyList(), now = now, zone = zone).isEmpty())
    }

    @Test
    fun testDayIsClassifiedAgainstPassedNowNotTheDeviceDate() {
        // A fixed `now` that is emphatically not the day this test runs. Today/yesterday must
        // be measured against it, not the real clock — the earlier `isDateInToday` version
        // would have classified all three as `.on(...)`.
        val fixedNow = Instant.ofEpochSecond(1_700_000_000)
        val items = listOf(
            item(3, networkId = 1, "#a", date = fixedNow),
            item(2, networkId = 1, "#a", date = fixedNow.plusSeconds(-24 * 3600)),
            item(1, networkId = 1, "#a", date = fixedNow.plusSeconds(-5 * 24 * 3600)),
        )
        val groups = HighlightGrouping.group(items, now = fixedNow, zone = zone)
        assertEquals(
            listOf(
                HighlightDay.Today,
                HighlightDay.Yesterday,
                HighlightDay.On(startOfDay(fixedNow.plusSeconds(-5 * 24 * 3600))),
            ),
            groups.map { it.day },
        )
    }

    // Port-only:

    /**
     * Pins "the day before" where `java.time` and Foundation disagree about it. Samoa skipped
     * 30 December 2011, so on the 31st yesterday is the 29th — to Foundation; a
     * `ZonedDateTime.minusDays(1)` lands on the day that does not exist and comes back to the
     * 31st. The expectations are the Swift's own answers (LurkerKit's `HighlightDay`, compiled
     * and run on a Mac).
     */
    @Test
    fun testTheDayBeforeASkippedDayIsStillYesterday() {
        val apia = ZoneId.of("Pacific/Apia")
        val noonOnThe31st = Instant.ofEpochMilli(1_325_282_400_000)
        assertEquals(
            HighlightDay.Yesterday,
            HighlightDay.of(date = Instant.ofEpochMilli(1_325_196_000_000), now = noonOnThe31st, zone = apia),
            "noon on the 29th",
        )
        assertEquals(
            HighlightDay.On(Instant.ofEpochMilli(1_325_066_400_000)),
            HighlightDay.of(date = Instant.ofEpochMilli(1_325_113_200_000), now = noonOnThe31st, zone = apia),
            "and the 28th is a date",
        )
    }

    /**
     * Pins what LurkerKit does on a day that does not start at midnight. Cuba's clocks went
     * forward at 00:00 on 10 March 2024, so that day starts at 01:00; a day back from there is
     * 01:00 on the 9th, which is not where the 9th starts — and the 9th reads as a date, not as
     * yesterday. That is the Swift's answer for the same instants, kept rather than corrected.
     */
    @Test
    fun testADayThatStartsLateMakesTheDayBeforeADate() {
        val havana = ZoneId.of("America/Havana")
        val firstInstantOfThe10th = Instant.ofEpochMilli(1_710_046_800_000)
        assertEquals(
            HighlightDay.On(Instant.ofEpochMilli(1_709_960_400_000)),
            HighlightDay.of(date = Instant.ofEpochMilli(1_710_003_600_000), now = firstInstantOfThe10th, zone = havana),
            "noon on the 9th",
        )
        assertEquals(
            HighlightDay.Today,
            HighlightDay.of(date = firstInstantOfThe10th, now = firstInstantOfThe10th, zone = havana),
        )
    }
}
