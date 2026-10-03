// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import net.amiantos.lurker.ui.message.MessageTextStyle
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedReaction
import net.amiantos.lurkerkit.model.HighlightDay
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurker.ui.message.MessageText
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.model.RelayBotSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** A feed's rows and headers — lurker-ios's `HistoryFeedViewController` table half. */
class FeedModelTest {

    private val style = MessageTextStyle(colors = LurkerColors.Dark)
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-07-25T14:41:00Z")
    private val date: (Instant, Boolean) -> String = { instant, withYear -> (if (withYear) "Y:" else "D:") + instant.toString().substring(0, 10) }

    private val state = ChatState(
        networks = mapOf(1 to Network(id = 1, name = "Libera", nick = "me"), 2 to Network(id = 2, name = "OFTC", nick = "me")),
    )

    private fun item(
        id: Long,
        target: String = "#lurker",
        networkId: Int = 1,
        type: EventType = EventType.Message,
        nick: String? = "alice",
        text: String = "line $id",
        at: Instant? = now,
        networkName: String? = null,
        reaction: FeedReaction? = null,
    ) = HighlightItem(
        Message(id = id, type = type, nick = nick, text = text, date = at, matched = true, msgid = "m$id"),
        networkId,
        target,
        networkName,
        reaction = reaction,
    )

    @Test
    fun theLocationNamesTheNetworkAndTargetOnce() {
        assertEquals("Libera/#lurker", FeedModel.location("Libera", "#lurker"))
        assertEquals("Libera", FeedModel.location("Libera", "Libera"))
        assertEquals("#lurker", FeedModel.location(null, "#lurker"))
    }

    @Test
    fun aServerLogsHeaderIsItsNetworkAlone() {
        val sections = FeedModel.sections(listOf(item(1, target = Buffer.serverTarget(1), type = EventType.Motd, nick = null)), state, style, now, zone, date)
        assertEquals("Libera", sections.single().location)
    }

    @Test
    fun dayLabelsCarryTheYearOnlyWhenItIsntThisOne() {
        assertEquals("Today", FeedModel.dayLabel(HighlightDay.Today, now, zone, date))
        assertEquals("Yesterday", FeedModel.dayLabel(HighlightDay.Yesterday, now, zone, date))
        assertEquals("Earlier", FeedModel.dayLabel(HighlightDay.Undated, now, zone, date))
        assertEquals("D:2026-03-01", FeedModel.dayLabel(HighlightDay.On(Instant.parse("2026-03-01T00:00:00Z")), now, zone, date))
        assertEquals("Y:2025-03-01", FeedModel.dayLabel(HighlightDay.On(Instant.parse("2025-03-01T00:00:00Z")), now, zone, date))
    }

    @Test
    fun runsSplitByBufferAndDayAndKnowWhereTheyStart() {
        val items = listOf(
            item(5),
            item(4),
            item(3, target = "#swift"),
            item(2, target = "#swift", at = now.minusSeconds(86_400)),
            item(1, at = null),
        )
        val sections = FeedModel.sections(items, state, style, now, zone, date)
        assertEquals(listOf("Libera/#lurker", "Libera/#swift", "Libera/#swift", "Libera/#lurker"), sections.map { it.location })
        assertEquals(listOf("Today", "Today", "Yesterday", "Earlier"), sections.map { it.day })
        assertEquals(listOf(0, 2, 3, 4), sections.map { it.offset })
        assertEquals(listOf(2, 1, 1, 1), sections.map { it.rows.size })
    }

    @Test
    fun theServersNetworkNameWinsAndTheRosterFillsIn() {
        assertEquals("Renamed", FeedModel.networkName(item(1, networkName = "Renamed"), state))
        assertEquals("OFTC", FeedModel.networkName(item(1, networkId = 2), state))
    }

