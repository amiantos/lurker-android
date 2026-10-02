// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.startOfDay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * One rendered row of a message list.
 *
 * A message is either dialogue (a bubble, carrying where it sits in its run) or narration
 * (a full-width line) — see `EventType.isBubble`. A run of consecutive membership churn
 * collapses into a single `Consolidated` summary. The rest are markers: breaks in the flow
 * that name themselves.
 *
 * Port note: the cases' unlabelled payloads are named here — `Bubble(message, position)`,
 * `Line(message)`, `Consolidated(summary)`, `DateDivider(day)`, `Typing(nicks)`. ⚠ And one
 * label is renamed: LurkerKit's `awayDivider(at:message:)` is `AwayDivider(at, awayMessage)`,
 * because every row answers `message` with the `Message` it renders (below), and a Kotlin
 * class cannot also hold a `message` that is the away reason.
 */
sealed interface MessageRow {
    data class Bubble(override val message: Message, val position: RunPosition) : MessageRow

    data class Line(override val message: Message) : MessageRow

    data class Consolidated(val summary: ConsolidationSummary) : MessageRow

    /** The read boundary — "New messages". */
    data object UnreadDivider : MessageRow

    /**
     * A day change, carrying that day's local midnight.
     *
     * An absolute instant, not a formatted string: the label is produced at render time, so a
     * redraw is enough to correct it when the day rolls over or the locale changes, with no
     * rebuild of the row stream. Invalidating that redraw is the renderer's job, not this
     * type's — see iOS's `ChatViewController.observeDateLabelInvalidation`.
     */
    data class DateDivider(val day: Instant) : MessageRow

    /**
     * "You've reached the beginning", once the buffer has no older history left. The honest
     * counterpart to a loading placeholder: "nothing more" vs "still fetching".
     */
    data object StartOfHistory : MessageRow

    /**
     * The `/clear` boundary (lurker-ios#121) — everything above it is hidden, and this row is
     * the way back. Carries the instant the clear was issued, for the same reason `AwayDivider`
     * does.
     *
     * Drawn even when it is the ONLY row. Clearing a buffer you then can't un-clear without
     * typing `/clear off` blind is the one outcome this feature must not have, so an empty
     * visible region still gets the divider.
     */
    data class ClearedDivider(val at: Instant) : MessageRow

    /**
     * Where your own `/away` falls in this buffer (lurker-ios#68) — the point past which the
     * conversation carried on without you. Carries the away reason when one was given.
     *
     * The instant rides the row for the same reason `DateDivider` carries an `Instant` rather
     * than a string: what the label says is a render-time decision, and a row that had to be
     * paired back up with store state to be drawn would be a row a second message-list style
     * couldn't render on its own.
     */
    data class AwayDivider(val at: Instant, val awayMessage: String?) : MessageRow

    /**
     * Where your own `/back` falls. Carries the away instant too, so the row can say how
     * long you were gone without the renderer having to hold the away state as well.
     */
    data class BackDivider(val awayAt: Instant, val at: Instant) : MessageRow

    /**
     * The live composing line at the foot of the buffer (lurker-ios#61) — a keyboard glyph and
     * the nicks, rendered on iOS by `MessageRenderer.renderTyping`. Not a message: it has no id,
     * never anchors a scroll, and disappears without leaving a gap in the record.
     */
    data class Typing(val nicks: List<String>) : MessageRow

    /**
     * A stable message id to anchor the viewport by, or null for a row that has none.
     *
     * A summary anchors on its *last* event, because a run only ever grows upward as older
     * history loads — so its bottom id doesn't move. Ephemeral lines (id 0) can't be
     * re-found after a reload, so they don't anchor, and neither do the markers: there's
     * nothing to re-find, and anchoring to a row that can vanish on a timer would drop the
     * reader when it does. The start-of-history marker in particular sits exactly where a
     * prepend lands, so anchoring to it would pin the viewport to the row a prepend displaces.
     */
    val anchorId: Long?
        get() {
            val id: Long = when (this) {
                is Bubble -> message.id
                is Line -> message.id
                is Consolidated -> summary.lastId
                UnreadDivider, is DateDivider, StartOfHistory, is ClearedDivider, is AwayDivider, is BackDivider,
                is Typing -> return null
            }
            return if (id > 0) id else null
        }

