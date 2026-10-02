// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.MessageRows
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.SpeakerMap
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The layout-independent half of the message list: which rows come out, in what order, and
 * where the dividers cut.
 *
 * Worth pinning because the cuts interact. Every divider is a hard break for *both* the
 * consolidation pass and the bubble-run pass, and a bug there is quiet — a run that silently
 * swallows the first unread message, or a summary sitting under the wrong date, looks like a
 * plausible list rather than a broken one.
 */
class MessageRowsTests {

    /**
     * A fixed zone so a day boundary means the same thing wherever this runs. Without it
     * the day-change tests pass or fail depending on the machine's timezone.
     */
    private val utc: ZoneId = ZoneId.of("UTC")

    /** `utc.startOfDay(for:)`, which a `ZoneId` has no one call for. Port-only. */
    private fun startOfDay(date: Instant): Instant = date.atZone(utc).toLocalDate().atStartOfDay(utc).toInstant()

    /** 2026-07-20 12:00:00 UTC — midday, so ±hours stay inside the day. */
    private val noon: Instant = Instant.ofEpochSecond(1_784_548_800)

    private fun msg(
        id: Long,
        type: EventType = EventType.Message,
        nick: String = "alice",
        text: String? = "hi",
        newNick: String? = null,
        date: Instant? = null,
    ): Message =
        Message(id = id, type = type, nick = nick, text = text, date = date, newNick = newNick)

    private fun build(
        messages: List<Message>,
        dividerAfterId: Long? = null,
        hasMoreOlder: Boolean = true,
        hasMoreNewer: Boolean = false,
        typists: List<String> = emptyList(),
        settings: Settings = Settings(),
        speakers: SpeakerMap = SpeakerMap(),
        away: AwayState? = null,
        now: Instant? = null,
    ): List<MessageRow> =
        MessageRows.build(
            messages = messages, dividerAfterId = dividerAfterId, hasMoreOlder = hasMoreOlder,
            hasMoreNewer = hasMoreNewer, typists = typists, settings = settings, speakers = speakers,
            away = away, now = now ?: noon, zone = utc,
        )

    /**
     * Fetch a row by index, reporting rather than trapping when it isn't there.
     *
     * On iOS, subscripting directly would make an off-by-one regression crash the test
     * *process* — which aborts the run and takes every other suite's results with it, so the one
     * failure you see is whichever test happened to sort first. This reports the real one.
     */
    private fun row(rows: List<MessageRow>, index: Int): MessageRow? {
        if (index !in rows.indices) {
            fail("no row at $index — got ${rows.size} rows: $rows")
        }
        return rows[index]
    }

    private fun isDate(row: MessageRow?): Boolean = row is MessageRow.DateDivider

    private fun isSummary(row: MessageRow): Boolean = row is MessageRow.Consolidated

    // MARK: - Date dividers

    @Test
    fun testADateDividerOpensTheBuffer() {
        // Including the very first message: the reader should never have to guess what day
        // the top of the buffer is.
        val rows = build(listOf(msg(1, date = noon)))
        assertTrue(isDate(row(rows, 0)), "the first row is the day of the first message")
        assertEquals(2, rows.size)
    }

    @Test
    fun testADividerLandsOnEachDayChange() {
        val rows = build(
            listOf(
                msg(1, date = noon),
                msg(2, date = noon.plusSeconds(3600)), // same day
                msg(3, date = noon.plusSeconds(86_400)), // next day
            ),
        )
        assertEquals(2, rows.filter(::isDate).size)
        // date, msg, msg, date, msg
        assertTrue(isDate(row(rows, 0)))
        assertTrue(isDate(row(rows, 3)))
    }

    @Test
    fun testTheDividerCarriesLocalMidnight() {
        val divider = row(build(listOf(msg(1, date = noon))), 0) as? MessageRow.DateDivider
            ?: fail("expected a date divider")
        assertEquals(startOfDay(noon), divider.day)
    }

