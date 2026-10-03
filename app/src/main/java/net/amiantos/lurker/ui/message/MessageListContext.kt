// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyQuote
import net.amiantos.lurkerkit.model.RunPosition
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.rendering.NickHighlighter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Everything a row needs that comes from the screen rather than from the row itself. lurker-ios's
 * `MessageListContext`.
 *
 * Passed in rather than reached for, so rows can be built without the conversation screen — the
 * highlights feed (U7) will — and so the screen's own state stays the screen's. The functions are
 * resolvers the screen already has, handed over whole rather than copied per row.
 *
 * A plain class on purpose: a screen builds a new one whenever what it draws from changes, and the
 * rows on screen recompose against it — which is how a reaction landing reaches a row whose message
 * didn't change.
 */
class MessageListContext(
    /** The colours and the face's measurements — iOS's `traits`. */
    val style: MessageTextStyle,
    /** What to call the network a nick-less line belongs to. */
    val networkName: (Message) -> String?,
    /** Colours known nicks mentioned in message bodies. */
    val highlighter: NickHighlighter,
    /** Lowercased nick → channel-mode glyph, for the author header. */
    val modePrefixes: Map<String, String>,
    val settings: Settings,
    /** Whether the row at an index is status narration, so a run of it can be spaced as one block. */
    val isStatusRow: (Int) -> Boolean,
    /**
     * The row at an index of the row stream (oldest first, as `MessageRows` builds it), or null out
     * of range — a row looks at its neighbours to decide whether it opens an author block.
     */
    val row: (Int) -> MessageRow?,
    /**
     * Which spoilers in a message the reader has opened, by their ordinal within it. Held by the
     * screen, keyed by message id: a row index shifts every time history loads above.
     */
    val revealedSpoilers: (Message) -> Set<Int>,
    /** A spoiler in a message was tapped. The screen owns the toggle. */
    val onToggleSpoiler: (Message, Int) -> Unit,
    /**
     * Jump to a reply's quoted line (lurker-ios#184). Null where the quote isn't live — and in the
     * conversation until U2b, which owns jumps; a quote with no jump is drawn but not tappable.
     */
    val onJumpToReply: ((ReplyQuote) -> Unit)? = null,
    /** What a line's reaction chips need (lurker-ios#183), or null on screens that don't draw them. */
    val reactions: ReactionContext? = null,
    /** Which time zone decides a minute and a day. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Today, as the day labels read it — the screen advances it at midnight. */
    val today: LocalDate = LocalDate.now(zone),
    val locale: Locale = Locale.getDefault(),
    // U8: link previews (iOS's `PreviewContext`) and a media viewer to open them in.
) {
    companion object {
        /**
         * A context over [rows], answering `row` and `isStatusRow` from them — what a screen and a
         * test both want, so neither spells the two resolvers out.
         */
        fun over(
            rows: List<MessageRow>,
            style: MessageTextStyle,
            networkName: (Message) -> String? = { null },
            highlighter: NickHighlighter = NickHighlighter(emptyList()),
            modePrefixes: Map<String, String> = emptyMap(),
            settings: Settings = Settings(),
            revealedSpoilers: (Message) -> Set<Int> = { emptySet() },
            onToggleSpoiler: (Message, Int) -> Unit = { _, _ -> },
            onJumpToReply: ((ReplyQuote) -> Unit)? = null,
            reactions: ReactionContext? = null,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone),
            locale: Locale = Locale.getDefault(),
        ): MessageListContext = MessageListContext(
            style = style,
            networkName = networkName,
            highlighter = highlighter,
            modePrefixes = modePrefixes,
            settings = settings,
            isStatusRow = { index -> rows.getOrNull(index)?.isStatus == true },
            row = { index -> rows.getOrNull(index) },
            revealedSpoilers = revealedSpoilers,
            onToggleSpoiler = onToggleSpoiler,
            onJumpToReply = onJumpToReply,
            reactions = reactions,
            zone = zone,
            today = today,
            locale = locale,
        )
    }
}

/**
 * What a row needs to draw reaction chips: the groups standing on a line, whether a reaction can go
 * out on it now, and where a tap goes. Resolvers rather than values, so a rebuild reads the store
 * once per drawn row and not per loaded one. lurker-ios's `ReactionContext`.
 */
class ReactionContext(
    val groups: (Message) -> List<ReactionGroup>,
    val canToggle: (Message) -> Boolean,
    /**
     * Whether the line could ever take a reaction from here — a notice or an encrypted line shows
     * its chips but offers no add chip. U6: read by the add chip, which arrives with the picker.
     */
    val showsAdd: (Message) -> Boolean,
    val onToggle: (Message, String) -> Unit,
    /** U6: the reaction sheet — who gave what, and a way to add yours. */
    val onOpen: (Message) -> Unit,
)

