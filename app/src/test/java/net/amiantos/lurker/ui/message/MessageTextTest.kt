// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.ConsolidationSummary.Entry
import net.amiantos.lurkerkit.model.ConsolidationSummary.IdentityGroup
import net.amiantos.lurkerkit.model.ConsolidationSummary.IdentityGroup.Kind
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.rendering.NickHighlighter
import net.amiantos.lurkerkit.support.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * `MessageText`, the port of lurker-ios's `MessageRenderer` compact functions. Most of these pin a
 * rule iOS carries a comment for — and several of those comments describe a bug that shipped.
 */
class MessageTextTest {

    private val style = MessageTextStyle(colors = LurkerColors.Dark)
    private val colors = style.colors

    private fun message(
        text: String?,
        type: EventType = EventType.Message,
        nick: String? = "alice",
        id: Long = 7,
        isSelf: Boolean = false,
        modes: List<ModeChange> = emptyList(),
        newNick: String? = null,
        kicked: String? = null,
        replyTo: ReplyContext? = null,
    ) = Message(
        id = id, type = type, nick = nick, text = text, isSelf = isSelf, modes = modes,
        newNick = newNick, kicked = kicked, replyTo = replyTo,
    )

    private fun body(
        text: String,
        id: Long = 7,
        highlighter: NickHighlighter? = null,
        revealed: Set<Int> = emptySet(),
        onToggle: ((Int) -> Unit)? = { },
    ) = MessageText.renderCompactBody(
        message(text, id = id), style, highlighter = highlighter, revealed = revealed, onToggleSpoiler = onToggle,
    )

    /** Every span style covering [index], merged in the order Compose applies them. */
    private fun AnnotatedString.styleAt(index: Int): SpanStyle =
        spanStyles.filter { it.start <= index && index < it.end }.fold(SpanStyle()) { acc, range -> acc.merge(range.item) }

    private fun AnnotatedString.indexOf(s: String): Int = text.indexOf(s).also { check(it >= 0) { "\"$s\" not in \"$text\"" } }

    private fun AnnotatedString.urls() =
        getLinkAnnotations(0, length).filter { it.item is LinkAnnotation.Url }
            .map { (it.item as LinkAnnotation.Url).url to text.substring(it.start, it.end) }

    // MARK: - Formatting runs

    @Test
    fun `plain text is drawn in the log's foreground, explicitly`() {
        val line = body("hello")
        assertEquals("hello", line.text)
        assertEquals(colors.fg, line.styleAt(0).color)
    }

    @Test
    fun `bold italic underline and strike each style their run and stop at the toggle`() {
        val line = body("\u0002b\u0002 \u001Di\u001D \u001Fu\u001F \u001Es\u001E n")
        assertEquals("b i u s n", line.text)
        assertEquals(FontWeight.Bold, line.styleAt(line.indexOf("b")).fontWeight)
        assertEquals(FontStyle.Italic, line.styleAt(line.indexOf("i")).fontStyle)
        assertEquals(TextDecoration.Underline, line.styleAt(line.indexOf("u")).textDecoration)
        assertEquals(TextDecoration.LineThrough, line.styleAt(line.indexOf("s")).textDecoration)
        val plain = line.styleAt(line.indexOf("n"))
        assertNull(plain.fontWeight)
        assertNull(plain.fontStyle)
        assertNull(plain.textDecoration)
    }

    @Test
    fun `a mIRC colour is the palette's literal slot, foreground and background`() {
        val line = body("\u000304,02red\u0003 plain")
        val red = line.styleAt(0)
        assertEquals(colors.mirc[4], red.color)
        assertEquals(colors.mirc[2], red.background)
        assertEquals(colors.fg, line.styleAt(line.indexOf("plain")).color)
    }

    @Test
    fun `a slot the palette can't paint is no colour at all`() {
        val line = body("\u000342odd")
        assertEquals(colors.fg, line.styleAt(0).color)
    }

    @Test
    fun `truecolour is exactly what was sent`() {
        val line = body("\u0004FF8000orange")
        assertEquals("orange", line.text)
        assertEquals(Color(0xFFFF8000), line.styleAt(0).color)
    }

    @Test
    fun `reverse swaps plain text into the theme inverted`() {
        val line = body("\u0016rev\u0016")
        val reversed = line.styleAt(0)
        assertEquals(colors.bg, reversed.color)
        assertEquals(colors.fg, reversed.background)
    }

