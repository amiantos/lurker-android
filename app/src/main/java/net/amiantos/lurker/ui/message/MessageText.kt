// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeNarration
import net.amiantos.lurkerkit.model.PreviewText
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyQuote
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.rendering.IRCColor
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.NickColor
import net.amiantos.lurkerkit.rendering.NickHighlighter
import net.amiantos.lurkerkit.rendering.URLMatcher
import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.text.BreakIterator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * What the renderer draws with: the scheme's colours, and the compact face's two measurements.
 * lurker-ios's `UITraitCollection` + `Palette`, as the inputs `MessageRenderer` takes — a plain
 * value, so every function below is pure and runs in a JVM test.
 *
 * Built once per screen from the theme and the measured face (`rememberMessageTextStyle`); the
 * defaults are the 14sp `bodyMedium` a phone gets at the default font scale, for tests and previews.
 */
@Immutable
data class MessageTextStyle(
    val colors: LurkerColors,
    /** The compact face's size, in sp — the one text size. */
    val fontSizeSp: Float = 14f,
    /**
     * One character of the monospaced face, in sp — iOS's `compactIndent`: how far a body sits in
     * from its author, and how far a wrapped narration line hangs. Measured, not guessed, by the
     * screen; 0.6em is what a monospaced face's advance almost always is.
     */
    val indentSp: Float = fontSizeSp * 0.6f,
) {
    /**
     * The typing line's keyboard glyph, wide. iOS measures its symbol (about two and a half
     * characters at the default size); this one is a fixed slice of the font size, so it tracks the
     * font scale with the text it sits in.
     */
    val typingGlyphWidthSp: Float get() = fontSizeSp * TYPING_GLYPH_EM

    companion object {
        /** The keyboard glyph's width, in ems — `MessageRow` sizes its placeholder from this. */
        const val TYPING_GLYPH_EM = 1.4f
    }
}

/**
 * Turns a `Message` into styled text, mirroring the web client: mIRC formatting, colours, and
 * auto-linked URLs. One font size throughout — hierarchy comes from weight, italics, and colour,
 * never size. lurker-ios's `MessageRenderer`, the compact functions (the list draws in one shape;
 * the bubble style and its renderer were deleted by lurker-ios#75, and there is no second style to
 * leave a seam for).
 *
 * What comes back is a *body*: the author and the time belong to the row, drawn once per block, and
 * must not also be baked into the text. [caption]/[captionColor] say what to call a line's author
 * for that header.
 *
 * Pure: kit values and a [MessageTextStyle] in, an [AnnotatedString] out — no `@Composable`, no
 * `Context` — so the highlights feed (U7) can render a line exactly as the list does, and every rule
 * here is pinned by `MessageTextTest`.
 */
object MessageText {

    /** The string annotation on a spoiler box, valued with its ordinal — iOS's `.spoiler`. */
    const val SPOILER_TAG = "lurker.spoiler"

    /**
     * The string annotation on a still-hidden spoiler, valued with what to say instead of the text
     * under it — iOS's `.spoilerHidden`. The wording rides along rather than living in [spoken]
     * because it isn't always the same: a box the reader can't open must not be announced as though
     * they could.
     */
    const val HIDDEN_TAG = "lurker.spoilerHidden"

    /** The inline-content id of the typing line's keyboard glyph. `MessageRow` supplies the glyph. */
    const val TYPING_GLYPH = "lurker.typingGlyph"

    /** What a hidden spoiler is called to TalkBack when the reader can open it. */
    const val HIDDEN_SPOILER_REVEALABLE = "hidden spoiler, double tap to reveal"

    /** …and when they can't (an id-less line — see [body]). */
    const val HIDDEN_SPOILER = "hidden spoiler"

    // MARK: - Authors

    /**
     * What names a message's author in its block header. Null leaves the block unheaded.
     *
     * [modePrefix] is the speaker's channel-mode glyph (`@`, `+`, …) when `look.nick.show_mode_prefix`
     * is on and they're a current member — the caller resolves it, because it comes from the nicklist
     * rather than from the message. It prefixes a nick only: a network or a notice's `-mark-` isn't a
     * channel member.
     */
    fun caption(message: Message, networkName: String?, modePrefix: String = ""): String? =
        when (message.type) {
            // IRC's own mark for a notice, in the place that names the speaker — the only thing
            // separating "NickServ said this" from "NickServ noticed this".
            EventType.Notice -> "-${message.nick ?: ""}-"
            // App-scoped, so it names the network it's *about* rather than a nick.
            EventType.System -> networkName ?: "System"
            // Raw server text has no author but the server itself.
            EventType.Motd, EventType.Other -> networkName
            else -> message.nick?.let { modePrefix + it }
        }

    /** The caption's colour. Usually a nick colour, but a system line names a *network*, and server text is nobody. */
    fun captionColor(message: Message, networkName: String?, style: MessageTextStyle): Color =
        when (message.type) {
            // A network-tied system line hashes its network name through the same palette as nicks,
            // so each network gets a stable, distinguishable colour — matching the web. The app
            // speaking in its own voice ("System", no network) stays muted.
            EventType.System -> networkName?.let { hashedColor(it, style) } ?: style.colors.fgMuted
            EventType.Motd, EventType.Other -> style.colors.fgMuted
            else -> nickColor(message.nick, message.isSelf, style)
        }