/** What to draw above a body, when the row starts a new author/minute block. lurker-ios's `CompactCell.Header`. */
data class CompactHeader(
    val nick: String,
    val color: Color,
    /** Null unless the minute changed — the whole point of the format. */
    val time: String?,
    /**
     * The speaker's channel-mode glyph, when [nick] opens with one. Carried separately from the name
     * it's already part of, because it doesn't wear the name's colour (see `MessageText.headerName`).
     * Empty for every header that isn't a channel member's.
     */
    val modePrefix: String = "",
    /**
     * Where a re-attributed relay line was bridged from (#277) — "Discord", "github". It rides the
     * header because it qualifies the *name*, and so is drawn once per author block.
     */
    val relaySource: String? = null,
) {
    /**
     * The name as TalkBack hears it. Relay provenance needs a connective it doesn't need in print,
     * where colour separates the two words: read out bare, "alice github" is two names.
     */
    val spokenNick: String
        get() = relaySource?.takeIf { it.isNotEmpty() }?.let { "$nick from $it" } ?: nick
}

/** A reply's quote line: the line it answers (null for "unavailable"), and where a tap on it goes. */
data class ReplyLine(val quote: ReplyQuote?, val onJump: ((ReplyQuote) -> Unit)?)

/** The chips under a line, and whether a tap can send. */
data class ReactionChips(val groups: List<ReactionGroup>, val canToggle: Boolean, val showsAdd: Boolean)

/**
 * How one row of the stream is drawn, decided without drawing it — the half of lurker-ios's
 * `MessageListRenderer` that isn't cell plumbing, so it can be tested.
 */
sealed interface RowPlan {
    /**
     * A centred marker — a day change, the start of history, the `/clear` boundary, your own
     * away/back, the unread divider. Its own shape rather than a message's: a break in the flow that
     * names itself shares none of a message's layout.
     */
    data class Marker(val text: String, val tone: MarkerTone) : RowPlan

    /**
     * A compact row: an optional author header, then a body indented under it. `startsBlock` and
     * `endsBlock` mark the ends of an author block — the gap after its last row, and the share of
     * that gap a matched block's wash keeps inside itself.
     */
    data class Compact(
        val content: Content,
        val header: CompactHeader?,
        val startsBlock: Boolean,
        val endsBlock: Boolean,
        /** The matched-rule wash, full-bleed. */
        val highlighted: Boolean = false,
        val reply: ReplyLine? = null,
        /** False for a header-less narration line (a `/me`), whose quote starts flush as the line does. */
        val indentsBody: Boolean = true,
        val reactions: ReactionChips? = null,
    ) : RowPlan

    /** What a compact row's body is made from. */
    sealed interface Content {
        data class Of(val message: Message) : Content

        data class Summary(val summary: ConsolidationSummary) : Content

        data class Typists(val nicks: List<String>) : Content
    }
}

/**
 * A marker's weight. Only the unread divider is loud: it's the one the reader is *looking for*, and a
 * second red row would cost it its meaning.
 */
enum class MarkerTone { Unread, Muted, Faint }

/**
 * The decisions behind each row: who heads a block, where a block ends, whether a reply is quoted
 * again. lurker-ios's `MessageListRenderer`, minus the cells.
 *
 * The shape: an author header (nick, plus the time when the minute changed) and the message indented
 * one character under it, several messages stacking beneath one header. A header appears on an
 * author change — `RunPosition.isFirst`, which `MessageRows` already computed — **or** on a minute
 * change, because the stamp needs a header to sit on and a run crossing a minute boundary would
 * otherwise lose it. Anything that names its own actor — a `/me`, a join, a collapsed run, the typing
 * line — is header-less and starts flush with the nicks, because it *is* that line.
 */
object MessageListLayout {