    @Test
    fun testAnUndatedMessageDoesNotEmitADivider() {
        // A synthesized/ephemeral line has no time. It can't name a day, and it must not
        // reset the day either — otherwise the next real message re-emits a divider for a
        // day that's already open.
        val rows = build(
            listOf(
                msg(1, date = noon),
                msg(2, date = null),
                msg(3, date = noon.plusSeconds(3600)),
            ),
        )
        assertEquals(1, rows.filter(::isDate).size)
    }

    /**
     * A buffer can *open* with an undated line — `LurkerStore.appendLocal` synthesizes one for
     * an unrecognized command, and in an empty system buffer that's the first row. It must
     * still sit under a day header rather than being stranded above the divider that appears
     * once real traffic lands.
     */
    @Test
    fun testALeadingUndatedRunAdoptsTheFirstDatedDay() {
        val rows = build(
            listOf(
                msg(1, date = null),
                msg(2, date = null),
                msg(3, date = noon),
            ),
        )
        assertEquals(1, rows.filter(::isDate).size, "one header, not one below the local lines")
        assertTrue(isDate(row(rows, 0)), "and it sits above them")
        val divider = row(rows, 0) as? MessageRow.DateDivider ?: fail("expected a date divider")
        assertEquals(startOfDay(noon), divider.day)
        assertEquals(4, rows.size)
    }

    @Test
    fun testAllUndatedMessagesGetNoDayHeader() {
        // Nothing to name. Guessing a day would be a claim the data doesn't support.
        val rows = build(listOf(msg(1, date = null), msg(2, date = null)))
        assertEquals(0, rows.filter(::isDate).size)
    }

    // MARK: - Start of history

    @Test
    fun testStartOfHistorySitsAboveEverything() {
        val rows = build(listOf(msg(1, date = noon)), hasMoreOlder = false)
        if (rows.firstOrNull() != MessageRow.StartOfHistory) {
            fail("the marker goes above even the first date divider")
        }
        assertTrue(isDate(row(rows, 1)))
    }

    @Test
    fun testStartOfHistoryIsSuppressedWhileMoreRemains() {
        val rows = build(listOf(msg(1, date = noon)), hasMoreOlder = true)
        assertFalse(rows.any { it is MessageRow.StartOfHistory })
    }

    @Test
    fun testStartOfHistoryIsSuppressedOnAnEmptyBuffer() {
        // "You've reached the beginning" over a blank list says less than the empty-state
        // placeholder does, and reads as a bug.
        assertTrue(build(emptyList(), hasMoreOlder = false).isEmpty())
    }

    // MARK: - Unread divider