    /**
     * A header's name line: the nick in its colour, the mode glyph in its rank's, and a relay line's
     * source trailing it. lurker-ios's `CompactCell.configure` header half.
     *
     * The glyph wears `look.color.member.*`, not the name's colour: a rank is a property of the room,
     * not of the person — the split the web's `NickRef` makes — and bold, because the rank hues are
     * ~3.5–4:1 on the light canvas, which clears the bar for large text and not for regular.
     *
     * The source (#277) is unbracketed and `fgFaint`, a tier below the timestamp at the other end of
     * the row: a hint, not a field. Last on the line on purpose — the name truncates from the tail, so
     * under width pressure the provenance goes before the speaker does.
     */
    fun headerName(header: CompactHeader, style: MessageTextStyle): AnnotatedString = buildAnnotatedString {
        val prefix = header.modePrefix
        val rank = style.colors.memberPrefix(prefix)
        withStyle(SpanStyle(color = header.color, fontWeight = FontWeight.SemiBold)) {
            if (rank != null && prefix.isNotEmpty() && header.nick.startsWith(prefix)) {
                withStyle(SpanStyle(color = rank, fontWeight = FontWeight.Bold)) { append(prefix) }
                append(header.nick.substring(prefix.length))
            } else {
                append(header.nick)
            }
        }
        val source = header.relaySource
        if (!source.isNullOrEmpty()) {
            withStyle(SpanStyle(color = style.colors.fgFaint)) { append(" $source") }
        }
    }

    // MARK: - Compact rows

    /**
     * The body of a compact row — the text alone, with no time and no author.
     *
     * Those are the row's (`MessageRow` draws the header), which is what lets several messages from
     * one person stack under a single nick. Same mIRC colours, same auto-linking, same nick colouring
     * inside the body as the web.
     *
     * @param revealed which of this message's spoilers the reader has opened, by ordinal.
     * @param hiddenUrls the addresses a link preview stands in for, taken out of the text — the row's
     *   `PreviewPlan.hidden`.
     * @param onToggleSpoiler what a tap on spoiler `n` does; null where there's nothing to toggle.
     */
    fun renderCompactBody(
        message: Message,
        style: MessageTextStyle,
        settings: Settings = Settings(),
        highlighter: NickHighlighter? = null,
        revealed: Set<Int> = emptySet(),
        hiddenUrls: Set<String> = emptySet(),
        onToggleSpoiler: ((Int) -> Unit)? = null,
    ): AnnotatedString {
        // A line that names its own actor keeps doing so: it has no header to be named by.
        if (message.type == EventType.Action) {
            val color = nickColor(message.nick, message.isSelf, style)
            val line = StyledBody()
            line.append("* ${message.nick ?: "*"} ", SpanStyle(color = color))
            line.append(body(message, style, fallback = color, highlighter, revealed, hiddenUrls))
            // Two, to clear the `* ` this line opens with.
            return finish(line, style, flushFirstLine = true, indentCharacters = 2, onToggleSpoiler = onToggleSpoiler)
        }
        if (message.type.isActivity) {
            // No arrow column. "alice joined" already says which direction it went, and the
            // narration starts flush with the nicks above it, so arrows would be an extra column of
            // punctuation buying nothing.
            return finish(activity(message, style, settings, revealed), style, flushFirstLine = true, onToggleSpoiler = onToggleSpoiler)
        }
        // `fg`, explicitly: `body` stamps a foreground on every run, so this fallback IS the log's
        // primary text colour.
        return finish(
            body(message, style, fallback = style.colors.fg, highlighter, revealed, hiddenUrls),
            style,
            flushFirstLine = false,
            onToggleSpoiler = onToggleSpoiler,
        )
    }

    /**
     * A collapsed run — "alice, bob and 3 others joined; dave left". Nicks keep their colours; the
     * categories and connectives are muted. Header-less, like the activity lines it stands for.
     */
    fun renderCompactConsolidation(summary: ConsolidationSummary, style: MessageTextStyle): AnnotatedString {
        val line = StyledBody()
        summary.groups.forEachIndexed { index, group ->
            if (index > 0) line.append("; ", muted(style))
            identityClause(group, style, line)
        }
        return finish(line, style, flushFirstLine = true, onToggleSpoiler = null)
    }