    /**
     * The single message this row renders, or null for a row that renders something else.
     *
     * A consolidated summary is deliberately null: it stands for a *run* of events, so there is no
     * one message to act on — and the actions a message offers (lurker-ios#60) are all singular.
     *
     * Port note: `Bubble` and `Line` override this with the `Message` they carry, so the first
     * two branches below are what those overrides answer rather than code that runs.
     */
    val message: Message?
        get() = when (this) {
            is Bubble -> message
            is Line -> message
            is Consolidated, UnreadDivider, is DateDivider, StartOfHistory, is ClearedDivider, is AwayDivider,
            is BackDivider, is Typing -> null
        }

    /**
     * Whether this row is status narration — a consolidated summary or a standalone activity
     * line (a join, mode, topic, …), but *not* a `/me` action, which is conversation and so
     * breaks a status block rather than joining it.
     *
     * Drives the block spacing that sets a cluster of status lines apart from the chat around
     * it. A marker is a hard break, so a status block never runs through one.
     */
    val isStatus: Boolean
        get() = when (this) {
            is Consolidated -> true
            is Line -> message.type.isActivity
            is Bubble, UnreadDivider, is DateDivider, StartOfHistory, is ClearedDivider, is AwayDivider,
            is BackDivider, is Typing -> false
        }

    /**
     * Whether this row stands in for message `id` — its own row, or the summary whose span
     * covers it after a history page merged it into a consolidated run.
     */
    fun represents(id: Long): Boolean =
        when (this) {
            is Bubble -> message.id == id
            is Line -> message.id == id
            is Consolidated -> summary.firstId <= id && id <= summary.lastId
            UnreadDivider, is DateDivider, StartOfHistory, is ClearedDivider, is AwayDivider, is BackDivider,
            is Typing -> false
        }
}

/**
 * Turns a buffer's filtered messages into the row stream a message list renders.
 *
 * Lives here rather than in the UI because it is the layout-independent half of
 * the list: the same rows feed whatever cell styles render them, so a second style inherits
 * the dividers, the consolidation and the run positions rather than rebuilding them.
 */
object MessageRows {

    /**
     * How long after `/back` the away/back pair keeps being drawn. Matches the web's
     * `PRESENCE_MARKER_TTL_MS`.
     */
    val presenceMarkerTTL: Duration = Duration.ofSeconds(30 * 60)

    /**
     * Whether `date` falls after `instant` — the anchoring test both presence markers use.
     * A message with no clock is never "after" anything: there is no instant to compare, and
     * treating it as epoch (which the web's `Date.parse(…) || 0` effectively does) would put
     * a marker above a line that could as easily belong below it.
     */
    private fun crosses(date: Instant?, instant: Instant?): Boolean {
        if (date == null || instant == null) return false
        return date > instant
    }