    @Test
    fun `reset clears every attribute and colour`() {
        val line = body("\u0002\u000304bold red\u000Fplain")
        val plain = line.styleAt(line.indexOf("plain"))
        assertEquals(colors.fg, plain.color)
        assertNull(plain.fontWeight)
    }

    // MARK: - Links

    @Test
    fun `a link stops short of the sentence's full stop`() {
        val line = body("see https://lurker.chat/changelog.")
        assertEquals(listOf("https://lurker.chat/changelog" to "https://lurker.chat/changelog"), line.urls())
    }

    @Test
    fun `a bare www host opens over http`() {
        val line = body("try www.example.com now")
        assertEquals(listOf("http://www.example.com" to "www.example.com"), line.urls())
    }

    @Test
    fun `a bracketed link loses its brackets and keeps its styling after them`() {
        val line = body("<https://a.example/x> \u0002bold\u0002")
        assertEquals("https://a.example/x bold", line.text)
        assertEquals(listOf("https://a.example/x" to "https://a.example/x"), line.urls())
        // The bold run moved left with the deletion — the styling follows the characters.
        assertEquals(FontWeight.Bold, line.styleAt(line.indexOf("bold")).fontWeight)
    }

    @Test
    fun `an email in angle brackets keeps them`() {
        val line = body("Co-Authored-By: Claude <noreply@anthropic.com>")
        assertTrue(line.text.endsWith("<noreply@anthropic.com>"))
        assertEquals("mailto:noreply@anthropic.com", line.urls().single().first)
    }

    @Test
    fun `a link keeps the colour around it and is underlined`() {
        val line = body("https://a.example")
        val link = line.getLinkAnnotations(0, line.length).single().item as LinkAnnotation.Url
        // Unspecified: the span's colour, not one link colour that would flatten a nick-coloured /me.
        assertEquals(androidx.compose.ui.graphics.Color.Unspecified, link.styles?.style?.color)
        assertEquals(androidx.compose.ui.text.style.TextDecoration.Underline, link.styles?.style?.textDecoration)
    }

    // MARK: - Mentions

    @Test
    fun `a known nick in the body takes its palette colour`() {
        val line = body("hey bob, look", highlighter = NickHighlighter(listOf("bob")))
        assertEquals(MessageText.hashedColor("bob", style), line.styleAt(line.indexOf("bob")).color)
        assertEquals(colors.fg, line.styleAt(line.indexOf("look")).color)
    }

    @Test
    fun `a nick the sender coloured keeps the sender's colour`() {
        val line = body("\u000304bob\u0003 hi", highlighter = NickHighlighter(listOf("bob")))
        assertEquals(colors.mirc[4], line.styleAt(0).color)
    }

    @Test
    fun `a nick inside a link isn't coloured`() {
        val line = body("https://example.com/bob", highlighter = NickHighlighter(listOf("bob")))
        assertEquals(colors.fg, line.styleAt(line.indexOf("bob")).color)
    }

    @Test
    fun `under reverse a nick's colour is the block it sits on`() {
        val line = body("\u0016hi bob\u0016", highlighter = NickHighlighter(listOf("bob")))
        val nick = line.styleAt(line.indexOf("bob"))
        assertEquals(colors.bg, nick.color)
        assertEquals(MessageText.hashedColor("bob", style), nick.background)
    }

    // MARK: - Spoilers

    private val spoiler = "the ending is \u000301,01he was a ghost\u0003 ok"

    @Test
    fun `a hidden spoiler is a solid box of its own colour`() {
        val line = body(spoiler)
        val box = line.styleAt(line.indexOf("ghost"))
        assertEquals(colors.mirc[1], box.color)
        assertEquals(colors.mirc[1], box.background)
    }

    @Test
    fun `a hidden spoiler never reaches the spoken label`() {
        val spoken = MessageText.spoken(body(spoiler))
        assertEquals("the ending is hidden spoiler, double tap to reveal ok", spoken)
        assertFalse(spoken.contains("ghost"))
    }

    @Test
    fun `a revealed spoiler is the message colour over a wash of the sender's`() {
        val line = body(spoiler, revealed = setOf(0))
        val shown = line.styleAt(line.indexOf("ghost"))
        assertEquals(colors.fg, shown.color)
        assertEquals(colors.mirc[1].copy(alpha = 0.22f), shown.background)
        assertEquals("the ending is he was a ghost ok", MessageText.spoken(line))
    }

