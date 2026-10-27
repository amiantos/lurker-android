// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import net.amiantos.lurker.ui.media.MediaSource
import net.amiantos.lurker.ui.media.PreviewContext
import net.amiantos.lurkerkit.model.UserMetadata
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyQuote
import net.amiantos.lurkerkit.model.RunPosition
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.TimestampFormat
import net.amiantos.lurkerkit.rendering.NickHighlighter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import net.amiantos.lurkerkit.model.MemberPrefix

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
    val modePrefixes: Map<String, MemberPrefix.Mark>,
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
     * Jump to a reply's quoted line (lurker-ios#184) — the conversation's jump, which fetches the
     * line when it's outside the loaded slice. Null where the quote isn't live; a quote with no jump
     * is drawn but not tappable.
     */
    val onJumpToReply: ((ReplyQuote) -> Unit)? = null,
    /** What a line's reaction chips need (lurker-ios#183), or null on screens that don't draw them. */
    val reactions: ReactionContext? = null,
    /**
     * A long press on a row (lurker-ios#60): on its line, a link in it, or its chips — see [RowPress].
     * Null on screens with no actions sheet; the rows then take no long press at all.
     */
    val onLongPress: ((RowPress) -> Unit)? = null,
    /** Which time zone a header's time and a day are read in. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Today, as the day labels read it — the screen advances it at midnight. */
    val today: LocalDate = LocalDate.now(zone),
    val locale: Locale = Locale.getDefault(),
    /**
     * Link previews (iOS's `PreviewContext`), or null on the screens that don't show them — the feeds,
     * and any screen while both settings are off. Rebuilt with a new `revision` when preview state
     * moved for something on screen, which is how a resolved preview reaches a row whose message
     * didn't change.
     */
    val previews: PreviewContext? = null,
    /**
     * Present a message's media full-screen, positioned on one item — the viewer `MainScaffold`
     * hosts. Null on screens with nothing to present from, where a tap opens the address instead.
     */
    val onOpenMedia: ((List<LinkPreview>, Int) -> Unit)? = null,
    /**
     * A nick's display name on this screen's network (lurker#1203), raw with its mIRC codes, or null
     * to print the nick — `ChatState.displayName`, which applies the setting and the strip-to-empty
     * rule. The layout decides where a raw name goes (the head) and where its plain form does
     * (narration, quotes); the relay exclusion is the layout's too.
     */
    val displayName: (String) -> String? = { null },
    /** [displayName]'s plain form — `ChatState.plainDisplayName` — for narration and quotes. */
    val plainName: (String) -> String? = { null },
    /**
     * Something that changes exactly when [displayName] would answer differently — the metadata set
     * and the setting — for the rows' render caches to key on. The lambdas above are new on every
     * context, and a context is rebuilt on every frame that moves the rows; keyed on them, every
     * visible body would be re-parsed per incoming line.
     */
    val namesKey: Any? = null,
    /** The avatar column (lurker#607), or null while `look.message.avatars` is off. */
    val avatars: AvatarContext? = null,
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
            modePrefixes: Map<String, MemberPrefix.Mark> = emptyMap(),
            settings: Settings = Settings(),
            revealedSpoilers: (Message) -> Set<Int> = { emptySet() },
            onToggleSpoiler: (Message, Int) -> Unit = { _, _ -> },
            onJumpToReply: ((ReplyQuote) -> Unit)? = null,
            reactions: ReactionContext? = null,
            onLongPress: ((RowPress) -> Unit)? = null,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone),
            locale: Locale = Locale.getDefault(),
            previews: PreviewContext? = null,
            onOpenMedia: ((List<LinkPreview>, Int) -> Unit)? = null,
            displayName: (String) -> String? = { null },
            plainName: (String) -> String? = { null },
            namesKey: Any? = null,
            avatars: AvatarContext? = null,
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
            onLongPress = onLongPress,
            zone = zone,
            today = today,
            locale = locale,
            previews = previews,
            onOpenMedia = onOpenMedia,
            displayName = displayName,
            plainName = plainName,
            namesKey = namesKey,
            avatars = avatars,
        )
    }
}