    /**
     * Build the row stream.
     *
     * **Every divider is a hard break for both passes.** Consolidation must not span one (a
     * run half-read and half-new would hide the new arrivals inside a summary; one spanning
     * midnight would sit under a date that's wrong for half of it), and neither may a bubble
     * run — tightened corners across a divider would knit together the very messages it
     * separates. So rather than special-casing each divider, the list is walked once and cut
     * into segments at every break; consolidation runs per segment, and the run pass breaks
     * on any non-bubble neighbour, which a divider row is. Adding a divider later (the
     * `/clear` marker, the away/back markers) is another cut, not another special case.
     *
     * Port note: `dividerAfterId` and `clearedBeforeId` are message ids, so they are `Long`
     * here. LurkerKit's `calendar` is `zone`, a `ZoneId`, as in `HighlightGrouping.group`: the
     * calendar is asked only where a day starts, and its time zone settles that.
     *
     * Port note: the presence lease is an exact `Duration` comparison where LurkerKit compares
     * `Double`s of seconds — see the note on `SmartFilter`, which holds here too.
     *
     * @param messages this buffer's messages, already filtered to what it renders, in order.
     * @param dividerAfterId the latched read boundary, or null if the server hasn't said yet.
     *   The unread divider only shows when there was a real read point (`> 0`) *and*
     *   something sits past it — a brand-new buffer with nothing previously read shows none.
     * @param hasMoreOlder whether the server has older history left. Pass `true` when unknown:
     *   "no more history" has to be something the server told us, not the absence of an
     *   answer, or an unhydrated buffer claims to have reached its beginning.
     * @param hasMoreNewer whether the loaded slice sits *below* the live tail — the buffer is
     *   detached (lurker-ios#42), showing an `around` window around some older message. The
     *   presence markers' foot fallback reads it to suppress itself (see there), and it
     *   suppresses the `/clear` filter outright (see `clearedBeforeId`).
     * @param clearedBeforeId the `/clear` boundary (lurker-ios#121); every message at or below
     *   it is hidden. 0 for a buffer that has never been cleared.
     * @param showsClearedHistory the reader is deliberately looking at what the marker hides —
     *   a jump landed on a row below the boundary (lurker-ios#121). Suppresses the filter exactly
     *   like detachment does, and is a SEPARATE flag for a reason: `hasMoreNewer` drives paging,
     *   and claiming it on a buffer holding the tail makes the near-bottom `loadNewer` fire
     *   and re-hide everything a frame later.
     *
     *   Passed in rather than read off the buffer because it belongs to the SCREEN, not the
     *   account: a fresh one is built per open, so the reveal retires itself and a buffer
     *   reopened later is cleared again. See iOS's `ChatViewController.showsClearedHistory`.
     *
     *   ⚠⚠ Suppressed entirely while DETACHED. A jump to a search hit or a highlight shows
     *   context around its anchor regardless of the marker — the user asked to see that
     *   message, and answering the tap with an empty screen because it predates a clear
     *   would be obeying the wrong instruction. Same rule as the web
     *   (`MessageList.vue:1064`), and the reason the filter lives here rather than in the
     *   store: the messages are still held, they are just not drawn.
     * @param clearedAt when the clear was issued, for the divider's label. The divider is drawn
     *   iff this is non-null, so a boundary with no instant hides rows and says nothing —
     *   which is why `Buffer.applyCleared` moves the two together.
     * @param typists who is composing right now, for the foot of the list.
     * @param settings the user's settings, for the event tier and the two consolidation keys.
     * @param speakers who has spoken in this buffer and when. Feeds both the `Smart` tier and
     *   consolidation's truncated name lists — see `SpeakerMap`.
     * @param ownNick your nick on this buffer's network, so the `Smart` tier never hides your
     *   own churn. Null where there is no network to have a nick on (the system buffer).
     * @param away your own away state for this buffer's network, or null for a buffer that
     *   doesn't take presence markers (the `:server:` log, the system buffer). See
     *   `presenceMarkerTTL` for when a settled pair stops being drawn.
     * @param now the instant the TTL is judged against. Passed in rather than read, so expiry
     *   is testable at a chosen moment — the same read-time lease `typists(in:now:)` uses.
     * @param zone which time zone decides a day boundary. Injected so tests can pin a
     *   timezone; callers should take the default.
     */
    fun build(
        messages: List<Message>,
        dividerAfterId: Long?,
        hasMoreOlder: Boolean,
        hasMoreNewer: Boolean = false,
        clearedBeforeId: Long = 0,
        clearedAt: Instant? = null,
        showsClearedHistory: Boolean = false,
        typists: List<String> = emptyList(),
        settings: Settings = Settings(),
        speakers: SpeakerMap = SpeakerMap(),
        ownNick: String? = null,
        away: AwayState? = null,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MessageRow> {
        val boundary = dividerAfterId ?: 0L

        // The away/back pair anchors on message *time*, never on id: ids are insertion order,
        // so a later history prepend renumbers what sits either side of an id-anchored marker
        // and moves it somewhere it never happened.
        //
        // Both halves retire together once the user has been back a while. In a slow buffer
        // they'd otherwise sit there for days, describing an absence nobody remembers, and
        // retiring only the `back` half would leave a permanent "away" over a user who is
        // demonstrably here. Nothing forces a redraw at expiry — a marker survives in an idle
        // buffer until the next rebuild — which is acceptable for something already half an
        // hour stale, and is why this is a read-time lease rather than a timer.
        val presenceSettled = away?.backAt?.let { Duration.between(it, now) > presenceMarkerTTL } ?: false
        val awayAt = if (presenceSettled) null else away?.since
        val backAt = if (presenceSettled) null else away?.backAt

        // All server-side (lurker-ios#65), so the phone agrees with whatever the user set on the
        // web. The fallbacks match the registry's own defaults, so behavior doesn't shift under
        // the user when bootstrap lands a moment after launch.
        // The `/clear` marker, unless the buffer is detached — see the parameter's note. Both
        // halves are read through these, so a detached view can't half-apply the marker.
        //
        // ⚠⚠ And neither can a half-stated one. A boundary with no instant would hide the rows
        // and draw no divider, which is the single outcome this feature must not have: a blank
        // buffer whose only way back is a `/clear off` the reader was never told about. The
        // server's `cleared_at` is nullable and its rename/case-fold merges COALESCE the two
        // columns independently, so this is reachable from the wire, not just from a bug here.
        // Showing messages the user cleared is the safe direction to fail in; stranding them
        // behind an invisible filter is not.
        val suppressed = hasMoreNewer || showsClearedHistory
        // Both halves or neither, in BOTH directions. A boundary with no instant hides every
        // row and draws nothing to undo with; an instant with no boundary hides nothing and
        // tells the reader their buffer is cleared, offering a `/clear off` that does nothing.
        // `Buffer`'s two fields are public, so the pairing is an invariant of the writers
        // (`applyCleared`, `clearedMarker`) rather than of the type — this is the reader's own
        // check, and it is one line.
        val clearBoundary = if (suppressed || clearedAt == null) 0L else maxOf(0L, clearedBeforeId)
        val clearInstant = if (clearBoundary > 0) clearedAt else null

        val eventMode = EventFilter.mode(settings)
        // At `None` there are no event rows left to fold, so the consolidation pass is
        // skipped outright rather than run over a stream it can't match.
        val consolidateEnabled = eventMode != EventMode.None &&
            settings.bool("chat.consolidate_joins", default = true)
        val maxNames = settings.int("chat.consolidate_max_names", default = 5)

        // The event tier (lurker#666, lurker-ios#63) applies before anything else looks at the
        // stream, so dividers anchor to the first row the reader can actually see and a segment
        // can't be built out of rows that will never render. It also has to sit above
        // consolidation specifically: a filtered-out event that reached a summary would inflate
        // its counts and name people whose own lines are hidden.
        @Suppress("NAME_SHADOWING")
        val messages = EventFilter.visible(
            // The clear boundary applies FIRST, above even the event tier: a cleared message
            // is not a row that was filtered, it is a row the user has said they are done
            // with. Feeding hidden lines to the tier or to consolidation would let them
            // inflate a summary's counts and name people whose own lines are gone.
            //
            // A message with no persisted id (a locally synthesized system line) has id 0 and
            // is never hidden — it has no place in the server's ordering to be above or below
            // the boundary, so there is nothing here to judge it by. That is why the STORE
            // drops the ones a clear predates when the marker moves (`applyCleared`'s call
            // site); what survives to here genuinely did arrive after it.
            if (clearBoundary > 0) messages.filter { it.id == 0L || it.id > clearBoundary } else messages,
            settings = settings, speakers = speakers, ownNick = ownNick,
        )

        // Who to float to the front of a summary that had to truncate its name list: the
        // people you were just talking to, rather than whoever happens to sort first.
        val recentSpeakers = speakers.nicks

        val rows = mutableListOf<MessageRow>()
        fun appendSegment(slice: List<Message>) {
            if (!consolidateEnabled) {
                // Off: every event stands on its own line, exactly as it arrived.
                rows.addAll(
                    slice.map {
                        if (it.type.isBubble) MessageRow.Bubble(it, RunPosition.solo) else MessageRow.Line(it)
                    },
                )
                return
            }
            for (row in Consolidation.consolidate(
                slice, maxNames = maxNames, recentSpeakers = recentSpeakers,
            )) {
                when (row) {
                    is Consolidation.Row.Summary ->
                        rows.add(MessageRow.Consolidated(row.summary))
                    is Consolidation.Row.Passthrough -> {
                        val message = row.message
                        rows.add(
                            if (message.type.isBubble) {
                                MessageRow.Bubble(message, RunPosition.solo)
                            } else {
                                MessageRow.Line(message)
                            },
                        )
                    }
                }
            }
        }

        // Above everything, including the first date: the buffer's history is exhausted.
        // Suppressed on an empty buffer, where the empty-state placeholder says it better.
        //
        // ⚠ And suppressed while a clear is in force, where it would be a lie of a useful
        // kind: `hasMoreOlder` answers "is there more to FETCH", but what the row SAYS is
        // "there is nothing above this" — and above this there is a buffer's worth of hidden
        // conversation the divider below is offering to bring back.
        if (!hasMoreOlder && messages.isNotEmpty() && clearInstant == null) rows.add(MessageRow.StartOfHistory)

        // The clear divider tops the visible region — above the first date, so the reader sees
        // "cleared at …" before any day (the web's ordering, `MessageList.vue:1222`).
        //
        // Emitted here rather than lazily at the first surviving row because the filter above
        // has already run: what remains IS the visible set, so its top is this. That also
        // covers the case the web has to special-case at the end of its loop — a clear that
        // hid everything leaves this row alone on screen, which is the whole point. Without
        // it the buffer would go blank with no way back but typing `/clear off` blind.
        if (clearInstant != null) rows.add(MessageRow.ClearedDivider(at = clearInstant))

        var segment = mutableListOf<Message>()
        var currentDay: Instant? = null
        var unreadDividerPlaced = false
        var awayDividerPlaced = false
        var backDividerPlaced = false

        // A buffer can *open* with undated lines — `LurkerStore.appendLocal` synthesizes a
        // dateless system line for things like an unrecognized command, and in an otherwise
        // empty buffer that line is the first row. Left alone, the loop below emits nothing
        // above it and then drops a date divider *underneath* it once real traffic arrives,
        // stranding it above the day it belongs to. So a leading undated run adopts the day of
        // the first dated message, and the divider goes up before any of them.
        //
        // Nothing is invented when there's no dated message at all: a buffer of purely local
        // lines has no day to name, and guessing one would be a claim we can't support.
        if (messages.firstOrNull()?.date == null) {
            val firstDated = messages.firstOrNull { it.date != null }?.date
            if (firstDated != null) {
                val day = startOfDay(firstDated, zone)
                rows.add(MessageRow.DateDivider(day))
                currentDay = day
            }
        }
        for (message in messages) {
            // Local midnight, so the divider follows the reader's calendar rather than UTC's.
            // An undated message can't change the day and doesn't reset it — it just rides
            // whichever segment it arrived in.
            val day = message.date?.let { startOfDay(it, zone) }
            val dayChanged = day != null && day != currentDay
            val crossesReadBoundary = !unreadDividerPlaced && boundary > 0 && message.id > boundary
            // Each presence marker goes above the first line that happened *after* the
            // transition, which is the first thing you missed (away) or the first thing you
            // were back for (back). An undated line can't answer "after", so it never carries
            // one — it just rides its segment, exactly as it does for the day divider.
            val opensAway = !awayDividerPlaced && crosses(message.date, awayAt)
            val opensBack = !backDividerPlaced && crosses(message.date, backAt)

            if (dayChanged || crossesReadBoundary || opensAway || opensBack) {
                appendSegment(segment)
                segment = mutableListOf()
            }
            // Date above unread when both land on the same message, matching the web: the day
            // is context for what follows, the unread marker is the thing you're looking for.
            // The presence pair sits between them, for the same reason in both directions.
            if (dayChanged) {
                rows.add(MessageRow.DateDivider(day))
                currentDay = day
            }
            if (opensAway && awayAt != null) {
                rows.add(MessageRow.AwayDivider(at = awayAt, awayMessage = away?.message))
                awayDividerPlaced = true
            }
            if (opensBack && awayAt != null && backAt != null) {
                rows.add(MessageRow.BackDivider(awayAt = awayAt, at = backAt))
                backDividerPlaced = true
            }
            if (crossesReadBoundary) {
                rows.add(MessageRow.UnreadDivider)
                unreadDividerPlaced = true
            }
            segment.add(message)
        }
        appendSegment(segment)

        // A presence marker with nothing below it belongs at the foot of the buffer.
        //
        // This is the *common* case, not an edge: you go away, and by definition nothing has
        // been said since — so neither timestamp has a message after it to sit above, and the
        // loop above places nothing. Left to the loop alone the markers would only ever appear
        // in the minority of buffers that kept talking without you, which is to say almost
        // never at the moment you'd look for one.
        //
        // Suppressed on an empty buffer, like `StartOfHistory` above and for the same reason: a
        // lone marker over no conversation isn't a marker, and it would take the empty-state
        // placeholder down with it (iOS's `ChatViewController` reads `rows.isEmpty` for that).
        //
        // And suppressed on a *detached* buffer, where this fallback is a claim the window
        // can't support. Its meaning is "nothing has been said since" — only knowable when the
        // loaded slice reaches the tail. Jump to a search hit from last week and it would pin
        // "You went away" under a week-old message, asserting an absence that happened days
        // after anything on screen. Note this suppresses only the *fallback*: an anchored
        // marker stays, because sitting above the first message after the away instant is true
        // wherever that message is, tail or not.
        if (messages.isNotEmpty() && !hasMoreNewer) {
            if (!awayDividerPlaced && awayAt != null) {
                rows.add(MessageRow.AwayDivider(at = awayAt, awayMessage = away?.message))
            }
            if (!backDividerPlaced && awayAt != null && backAt != null) {
                rows.add(MessageRow.BackDivider(awayAt = awayAt, at = backAt))
            }
        }

        // The typing line goes last, below even the newest message — it's the only row that
        // describes the present rather than the past. Appended *after* the run pass so it
        // never participates in one: it isn't a bubble, and a run that tried to include it
        // would re-tighten its corners every time somebody started or stopped typing.
        val built = withBubbleRuns(rows).toMutableList()
        if (typists.isNotEmpty()) built.add(MessageRow.Typing(typists))
        return built
    }

    /**
     * Second pass: fill in each bubble's `RunPosition` by looking at its neighbours. Only
     * consecutive bubble rows group; a line, a summary, or a divider between two bubbles is a
     * non-bubble neighbour and so breaks the run — exactly what we want.
     */
    private fun withBubbleRuns(rows: List<MessageRow>): List<MessageRow> {
        fun bubble(index: Int): Message? {
            if (index !in rows.indices) return null
            return (rows[index] as? MessageRow.Bubble)?.message
        }
        return rows.mapIndexed { index, row ->
            if (row !is MessageRow.Bubble) return@mapIndexed row
            val message = row.message
            val isFirst = !MessageGrouping.continuesRun(message, previous = bubble(index - 1))
            val isLast = bubble(index + 1)?.let { !MessageGrouping.continuesRun(it, previous = message) } ?: true
            MessageRow.Bubble(message, RunPosition(isFirst = isFirst, isLast = isLast))
        }
    }
}