    @Test
    fun `an id-less line's spoiler stays hidden, unannounced as openable, and has no tap target`() {
        val line = body(spoiler, id = 0)
        assertEquals("the ending is hidden spoiler ok", MessageText.spoken(line))
        assertTrue(line.getLinkAnnotations(0, line.length).isEmpty())
        assertTrue(MessageText.hiddenSpoilerOrdinals(line).isEmpty())
    }

    @Test
    fun `a spoiler's tap toggles its own ordinal`() {
        val tapped = mutableListOf<Int>()
        val line = body("\u000301,01one\u0003 and \u000304,04two\u0003", onToggle = { tapped.add(it) })
        val clickables = line.getLinkAnnotations(0, line.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()
        assertEquals(2, clickables.size)
        clickables.forEach { it.linkInteractionListener?.onClick(it) }
        assertEquals(listOf(0, 1), tapped)
        assertEquals(listOf(0, 1), MessageText.hiddenSpoilerOrdinals(line))
    }

    @Test
    fun `bold inside a spoiler is still one box, one ordinal, one announcement`() {
        val line = body("\u000301,01a \u0002b\u0002 c\u0003")
        assertEquals(listOf(0), MessageText.hiddenSpoilerOrdinals(line))
        assertEquals("hidden spoiler, double tap to reveal", MessageText.spoken(line))
    }

    @Test
    fun `a link inside a spoiler isn't a link, hidden or revealed`() {
        val text = "\u000301,01https://secret.example\u0003"
        assertTrue(body(text).urls().isEmpty())
        assertTrue(body(text, revealed = setOf(0)).urls().isEmpty())
    }

    @Test
    fun `a nick inside a spoiler isn't coloured`() {
        val line = body("\u000301,01bob did it\u0003", highlighter = NickHighlighter(listOf("bob")))
        assertEquals(colors.mirc[1], line.styleAt(0).color)
    }

    @Test
    fun `a 99,99 run is not a spoiler`() {
        val line = body("\u000399,99clear\u0003")
        assertEquals("clear", MessageText.spoken(line))
        assertTrue(MessageText.hiddenSpoilerOrdinals(line).isEmpty())
    }

    @Test
    fun `the spoken form keeps the line's links where the words now put them`() {
        val line = body("\u000301,01x\u0003 see https://a.example")
        val spoken = MessageText.spokenAnnotated(line)
        val link = spoken.getLinkAnnotations(0, spoken.length).single()
        assertEquals("https://a.example", spoken.text.substring(link.start, link.end))
        // Only the link: the spoiler's own tap target stays behind.
        assertTrue(spoken.getLinkAnnotations(0, spoken.length).all { it.item is LinkAnnotation.Url })
    }

    // MARK: - Line shapes

    @Test
    fun `a me line names its actor in their colour and hangs two characters`() {
        val line = MessageText.renderCompactBody(message("waves", type = EventType.Action, nick = "bob"), style)
        assertEquals("* bob waves", line.text)
        val bob = MessageText.nickColor("bob", isSelf = false, style)
        assertEquals(bob, line.styleAt(0).color)
        // The body takes the nick's colour too.
        assertEquals(bob, line.styleAt(line.indexOf("waves")).color)
        val indent = line.paragraphStyles.single().item.textIndent!!
        assertEquals(0.sp, indent.firstLine)
        assertEquals((style.indentSp * 2).sp, indent.restLine)
    }

    @Test
    fun `a message body sits one character in, first line included`() {
        val indent = body("hi").paragraphStyles.single().item.textIndent!!
        assertEquals(style.indentSp.sp, indent.firstLine)
        assertEquals(style.indentSp.sp, indent.restLine)
    }

    @Test
    fun `your own lines are the plain foreground, not the accent`() {
        assertEquals(colors.fg, MessageText.nickColor("me", isSelf = true, style))
        val line = MessageText.renderCompactBody(message("hi", type = EventType.Action, nick = "me", isSelf = true), style)
        assertEquals(colors.fg, line.styleAt(0).color)
    }

    @Test
    fun `a notice is captioned with IRC's own mark`() {
        assertEquals("-NickServ-", MessageText.caption(message("hi", type = EventType.Notice, nick = "NickServ"), null))
    }

    @Test
    fun `a system line names its network, or the app in a muted voice`() {
        val line = message("connected", type = EventType.System, nick = null)
        assertEquals("Libera", MessageText.caption(line, "Libera"))
        assertEquals(MessageText.hashedColor("Libera", style), MessageText.captionColor(line, "Libera", style))
        assertEquals("System", MessageText.caption(line, null))
        assertEquals(colors.fgMuted, MessageText.captionColor(line, null, style))
    }

    @Test
    fun `server text with no known network has nothing to be captioned with`() {
        assertNull(MessageText.caption(message("motd", type = EventType.Motd, nick = null), null))
    }

    @Test
    fun `a mode glyph prefixes a nick and wears its rank's colour`() {
        assertEquals("@alice", MessageText.caption(message("hi"), null, modePrefix = "@"))
        val name = MessageText.headerName(CompactHeader("@alice", Color.Red, time = null, modePrefix = "@"), style)
        assertEquals("@alice", name.text)
        assertEquals(colors.memberOp, name.styleAt(0).color)
        assertEquals(FontWeight.Bold, name.styleAt(0).fontWeight)
        assertEquals(Color.Red, name.styleAt(1).color)
        // The name is the body's weight, as on the web and iOS; only the glyph is bold.
        assertNull(name.styleAt(1).fontWeight)
    }

    @Test
    fun `a relay source trails the name, faint`() {
        val name = MessageText.headerName(CompactHeader("alice", Color.Red, time = null, relaySource = "github"), style)
        assertEquals("alice github", name.text)
        assertEquals(colors.fgFaint, name.styleAt(name.indexOf("github")).color)
    }

    // MARK: - Activity and consolidation

    private fun activity(message: Message) = MessageText.renderCompactBody(message, style).text

    // Sweep L06: a reason is formatted like any message — colour digits never leak, links open.
    @Test
    fun `a quit reason's colours are colours and its link opens`() {
        val line = MessageText.renderCompactBody(
            message("\u000304Leaving\u0003 https://x.example", type = EventType.Quit), style,
        )
        assertEquals("alice quit (Leaving https://x.example)", line.text)
        assertEquals(colors.mirc[4], line.styleAt(line.indexOf("Leaving")).color)
        assertEquals(listOf("https://x.example" to "https://x.example"), line.urls())
        // The rest of the reason is in the narration's grey, as the topic's text is.
        assertEquals(colors.fgMuted, line.styleAt(line.indexOf(" https")).color)
    }

    @Test
    fun `a reason or topic of nothing but codes draws no body`() {
        assertEquals("alice left", activity(message("\u0002\u0002\u000f", type = EventType.Part)))
        assertEquals("alice set the topic", activity(message("\u0003\u000f", type = EventType.Topic)))
        assertEquals(
            "bob was kicked by alice (out)",
            activity(message("\u0002out\u0002", type = EventType.Kick, kicked = "bob")),
        )
    }

    @Test
    fun `a spoiler in a reason opens`() {
        val reason = message("\u000301,01secret\u0003", type = EventType.Quit)
        val hidden = MessageText.renderCompactBody(reason, style, onToggleSpoiler = { })
        val open = MessageText.renderCompactBody(reason, style, revealed = setOf(0), onToggleSpoiler = { })
        val at = hidden.indexOf("secret")
        assertEquals(hidden.styleAt(at).color, hidden.styleAt(at).background)
        assertTrue(open.styleAt(open.indexOf("secret")).color != open.styleAt(open.indexOf("secret")).background)
    }

    @Test
    fun `activity lines narrate, starting flush`() {
        assertEquals("alice joined", activity(message(null, type = EventType.Join)))
        assertEquals("alice left (bye now)", activity(message("bye now", type = EventType.Part)))
        assertEquals("alice quit", activity(message("   ", type = EventType.Quit)))
        assertEquals("alice is now alice_", activity(message(null, type = EventType.Nick, newNick = "alice_")))
        assertEquals("bob was kicked by alice (spam)", activity(message("spam", type = EventType.Kick, kicked = "bob")))
        assertEquals("alice set the topic: ship it", activity(message("ship it", type = EventType.Topic)))
        assertEquals("alice set the topic", activity(message("", type = EventType.Topic)))
        val join = MessageText.renderCompactBody(message(null, type = EventType.Join), style)
        assertEquals(0.sp, join.paragraphStyles.single().item.textIndent!!.firstLine)
        assertEquals(colors.fgMuted, join.styleAt(join.indexOf("joined")).color)
    }

    @Test
    fun `a single prefix mode is narrated, its target coloured as a nick`() {
        val line = MessageText.renderCompactBody(
            message("+o dave", type = EventType.Mode, nick = "ChanServ", modes = listOf(ModeChange("+o", "dave", ModeChangeKind.Prefix))),
            style,
        )
        assertEquals("ChanServ gave op to dave", line.text)
        assertEquals(MessageText.hashedColor("dave", style), line.styleAt(line.indexOf("dave")).color)
    }

    @Test
    fun `a ban mask is narrated but never coloured as a nick`() {
        val line = MessageText.renderCompactBody(
            message("+b *!*@x", type = EventType.Mode, modes = listOf(ModeChange("+b", "*!*@x", ModeChangeKind.List))),
            style,
        )
        assertEquals("alice banned *!*@x", line.text)
        assertEquals(colors.fgMuted, line.styleAt(line.indexOf("*!*@x")).color)
    }

    private fun summary(vararg groups: IdentityGroup) =
        ConsolidationSummary(groups = groups.toList(), date = null, firstId = 1, lastId = 9)

    private fun nicks(vararg names: String) = names.map { Entry.Nick(it) }

    @Test
    fun `a consolidated run reads as a sentence, clause by clause`() {
        val text = MessageText.renderCompactConsolidation(
            summary(
                IdentityGroup(Kind.Joined, nicks("carol", "dave"), hidden = 0),
                IdentityGroup(Kind.Left, nicks("erin"), hidden = 0),
                IdentityGroup(Kind.Renamed, listOf(Entry.Renamed("frank", "frank_")), hidden = 0),
            ),
            style,
        ).text
        assertEquals("carol and dave joined; erin left; frank → frank_", text)
    }

    @Test
    fun `a truncated run says how many it left out`() {
        val one = MessageText.renderCompactConsolidation(summary(IdentityGroup(Kind.Joined, nicks("a", "b"), hidden = 1)), style)
        assertEquals("a, b, and 1 other joined", one.text)
        val many = MessageText.renderCompactConsolidation(summary(IdentityGroup(Kind.JoinedAndLeft, nicks("a"), hidden = 3)), style)
        assertEquals("a, and 3 others joined briefly", many.text)
    }

    @Test
    fun `mode runs use verbs for o and v and the token for anything else`() {
        fun text(kind: Kind, vararg names: String) =
            MessageText.renderCompactConsolidation(summary(IdentityGroup(kind, nicks(*names), hidden = 0)), style).text
        assertEquals("alice was opped", text(Kind.ModeGranted("o"), "alice"))
        assertEquals("a and b were devoiced", text(Kind.ModeRevoked("v"), "a", "b"))
        assertEquals("alice was briefly opped", text(Kind.ModeBriefly("o"), "alice"))
        assertEquals("alice was opped again", text(Kind.ModeRegranted("o"), "alice"))
        assertEquals("alice was given +a", text(Kind.ModeGranted("a"), "alice"))
        assertEquals("alice lost +a", text(Kind.ModeRevoked("a"), "alice"))
    }

    // MARK: - Typing

    @Test
    fun `the typing line is a glyph and up to three names`() {
        val line = MessageText.renderCompactTyping(listOf("alice", "bob", "carol", "dave", "erin"), style)!!
        assertEquals("Typing:\u00A0alice, bob, carol, +2", line.text)
        assertEquals(MessageText.spoken(line), line.text)
        val indent = line.paragraphStyles.single().item.textIndent!!
        assertEquals((style.indentSp + style.typingGlyphWidthSp).sp, indent.restLine)
        assertNull(MessageText.renderCompactTyping(emptyList(), style))
    }

    // MARK: - Times and markers

    @Test
    fun `a header time is 24-hour minutes`() {
        assertEquals("14:42", MessageText.compactHeaderTime(Instant.parse("2026-07-25T14:42:59Z"), ZoneOffset.UTC))
        assertEquals("02:05", MessageText.compactHeaderTime(Instant.parse("2026-07-25T02:05:00Z"), ZoneOffset.UTC))
    }

    @Test
    fun `a day label is relative near today and the full date otherwise`() {
        val today = LocalDate.of(2026, 7, 25)
        fun label(day: String) = MessageText.dayLabel(Instant.parse(day), today, ZoneOffset.UTC, Locale.US)
        assertEquals("Today", label("2026-07-25T00:00:00Z"))
        assertEquals("Yesterday", label("2026-07-24T00:00:00Z"))
        assertEquals("Wednesday, July 22, 2026", label("2026-07-22T00:00:00Z"))
    }

    @Test
    fun `the away marker carries its reason, trimmed`() {
        assertEquals("You went away", MessageText.awayLabel(null))
        assertEquals("You went away", MessageText.awayLabel(" \n "))
        assertEquals("You went away: lunch", MessageText.awayLabel("  lunch "))
    }

    @Test
    fun `the back marker says how long, floored to a minute`() {
        val away = Instant.parse("2026-07-25T12:00:00Z")
        assertEquals("You're back — away 1m", MessageText.backLabel(away, away.plusSeconds(20)))
        assertEquals("You're back — away 1h 5m", MessageText.backLabel(away, away.plusSeconds(3_900)))
        assertEquals("You're back — away 2d 3h 4m", MessageText.backLabel(away, away.plusSeconds(2 * 86_400 + 3 * 3_600 + 4 * 60)))
        assertEquals("You're back — away 1h", MessageText.backLabel(away, away.plusSeconds(3_600)))
        assertEquals("You're back", MessageText.backLabel(away, away.minusSeconds(1)))
    }

    @Test
    fun `the cleared marker names the way back`() {
        val label = MessageText.clearedLabel(Instant.parse("2026-07-25T15:42:00Z"), ZoneOffset.UTC, Locale.US)
        assertTrue(label, label.startsWith("cleared 7/25/26"))
        assertTrue(label, label.endsWith(" · /clear off to undo"))
    }

    // MARK: - Replies

    private fun quote(type: EventType = EventType.Message, nick: String = "alice", text: String = "hello \u0002there\u0002") =
        Replies.shown(
            ReplyContext("m1", ReplyParent(id = 3, nick = nick, type = type, text = text)),
            line = message("ok"),
            networkId = 1,
            target = "#c",
            ignores = IgnoreSet.empty,
            relayBots = RelayBotSet.empty,
            ownNick = "me",
        ).quote

    @Test
    fun `a quote is written the way IRC writes the line`() {
        assertEquals("╭─ <alice> hello there", MessageText.renderReplyQuote(quote(), indented = false, style).text)
        assertEquals("╭─ * alice waves", MessageText.renderReplyQuote(quote(EventType.Action, text = "waves"), false, style).text)
        assertEquals("╭─ -ChanServ- hi", MessageText.renderReplyQuote(quote(EventType.Notice, "ChanServ", "hi"), false, style).text)
        assertEquals("╭─ original message unavailable", MessageText.renderReplyQuote(null, false, style).text)
    }

    @Test
    fun `a quote is italic, its nick in the nick's colour`() {
        val line = MessageText.renderReplyQuote(quote(), indented = true, style)
        assertEquals(FontStyle.Italic, line.styleAt(line.indexOf("hello")).fontStyle)
        assertEquals(MessageText.hashedColor("alice", style), line.styleAt(line.indexOf("alice")).color)
        assertEquals(style.indentSp.sp, line.paragraphStyles.single().item.textIndent!!.firstLine)
    }

    @Test
    fun `a quote is spoken as a reply`() {
        assertEquals("In reply to alice: hello there", MessageText.spokenReplyQuote(quote()))
        assertEquals("In reply to a message that's unavailable", MessageText.spokenReplyQuote(null))
    }

    // MARK: - Reactions

    @Test
    fun `a chip spells out twelve characters and cuts on clusters`() {
        assertEquals("👍", MessageText.chipValue("👍"))
        assertEquals("twelve chars", MessageText.chipValue("twelve chars"))
        assertEquals("thirteen ch…", MessageText.chipValue("thirteen char"))
        val families = "👨‍👩‍👧".repeat(13)
        assertEquals("👨‍👩‍👧".repeat(11) + "…", MessageText.chipValue(families))
    }

    @Test
    fun `a chip is spoken with its count and who`() {
        assertEquals("👍, 2: alice, bob", MessageText.spokenReaction(ReactionGroup("👍", listOf("alice", "bob"), mine = false)))
    }

    // MARK: - StyledBody

    @Test
    fun `a deletion moves every span with its characters`() {
        val body = StyledBody()
        body.append("ab")
        val bold = body.append("cd", SpanStyle(fontWeight = FontWeight.Bold))
        body.ink(bold)
        body.deleteCharacters(TextRange(0, 1))
        assertEquals("bcd", body.string)
        assertEquals(listOf(TextRange(1, 3)), body.inkRanges())
        assertTrue(body.ink(1))
        assertFalse(body.ink(0))
        // A span wholly inside a cut vanishes.
        body.deleteCharacters(TextRange(1, 3))
        assertEquals("b", body.string)
        assertTrue(body.inkRanges().isEmpty())
    }
}