/**
 * What the rows need to draw avatar tiles (lurker#607): how big, where the pictures come from, what a
 * message's tile is, and where a tap on one goes. lurker-ios's `AvatarContext`.
 */
class AvatarContext(
    val size: Dp,
    /** Ungated on the instance's previews flag: the bundled and uploaded tiles exist without them. */
    val media: MediaSource,
    val spec: (Message) -> AvatarSpec,
    val onTap: (Message) -> Unit,
)

/**
 * What a row needs to draw reaction chips: the groups standing on a line, whether tapping each would
 * do anything now, and where a tap goes. Resolvers rather than values, so a rebuild reads the store
 * once per drawn row and not per loaded one. lurker-ios's `ReactionContext`.
 */
class ReactionContext(
    val groups: (Message) -> List<ReactionGroup>,
    /**
     * Whether tapping each of a line's chips can go out now — ours takes it back, anyone else's adds
     * ours, and a network can allow one and not the other (`Reactions.canToggle`, lurker#1101). Asked
     * once per row, which resolves what the network takes once for its chips.
     */
    val canToggle: (Message) -> (ReactionGroup) -> Boolean,
    /**
     * Whether the line could ever take a reaction from here — a notice or an encrypted line shows
     * its chips but offers no add chip.
     */
    val showsAdd: (Message) -> Boolean,
    val onToggle: (Message, String) -> Unit,
    /**
     * The reaction sheet — who gave what, and a way to add yours: the add chip, and a chip on a line
     * a reaction can't go out on right now (a tap there can't toggle, so it shows instead).
     */
    val onOpen: (Message) -> Unit,
)

/** What to draw above a body, when the row starts a new author block. lurker-ios's `CompactCell.Header`. */
data class CompactHeader(
    val nick: String,
    val color: Color,
    /** Null only for a line with no clock. */
    val time: String?,
    /**
     * The speaker's channel-mode glyph and its colour tier, when [nick] opens with the glyph. Carried
     * separately from the name it's already part of, because it doesn't wear the name's colour (see
     * `MessageText.headerName`), and as one value so the glyph and its colour can't disagree. Null for
     * every header that isn't a channel member's.
     */
    val modeMark: MemberPrefix.Mark? = null,
    /**
     * Where a re-attributed relay line was bridged from (#277) — "Discord", "github". It rides the
     * header because it qualifies the *name*, and so is drawn once per author block.
     */
    val relaySource: String? = null,
    /**
     * The display name to draw in place of the nick (lurker#1203), raw with its mIRC codes, or null to
     * draw [nick]. The layout has already applied the setting, the strip-to-empty rule and the relay
     * exclusion; this only paints. The colour stays [color], the nick's.
     */
    val displayName: String? = null,
) {
    /**
     * The name as TalkBack hears it: the shown name where there is one, with the rank glyph the nick
     * carries. Relay provenance needs a connective it doesn't need in print, where colour separates
     * the two words: read out bare, "alice github" is two names.
     */
    val spokenNick: String
        get() {
            val said = UserMetadata.shown(displayName)?.let { (modeMark?.glyph ?: "") + it } ?: nick
            return relaySource?.takeIf { it.isNotEmpty() }?.let { "$said from $it" } ?: said
        }
}

/**
 * A reply's quote line: the line it answers (null for "unavailable"), where a tap on it goes, and the
 * quoted nick's shown name (lurker#1203) — null for nicks.
 */
data class ReplyLine(val quote: ReplyQuote?, val onJump: ((ReplyQuote) -> Unit)?, val names: ((String) -> String?)? = null)

