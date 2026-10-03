// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import net.amiantos.lurker.ui.message.CompactHeader
import net.amiantos.lurker.ui.message.MessageText
import net.amiantos.lurker.ui.message.MessageTextStyle
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedReaction
import net.amiantos.lurkerkit.model.HighlightDay
import net.amiantos.lurkerkit.model.HighlightGrouping
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyQuote
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant
import java.time.ZoneId

/**
 * A channel+day run, as drawn: `Network/#channel` on the leading edge of its header, the day on the
 * trailing edge (iMessage search's per-group header), over the rows that share them. The run
 * boundaries and the day are `HighlightGrouping`'s; this carries the resolved words.
 *
 * @param offset the flat index of the run's first row, so paging can read the true position however
 *   the runs are sized.
 */
data class FeedSection(val location: String, val day: String, val offset: Int, val rows: List<FeedRow>)

/**
 * One row: a line from somewhere else, rendered in the message list's own language — the author header,
 * the indent, nicks and mIRC colours intact — so an entry reads as a slice of its conversation.
 *
 * @param header null only for a line with neither a name to show nor a time.
 * @param reply the quote a reply carries, static here (the row's tap jumps to the reply, where the
 *   quote is live again); null when the line isn't a reply.
 */
data class FeedRow(
    val item: HighlightItem,
    val header: CompactHeader?,
    val body: AnnotatedString,
    /**
     * What TalkBack reads for the body: built from the rendered line BEFORE its links were made inert,
     * because that pass rebuilds the string without its annotations — and a still-hidden spoiler's
     * substitution rides one. Read from the drawn text instead, it would announce the secret.
     */
    val spokenBody: String,
    val reply: FeedReply?,
    /** False for a `/me`, whose quote starts flush as the line does. */
    val indentsBody: Boolean,
    /** The lazy list's key — unique across the feed ([FeedModel.sections]). */
    val key: String = baseKey(item),
) {
    companion object {
        /** A line by its id; a reaction row by its reaction's too, since it shares its line's id. */
        fun baseKey(item: HighlightItem): String = "m${item.message.id}" + (item.reaction?.let { "r${it.reactionId}" } ?: "")
    }
}

/** A reply's quote line — the answered line, or null for "unavailable". */
data class FeedReply(val quote: ReplyQuote?)

/**
 * The decisions behind a feed's rows and headers — lurker-ios's `HistoryFeedViewController` table
 * half, pure, so it runs in a JVM test.
 */
object FeedModel {

    /**
     * Fold the flat, newest-first list into the channel+day runs the list draws, each row rendered.
     *
     * Read against [state] as it stands when the page lands — the network names, the relay bots, the
     * ignore rules a quote is judged by — not live: a row already drawn doesn't change under the reader.
     *
     * @param date formats a day that's neither today nor yesterday: with the year only when it isn't
     *   this one (`MMMd` / `MMMdyyyy`).
     */
    fun sections(
        items: List<HighlightItem>,
        state: ChatState,
        style: MessageTextStyle,
        now: Instant,
        zone: ZoneId,
        date: (Instant, withYear: Boolean) -> String,
    ): List<FeedSection> {
        // ⚠ A key the feed somehow repeats (two pages overlapping across a cursor) is suffixed rather
        // than handed to the list twice, which would crash it — a doubled row is recoverable, a crash isn't.
        val seen = HashMap<String, Int>()
        return HighlightGrouping.group(items, now = now, zone = zone).map { group ->
            val first = group.items[0]
            val networkName = networkName(first, state)
            FeedSection(
                location = location(networkName, state.buffer(first.bufferKey).displayName(networkName = networkName)),
                day = dayLabel(group.day, now, zone, date),
                offset = group.offset,
                rows = group.items.map { item ->
                    val base = FeedRow.baseKey(item)
                    val count = seen.getOrDefault(base, 0)
                    seen[base] = count + 1
                    row(item, state, style, zone).copy(key = if (count == 0) base else "$base#$count")
                },
            )
        }
    }

    /**
     * The rows an ignore rule doesn't hide (lurker#301). Cross-buffer, so each row is judged against its
     * own network's rules and target. Level, channel and pattern rules all apply, not just whole-identity
     * ones: a search returning exactly what you'd told the client to hide would be the one place the rule
     * didn't hold. Filtered as pages land, not live — a rule made while the list is open takes effect on
     * the next pull, when everything else about these rows is re-read too.
     */
    fun visible(items: List<HighlightItem>, ignores: IgnoreSet): List<HighlightItem> =
        items.filter { !ignores.isMessageHidden(networkId = it.networkId, message = it.message, target = it.target) }

    /**
     * The header's leading text: `Network/#channel`. A server log's display name IS its network's, so
     * the two aren't joined into "Libera/Libera" — deduped rather than branching on kind, since the kind
     * is already what produced the name.
     */
    fun location(networkName: String?, displayTarget: String): String =
        when {
            networkName == null -> displayTarget
            networkName == displayTarget -> networkName
            else -> "$networkName/$displayTarget"
        }

    /** Today / Yesterday / a short date / "Earlier" for an undated row. */
    fun dayLabel(day: HighlightDay, now: Instant, zone: ZoneId, date: (Instant, withYear: Boolean) -> String): String =
        when (day) {
            HighlightDay.Today -> "Today"
            HighlightDay.Yesterday -> "Yesterday"
            HighlightDay.Undated -> "Earlier"
            is HighlightDay.On -> date(day.date, day.date.atZone(zone).year != now.atZone(zone).year)
        }

