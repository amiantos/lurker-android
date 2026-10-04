// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.startOfDay
import java.time.Instant
import java.time.ZoneId

/**
 * Which day a highlight falls on, for the results list's per-group header stamp. Structured
 * rather than a formatted string so the pure grouping stays UI- and locale-free and the
 * client formats it (Today / Yesterday / a date). `On` carries the start-of-day.
 */
sealed interface HighlightDay {
    data object Today : HighlightDay

    data object Yesterday : HighlightDay

    data class On(val date: Instant) : HighlightDay

    data object Undated : HighlightDay

    companion object {
        /**
         * Classify a date against `now` in `zone`. A null date (an event with no
         * readable time) is `Undated`.
         *
         * "Today"/"yesterday" are measured against the passed-in `now`, NOT the device's current
         * date — on iOS `Calendar.isDateInToday`/`isDateInYesterday` consult the real clock, which
         * would make the result depend on when it runs (breaking tests, previews, and any as-of
         * caller).
         *
         * Port note: LurkerKit's `init(date:now:calendar:)`; an interface has no constructor, so
         * it is `of` here. LurkerKit's `calendar` is `zone`, a `ZoneId`: a Foundation `Calendar` is
         * asked two things here, where a day starts and what the day before it is, and its time
         * zone settles both.
         *
         * Port note: as in LurkerKit, yesterday is `todayStart` moved back a calendar day, and
         * the row is yesterday when it falls on the same day as that instant
         * (`Calendar.isDate(_:inSameDayAs:)`, here the two start-of-days compared). Checked
         * against the Swift for a `now` on every day of 2000–2030 in every zone the two know;
         * they differ only where their time-zone data does (a rule change one has and the other
         * has not yet).
         */
        fun of(date: Instant?, now: Instant, zone: ZoneId): HighlightDay {
            if (date == null) return Undated
            val dayStart = startOfDay(date, zone)
            val todayStart = startOfDay(now, zone)
            // Yesterday is asked as a calendar day, not compared as an instant: where a DST change
            // moves midnight (Havana, Santiago, Cairo), today's start minus a day lands at 01:00
            // on a yesterday that began at 00:00, and its rows got a dated header instead.
            return if (dayStart == todayStart) {
                Today
            } else if (dayStart == startOfDay(dayBefore(todayStart, zone), zone)) {
                Yesterday
            } else {
                On(dayStart)
            }
        }

        /**
         * `Calendar.date(byAdding: .day, value: -1, to:)`: `date` moved back one calendar day,
         * keeping its wall-clock time. Port-only.
         *
         * Port note: written out rather than left to `ZonedDateTime.minusDays`, because the two
         * part ways where a zone's offset moves by a whole day. Samoa skipped 30 December 2011:
         * the day before the 31st is the 29th to Foundation and, the 30th not existing, the 31st
         * itself to `java.time` — which made the 29th `On` here and `Yesterday` on iOS. This is
         * the rule of ICU's `Calendar::add`, which Foundation's answers follow: go back 24
         * hours; if the offset there differs, correct by the difference, less any whole days in
         * it; and if the corrected time is still not the wall-clock time asked for, that time
         * does not exist on that day, and a correction that went backwards is undone.
         */
        private fun dayBefore(date: Instant, zone: ZoneId): Instant {
            val rules = zone.rules
            val moved = date.minusSeconds(SECONDS_IN_DAY.toLong())
            val prevOffset = rules.getOffset(date).totalSeconds
            val newOffset = rules.getOffset(moved).totalSeconds
            val adjustment = (prevOffset - newOffset) % SECONDS_IN_DAY
            if (adjustment == 0) return moved
            val adjusted = moved.plusSeconds(adjustment.toLong())
            val wallTime = date.atZone(zone).toLocalTime()
            if (adjusted.atZone(zone).toLocalTime() != wallTime && adjustment < 0) return moved
            return adjusted
        }

        private const val SECONDS_IN_DAY = 86_400
    }
}

/**
 * One channel+day run of highlights: consecutive matches (input order preserved) that share a
 * buffer and a local calendar day. `offset` is the run's first item's index in the flat input,
 * so the list can page off global position regardless of run sizes.
 */
data class HighlightGroup(
    val networkId: Int?,
    val target: String,
    val day: HighlightDay,
    val offset: Int,
    val items: List<HighlightItem>,
)

/**
 * Groups a flat, order-preserving list of highlights into channel+day runs — the shape the
 * recent-highlights list (and later search/bookmarks) renders. A new run begins whenever the
 * buffer or the local day changes from the previous row, so consecutive matches in one channel
 * on one day share a header; when channels interleave in time a channel repeats, in order,
 * exactly like iMessage search.
 */
object HighlightGrouping {
    /**
     * `now` fixes the reference for Today/Yesterday (passed in, not read, so the result is
     * deterministic and testable). Buffer identity folds case via `BufferKey.id`, so `#Chan`
     * and `#chan` stay one run.
     *
     * Port note: `zone` is a `ZoneId`, defaulting to the device's, where LurkerKit takes a
     * `calendar: Calendar` defaulting to `.current` — see `HighlightDay.of`.
     */
    fun group(
        items: List<HighlightItem>,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HighlightGroup> {
        // Port note: each run gathers into a list of its own and becomes a group once, at the
        // end. LurkerKit appends to the last group in place; a copy per item here would make a
        // long single-channel run quadratic.
        class Run(val first: HighlightItem, val day: HighlightDay, val offset: Int) {
            val items = mutableListOf(first)
        }
        val runs = mutableListOf<Run>()
        for ((index, item) in items.withIndex()) {
            val day = HighlightDay.of(date = item.message.date, now = now, zone = zone)
            val last = runs.lastOrNull()
            if (last != null && last.items.last().bufferKey.id == item.bufferKey.id && last.day == day) {
                last.items.add(item)
            } else {
                runs.add(Run(first = item, day = day, offset = index))
            }
        }
        return runs.map { run ->
            HighlightGroup(
                networkId = run.first.networkId,
                target = run.first.target,
                day = run.day,
                offset = run.offset,
                items = run.items,
            )
        }
    }
}