/** The chips under a line, each with whether a tap on it can send. */
data class ReactionChips(val chips: List<Chip>, val showsAdd: Boolean) {
    /** One standing reaction, and whether tapping it can go out right now (else it opens the sheet). */
    data class Chip(val group: ReactionGroup, val canToggle: Boolean)
}

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
        /**
         * The time after the body's text, for a row with no header to carry it — a `/me`, a join, a
         * collapsed run, a line with nothing to call it (lurker#1237). Null on a headed row, a run's
         * continuation, and wherever the format hides the time.
         */
        val trailingTime: String? = null,
        /**
         * The tile a run's first row draws (lurker#607), null on a continuation or a narration line —
         * which still take the gutter while the column is on, so bodies stay on one edge.
         */
        val avatar: AvatarSpec? = null,
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
 * The shape: an author header (nick and time) and the message indented one character under it,
 * several messages stacking beneath one header. A header appears where a run starts —
 * `RunPosition.isFirst`, which `MessageRows` already computed. Anything that names its own actor — a `/me`, a join, a collapsed run, the typing
 * line — is header-less and starts flush with the nicks, because it *is* that line.
 */
object MessageListLayout {

    /** How the row at [index] is drawn. */
    fun plan(row: MessageRow, index: Int, context: MessageListContext): RowPlan =
        when (row) {
            is MessageRow.Bubble -> {
                // Once, not three times: it walks the neighbouring rows and builds a caption.
                val header = header(row.message, row.position, context)
                RowPlan.Compact(
                    content = RowPlan.Content.Of(row.message),
                    header = header,
                    // A caption-less line under a marker still opens a block: the marker closed the
                    // one above it, and leaves the wash for the row below to take (lurker#1238).
                    startsBlock = header != null || isMarker(context.row(index - 1)),
                    endsBlock = endsBlock(index, context),
                    highlighted = row.message.matched,
                    reply = replyLine(row.message, row.position, index, context),
                    reactions = reactions(row.message, context),
                    // A line with nothing to call it — a nick-less error, server text before its
                    // network resolves — has no header to carry its time, at the head of its run or
                    // after it, so the time follows the text as on any header-less row.
                    trailingTime = if (header == null && MessageText.caption(row.message, context.networkName(row.message)) == null) {
                        time(row.message.date, context)
                    } else {
                        null
                    },
                    avatar = if (header != null) context.avatars?.spec?.invoke(row.message) else null,
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
                // No header to carry its time, so the time follows the text (lurker#1237).
                trailingTime = time(row.message.date, context),
            )
            is MessageRow.Consolidated -> RowPlan.Compact(
                content = RowPlan.Content.Summary(row.summary),
                header = null,
                startsBlock = startsBlock(index, context),
                endsBlock = endsBlock(index, context),
                trailingTime = time(row.summary.date, context),
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
     * A compact line's time: `look.buffer.time_format_compact`, with the date before it on a line from
     * a day other than [MessageListContext.today] — the screen's day, advanced at midnight, so a
     * redraw past midnight dates yesterday's lines. Null when the format hides it, or the line has no
     * clock.
     */
    fun time(date: Instant?, context: MessageListContext): String? =
        date?.let {
            TimestampFormat.compact(
                it,
                context.settings,
                now = context.today.atStartOfDay(context.zone).toInstant(),
                zone = context.zone,
            )
        }

    /**
     * The header for a message, or null when it continues the block above it. Every header carries
     * its time and no continuation does, as the web's compact layout has it: the run is the unit, so
     * a run that crosses a minute keeps going under its first stamp.
     */
    fun header(message: Message, position: RunPosition, context: MessageListContext): CompactHeader? {
        if (!position.isFirst) return null

        // No rank glyph on a re-attributed relay line (#277): the name belongs to someone speaking
        // through a bridge, so a hit in the nicklist would be a coincidence of spelling. The bot's
        // own rank isn't shown either: it isn't the one talking.
        val mark = if (message.relayBot == null) message.nick?.let { context.modePrefixes[it.lowercase()] } else null
        val prefix = mark?.glyph ?: ""
        // Null means there's nothing to call this line: server text whose network hasn't resolved
        // yet, most often. An empty header is a blank line with a stray timestamp beside it.
        val networkName = context.networkName(message)
        val name = MessageText.caption(message, networkName, modePrefix = prefix) ?: return null
        return CompactHeader(
            nick = name,
            color = MessageText.captionColor(message, networkName, context.style),
            time = time(message.date, context),
            // Only when `caption` actually used it: it prefixes a nick and nothing else. ⚠ Asked by
            // building the caption without it, never by `name.startsWith(prefix)`: a network's own
            // symbol can be any character (lurker-ios#191), and with PREFIX `(ov)-+` a notice's
            // `-alice-` "starts with" an op's `-`, which then wore the op colour.
            modeMark = if (prefix.isNotEmpty() && name != MessageText.caption(message, networkName, modePrefix = "")) mark else null,
            // Nil for everything but a re-attributed relay line, and nil for a bare `<nick> message`
            // relay too, whose envelope names no source — the web's call as well.
            relaySource = message.relaySource,
            // The display name (lurker#1203) for a head that names a person. Never for a re-attributed
            // relay line: the bridged speaker isn't a network nick, so a record under that spelling
            // would be someone else's. A notice keeps its `-nick-`: it's the bot's mark, not a name.
            displayName = if (isPerson(message) && message.relayBot == null) message.nick?.let(context.displayName) else null,
        )
    }

    /** What a line's head stands for — one classification for the display name and the tile's placeholder glyph. */
    enum class HeadKind {
        /** A person on the network: a display name may replace the nick, a tile may wear their avatar. */
        Person,

        /** `-nick-`: a notice, the bot talking at the room. */
        Notice,

        /** The server itself, or the app: a system line, raw server text. */
        Server,

        /** Nothing to call it. */
        None,
    }

    fun headKind(message: Message): HeadKind =
        when (message.type) {
            EventType.Notice -> HeadKind.Notice
            EventType.System, EventType.Motd, EventType.Other -> HeadKind.Server
            else -> if (message.nick != null) HeadKind.Person else HeadKind.None
        }

    /** Whether a line's head names a person: not a notice's `-mark-`, not the server, not the app. */
    fun isPerson(message: Message): Boolean = headKind(message) == HeadKind.Person

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
        return header(next.message, next.position, context) != null
    }

    /** Whether a header-less row opens a block. Only status narration ever doesn't: a run of joins is one block. */
    fun startsBlock(index: Int, context: MessageListContext): Boolean =
        !(context.isStatusRow(index) && context.isStatusRow(index - 1))

    /** Whether [row] is a marker — what closes the block above it, whatever comes next. */
    fun isMarker(row: MessageRow?): Boolean =
        when (row) {
            MessageRow.UnreadDivider, is MessageRow.DateDivider, MessageRow.StartOfHistory,
            is MessageRow.ClearedDivider, is MessageRow.AwayDivider, is MessageRow.BackDivider,
            -> true
            is MessageRow.Bubble, is MessageRow.Line, is MessageRow.Consolidated, is MessageRow.Typing, null -> false
        }

    /**
     * A reply's quote line, or null when the row isn't a reply — or is the next chunk of one already
     * quoted in the same author run (`Replies.continues`: obby and goguma tag every chunk of a long
     * reply).
     */
    fun replyLine(message: Message, position: RunPosition?, index: Int, context: MessageListContext): ReplyLine? {
        if (message.replyTo == null) return null
        if (position?.isFirst != true && Replies.continues(message, previous = previousMessage(index, context))) return null
        return ReplyLine(quote = message.replyQuote, onJump = context.onJumpToReply, names = context.plainName)
    }

    /** The chips for a line, or null when it has none (or the screen draws none). */
    fun reactions(message: Message, context: MessageListContext): ReactionChips? {
        val reactions = context.reactions ?: return null
        val groups = reactions.groups(message)
        if (groups.isEmpty()) return null
        val canToggle = reactions.canToggle(message)
        return ReactionChips(
            chips = groups.map { ReactionChips.Chip(it, canToggle = canToggle(it)) },
            showsAdd = reactions.showsAdd(message),
        )
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