    @Test
    fun testTheUnreadDividerLandsBeforeTheFirstUnreadMessage() {
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon), msg(3, date = noon)), dividerAfterId = 2)
        // date, msg1, msg2, unread, msg3
        if (row(rows, 3) != MessageRow.UnreadDivider) fail("expected the divider at index 3")
    }

    @Test
    fun testNoUnreadDividerWithoutARealReadPoint() {
        // A brand-new buffer has nothing previously read; a marker there would claim
        // everything is new, which is true but useless.
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon)), dividerAfterId = 0)
        assertFalse(rows.any { it is MessageRow.UnreadDivider })
    }

    @Test
    fun testNoUnreadDividerWhenEverythingIsRead() {
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon)), dividerAfterId = 99)
        assertFalse(rows.any { it is MessageRow.UnreadDivider })
    }

    @Test
    fun testTheUnreadDividerIsPlacedOnlyOnce() {
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon), msg(3, date = noon)), dividerAfterId = 1)
        assertEquals(1, rows.filter { it is MessageRow.UnreadDivider }.size)
    }

    @Test
    fun testTheDateSitsAboveTheUnreadMarkerWhenTheyCollide() {
        // Reading the previous evening and coming back the next morning puts both breaks on
        // the same message. The day is context for what follows; the unread marker is the
        // thing the reader is looking for, so it goes closest to the first new message.
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(86_400))), dividerAfterId = 1)
        // date, msg1, date, unread, msg2
        assertTrue(isDate(row(rows, 2)))
        if (row(rows, 3) != MessageRow.UnreadDivider) fail("unread must sit below the date")
    }

    // MARK: - Dividers are hard breaks for consolidation

    @Test
    fun testConsolidationDoesNotSpanTheUnreadDivider() {
        // Three joins straddling the read boundary. Collapsed as one run, the arrival the
        // reader hasn't seen would be hidden inside a summary above the marker.
        val joins = (1L..3L).map { msg(it, EventType.Join, "user$it", text = null, date = noon) }
        val rows = build(joins, dividerAfterId = 2)
        val dividerIndex = rows.indexOfFirst { it is MessageRow.UnreadDivider }
        if (dividerIndex < 0) fail("expected an unread divider")
        assertTrue(rows.subList(0, dividerIndex).any(::isSummary), "the read pair collapses")
        // The single unread join stands alone rather than joining the summary above.
        if (row(rows, dividerIndex + 1) !is MessageRow.Line) fail("expected a standalone join")
        assertEquals(dividerIndex + 2, rows.size)
    }

    @Test
    fun testConsolidationDoesNotSpanADayChange() {
        // A summary carries one timestamp. One spanning midnight would sit under a date
        // that's wrong for half the events inside it.
        val rows = build(
            listOf(
                msg(1, EventType.Join, "a", text = null, date = noon),
                msg(2, EventType.Join, "b", text = null, date = noon),
                msg(3, EventType.Join, "c", text = null, date = noon.plusSeconds(86_400)),
                msg(4, EventType.Join, "d", text = null, date = noon.plusSeconds(86_400)),
            ),
        )
        assertEquals(2, rows.filter(::isSummary).size, "one summary per day, not one across both")
    }

    // MARK: - Away/back presence markers (lurker-ios#68)

    /**
     * Away at noon+1h, back at noon+2h — so a message at noon is before both, one at +90m is
     * between them, and one at +3h is after both.
     */
    private fun awayPair(back: Boolean = true, message: String? = null): AwayState =
        AwayState(
            active = !back,
            message = message,
            since = noon.plusSeconds(3600),
            backAt = if (back) noon.plusSeconds(7200) else null,
        )

    private fun markerIndex(rows: List<MessageRow>, away: Boolean): Int? =
        rows.indexOfFirst {
            when (it) {
                is MessageRow.AwayDivider -> away
                is MessageRow.BackDivider -> !away
                else -> false
            }
        }.takeIf { it >= 0 }

    @Test
    fun testTheAwayMarkerLandsAboveTheFirstMessageYouMissed() {
        // The point of the marker: everything below it happened while you were gone.
        val rows = build(
            listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(5400))),
            away = awayPair(back = false),
        )
        val index = markerIndex(rows, away = true) ?: fail("expected an away marker")
        assertEquals(1L, row(rows, index - 1)?.message?.id, "the last line you were present for")
        assertEquals(2L, row(rows, index + 1)?.message?.id, "the first one you weren't")
    }

    @Test
    fun testTheBackMarkerLandsAboveTheFirstMessageYouWereBackFor() {
        val rows = build(
            listOf(
                msg(1, date = noon),
                msg(2, date = noon.plusSeconds(5400)), // during the away
                msg(3, date = noon.plusSeconds(7500)), // after the back
            ),
            away = awayPair(), now = noon.plusSeconds(7800),
        )
        val awayIndex = markerIndex(rows, away = true)
        val backIndex = markerIndex(rows, away = false)
        if (awayIndex == null || backIndex == null) fail("expected both markers")
        assertTrue(awayIndex < backIndex, "you go away before you come back")
        assertEquals(3L, row(rows, backIndex + 1)?.message?.id)
    }

    @Test
    fun testTheBackMarkerCarriesTheAwayInstant() {
        // So the row can say how long you were gone without the renderer holding away state.
        val rows = build(
            listOf(msg(1, date = noon.plusSeconds(7500))), away = awayPair(),
            now = noon.plusSeconds(7800),
        )
        val index = markerIndex(rows, away = false)
        val marker = index?.let { rows[it] as? MessageRow.BackDivider } ?: fail("expected a back marker")
        assertEquals(noon.plusSeconds(3600), marker.awayAt)
        assertEquals(noon.plusSeconds(7200), marker.at)
    }

    @Test
    fun testBothMarkersStackWhenNothingWasSaidInBetween() {
        // Nobody spoke during the away, so both anchor to the first line after it. They stack
        // rather than collapsing: the pair is what says the gap sat *here* and cost you
        // nothing, and dropping the away half would take the reason with it. Same call the web
        // makes — pinned so it stays a decision.
        val rows = build(
            listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(7500))),
            away = awayPair(message = "lunch"), now = noon.plusSeconds(7800),
        )
        val awayIndex = markerIndex(rows, away = true) ?: fail("expected an away marker")
        assertEquals(awayIndex + 1, markerIndex(rows, away = false), "back sits directly under away")
        assertEquals(2L, row(rows, awayIndex + 2)?.message?.id)
    }

    @Test
    fun testAMarkerWithNothingBelowItLandsAtTheFoot() {
        // The common case, not an edge: you go away and nothing has been said since, so neither
        // instant has a message after it to sit above. Anchoring alone would mean the markers
        // only ever appeared in the buffers that kept talking without you — which is to say
        // almost never at the moment you'd look for one.
        val rows = build(listOf(msg(1, date = noon)), away = awayPair(back = false))
        assertEquals(rows.size - 1, markerIndex(rows, away = true), "below the last message")
        assertEquals(1L, row(rows, rows.size - 2)?.message?.id)
    }

    @Test
    fun testASettledPairWithNothingBelowItLandsAtTheFootInOrder() {
        val rows = build(
            listOf(msg(1, date = noon)), away = awayPair(), now = noon.plusSeconds(7500),
        )
        assertEquals(rows.size - 2, markerIndex(rows, away = true))
        assertEquals(rows.size - 1, markerIndex(rows, away = false))
    }

    @Test
    fun testTheTypingLineStillSitsBelowAFootMarker() {
        // Typing describes the present; a marker describes something that already happened.
        val rows = build(listOf(msg(1, date = noon)), typists = listOf("bob"), away = awayPair(back = false))
        if (rows.lastOrNull() !is MessageRow.Typing) fail("expected the typing line last")
        assertEquals(rows.size - 2, markerIndex(rows, away = true))
    }

    @Test
    fun testADetachedBufferGetsNoFootMarker() {
        // Jump to a search hit from last week (lurker-ios#42): the window sits below the live
        // tail, so "nothing has been said since" is a claim about a tail this buffer can't see.
        // Pinning it under a week-old message asserts an absence that happened days afterwards.
        val rows = build(listOf(msg(1, date = noon)), hasMoreNewer = true, away = awayPair(back = false))
        assertNull(markerIndex(rows, away = true))
        assertNull(markerIndex(rows, away = false))
    }

    @Test
    fun testADetachedBufferStillAnchorsAMarkerItCanPlace() {
        // Only the fallback is suppressed. A marker with a message to sit above is true
        // wherever that message is — the window's relationship to the tail doesn't change what
        // happened between two lines that are both on screen.
        val rows = build(
            listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(5400))),
            hasMoreNewer = true, away = awayPair(back = false),
        )
        val index = markerIndex(rows, away = true) ?: fail("expected a marker")
        assertEquals(2L, row(rows, index + 1)?.message?.id)
    }

    @Test
    fun testAnEmptyBufferGetsNoMarkers() {
        // A lone marker over no conversation isn't a marker — and it would suppress the
        // empty-state placeholder, which reads `rows.isEmpty`.
        assertTrue(build(emptyList(), away = awayPair(back = false)).isEmpty())
    }

    @Test
    fun testNoMarkersWithoutAwayState() {
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(7200))))
        assertNull(markerIndex(rows, away = true))
        assertNull(markerIndex(rows, away = false))
    }

    @Test
    fun testBothMarkersRetireOnceYouHaveBeenBackAWhile() {
        // In a slow buffer they'd otherwise describe an absence nobody remembers. Both go, or
        // the surviving "away" would sit permanently over a user who is demonstrably here.
        val messages = listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(10_800)))
        val backAt = noon.plusSeconds(7200)
        val live = build(messages, away = awayPair(), now = backAt.plus(MessageRows.presenceMarkerTTL))
        assertNotNull(markerIndex(live, away = true), "still inside the lease")
        assertNotNull(markerIndex(live, away = false))

        val expired = build(
            messages, away = awayPair(), now = backAt.plus(MessageRows.presenceMarkerTTL).plusSeconds(1),
        )
        assertNull(markerIndex(expired, away = true))
        assertNull(markerIndex(expired, away = false))
    }

    @Test
    fun testAnUnfinishedAwayNeverExpires() {
        // The lease runs from `backAt`. While you're still away there's nothing to run from,
        // and the marker is describing the present rather than a memory of it.
        val rows = build(
            listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(5400))),
            away = awayPair(back = false),
            now = noon.plusSeconds(86_400 * 7),
        )
        assertNotNull(markerIndex(rows, away = true))
        assertNull(markerIndex(rows, away = false), "you haven't come back")
    }

    @Test
    fun testTheAwayMarkerCarriesItsReason() {
        val rows = build(
            listOf(msg(1, date = noon), msg(2, date = noon.plusSeconds(5400))),
            away = awayPair(back = false, message = "lunch"),
        )
        val index = markerIndex(rows, away = true)
        val marker = index?.let { rows[it] as? MessageRow.AwayDivider } ?: fail("expected an away marker")
        assertEquals("lunch", marker.awayMessage)
    }

    @Test
    fun testAnUndatedLineNeverAnchorsAPresenceMarker() {
        // An undated line can't answer "did this happen after you left?", so it can't be what a
        // marker sits above — treating it as epoch (which the web's `Date.parse(…) || 0` does)
        // would put the marker over a line that could as easily belong below it. It falls
        // through to the foot instead, which is where "nothing has been said since" belongs.
        val rows = build(listOf(msg(1, date = null), msg(2, date = null)), away = awayPair(back = false))
        assertEquals(rows.size - 1, markerIndex(rows, away = true), "at the foot, not between them")
    }

    @Test
    fun testADatedLineAnchorsTheMarkerAboveTheUndatedRunItFollows() {
        // The pair to the test above: with a dated message available, the marker anchors to it
        // rather than falling to the foot — so the undated line above stays above the marker.
        val rows = build(
            listOf(msg(1, date = null), msg(2, date = noon.plusSeconds(5400))),
            away = awayPair(back = false),
        )
        val index = markerIndex(rows, away = true) ?: fail("expected a marker")
        assertEquals(1L, row(rows, index - 1)?.message?.id)
        assertEquals(2L, row(rows, index + 1)?.message?.id)
    }

    @Test
    fun testConsolidationDoesNotSpanAPresenceMarker() {
        // Same rule as every other divider: a run half-before and half-after the marker would
        // hide the absence inside a summary.
        val joins = listOf(
            msg(1, EventType.Join, "a", text = null, date = noon),
            msg(2, EventType.Join, "b", text = null, date = noon),
            msg(3, EventType.Join, "c", text = null, date = noon.plusSeconds(5400)),
            msg(4, EventType.Join, "d", text = null, date = noon.plusSeconds(5400)),
        )
        val rows = build(joins, away = awayPair(back = false))
        assertEquals(2, rows.filter(::isSummary).size, "one summary either side, not one across")
    }

    @Test
    fun testTheDateDividerSitsAboveThePresenceMarker() {
        // Both land on the same message when an away spans midnight. The day is context for
        // what follows; the marker is a thing that happened inside that day.
        val away = AwayState(active = true, since = noon, backAt = null)
        val rows = build(listOf(msg(1, date = noon.plusSeconds(86_400))), away = away)
        assertTrue(isDate(row(rows, 0)))
        assertEquals(1, markerIndex(rows, away = true))
    }

    // MARK: - Dividers are hard breaks for bubble runs

    @Test
    fun testABubbleRunDoesNotSpanTheUnreadDivider() {
        // Same author, close in time — a run, but for the divider between them. Tightened
        // corners across it would knit together the messages it exists to separate.
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon)), dividerAfterId = 1)
        val first = (row(rows, 1) as? MessageRow.Bubble)?.position
        val second = (row(rows, 3) as? MessageRow.Bubble)?.position
        if (first == null || second == null) fail("expected bubbles either side of the divider")
        assertTrue(first.isLast, "the run closes above the divider")
        assertTrue(second.isFirst, "and reopens below it")
    }

    @Test
    fun testABubbleRunGroupsWhenNothingBreaksIt() {
        val rows = build(listOf(msg(1, date = noon), msg(2, date = noon)))
        val first = (row(rows, 1) as? MessageRow.Bubble)?.position
        val second = (row(rows, 2) as? MessageRow.Bubble)?.position
        if (first == null || second == null) fail("expected two bubbles")
        assertTrue(first.isFirst)
        assertFalse(first.isLast)
        assertFalse(second.isFirst)
        assertTrue(second.isLast)
    }

    // MARK: - Settings

    @Test
    fun testConsolidationOffPassesEveryEventThrough() {
        val settings = Settings(
            registry = emptyMap(), values = mapOf("chat.consolidate_joins" to SettingValue.Bool(false)),
        )
        val joins = (1L..3L).map { msg(it, EventType.Join, "user$it", text = null, date = noon) }
        val rows = build(joins, settings = settings)
        assertFalse(rows.any(::isSummary))
        assertEquals(3, rows.filter { it is MessageRow.Line }.size)
    }

    @Test
    fun testMaxNamesIsHonored() {
        val settings = Settings(registry = emptyMap(), values = mapOf("chat.consolidate_max_names" to SettingValue.Int(2)))
        val joins = (1L..5L).map { msg(it, EventType.Join, "user$it", text = null, date = noon) }
        val rows = build(joins, settings = settings)
        val summary = (row(rows, 1) as? MessageRow.Consolidated)?.summary ?: fail("expected a summary")
        assertEquals(2, summary.groups.firstOrNull()?.visible?.size)
        assertEquals(3, summary.groups.firstOrNull()?.hidden)
    }

    /**
     * A truncated name list shows the people you were just talking to, not whoever happened to
     * arrive first. `Consolidation` has always been able to do this; until lurker-ios#63 nothing
     * passed it a speaker set, so every capped summary silently showed insertion order.
     */
    @Test
    fun testATruncatedSummaryFloatsRecentSpeakersToTheFront() {
        val settings = Settings(registry = emptyMap(), values = mapOf("chat.consolidate_max_names" to SettingValue.Int(2)))
        val joins = (1L..5L).map { msg(it, EventType.Join, "user$it", text = null, date = noon) }
        val speakers = SpeakerMap(
            listOf(
                Speaker(nick = "user4", lastSpoke = noon), Speaker(nick = "user5", lastSpoke = noon),
            ),
        )
        val rows = build(joins, settings = settings, speakers = speakers)
        val summary = (row(rows, 1) as? MessageRow.Consolidated)?.summary ?: fail("expected a summary")
        assertEquals(
            listOf<ConsolidationSummary.Entry>(
                ConsolidationSummary.Entry.Nick("user4"), ConsolidationSummary.Entry.Nick("user5"),
            ),
            summary.groups.firstOrNull()?.visible,
        )
        assertEquals(3, summary.groups.firstOrNull()?.hidden)
    }

    // MARK: - Typing

    @Test
    fun testTypingGoesLastAndOutsideTheRunPass() {
        val rows = build(listOf(msg(1, date = noon)), typists = listOf("bob"))
        val typing = rows.lastOrNull() as? MessageRow.Typing ?: fail("expected a typing row")
        assertEquals(listOf("bob"), typing.nicks)
        // The bubble above it still closes its run — the typing row is not a bubble neighbour.
        val position = (row(rows, 1) as? MessageRow.Bubble)?.position ?: fail("expected a bubble")
        assertTrue(position.isLast)
    }

    // MARK: - Row queries

    @Test
    fun testMarkersNeverAnchorTheViewport() {
        val rows = build(listOf(msg(1, date = noon)), hasMoreOlder = false, typists = listOf("bob"))
        // start-of-history, date, bubble, typing
        assertEquals(4, rows.size)
        assertNull(row(rows, 0)?.anchorId)
        assertNull(row(rows, 1)?.anchorId)
        assertEquals(1L, row(rows, 2)?.anchorId)
        assertNull(row(rows, 3)?.anchorId)
    }

    @Test
    fun testASummaryRepresentsEveryMessageInItsSpan() {
        val joins = (1L..3L).map { msg(it, EventType.Join, "user$it", text = null, date = noon) }
        val rows = build(joins)
        val summary = row(rows, 1)
        if (summary == null || summary !is MessageRow.Consolidated) fail("expected a summary")
        // The middle id has no row of its own; the summary stands in for it, which is what
        // lets the scroll anchor re-find a line after a history page merges it into a run.
        assertTrue(summary.represents(2))
        assertFalse(summary.represents(4))
    }

    @Test
    fun testOnlyNarrationCountsAsStatus() {
        val rows = build(
            listOf(
                msg(1, EventType.Join, "a", text = null, date = noon),
                msg(2, EventType.Message, "b", date = noon),
                msg(3, EventType.Action, "c", date = noon),
            ),
        )
        // date, join, message, action
        assertEquals(4, rows.size)
        assertEquals(true, row(rows, 1)?.isStatus)
        assertEquals(false, row(rows, 2)?.isStatus)
        assertEquals(false, row(rows, 3)?.isStatus, "an action is conversation, so it breaks a status block")
        assertEquals(false, row(rows, 0)?.isStatus, "a divider is a hard break, not part of a block")
    }

    // Port-only:

    /**
     * `testTheDividerCarriesLocalMidnight` holds the divider against a start of day worked out
     * the way the builder works it out. This holds it against the instant itself.
     */
    @Test
    fun testTheDividerIsMidnightOfTheMessagesDay() {
        val divider = row(build(listOf(msg(1, date = noon))), 0) as? MessageRow.DateDivider
            ?: fail("expected a date divider")
        assertEquals(Instant.parse("2026-07-20T00:00:00Z"), divider.day)
    }

    private fun dateDividers(messages: List<Message>, zone: String): List<Instant> =
        MessageRows.build(
            messages = messages, dividerAfterId = null, hasMoreOlder = true, now = noon, zone = ZoneId.of(zone),
        ).mapNotNull { (it as? MessageRow.DateDivider)?.day }

    /**
     * São Paulo's clocks went from 00:00 to 01:00 on 4 November 2018, so that day has no
     * midnight. Its divider carries the day's first instant, 01:00 local. The answer is
     * LurkerKit's.
     */
    @Test
    fun testADayWithNoMidnightStartsAtItsFirstInstant() {
        val days = dateDividers(
            listOf(msg(1, date = Instant.parse("2018-11-04T15:00:00Z"))), zone = "America/Sao_Paulo",
        )
        assertEquals(listOf(Instant.parse("2018-11-04T03:00:00Z")), days)
    }

    /**
     * Havana's clocks went from 01:00 back to 00:00 on 3 November 2024, so the hour after
     * midnight ran twice. Both runs are the same day, under one divider that carries the
     * first midnight. The answer is LurkerKit's.
     */
    @Test
    fun testAMidnightHourThatRunsTwiceIsOneDay() {
        val days = dateDividers(
            listOf(
                msg(1, date = Instant.parse("2024-11-03T04:30:00Z")), // 00:30, the first time
                msg(2, date = Instant.parse("2024-11-03T05:30:00Z")), // 00:30 again
                msg(3, date = Instant.parse("2024-11-04T05:30:00Z")), // 00:30 the next day
            ),
            zone = "America/Havana",
        )
        assertEquals(listOf(Instant.parse("2024-11-03T04:00:00Z"), Instant.parse("2024-11-04T05:00:00Z")), days)
    }

    /**
     * Samoa skipped 30 December 2011: the day after the 29th is the 31st, and the two are a
     * day apart on the clock and one divider apart in the list. The answer is LurkerKit's.
     */
    @Test
    fun testASkippedDayLeavesNoDividerOfItsOwn() {
        val days = dateDividers(
            listOf(
                msg(1, date = Instant.parse("2011-12-30T09:30:00Z")), // 23:30 on the 29th
                msg(2, date = Instant.parse("2011-12-30T10:30:00Z")), // 00:30 on the 31st
            ),
            zone = "Pacific/Apia",
        )
        assertEquals(listOf(Instant.parse("2011-12-29T10:00:00Z"), Instant.parse("2011-12-30T10:00:00Z")), days)
    }
}