    /**
     * The network's name for a row — the server-resolved one, falling back to the client's own roster
     * when the row didn't carry it (an older server), so a system/motd row still names its network.
     */
    fun networkName(item: HighlightItem, state: ChatState): String? =
        item.networkName ?: item.networkId?.let { state.networks[it]?.name }

    /**
     * One row, as iOS's `cellForRowAt` builds it.
     *
     * Every row is its own block — they come from different buffers and hours — so each carries a header
     * with its time, rather than the message list's "only when the minute changed", which means nothing
     * across unrelated conversations. No nick for a `/me` or an activity line, which print their actor
     * inside the sentence; the header stays, carrying the time alone, since the section header gives
     * only the day.
     *
     * No matched wash: in Activity every row matched (a monotone wall), and in Bookmarks it would claim
     * a mention that isn't what put the row there. And no live links ([inert]): a tap anywhere on the
     * row is its jump.
     *
     * A line from a marked relay bot reads as the person inside its envelope, as it does in its buffer
     * (#277) — before replies are presented, which judge the line as it reads. A reaction row is the
     * reactor's, never relayed: it heads the row, and the body says what they reacted to which line.
     */
    fun row(item: HighlightItem, state: ChatState, style: MessageTextStyle, zone: ZoneId = ZoneId.systemDefault()): FeedRow {
        val networkName = networkName(item, state)
        val reaction = item.reaction
        val line = if (reaction == null) {
            state.relayBots.reattributing(listOf(item.message), networkId = item.networkId).firstOrNull() ?: item.message
        } else {
            item.message
        }
        val name = if (line.type.isBubble) MessageText.caption(line, networkName) else null
        val time = line.date?.let { MessageText.compactHeaderTime(it, zone) }
        val shown = if (reaction == null) {
            Replies.presenting(
                listOf(line),
                networkId = item.networkId,
                target = item.target,
                ignores = state.ignores,
                relayBots = state.relayBots,
                ownNick = item.networkId?.let { state.networks[it]?.nick },
            ).firstOrNull() ?: line
        } else {
            line
        }
        val rendered = if (reaction != null) reactionBody(reaction, style) else MessageText.renderCompactBody(shown, style)
        val header = if (name == null && time == null) {
            null
        } else {
            CompactHeader(
                nick = name ?: "",
                color = MessageText.captionColor(line, networkName, style),
                time = time,
                relaySource = line.relaySource,
            )
        }
        return FeedRow(
            item = item,
            header = header,
            body = inert(rendered),
            spokenBody = MessageText.spoken(rendered),
            reply = if (shown.replyTo == null) null else FeedReply(shown.replyQuote),
            indentsBody = shown.type != EventType.Action,
        )
    }

    /**
     * `👍 on “your line”` — the value in the body's ink and the rest muted, indented like a body. The
     * web's `bob | 👍 on "…"`.
     */
    fun reactionBody(reaction: FeedReaction, style: MessageTextStyle): AnnotatedString =
        buildAnnotatedString {
            withStyle(SpanStyle(color = style.colors.fg)) { append(reaction.value) }
            val line = reaction.lineText?.let(IRCFormatting::strip) ?: ""
            withStyle(SpanStyle(color = style.colors.fgMuted)) { append(" on “$line”") }
            val indent = style.indentSp.sp
            addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent)), 0, length)
        }

    /**
     * A body with its links still drawn as links but no longer tappable — iOS's `interactive: false`, so
     * a tap on a URL reaches the row's jump instead of a browser. Spoiler boxes carry no tap target here
     * either (none was given), so they stay shut, as they are in the iOS feed.
     *
     * ⚠ Rebuilt from the spans alone, so every annotation goes — the spoken label must come from the
     * line before this ([FeedRow.spokenBody]).
     */
    fun inert(body: AnnotatedString): AnnotatedString {
        val links = body.getLinkAnnotations(0, body.length).filter { it.item is LinkAnnotation.Url }
        if (links.isEmpty()) return body
        val underline = links.map { AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.Underline), it.start, it.end) }
        return AnnotatedString(body.text, spanStyles = body.spanStyles + underline, paragraphStyles = body.paragraphStyles)
    }

    /**
     * Whether a row points into a buffer that isn't open, so jumping would be a dead end — the
     * conversation would find no row for it and bounce straight back to the list. Tested with the same
     * condition that screen uses (`rosterSettled`): while the roster is still arriving absence proves
     * nothing, so navigate and let the conversation wait, as it does for a notification tap.
     *
     * Not an edge case for search: the index covers every message the account ever received, channels
     * long since parted included.
     */
    fun pointsIntoClosedBuffer(item: HighlightItem, state: ChatState): Boolean =
        state.rosterSettled && state.buffers[item.bufferKey.id] == null

    /** What the Buffer Closed alert says — iOS's `reportClosedBuffer`. */
    fun closedBufferMessage(item: HighlightItem, state: ChatState): String {
        val name = state.buffer(item.bufferKey).displayName(networkName = networkName(item, state))
        return "$name isn't open, so this message can't be shown in context."
    }
}