    /** How the row at [index] is drawn. */
    fun plan(row: MessageRow, index: Int, context: MessageListContext): RowPlan =
        when (row) {
            is MessageRow.Bubble -> {
                // Once, not three times: it walks the neighbouring rows and builds a caption.
                val header = header(row.message, row.position, index, context)
                RowPlan.Compact(
                    content = RowPlan.Content.Of(row.message),
                    header = header,
                    startsBlock = header != null,
                    endsBlock = endsBlock(index, context),
                    highlighted = row.message.matched,
                    reply = replyLine(row.message, row.position, index, context),
                    reactions = reactions(row.message, context),
                )
            }
            is MessageRow.Line -> RowPlan.Compact(
                content = RowPlan.Content.Of(row.message),
                header = null,
                startsBlock = startsBlock(index, context),
                endsBlock = endsBlock(index, context),
                highlighted = row.message.matched,
                reply = replyLine(row.message, null, index, context),
                // A header-less narration line (a `/me`) starts flush with the nicks, so its quote
                // does too.
                indentsBody = false,
                reactions = reactions(row.message, context),
            )
            is MessageRow.Consolidated -> RowPlan.Compact(
                content = RowPlan.Content.Summary(row.summary),
                header = null,
                startsBlock = startsBlock(index, context),
                endsBlock = endsBlock(index, context),
            )
            is MessageRow.Typing -> RowPlan.Compact(
                content = RowPlan.Content.Typists(row.nicks),
                header = null,
                startsBlock = startsBlock(index, context),
                endsBlock = endsBlock(index, context),
            )
            MessageRow.UnreadDivider -> RowPlan.Marker("New messages", MarkerTone.Unread)
            is MessageRow.DateDivider ->
                RowPlan.Marker(MessageText.dayLabel(row.day, context.today, context.zone, context.locale), MarkerTone.Muted)
            MessageRow.StartOfHistory -> RowPlan.Marker("— start of history —", MarkerTone.Faint)
            // The same muted grey as the date and presence markers, as the web draws it: a marker
            // among markers, not a control — the way back is `/clear off`, which the label names.
            is MessageRow.ClearedDivider ->
                RowPlan.Marker(MessageText.clearedLabel(row.at, context.zone, context.locale), MarkerTone.Muted)
            is MessageRow.AwayDivider -> RowPlan.Marker(MessageText.awayLabel(row.awayMessage), MarkerTone.Muted)
            is MessageRow.BackDivider -> RowPlan.Marker(MessageText.backLabel(row.awayAt, row.at), MarkerTone.Muted)
        }

    /**
     * The header for a message, or null when it continues the block above it. The time is carried
     * only when the minute differs from the previous message's — that's the format's whole idea, and
     * why a minute change forces a header even mid-run.
     */
    fun header(message: Message, position: RunPosition, index: Int, context: MessageListContext): CompactHeader? {
        val previous = previousMessage(index, context)
        val minuteChanged = changedMinute(message.date, previous?.date, context.zone)
        if (!position.isFirst && !minuteChanged) return null

        // No rank glyph on a re-attributed relay line (#277): the name belongs to someone speaking
        // through a bridge, so a hit in the nicklist would be a coincidence of spelling. The bot's
        // own rank isn't shown either: it isn't the one talking.
        val prefix = if (message.relayBot == null) {
            message.nick?.let { context.modePrefixes[it.lowercase()] } ?: ""
        } else {
            ""
        }
        // Null means there's nothing to call this line: server text whose network hasn't resolved
        // yet, most often. An empty header is a blank line with a stray timestamp beside it.
        val networkName = context.networkName(message)
        val name = MessageText.caption(message, networkName, modePrefix = prefix) ?: return null
        return CompactHeader(
            nick = name,
            color = MessageText.captionColor(message, networkName, context.style),
            time = if (minuteChanged) message.date?.let { MessageText.compactHeaderTime(it, context.zone) } else null,
            // Only when `caption` actually used it: it prefixes a nick and nothing else.
            modePrefix = if (name.startsWith(prefix)) prefix else "",
            // Nil for everything but a re-attributed relay line, and nil for a bare `<nick> message`
            // relay too, whose envelope names no source — the web's call as well.
            relaySource = message.relaySource,
        )
    }

    /**
     * Whether the row at [index] is the last of its block — whatever follows starts a new one, or
     * there's nothing after it. Asked of the *next* row rather than tracked as state, because a lazy
     * list composes rows in whatever order it likes.
     */
    fun endsBlock(index: Int, context: MessageListContext): Boolean {
        val next = context.row(index + 1) ?: return true
        // A run of status narration is one block. Without this a netsplit with consolidation off puts
        // three quarters of a line between every join.
        if (context.isStatusRow(index) && context.isStatusRow(index + 1)) return false
        // A divider, a `/me`, the typing line: each stands alone, so the row above it ends whatever
        // it was part of.
        if (next !is MessageRow.Bubble) return true
        return header(next.message, next.position, index + 1, context) != null
    }

    /** Whether a header-less row opens a block. Only status narration ever doesn't: a run of joins is one block. */
    fun startsBlock(index: Int, context: MessageListContext): Boolean =
        !(context.isStatusRow(index) && context.isStatusRow(index - 1))