    /**
     * "⌨ alice, bob" — the live composing line at the foot of the buffer (lurker-ios#61).
     *
     * Narration about the room, not speech in it, with no author to caption. Names keep their
     * palette colours so you can pick out who without reading; the glyph and the separators are
     * muted. A symbol and a bare list rather than a sentence, matching the web status bar: the row
     * exists to be glanced at, and "is typing…"/"are typing…" spent a third of a phone-width line
     * restating the glyph — while the singular/plural swap reflowed the most-often-redrawn row in the
     * buffer. Upright, not italic: setting the row that changes most often in a second style made it
     * pull the eye harder than a thing that says nothing has earned.
     *
     * Past three names the list stops being scannable; the overflow is the web's `+N`. Fixed, unlike
     * consolidation's name cap: this line isn't a record, it's gone the moment they stop.
     *
     * The glyph is inline content ([TYPING_GLYPH]) whose stand-in text is "Typing:" — what TalkBack
     * reads in its place, the colon doing in speech what the glyph does in print. A non-breaking space
     * follows it, so the row can't wrap with the glyph alone on its first line. It hangs by the
     * glyph's width on top of the usual character, so a wrapped list continues under the first
     * *name*. Null for an empty list.
     */
    fun renderCompactTyping(nicks: List<String>, style: MessageTextStyle): AnnotatedString? {
        if (nicks.isEmpty()) return null
        val visible = nicks.take(3)
        val hidden = nicks.size - visible.size
        val names = StyledBody()
        // Non-breaking, so the row can't wrap here: a breakable space let the line end after the
        // glyph \u2014 a first line holding nothing but a keyboard symbol.
        names.append("\u00A0", muted(style))
        visible.forEachIndexed { index, nick ->
            if (index > 0) names.append(", ", muted(style))
            names.append(nickName(nick), SpanStyle(color = nickColor(nick, isSelf = false, style)))
        }
        if (hidden > 0) names.append(", +$hidden", muted(style))
        val indent = style.indentSp + style.typingGlyphWidthSp
        return buildAnnotatedString {
            appendInlineContent(TYPING_GLYPH, "Typing:")
            append(names.toAnnotatedString(paragraph = null, linkStyles = null, onToggleSpoiler = null))
            addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = indent.sp)), 0, length)
        }
    }

    /**
     * `14:42` for a compact header.
     *
     * Minutes, not seconds: the stamp only appears when the minute changes, so a seconds field would
     * be the precise moment of whichever message happened to start the minute — precision the format
     * doesn't carry. Fixed 24-hour through the root locale, as iOS uses POSIX, since `HH` in some
     * locales still comes out in other digits.
     */
    fun compactHeaderTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        headerTimeFormatter.withZone(zone).format(instant)

    private val headerTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    // MARK: - Markers

    /**
     * The label on a day-change divider — "Today", "Yesterday", or "Friday, July 25, 2026".
     *
     * iOS's relative formatting, which is the platform convention there (Messages, Mail) and earns
     * its keep on a divider the reader passes every day. The relative words are English, like every
     * string in the app for now; the date itself is the reader's locale. [today] is the screen's,
     * advanced at midnight — the row only carries the day, so a redraw is what corrects a label left
     * open past midnight.
     */
    fun dayLabel(
        day: Instant,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val date = day.atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            today.plusDays(1) -> "Tomorrow"
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
        }
    }

    /**
     * The label on the away marker (lurker-ios#68). The reason rides the same line when there is one
     * — the useful half of the marker for anyone reading their own scrollback later.
     *
     * Tested and shown as the same value: the server trims before it stores one, and testing one
     * string while printing another is the kind of seam that outlives the reason it was fine.
     */
    fun awayLabel(message: String?): String {
        val reason = message?.trimmingWhitespacesAndNewlines() ?: ""
        return if (reason.isEmpty()) "You went away" else "You went away: $reason"
    }

    /**
     * The label on the back marker. The duration is what makes it worth a row at all, so it's
     * dropped only when the two instants can't be subtracted into one — a clock disagreeing with
     * itself rather than a span.
     */
    fun backLabel(awayAt: Instant, backAt: Instant): String {
        val gone = awayDuration(awayAt, backAt) ?: return "You're back"
        return "You're back — away $gone"
    }

    /**
     * The label on the `/clear` marker (lurker-ios#121), naming the way back.
     *
     * The row is a marker, not a control, so the label carries the command: a divider that only said
     * "cleared 3:42 PM" would leave the hidden conversation behind a `/clear off` nobody knew to type.
     * Date *and* time: a clear is undone days later as often as minutes later.
     */
    fun clearedLabel(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val stamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)
        return "cleared $stamp · /clear off to undo"
    }

    /**
     * How long an away lasted, abbreviated ("45m", "1h 5m", "2d 3h 4m"), zero units dropped.
     *
     * Clamped up to a minute rather than shown as nothing: the marker is about a span the reader
     * missed, and a sub-minute one is better described by its floor. Null for a backwards interval.
     */
    internal fun awayDuration(from: Instant, to: Instant): String? {
        val seconds = Duration.between(from, to).seconds
        if (seconds < 0) return null
        val total = max(60L, seconds)
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        return listOfNotNull(
            if (days > 0) "${days}d" else null,
            if (hours > 0) "${hours}h" else null,
            if (minutes > 0) "${minutes}m" else null,
        ).joinToString(" ")
    }

    // MARK: - Replies

    /**
     * An IRCv3 reply's quote (lurker-ios#184) — the web's `ReplyQuote.vue`: the answered line written
     * the way IRC writes it, `<alice> text`, `* bob waves`, `-ChanServ- text`, after a rounded
     * box-drawing arm, so it reads as what was said rather than a sentence starting with a name.
     * Italic, one line; the row fades it as a whole so the quoted nick keeps its own colour. With
     * nothing to quote it says so.
     *
     * The arm is upright and lifted a little: in an italic line a slanted corner stops lining up, and
     * on the baseline it sits below the text's middle. [indented] lines it up with a body that sits
     * one character in; a header-less narration line's quote starts flush, as the line does.
     */
    fun renderReplyQuote(quote: ReplyQuote?, indented: Boolean, style: MessageTextStyle): AnnotatedString =
        buildAnnotatedString {
            val fg = style.colors.fg
            withStyle(SpanStyle(color = fg, baselineShift = BaselineShift(REPLY_ARM_LIFT))) { append("╭─ ") }
            val plain = SpanStyle(color = fg, fontStyle = FontStyle.Italic)
            if (quote == null) {
                withStyle(plain) { append("original message unavailable") }
            } else {
                val (open, close) = when (quote.type) {
                    EventType.Action -> "* " to ""
                    EventType.Notice -> "-" to "-"
                    else -> "<" to ">"
                }
                withStyle(plain) {
                    append(open)
                    withStyle(SpanStyle(color = nickColor(quote.nick, quote.isSelf, style))) { append(quote.nick) }
                    append("$close ")
                    val source = quote.relaySource
                    if (!source.isNullOrEmpty()) append("[$source] ")
                    append(Replies.excerpt(quote.text))
                }
            }
            if (indented) {
                val indent = style.indentSp.sp
                addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent)), 0, length)
            }
        }

    /** What TalkBack says for a reply's quote. */
    fun spokenReplyQuote(quote: ReplyQuote?): String {
        if (quote == null) return "In reply to a message that's unavailable"
        return "In reply to ${quote.nick}: ${Replies.excerpt(quote.text)}"
    }

    /** iOS lifts the arm by 18% of the point size, as the web nudges it up 3px. */
    private const val REPLY_ARM_LIFT = 0.18f

    // MARK: - Reactions

    /**
     * A chip's value, spelled out to at most twelve characters — past it the start shows and the sheet
     * (U6) has the whole (#1014 on the web, halloy's rule). Cut on grapheme clusters, so an emoji is
     * never split.
     */
    fun chipValue(value: String): String {
        val boundaries = graphemeEnds(value)
        if (boundaries.size <= MAX_CHIP_CHARACTERS) return value
        return value.substring(0, boundaries[MAX_CHIP_CHARACTERS - 2]) + "…"
    }

    /** "👍, 2: alice, bob" — what TalkBack reads for a chip; it names the emoji itself. */
    fun spokenReaction(group: ReactionGroup): String =
        "${group.value}, ${group.nicks.size}: ${group.nicks.joinToString(", ")}"

    private const val MAX_CHIP_CHARACTERS = 12

    /** Where each grapheme cluster of [text] ends, in UTF-16 offsets. */
    private fun graphemeEnds(text: String): List<Int> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val ends = mutableListOf<Int>()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            ends.add(end)
            end = iterator.next()
        }
        return ends
    }

    // MARK: - Speech

    /**
     * A rendered line as TalkBack should hear it.
     *
     * ⚠⚠ A still-hidden spoiler is substituted, and that is not cosmetic: without it a screen reader
     * announces the secret while the screen shows a blank box (lurker-ios's "hidden text LEAKS to
     * VoiceOver"). The web stops this with `aria-hidden`; here the row's semantics are built out of
     * this string instead of the drawn text, so this is where it has to be stopped. The words name the
     * affordance rather than describing a redaction, because to TalkBack it is a thing you can act on
     * (the row's "Reveal spoiler" actions).
     *
     * The typing glyph needs nothing here: its stand-in text "Typing:" is already in the string.
     * A line with nothing to substitute comes back untouched, so the trim can't turn a whitespace-only
     * body into an empty label.
     */
    fun spoken(rendered: AnnotatedString): String = spokenAnnotated(rendered).text

    /**
     * [spoken], keeping the line's links where they land in the spoken words — what the row hands
     * TalkBack as its text, so a link in a message stays actionable from TalkBack's links menu while
     * the hidden text never reaches it. Only links: a spoiler's own tap target, and every style, are
     * left behind.
     */
    fun spokenAnnotated(rendered: AnnotatedString): AnnotatedString {
        val plain = rendered.text
        val urls = rendered.getLinkAnnotations(0, rendered.length).filter { it.item is LinkAnnotation.Url }
        // Adjacent hidden runs that say the same thing are one announcement — a bold or italic
        // inside a box splits it into several runs, and the reader should hear about the box once.
        // (What `enumerateAttribute` coalesces for free on iOS.)
        val hidden = mutableListOf<AnnotatedString.Range<String>>()
        for (range in rendered.getStringAnnotations(HIDDEN_TAG, 0, rendered.length).sortedBy { it.start }) {
            val last = hidden.lastOrNull()
            if (last != null && last.end == range.start && last.item == range.item) {
                hidden[hidden.lastIndex] = AnnotatedString.Range(last.item, last.start, range.end)
            } else {
                hidden.add(range)
            }
        }
        val spoken = buildAnnotatedString {
            fun segment(from: Int, to: Int) {
                if (from >= to) return
                val offset = length - from
                append(plain, from, to)
                for (url in urls) {
                    if (url.start >= from && url.end <= to) {
                        addLink(url.item as LinkAnnotation.Url, url.start + offset, url.end + offset)
                    }
                }
            }
            var cursor = 0
            for (range in hidden) {
                segment(cursor, range.start)
                append(range.item)
                cursor = range.end
            }
            segment(cursor, plain.length)
        }
        if (hidden.isEmpty()) return spoken
        val text = spoken.text
        val start = text.indexOfFirst { !it.isInWhitespacesAndNewlines() }
        if (start < 0) return AnnotatedString("")
        val end = text.indexOfLast { !it.isInWhitespacesAndNewlines() } + 1
        return spoken.subSequence(start, end)
    }

    /**
     * The ordinals of the spoilers still hidden in a rendered line, in reading order — one TalkBack
     * action per box, because the tap that opens one is aimed at a point and TalkBack has none to
     * give. Only the openable ones: a box with no tap target has no ordinal annotation.
     */
    fun hiddenSpoilerOrdinals(rendered: AnnotatedString): List<Int> {
        val hidden = rendered.getStringAnnotations(HIDDEN_TAG, 0, rendered.length)
        return rendered.getStringAnnotations(SPOILER_TAG, 0, rendered.length)
            .sortedBy { it.start }
            .filter { spoiler -> hidden.any { it.start < spoiler.end && spoiler.start < it.end } }
            .mapNotNull { it.item.toIntOrNull() }
            .distinct()
    }

    // MARK: - Colours

    /**
     * Your own nick is the plain foreground, not the accent.
     *
     * Matches the web, whose `look.nick.self_color` defaults to `var(--fg)`. The accent is the app's
     * voice — a live control — and wearing it in the log made every line you'd written look like a
     * piece of UI rather than a thing you said. (The in-body pass agrees by omission: the screen
     * builds its `NickHighlighter` without your own nick.)
     */
    fun nickColor(nick: String?, isSelf: Boolean, style: MessageTextStyle): Color =
        if (isSelf) style.colors.fg else hashedColor(nick ?: "", style)

    /**
     * A stable colour for a name, from the shared nick palette. Nicks and network names both run
     * through it, so the same name is always the same colour, on every client.
     */
    fun hashedColor(name: String, style: MessageTextStyle): Color {
        val palette = style.colors.nick
        return palette[NickColor.index(name, paletteCount = palette.size)]
    }

    /**
     * The colour a formatting code named, or null for a slot the palette can't paint (16+).
     *
     * Every value is a literal: a slot is the palette's, and a truecolour `\x04` is exactly what was
     * sent, with no light variant — the sender picked it knowing what it'd be drawn on, and ASCII art
     * in particular is a picture, not text to re-theme. There is no theme-slot branch, and there must
     * not be one (see `IRCPalette.mirc`).
     */
    private fun ircColor(color: IRCColor, style: MessageTextStyle): Color? =
        when (color) {
            is IRCColor.Slot -> style.colors.mirc.getOrNull(color.index)
            is IRCColor.Rgb -> Color(0xFF000000.toInt() or color.value.toInt())
        }

    // MARK: - Line building blocks

    private fun muted(style: MessageTextStyle) = SpanStyle(color = style.colors.fgMuted)

    /** A nick that has to say *something*: "someone" for the nick-less event that shouldn't happen but mustn't render blank. */
    private fun nickName(nick: String?): String = if (nick.isNullOrEmpty()) "someone" else nick

    private fun nickToken(line: StyledBody, nick: String?, style: MessageTextStyle, isSelf: Boolean = false) {
        line.append(nickName(nick), SpanStyle(color = nickColor(nick, isSelf, style)))
    }

    /** A part, quit or kick reason in parentheses, or nothing when there isn't one. */
    private fun appendReason(line: StyledBody, message: Message, style: MessageTextStyle, revealed: Set<Int>) =
        appendActivityBody(line, message, style, revealed, open = " (", close = ")")

    /**
     * The text an activity line carries — a topic, a reason — between [open] and [close] in the
     * narration's grey, or nothing at all when it has no visible text. Through [body] (sweep L06): it
     * carries mIRC colours and links like any message, and as plain text its colour digits leaked
     * ("(04Leaving") and its URLs couldn't be tapped. Judged on what [body] drew, so a text of nothing
     * but formatting codes leaves no empty "()" or dangling ": ".
     */
    private fun appendActivityBody(
        line: StyledBody,
        message: Message,
        style: MessageTextStyle,
        revealed: Set<Int>,
        open: String,
        close: String = "",
    ) {
        val text = message.text ?: return
        if (text.isEmpty()) return
        val drawn = body(message, style, fallback = style.colors.fgMuted, revealed = revealed)
        if (drawn.string.trimmingWhitespacesAndNewlines().isEmpty()) return
        line.append(open, muted(style))
        line.append(drawn)
        if (close.isNotEmpty()) line.append(close, muted(style))
    }

    /**
     * Indent and seal a line: a message body sits under its author throughout; narration that names
     * its own actor — a `/me`, a join, a collapsed run — starts flush, since it *is* the nick line,
     * and only its wrapped continuations tuck in. [indentCharacters] is two for a `/me`, whose `* `
     * prefix is two characters wide. iOS's `spaced`.
     *
     * The line spacing iOS sets here (`compactLineGap`) is the text style's line height on Android:
     * `bodyMedium`'s 20sp already puts a wrapped line the same distance from the next as one message
     * is from the next, which is the rhythm that constant exists to keep.
     */
    private fun finish(
        line: StyledBody,
        style: MessageTextStyle,
        flushFirstLine: Boolean,
        indentCharacters: Int = 1,
        onToggleSpoiler: ((Int) -> Unit)?,
    ): AnnotatedString {
        val indent = (style.indentSp * indentCharacters).sp
        val paragraph = ParagraphStyle(textIndent = TextIndent(firstLine = if (flushFirstLine) 0.sp else indent, restLine = indent))
        return line.toAnnotatedString(paragraph, linkStyles(style), onToggleSpoiler)
    }

    /**
     * How a link looks: the colour of the text around it, underlined — iOS's treatment (the web's
     * `--link`). Not the accent: one colour for every link flattens a `/me`'s nick-coloured body and a
     * sender's mIRC run, which is how `/me` links once came out white on iOS. Opened by the `Text`'s
     * `UriHandler`.
     *
     * Port note: iOS draws the underline at 40% of the text's colour. A Compose `SpanStyle` has no
     * separate underline colour, so here it's the text's own.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun linkStyles(style: MessageTextStyle) =
        TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))

    /**
     * A structural line — "alice joined", "bob is now bob_afk", "ChanServ gave op to dave". The actor
     * and any nicks it names are coloured; the connective words are muted, so the line reads as
     * narration about the room rather than something someone said in it.
     *
     * [revealed] reaches the one body a line can carry — a topic or a reason — so a spoiler in it opens.
     */
    private fun activity(message: Message, style: MessageTextStyle, settings: Settings, revealed: Set<Int>): StyledBody {
        val line = StyledBody()
        val actor = { nickToken(line, message.nick, style, isSelf = message.isSelf) }
        // Both off by default, matching the registry. The account sits between the nick and the
        // host, as it does on the web (and in weechat, irssi and thelounge).
        val account = if (settings.bool("chat.show_join_account", default = false)) {
            message.account?.let { " [$it]" } ?: ""
        } else {
            ""
        }
        val host = if (settings.bool("chat.show_event_host", default = false)) {
            message.userHostMask?.let { " ($it)" } ?: ""
        } else {
            ""
        }
        val muted = muted(style)
        when (message.type) {
            EventType.Join -> {
                actor()
                line.append("$account$host joined", muted)
            }
            EventType.Part -> {
                actor()
                line.append("$host left", muted)
                appendReason(line, message, style, revealed)
            }
            EventType.Quit -> {
                actor()
                line.append("$host quit", muted)
                appendReason(line, message, style, revealed)
            }
            EventType.Nick -> {
                actor()
                line.append(" is now ", muted)
                nickToken(line, message.newNick, style, isSelf = message.isSelf)
                line.append(host, muted)
            }
            EventType.Kick -> {
                nickToken(line, message.kicked, style)
                line.append(" was kicked by ", muted)
                actor()
                appendReason(line, message, style, revealed)
            }
            EventType.Mode -> {
                actor()
                for (segment in ModeNarration.describe(message.modes, rawText = message.text)) {
                    when (segment) {
                        is ModeNarration.Segment.Text -> line.append(segment.text, muted)
                        is ModeNarration.Segment.Arg -> line.append(segment.arg, muted)
                        // Only ever emitted for a `prefix` change, so this is a real member and
                        // never a mask — a ban's target must not get a nick's colour.
                        is ModeNarration.Segment.Nick -> nickToken(line, segment.nick, style)
                    }
                }
            }
            EventType.Chghost -> {
                // The suffix here is the mask *before* the change — the new one is the body of the
                // line — which is what makes weechat's "nick (old) has changed host to new" readable.
                actor()
                line.append("$host changed host to ${message.chghostMask}", muted)
            }
            EventType.Topic -> {
                actor()
                line.append(" set the topic", muted)
                // The same muted as the ": " before it — two greys mid-sentence read as a seam, and the
                // topic is a continuation of the narration, not a quote.
                appendActivityBody(line, message, style, revealed, open = ": ")
            }
            EventType.Invite -> {
                actor()
                line.append(" invited ", muted)
                nickToken(line, message.invited, style)
            }
            else -> {
                // Only actions and activity reach here, but a line still has to show *something* if
                // that ever changes: the actor, then whatever text it has.
                actor()
                val text = message.text
                if (!text.isNullOrEmpty()) line.append(" $text", muted)
            }
        }
        return line
    }

    /** One summary category as "alice, bob and carol joined" — the names as nick tokens, the rest muted. */
    private fun identityClause(group: ConsolidationSummary.IdentityGroup, style: MessageTextStyle, line: StyledBody) {
        val muted = muted(style)
        group.visible.forEachIndexed { index, entry ->
            if (index > 0) {
                // "and" before the final name only when the list isn't truncated; a truncated list
                // ends "…, and N others" instead.
                val isLast = index == group.visible.size - 1
                line.append(if (isLast && group.hidden == 0) " and " else ", ", muted)
            }
            when (entry) {
                is ConsolidationSummary.Entry.Nick -> nickToken(line, entry.nick, style)
                is ConsolidationSummary.Entry.Renamed -> {
                    nickToken(line, entry.from, style)
                    line.append(" → ", muted)
                    nickToken(line, entry.to, style)
                }
            }
        }
        if (group.hidden > 0) line.append(", and ${group.hidden} other${if (group.hidden == 1) "" else "s"}", muted)
        line.append(verb(group), muted)
    }

    private fun verb(group: ConsolidationSummary.IdentityGroup): String =
        when (group.kind) {
            ConsolidationSummary.IdentityGroup.Kind.Joined -> " joined"
            ConsolidationSummary.IdentityGroup.Kind.Left -> " left"
            ConsolidationSummary.IdentityGroup.Kind.Reconnected -> " reconnected"
            ConsolidationSummary.IdentityGroup.Kind.JoinedAndLeft -> " joined briefly"
            ConsolidationSummary.IdentityGroup.Kind.Renamed -> "" // the → in the name conveys it
            ConsolidationSummary.IdentityGroup.Kind.Rehosted -> " changed host"
            // Listed rather than defaulted, so adding a presence kind fails to compile here instead
            // of silently falling into modeVerb, whose letter is null for a presence case.
            is ConsolidationSummary.IdentityGroup.Kind.ModeGranted,
            is ConsolidationSummary.IdentityGroup.Kind.ModeRevoked,
            is ConsolidationSummary.IdentityGroup.Kind.ModeBriefly,
            is ConsolidationSummary.IdentityGroup.Kind.ModeRegranted,
            -> modeVerb(group)
        }

    /**
     * What a member-prefix letter grants, for the summary's "were opped" phrasing. Only the LETTER
     * travels on the group — the words are each client's own, as "joined" is. `o` and `v` are
     * effectively all real traffic and get verbs; anything else falls back to the token, because
     * inventing English for `+a` would be guessing and "was given +a" is honest.
     */
    private val modeVerbs: Map<String, Pair<String, String>> = mapOf(
        "o" to ("opped" to "deopped"),
        "v" to ("voiced" to "devoiced"),
    )

    private fun modeVerb(group: ConsolidationSummary.IdentityGroup): String {
        val kind = group.kind
        val letter = kind.modeLetter ?: return ""
        val be = if (group.visible.size + group.hidden == 1) " was " else " were "
        val verb = modeVerbs[letter]
        if (verb != null) {
            return when (kind) {
                is ConsolidationSummary.IdentityGroup.Kind.ModeGranted -> "$be${verb.first}"
                is ConsolidationSummary.IdentityGroup.Kind.ModeRevoked -> "$be${verb.second}"
                is ConsolidationSummary.IdentityGroup.Kind.ModeBriefly -> "${be}briefly ${verb.first}"
                else -> "$be${verb.first} again"
            }
        }
        return when (kind) {
            is ConsolidationSummary.IdentityGroup.Kind.ModeGranted -> "${be}given +$letter"
            is ConsolidationSummary.IdentityGroup.Kind.ModeRevoked -> " lost +$letter"
            is ConsolidationSummary.IdentityGroup.Kind.ModeBriefly -> " briefly had +$letter"
            else -> "${be}given +$letter again"
        }
    }

    // MARK: - Body

    /**
     * A message's text: formatting runs, then links, then nick colouring, then the kit's deletions.
     */
    private fun body(
        message: Message,
        style: MessageTextStyle,
        fallback: Color,
        highlighter: NickHighlighter? = null,
        revealed: Set<Int> = emptySet(),
        hiddenUrls: Set<String> = emptySet(),
    ): StyledBody {
        val body = StyledBody()
        // The spans the sender coloured themselves — an explicit colour wins over nick colouring, so
        // these are off-limits to the mention pass below.
        val mircColored = mutableListOf<TextRange>()
        // Spoilers are off-limits to BOTH later passes, revealed or not.
        val spoilered = mutableListOf<TextRange>()
        // -1 so the first box becomes 0; see the coalescing note where it's bumped.
        var spoilerOrdinal = -1
        var previousRunWasSpoiler = false
        var previousSpoilerColor: IRCColor? = null
        // Reversed runs the nick pass may still colour, with the ink their text is drawn in.
        val reversed = mutableListOf<Pair<TextRange, Color>>()
        for (run in IRCFormatting.parse(message.text ?: "")) {
            val explicitFg = run.fg?.let { ircColor(it, style) }
            val explicitBg = run.bg?.let { ircColor(it, style) }
            // `explicitFg != null` as well, so the reveal below can't trip on a slot the palette
            // can't paint.
            val isSpoiler = run.hidesText && explicitFg != null
            // Always an explicit colour, and reverse resolved here — see `FormattingRun.paint`.
            val paint = run.paint(fg = explicitFg, bg = explicitBg, text = fallback, canvas = style.colors.bg)
            var foreground = paint.ink
            var background = paint.fill
            val decorations = listOfNotNull(
                if (run.underline) TextDecoration.Underline else null,
                if (run.strike) TextDecoration.LineThrough else null,
            )
            var hiddenAnnouncement: String? = null
            var spoilerTarget: Int? = null

            // A run whose foreground and background match is the IRC spoiler convention: text the
            // sender deliberately made invisible. Hidden, it already renders as a solid box because
            // that is literally what was sent. Revealed, the text takes the ordinary message colour
            // over a faint wash of the sender's — the web's treatment, because the two commonest
            // spoiler colours are black and white, and either as *text* on a tint of itself is
            // unreadable in the scheme that matches it.
            //
            // ⚠ `hidesText`, not just `fg == bg`: slots 16–98 and mIRC's 99 paint nothing, and
            // `\x0399,99text\x03` would otherwise announce "hidden spoiler" over text drawn in the
            // clear, with a tap that reveals nothing.
            if (isSpoiler) {
                // ⚠ `id` is 0 for every ephemeral event, so a reveal keyed by it would be shared by
                // all of them in a buffer. An id-less line gets no tap target: still hidden, still
                // kept out of the spoken label, just not openable.
                val revealable = message.id != 0L
                // ⚠ Ordinals count spoiler BOXES, not formatting runs. A bold inside a spoiler splits
                // it into several runs, and numbering those would make one box take three taps to
                // open. Consecutive spoiler runs sharing a colour are one box and one ordinal.
                if (!previousRunWasSpoiler || previousSpoilerColor != run.fg) spoilerOrdinal += 1
                val ordinal = spoilerOrdinal
                if (revealable) spoilerTarget = ordinal
                if (revealable && ordinal in revealed) {
                    foreground = fallback
                    background = explicitFg!!.copy(alpha = 0.22f)
                } else {
                    hiddenAnnouncement = if (revealable) HIDDEN_SPOILER_REVEALABLE else HIDDEN_SPOILER
                }
            }
            previousRunWasSpoiler = isSpoiler
            previousSpoilerColor = if (isSpoiler) run.fg else null

            val range = body.append(
                run.text,
                SpanStyle(
                    color = foreground,
                    background = background ?: Color.Unspecified,
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                ),
            )
            if (background != null || run.underline || run.strike) body.ink(range)
            spoilerTarget?.let { body.spoiler(range, it) }
            hiddenAnnouncement?.let { body.hidden(range, it) }
            if (explicitFg != null) mircColored.add(range)
            if (isSpoiler) spoilered.add(range)
            if (run.reverse && !isSpoiler && explicitFg == null) reversed.add(range to foreground)
        }

        // Auto-link URLs over the ASSEMBLED text (control codes already stripped) — never per run,
        // or a code inside a URL splits it (see `PreviewText`).
        val text = body.string
        val links = mutableListOf<TextRange>()
        for (match in URLMatcher.matches(text)) {
            // ⚠ Never inside a spoiler, and not only while it's hidden. A link there would be
            // tappable through the box — opening the secret without revealing it — and its styling
            // would draw the shape of hidden text. The web skips URL and nick splitting inside a
            // spoiler for exactly this; a URL in a revealed spoiler is readable, not tappable.
            if (spoilered.any { intersects(it, match.range) }) continue
            body.link(match.range, match.href)
            links.add(match.range)
        }

        // Colour known nicks named in the body — but never over a span the sender coloured, inside a
        // link, or inside a spoiler (a nick coloured in a hidden box would be a visible word in a box
        // of invisible ones: the shape of the secret, and possibly the secret).
        if (highlighter != null && !highlighter.isEmpty) {
            for (range in highlighter.matches(text)) {
                val taken = mircColored.any { intersects(it, range) } ||
                    links.any { intersects(it, range) } ||
                    spoilered.any { intersects(it, range) }
                if (taken) continue
                val color = hashedColor(text.substring(range.start, range.end), style)
                body.style(range, SpanStyle(color = color))
                // A nick's colour is its text's foreground, so under reverse it is the BLOCK the name
                // sits on, and the text keeps the ink the swap gave it. Per overlap, because a name
                // can straddle a \x16. (How a `\x0399` run still lets a nick be coloured under
                // reverse: 99 paints nothing, so `mircColored` never claimed it.)
                for ((span, ink) in reversed) {
                    val overlap = intersection(span, range) ?: continue
                    body.style(overlap, SpanStyle(color = ink, background = color))
                }
            }
        }

        // ⚠⚠ The deletions happen HERE, last, after every pass that holds ranges into the assembled
        // string — `mircColored`, `spoilered` and `links` are plain offsets and do not move when
        // characters do. The rule itself is the kit's (`PreviewText`), so it is tested where the
        // decision lives: it takes the `<…>` off a bracketed link on every message, and the
        // address a preview stands in for.
        PreviewText.stripHiddenUrls(body, hidden = hiddenUrls, spoilered = spoilered)
        return body
    }

    private fun intersects(a: TextRange, b: TextRange): Boolean = max(a.start, b.start) < min(a.end, b.end)

    private fun intersection(a: TextRange, b: TextRange): TextRange? {
        val start = max(a.start, b.start)
        val end = min(a.end, b.end)
        return if (start < end) TextRange(start, end) else null
    }
}