    @Test
    fun aRepeatedRowGetsAKeyOfItsOwn() {
        val keys = FeedModel.sections(listOf(item(2), item(2), item(1)), state, style, now, zone, date).flatMap { it.rows }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyRowCarriesItsTimeAndASpeakerNamesThemselves() {
        val row = FeedModel.row(item(1), state, style, zone)
        assertEquals("alice", row.header?.nick)
        assertEquals("14:41", row.header?.time)
        assertTrue(row.indentsBody)
        assertNull(row.reply)
        assertEquals("line 1", row.body.text)
    }

    @Test
    fun aMeLineKeepsItsTimeButNotASecondNick() {
        val row = FeedModel.row(item(1, type = EventType.Action, text = "waves"), state, style, zone)
        assertEquals("", row.header?.nick)
        assertEquals("14:41", row.header?.time)
        assertFalse(row.indentsBody)
        assertEquals("* alice waves", row.body.text)
    }

    @Test
    fun aNoticeIsNamedTheWayIrcMarksIt() {
        assertEquals("-NickServ-", FeedModel.row(item(1, type = EventType.Notice, nick = "NickServ"), state, style, zone).header?.nick)
    }

    @Test
    fun anUndatedActivityLineHasNoHeaderAtAll() {
        assertNull(FeedModel.row(item(1, type = EventType.Join, at = null), state, style, zone).header)
    }

    @Test
    fun aReactionSaysWhatWasGivenOnWhichLine() {
        val reaction = FeedReaction(reactionId = 9, value = "👍", lineText = "shipped the \u000304fix\u0003")
        val row = FeedModel.row(item(1, nick = "bob", text = "👍", reaction = reaction), state, style, zone)
        assertEquals("bob", row.header?.nick)
        assertEquals("👍 on “shipped the fix”", row.body.text)
    }

    @Test
    fun linksStayDrawnButTheTapIsTheRows() {
        val row = FeedModel.row(item(1, text = "see https://lurker.chat now"), state, style, zone)
        assertTrue(row.body.getLinkAnnotations(0, row.body.length).none { it.item is LinkAnnotation.Url })
        val start = row.body.text.indexOf("https")
        assertTrue(row.body.spanStyles.any { it.start <= start && it.item.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun aHiddenSpoilerNeverReachesTalkBack() {
        val row = FeedModel.row(item(1, text = "it was \u000301,01the butler\u0003 all along"), state, style, zone)
        assertTrue(row.body.text.contains("the butler"))
        assertFalse(row.spokenBody.contains("the butler"))
        // The row's double tap is its jump, so the box isn't offered as something to open.
        assertTrue(row.spokenBody, row.spokenBody.contains(MessageText.HIDDEN_SPOILER))
        assertFalse(row.spokenBody, row.spokenBody.contains("double tap"))
    }

    @Test
    fun ignoredLinesAreJudgedByTheirOwnNetwork() {
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(mask = "spammer", levels = listOf("ALL")))))
        val items = listOf(item(3, nick = "spammer"), item(2, nick = "spammer", networkId = 2), item(1))
        assertEquals(listOf(2L, 1L), FeedModel.visible(items, ignores).map { it.message.id })
    }

    @Test
    fun aClosedBufferIsOnlyKnownOnceTheRosterHasSettled() {
        val row = item(1, target = "#gone")
        assertFalse(FeedModel.pointsIntoClosedBuffer(row, state))
        val settled = state.copy(backlogComplete = true)
        assertTrue(FeedModel.pointsIntoClosedBuffer(row, settled))
        val open = Buffer(networkId = 1, target = "#gone", kind = BufferKind.Channel)
        assertFalse(FeedModel.pointsIntoClosedBuffer(row, settled.copy(buffers = mapOf(open.key.id to open))))
        assertEquals("#gone isn't open, so this message can't be shown in context.", FeedModel.closedBufferMessage(row, settled))
    }

    @Test
    fun aRowIsRenderedOnceHoweverOftenTheListRegroups() {
        val cache = FeedRowCache { FeedModel.row(it, state, style, zone) }
        val first = listOf(item(3), item(2))
        FeedModel.sections(first, state, style, now, zone, date, render = cache::row)
        FeedModel.sections(first + item(1), state, style, now, zone, date, render = cache::row)
        assertEquals(3, cache.renders)
    }

    @Test
    fun aQuotedSpoilerStaysHiddenOnScreenAndToTalkBack() {
        val quote = Replies.shown(
            ReplyContext("m1", ReplyParent(id = 3, nick = "alice", type = EventType.Message, text = "it was \u000301,01the butler\u0003")),
            line = Message(id = 4, type = EventType.Message, nick = "bob", text = "no way", msgid = "m4"),
            networkId = 1,
            target = "#lurker",
            ignores = IgnoreSet.empty,
            relayBots = RelayBotSet.empty,
            ownNick = "me",
        ).quote
        val reply = FeedModel.replyQuote(quote, indented = true, style)
        assertFalse(reply.shown.text, reply.shown.text.contains("butler"))
        assertTrue(reply.shown.text, reply.shown.text.startsWith("╭─ <alice> it was █"))
        assertEquals("In reply to alice: it was ${MessageText.HIDDEN_SPOILER}", reply.spoken)
        assertEquals(MessageText.spokenReplyQuote(null), FeedModel.replyQuote(null, indented = false, style).spoken)
    }

    @Test
    fun aReactionNeverPrintsTheSpoilerInYourLine() {
        val reaction = FeedReaction(reactionId = 9, value = "😮", lineText = "the ending: \u000301,01he was a ghost\u0003")
        val row = FeedModel.row(item(1, nick = "bob", text = "😮", reaction = reaction), state, style, zone)
        assertFalse(row.body.text, row.body.text.contains("ghost"))
        assertFalse(row.spokenBody, row.spokenBody.contains("ghost"))
        assertEquals("😮 on \u201Cthe ending: ${MessageText.HIDDEN_SPOILER}\u201D", row.spokenBody)
    }
}