    /**
     * A reply's quote line, or null when the row isn't a reply — or is the next chunk of one already
     * quoted in the same author run (`Replies.continues`: obby and goguma tag every chunk of a long
     * reply).
     */
    fun replyLine(message: Message, position: RunPosition?, index: Int, context: MessageListContext): ReplyLine? {
        if (message.replyTo == null) return null
        if (position?.isFirst != true && Replies.continues(message, previous = previousMessage(index, context))) return null
        return ReplyLine(quote = message.replyQuote, onJump = context.onJumpToReply)
    }

    /** The chips for a line, or null when it has none (or the screen draws none). */
    fun reactions(message: Message, context: MessageListContext): ReactionChips? {
        val reactions = context.reactions ?: return null
        val groups = reactions.groups(message)
        if (groups.isEmpty()) return null
        return ReactionChips(groups = groups, canToggle = reactions.canToggle(message), showsAdd = reactions.showsAdd(message))
    }

    /**
     * The nearest message above [index], skipping nothing: a divider carries none, and a divider is a
     * hard break anyway — a header under one is wanted regardless.
     */
    fun previousMessage(index: Int, context: MessageListContext): Message? {
        if (index <= 0) return null
        return context.row(index - 1)?.message
    }

    /**
     * Whether two instants fall in different minutes of the reader's clock.
     *
     * A message with no clock never counts as a change: it has no stamp to show, so breaking the block
     * for it would cost a header and gain nothing. A message *following* one with no clock does count,
     * so the first line that knows what time it is says so.
     */
    fun changedMinute(date: Instant?, previous: Instant?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (date == null || previous == null) return date != null
        val minute = date.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        val previousMinute = previous.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        return minute != previousMinute
    }

    /**
     * A stable, unique key per row, for the lazy list — so a row keeps its place (and its state) as
     * rows arrive around it.
     *
     * A message by its id; a summary by its last id, which doesn't move as older history grows the
     * run upward; a marker by what it marks. ⚠ Ephemeral lines (id 0) have no id to be keyed by, so
     * they're numbered in arrival order among themselves — stable while history prepends, since
     * history never carries one. And a key the stream somehow repeats is suffixed rather than handed
     * to the list twice, which would crash it: a rendering glitch is recoverable, a crash isn't.
     */
    fun rowKeys(rows: List<MessageRow>): List<String> {
        val seen = HashMap<String, Int>()
        var ephemeral = 0
        return rows.map { row ->
            val base = when (row) {
                is MessageRow.Bubble, is MessageRow.Line -> {
                    val id = row.message?.id ?: 0L
                    if (id > 0) "m$id" else "e${ephemeral++}"
                }
                is MessageRow.Consolidated ->
                    if (row.summary.lastId > 0) "c${row.summary.lastId}" else "e${ephemeral++}"
                MessageRow.UnreadDivider -> "unread"
                is MessageRow.DateDivider -> "d${row.day.epochSecond}"
                MessageRow.StartOfHistory -> "start"
                is MessageRow.ClearedDivider -> "cleared"
                is MessageRow.AwayDivider -> "away"
                is MessageRow.BackDivider -> "back"
                is MessageRow.Typing -> "typing"
            }
            val count = seen.getOrDefault(base, 0)
            seen[base] = count + 1
            if (count == 0) base else "$base#$count"
        }
    }

    /** The lazy list's content type for a row, so it reuses like with like. */
    fun contentType(row: MessageRow): String =
        when (row) {
            is MessageRow.Bubble, is MessageRow.Line -> "message"
            is MessageRow.Consolidated -> "summary"
            is MessageRow.Typing -> "typing"
            MessageRow.UnreadDivider, is MessageRow.DateDivider, MessageRow.StartOfHistory,
            is MessageRow.ClearedDivider, is MessageRow.AwayDivider, is MessageRow.BackDivider,
            -> "marker"
        }

    /**
     * The whole row as TalkBack hears it: the name, the line, the time — iOS's label order. Empties
     * dropped as well as nulls, so a header with a time and no nick doesn't open with a comma.
     *
     * [spokenBody] is `MessageText.spokenAnnotated`'s, and its links come through at their new
     * offsets: this is the row's semantic text, so a link stays reachable from TalkBack's links menu.
     */
    fun spokenRow(header: CompactHeader?, spokenBody: AnnotatedString): AnnotatedString {
        val parts = listOfNotNull(
            header?.spokenNick?.let(::AnnotatedString),
            spokenBody,
            header?.time?.let(::AnnotatedString),
        ).filter { it.isNotEmpty() }
        return buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append(", ")
                append(part)
            }
        }
    }
}
