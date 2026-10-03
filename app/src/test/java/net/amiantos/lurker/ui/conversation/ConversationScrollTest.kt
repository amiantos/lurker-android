// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventFilter
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.Instant
import java.time.ZoneOffset

/**
 * The conversation's position machine — lurker-ios's `ChatViewController` landing, jump, divider,
 * banner, paging and mark-read rules, one test per rule, each named after it. Every one of these is
 * a bug iOS shipped and traced on a device; the comments in `ConversationScroll` say which.
 */
class ConversationScrollTest {

    private val channel = BufferKey(1, "#lurker")
    private val libera = Network(id = 1, name = "Libera", state = ConnectionState.Connected, nick = "me")

    private fun row(
        key: BufferKey = channel,
        hydrated: Boolean = true,
        readStateKnown: Boolean = false,
        lastReadId: Long = 0,
        hasMoreOlder: Boolean = true,
        hasMoreNewer: Boolean = false,
        clearedBeforeId: Long = 0,
        clearedAt: Instant? = null,
    ) = Buffer(
        networkId = key.networkId,
        target = key.target,
        kind = BufferKind.of(key.networkId, key.target),
        hydrated = hydrated,
        readStateKnown = readStateKnown,
        lastReadId = lastReadId,
        hasMoreOlder = hasMoreOlder,
        hasMoreNewer = hasMoreNewer,
        clearedBeforeId = clearedBeforeId,
        clearedAt = clearedAt,
        joined = true,
    )

    private fun msg(id: Long, nick: String? = "alice", type: EventType = EventType.Message, text: String? = "line $id") =
        Message(id = id, type = type, nick = nick, text = text, date = Instant.parse("2026-07-25T14:00:00Z").plusSeconds(id))

    private fun msgs(range: LongRange) = range.map { msg(it) }

    private fun state(
        buffer: Buffer? = row(),
        messages: List<Message> = emptyList(),
        key: BufferKey = channel,
        connection: SocketStatus = SocketStatus.Connected,
        burstGeneration: Int = 1,
        ignores: IgnoreSet = IgnoreSet.empty,
        settings: Settings = Settings(),
    ) = ChatState(
        connection = connection,
        snapshotSinceOpen = true,
        backlogComplete = true,
        networks = mapOf(1 to libera),
        buffers = if (buffer == null) emptyMap() else mapOf(buffer.key.id to buffer),
        messages = mapOf(key.id to messages),
        ignores = ignores,
        settings = settings,
        burstGeneration = burstGeneration,
    )

    private fun kindOf(key: BufferKey) = BufferKind.of(key.networkId, key.target)

    private fun machine(key: BufferKey = channel) = ConversationScroll(kindOf(key))

    /** A build of [state] under the machine's current options, as the screen's pipeline makes one. */
    private fun build(scroll: ConversationScroll, state: ChatState, seq: Long = 0, key: BufferKey = channel): BuiltRows =
        ConversationModel.built(
            ConversationProjector(key, kindOf(key)).project(state),
            scroll.options,
            seq = seq,
            zone = ZoneOffset.UTC,
        )

    /** Layout facts with the levers spelled out. */
    private fun facts(
        nearBottom: Boolean = true,
        pagesOlder: Boolean = false,
        pagesNewer: Boolean = false,
        dividerVisible: Boolean = false,
        dividerAbove: Boolean = false,
    ) = LayoutFacts(nearBottom, pagesOlder, pagesNewer, dividerVisible, dividerAbove)

    // MARK: - The read boundary and mark-read

    @Test
    fun `the divider latches only once the server has stated read state, never on a row or a hydrate`() {
        val scroll = machine()
        // A snapshot shell, then a `history mode:latest` reply: a row, hydrated, `lastReadId` 0 by
        // default — none of it is the server saying where the reader left off.
        scroll.onFrame(state(row(hydrated = false)), channel, 1)
        scroll.onFrame(state(row(hydrated = true)), channel, 2)
        assertNull(scroll.options.dividerAfterId)
        val step = scroll.onFrame(state(row(readStateKnown = true, lastReadId = 5)), channel, 3)
        assertTrue(step.optionsChanged)
        assertEquals(5L, scroll.options.dividerAfterId)
    }

    @Test
    fun `the divider is held for the visit, however the pointer moves after`() {
        val scroll = machine()
        scroll.onFrame(state(row(readStateKnown = true, lastReadId = 5)), channel, 1)
        val step = scroll.onFrame(state(row(readStateKnown = true, lastReadId = 9)), channel, 2)
        assertFalse(step.optionsChanged)
        assertEquals(5L, scroll.options.dividerAfterId)
    }

    @Test
    fun `mark-read is gated on the latched divider`() {
        val scroll = machine()
        scroll.onFrame(state(row(hydrated = true)), channel, 1)
        assertFalse(scroll.marksRead)
        scroll.onFrame(state(row(readStateKnown = true, lastReadId = 5)), channel, 2)
        assertTrue(scroll.marksRead)
    }

    @Test
    fun `the latch is taken on the same frame before the mark, so it never latches our own mark`() {
        val scroll = machine()
        // The frame that carries the pointer: after `onFrame` returns the screen marks read, and
        // what's latched is the pointer as the server stated it before that mark.
        scroll.onFrame(state(row(readStateKnown = true, lastReadId = 5), messages = msgs(1L..10L)), channel, 1)
        assertTrue(scroll.marksRead)
        // Our mark comes back as a `read-state` at 10; the divider doesn't move.
        scroll.onFrame(state(row(readStateKnown = true, lastReadId = 10), messages = msgs(1L..10L)), channel, 2)
        assertEquals(5L, scroll.options.dividerAfterId)
    }

    @Test
    fun `the latched divider survives the screen being recreated`() {
        val scroll = machine()
        scroll.onFrame(state(row(readStateKnown = true, lastReadId = 5)), channel, 1)
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(scroll.memory()) } }.toByteArray()
        val memory = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as ConversationScroll.Memory
        val restored = ConversationScroll(BufferKind.Channel, memory)
        // By now the pointer is our own mark; re-latching would lose the divider.
        restored.onFrame(state(row(readStateKnown = true, lastReadId = 10)), channel, 1)
        assertEquals(5L, restored.options.dividerAfterId)
        assertTrue(restored.marksRead)
    }

    // MARK: - The unread banner and dividerSeen

    /** A latched machine over a hydrated buffer whose divider sits after message 5 of 1…10. */
    private fun withDivider(hydrated: Boolean = true, key: BufferKey = channel): Pair<ConversationScroll, BuiltRows> {
        val scroll = machine(key)
        val s = state(row(key, hydrated = hydrated, readStateKnown = true, lastReadId = 5), messages = msgs(1L..10L), key = key)
        scroll.onFrame(s, key, 1)
        val built = build(scroll, s, seq = 1, key = key)
        scroll.onRows(built, wasNearBottom = true)
        return scroll to built
    }

    @Test
    fun `the unread banner shows when a first unread exists above the viewport and hasn't been seen`() {
        val (scroll, built) = withDivider()
        assertNotNull(built.firstUnreadRow)
        val step = scroll.onLayout(facts(dividerAbove = true), built, detached = false, connectionBannerShown = false)
        assertTrue(step.pills.showsUnread)
    }

    @Test
    fun `off screen is not above - a divider below the viewport raises no banner`() {
        val (scroll, built) = withDivider()
        val step = scroll.onLayout(facts(nearBottom = false, dividerAbove = false), built, detached = false, connectionBannerShown = false)
        assertFalse(step.pills.showsUnread)
    }

    @Test
    fun `the unread banner yields its slot to the connection banner`() {
        val (scroll, built) = withDivider()
        val step = scroll.onLayout(facts(dividerAbove = true), built, detached = false, connectionBannerShown = true)
        assertFalse(step.pills.showsUnread)
    }

    @Test
    fun `the unread banner stays down while a jump is pending`() {
        val (scroll, built) = withDivider()
        assertTrue(scroll.jumpToFirstUnread())
        val step = scroll.onLayout(facts(dividerAbove = true), built, detached = false, connectionBannerShown = false)
        assertFalse(step.pills.showsUnread)
    }

    @Test
    fun `seeing the divider retires the banner for good`() {
        val (scroll, built) = withDivider()
        scroll.onLayout(facts(dividerVisible = true), built, detached = false, connectionBannerShown = false)
        assertTrue(scroll.dividerSeen)
        // Read forward past it: it's above again, and the banner stays retired.
        val step = scroll.onLayout(facts(dividerAbove = true), built, detached = false, connectionBannerShown = false)
        assertFalse(step.pills.showsUnread)
    }

    @Test
    fun `dividerSeen does not latch against the pre-backlog stub`() {
        // Unhydrated: the live events that outran the backlog, all past the boundary, the divider
        // top-pinned to a stub that fits on screen — visible by arithmetic, seen by nobody.
        val (scroll, built) = withDivider(hydrated = false)
        scroll.onLayout(facts(dividerVisible = true), built, detached = false, connectionBannerShown = false)
        assertFalse(scroll.dividerSeen)
    }

    @Test
    fun `dividerSeen keys off history having landed, not hydrated - a server log latches once its row exists`() {
        val log = BufferKey(1, Buffer.serverTarget(1))
        val (scroll, built) = withDivider(hydrated = false, key = log)
        scroll.onLayout(facts(dividerVisible = true), built, detached = false, connectionBannerShown = false)
        assertTrue(scroll.dividerSeen)
    }

    @Test
    fun `dividerSeen does not latch while a jump is pending - a divider swept past wasn't seen`() {
        val (scroll, built) = withDivider()
        scroll.jumpTo(9)
        scroll.onLayout(facts(dividerVisible = true), built, detached = false, connectionBannerShown = false)
        assertFalse(scroll.dividerSeen)
    }

    @Test
    fun `no divider, no banner`() {
        val scroll = machine()
        val built = build(scroll, state(messages = msgs(1L..10L)))
        scroll.onRows(built, wasNearBottom = true)
        assertNull(built.firstUnreadRow)
        assertFalse(scroll.onLayout(facts(dividerAbove = true), built, false, false).pills.showsUnread)
    }

    // MARK: - Following the tail, and the badge

    @Test
    fun `the tail is followed from the bottom of an attached buffer`() {
        val scroll = machine()
        scroll.onRows(build(scroll, state(messages = msgs(1L..10L))), wasNearBottom = true)
        assertTrue(scroll.onRows(build(scroll, state(messages = msgs(1L..11L))), wasNearBottom = true).follow)
        assertFalse(scroll.onRows(build(scroll, state(messages = msgs(1L..12L))), wasNearBottom = false).follow)
    }

    @Test
    fun `after-paging must not cascade - a detached slice's newer page is never followed`() {
        val scroll = machine()
        val detached = row(hasMoreNewer = true)
        scroll.onRows(build(scroll, state(detached, msgs(1L..10L))), wasNearBottom = true)
        // A `loadNewer` page appends below a reader parked at the slice's bottom.
        assertFalse(scroll.onRows(build(scroll, state(detached, msgs(1L..20L))), wasNearBottom = true).follow)
        // The last page appends AND re-attaches in one build — still a page, still not followed.
        assertFalse(scroll.onRows(build(scroll, state(row(), msgs(1L..30L))), wasNearBottom = true).follow)
        // From here on the buffer is attached, and live traffic is followed again.
        assertTrue(scroll.onRows(build(scroll, state(row(), msgs(1L..31L))), wasNearBottom = true).follow)
    }

    @Test
    fun `nothing follows while a landing is pending - the landing places`() {
        val scroll = machine()
        scroll.onRows(build(scroll, state(messages = msgs(1L..10L))), wasNearBottom = true)
        scroll.jumpTo(3)
        assertFalse(scroll.onRows(build(scroll, state(messages = msgs(1L..11L))), wasNearBottom = true).follow)
    }

    @Test
    fun `the badge counts live appends while up in history, and nothing else`() {
        val scroll = machine()
        scroll.onRows(build(scroll, state(messages = msgs(5L..10L))), wasNearBottom = false)
        scroll.onRows(build(scroll, state(messages = msgs(5L..12L))), wasNearBottom = false)
        assertEquals(2, scroll.newWhileAway)
        // An older page moves the first id: pulled history, not arrivals.
        scroll.onRows(build(scroll, state(messages = msgs(1L..12L))), wasNearBottom = false)
        assertEquals(2, scroll.newWhileAway)
        // An ephemeral (id 0) is a local echo, not something missed below.
        scroll.onRows(build(scroll, state(messages = msgs(1L..12L) + msg(0, nick = null, type = EventType.Notice, text = "echo"))), wasNearBottom = false)
        assertEquals(2, scroll.newWhileAway)
        // Back at the bottom, however they got there: caught up.
        val built = build(scroll, state(messages = msgs(1L..12L)))
        scroll.onLayout(facts(nearBottom = true), built, false, false)
        assertEquals(0, scroll.newWhileAway)
    }

    @Test
    fun `the badge sits out a frame where the ignore rules changed`() {
        val scroll = machine()
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 1, mask = "spammer"))))
        val lines = msgs(1L..5L) + msg(6, nick = "spammer") + msg(7, nick = "spammer")
        scroll.onRows(build(scroll, state(messages = lines, ignores = ignores)), wasNearBottom = false)
        // `/unignore` restores 6 and 7 — a count increase nothing arrived to cause.
        scroll.onRows(build(scroll, state(messages = lines, ignores = IgnoreSet.empty)), wasNearBottom = false)
        assertEquals(0, scroll.newWhileAway)
    }

    @Test
    fun `the badge counts what the event tier will draw`() {
        val scroll = machine()
        val none = Settings(registry = emptyMap(), values = mapOf(EventFilter.modeKey to SettingValue.String("none")))
        scroll.onRows(build(scroll, state(messages = msgs(1L..5L), settings = none)), wasNearBottom = false)
        val rejoins = (6L..9L).map { msg(it, nick = "user$it", type = EventType.Join, text = null) }
        scroll.onRows(build(scroll, state(messages = msgs(1L..5L) + rejoins, settings = none)), wasNearBottom = false)
        assertEquals(0, scroll.newWhileAway)
    }

    @Test
    fun `the badge doesn't count a detached buffer's pages`() {
        val scroll = machine()
        val detached = row(hasMoreNewer = true)
        scroll.onRows(build(scroll, state(detached, msgs(1L..5L))), wasNearBottom = false)
        scroll.onRows(build(scroll, state(detached, msgs(1L..9L))), wasNearBottom = false)
        assertEquals(0, scroll.newWhileAway)
    }

    @Test
    fun `the jump-to-latest pill shows anywhere on a detached slice, even its bottom`() {
        val scroll = machine()
        val built = build(scroll, state(row(hasMoreNewer = true), msgs(1L..5L)))
        scroll.onRows(built, wasNearBottom = true)
        assertTrue(scroll.onLayout(facts(nearBottom = true), built, detached = true, connectionBannerShown = false).pills.showsLatest)
        assertFalse(scroll.onLayout(facts(nearBottom = true), built, detached = false, connectionBannerShown = false).pills.showsLatest)
        assertTrue(scroll.onLayout(facts(nearBottom = false), built, detached = false, connectionBannerShown = false).pills.showsLatest)
    }

    // MARK: - Jumps

    /**
     * Drives the machine the way the screen does: every frame through `onFrame`, strictly in order,
     * and builds stamped with the frame they're of. A live append is `list + message` — the same
     * held objects with more behind them, as the store makes it; a reply is freshly made messages.
     */
    private inner class Drive(val scroll: ConversationScroll = machine(), val key: BufferKey = channel) {
        var seq = 0L
            private set
        lateinit var last: ChatState
            private set

        fun frame(s: ChatState): ConversationScroll.FrameStep {
            seq += 1
            last = s
            return scroll.onFrame(s, key, seq)
        }

        /** A build of the last frame, taken onto the screen. */
        fun built(): BuiltRows = build(scroll, last, seq, key).also { scroll.onRows(it, wasNearBottom = true) }

        fun land(): ConversationScroll.Landing = scroll.landing(built())
    }

    @Test
    fun `a jump to a held message lands without a fetch, on the message's row`() {
        val d = Drive()
        d.frame(state(messages = msgs(1L..10L)))
        d.scroll.jumpTo(4)
        assertNull(d.scroll.requestAround(d.last, channel))
        val built = d.built()
        assertTrue(d.scroll.landing(built) is ConversationScroll.Landing.Converge)
        assertEquals(built.rowIndex(4), d.scroll.jumpTargetRow(built))
    }

    @Test
    fun `a jump to a message outside the slice fetches an around slice and waits for it`() {
        val d = Drive()
        d.frame(state(messages = msgs(100L..110L)))
        d.scroll.jumpTo(4)
        assertEquals(4L, d.scroll.requestAround(d.last, channel))
        // Asked: not again for the same burst.
        assertNull(d.frame(d.last).loadAround)
        assertEquals(ConversationScroll.Landing.Wait, d.land())
    }

    @Test
    fun `a pending jump keeps the hydrate from asking for the latest slice`() {
        val scroll = machine()
        scroll.jumpTo(4)
        assertTrue(scroll.jumpPending)
        assertNull(HydrateGate(BufferKind.Channel).check(SocketStatus.Connected, row(hydrated = false), 1, jumpPending = scroll.jumpPending))
    }

    @Test
    fun `the around request is re-armed by a drop and by a new burst`() {
        val d = Drive()
        val held = msgs(100L..110L)
        d.scroll.jumpTo(4)
        assertEquals(4L, d.frame(state(messages = held)).loadAround)
        assertNull(d.frame(state(messages = held, connection = SocketStatus.Reconnecting)).loadAround)
        assertEquals(4L, d.frame(state(messages = held)).loadAround)
        // The socket replaced under a steady Connected: the burst is the signal.
        assertEquals(4L, d.frame(state(messages = held, burstGeneration = 2)).loadAround)
    }

    @Test
    fun `the around reply is recognised by its rebuilt list, not by the buffer having messages`() {
        val d = Drive()
        // A small connect backlog is already held when the jump asks.
        val backlog = msgs(100L..104L)
        d.frame(state(messages = backlog))
        d.scroll.jumpTo(4)
        assertEquals(4L, d.frame(state(messages = backlog)).loadAround)
        // A live append keeps every held message: not the reply.
        d.frame(state(messages = backlog + msg(105)))
        assertEquals(ConversationScroll.Landing.Wait, d.land())
        // The reply rebuilds the list (anchor missing on the server, say): it has landed, and the
        // jump gives up — at the bottom, since there's something to land on.
        d.frame(state(row(hasMoreNewer = true), msgs(20L..30L)))
        assertEquals(ConversationScroll.Landing.AtTail, d.land())
        assertFalse(d.scroll.jumpPending)
    }

    @Test
    fun `an around reply that lacks the anchor but holds every id already held is still recognised`() {
        val d = Drive()
        // A small, fully loaded buffer; the quoted line has been pruned since.
        val all = msgs(1L..10L)
        val small = row(hasMoreOlder = false)
        d.frame(state(small, all))
        d.scroll.jumpTo(99)
        assertEquals(99L, d.frame(state(small, all)).loadAround)
        // The reply is the whole buffer again — every id already held, nothing replaced.
        d.frame(state(small, msgs(1L..10L)))
        assertEquals(ConversationScroll.Landing.AtTail, d.land())
        assertFalse(d.scroll.jumpPending)
    }

    @Test
    fun `a build of a frame before the reply is not taken for the reply's rows`() {
        val d = Drive()
        val backlog = msgs(100L..104L)
        d.frame(state(messages = backlog))
        d.scroll.jumpTo(4)
        d.frame(state(messages = backlog))
        val stale = build(d.scroll, d.last, d.seq)
        d.frame(state(row(hasMoreNewer = true), msgs(20L..30L)))
        // The reply has landed, but these rows are of the frame before it.
        assertEquals(ConversationScroll.Landing.Wait, d.scroll.landing(stale))
        assertEquals(ConversationScroll.Landing.AtTail, d.land())
    }

    @Test
    fun `an empty anchorMissing reply into an unhydrated buffer is recognised by it hydrating`() {
        val d = Drive()
        d.frame(state(row(hydrated = false)))
        d.scroll.jumpTo(4)
        assertEquals(4L, d.frame(state(row(hydrated = false))).loadAround)
        // Nothing to replace, no anchor to appear — but a history reply always hydrates.
        d.frame(state(row(hydrated = true)))
        // An empty buffer: let go, and let the empty state show.
        assertEquals(ConversationScroll.Landing.Idle, d.land())
        assertFalse(d.scroll.jumpPending)
    }

    @Test
    fun `a jump into a buffer the server says is empty lets go without a fetch`() {
        val d = Drive()
        d.frame(state(row(hydrated = true)))
        d.scroll.jumpTo(4)
        assertNull(d.scroll.requestAround(d.last, channel))
        assertEquals(ConversationScroll.Landing.Idle, d.land())
        assertFalse(d.scroll.jumpPending)
    }

    @Test
    fun `the reply with the anchor in it is landed on`() {
        val d = Drive()
        d.frame(state(messages = msgs(100L..110L)))
        d.scroll.jumpTo(4)
        d.frame(d.last)
        d.frame(state(row(hasMoreNewer = true), msgs(1L..8L)))
        val reply = d.built()
        assertTrue(d.scroll.landing(reply) is ConversationScroll.Landing.Converge)
        assertEquals(reply.rowIndex(4), d.scroll.jumpTargetRow(reply))
    }

    @Test
    fun `a live append and a reply are told apart by the held objects`() {
        val held = msgs(1L..5L)
        assertTrue(ReplyWatch.isAppend(held, held + msg(6)))
        assertTrue(ReplyWatch.isAppend(held, held))
        assertFalse(ReplyWatch.isAppend(held, msgs(1L..5L)))
        assertFalse(ReplyWatch.isAppend(held, msgs(1L..6L)))
        assertFalse(ReplyWatch.isAppend(held, held.drop(1)))
        // An empty start says nothing either way — the hydrate flag does.
        assertTrue(ReplyWatch.isAppend(emptyList(), msgs(1L..3L)))
    }

    @Test
    fun `the jump stays pending until it has settled, so nothing else steals the scroll`() {
        val scroll = machine()
        scroll.jumpTo(4)
        val built = build(scroll, state(messages = msgs(1L..10L)))
        val landing = scroll.landing(built) as ConversationScroll.Landing.Converge
        // An intervening build: no second chain, no follow, still pending.
        assertEquals(ConversationScroll.Landing.Wait, scroll.landing(built))
        assertFalse(scroll.onRows(build(scroll, state(messages = msgs(1L..11L))), wasNearBottom = true).follow)
        assertTrue(scroll.jumpPending)
        val end = scroll.finishJump(landing.token, built, interrupted = false)
        assertEquals(built.rowIndex(4), (end as ConversationScroll.Finish.Flash).row)
        assertFalse(scroll.jumpPending)
    }

    @Test
    fun `the target is re-resolved by message id on every pass, never a cached index`() {
        val scroll = machine()
        val first = build(scroll, state(messages = msgs(5L..10L)))
        scroll.jumpTo(7)
        scroll.landing(first)
        val before = scroll.jumpTargetRow(first)!!
        // An older page lands between passes: every index shifts, the message doesn't.
        val second = build(scroll, state(messages = msgs(1L..10L)))
        val after = scroll.jumpTargetRow(second)!!
        assertEquals(7L, second.rows[after].message?.id)
        assertTrue(after > before)
    }

    @Test
    fun `a target folded into a consolidated run resolves to the summary`() {
        val scroll = machine()
        val joins = (3L..6L).map { msg(it, nick = "user$it", type = EventType.Join, text = null) }
        val built = build(scroll, state(messages = msgs(1L..2L) + joins + msgs(7L..8L)))
        scroll.jumpTo(4)
        val row = scroll.jumpTargetRow(built)!!
        assertTrue(built.rows[row] is MessageRow.Consolidated)
    }

    @Test
    fun `a superseded or finished jump's chain is told so`() {
        val scroll = machine()
        scroll.jumpTo(4)
        val built = build(scroll, state(messages = msgs(1L..10L)))
        val first = (scroll.landing(built) as ConversationScroll.Landing.Converge).token
        scroll.jumpTo(6)
        assertFalse(scroll.isCurrent(first))
        assertEquals(ConversationScroll.Finish.Release, scroll.finishJump(first, built, interrupted = false))
        assertTrue(scroll.jumpPending)
    }

    @Test
    fun `a jump whose target vanished lands at the bottom`() {
        val scroll = machine()
        scroll.jumpTo(4)
        val built = build(scroll, state(messages = msgs(1L..10L)))
        val token = (scroll.landing(built) as ConversationScroll.Landing.Converge).token
        val gone = build(scroll, state(messages = msgs(6L..10L)))
        assertEquals(ConversationScroll.Finish.AtTail, scroll.finishJump(token, gone, interrupted = false))
    }

    @Test
    fun `a jump the reader interrupted stays put and never flashes`() {
        val scroll = machine()
        scroll.jumpTo(4)
        val built = build(scroll, state(messages = msgs(1L..10L)))
        val token = (scroll.landing(built) as ConversationScroll.Landing.Converge).token
        // The target is right there — still no pulse on a row they're dragging away from.
        assertEquals(ConversationScroll.Finish.Release, scroll.finishJump(token, built, interrupted = true))
        assertFalse(scroll.jumpPending)
    }

    @Test
    fun `a jump waits for a build made under its own options`() {
        val scroll = machine()
        val stale = build(scroll, state(messages = msgs(1L..10L)))
        scroll.jumpTo(4)
        // Built before the jump's exemption existed: not trusted, even though 4 is in it.
        assertEquals(ConversationScroll.Landing.Wait, scroll.landing(stale))
        assertTrue(scroll.landing(build(scroll, state(messages = msgs(1L..10L)))) is ConversationScroll.Landing.Converge)
    }

    @Test
    fun `a jump target an ignore rule now covers still renders`() {
        val scroll = machine()
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 1, mask = "spammer"))))
        val lines = msgs(1L..3L) + msg(4, nick = "spammer") + msgs(5L..6L)
        scroll.jumpTo(4)
        val built = build(scroll, state(messages = lines, ignores = ignores))
        assertNotNull(built.rowIndex(4))
        assertTrue(scroll.landing(built) is ConversationScroll.Landing.Converge)
    }

    @Test
    fun `a held message that doesn't render here gives up rather than waiting forever`() {
        val scroll = machine()
        // A system line in a channel: held, never drawn, nothing to fetch.
        val lines = msgs(1L..3L) + msg(4, nick = null, type = EventType.System, text = "sys") + msgs(5L..6L)
        val s = state(messages = lines)
        scroll.jumpTo(4)
        assertNull(scroll.requestAround(s, channel))
        assertEquals(ConversationScroll.Landing.AtTail, scroll.landing(build(scroll, s)))
    }

    @Test
    fun `a server log's jump to an unloaded message gives up once its history has landed`() {
        val log = BufferKey(1, Buffer.serverTarget(1))
        val scroll = machine(log)
        val s = state(row(log, hydrated = false), messages = msgs(10L..20L), key = log)
        scroll.jumpTo(4)
        // A server log can't fetch an `around` slice.
        assertNull(scroll.requestAround(s, log))
        assertEquals(ConversationScroll.Landing.AtTail, scroll.landing(build(scroll, s, key = log)))
    }

    // MARK: - Jump to first unread

    @Test
    fun `the unread jump fetches on the boundary but lands on the first unread rendered row`() {
        val d = Drive()
        // A large unread: the boundary (5) is older than everything loaded, so the divider is
        // pinned to the top of the window — the seam isn't loaded.
        val pointer = row(readStateKnown = true, lastReadId = 5)
        d.frame(state(pointer, msgs(100L..110L)))
        val pinned = d.built()
        // Under only the day's divider: every loaded row is unread.
        assertEquals(1, pinned.dividerRow)
        assertTrue(d.scroll.jumpToFirstUnread())
        // The FETCH anchors on the boundary…
        assertEquals(5L, d.scroll.requestAround(d.last, channel))
        // …and while it's in flight the top-pinned divider is no target.
        d.frame(d.last)
        val waiting = d.built()
        assertNull(d.scroll.jumpTargetRow(waiting))
        assertEquals(ConversationScroll.Landing.Wait, d.scroll.landing(waiting))
        // The slice arrives around the boundary, whose own id is a frame this channel never renders.
        val seam = msgs(1L..4L) + msg(5, nick = null, type = EventType.Other, text = null) + msgs(6L..9L)
        d.frame(state(row(readStateKnown = true, lastReadId = 5, hasMoreNewer = true), seam))
        val reply = d.built()
        assertTrue(d.scroll.landing(reply) is ConversationScroll.Landing.Converge)
        val target = d.scroll.jumpTargetRow(reply)!!
        assertEquals(reply.firstUnreadRow, target)
        assertEquals(6L, reply.rows[target].message?.id)
    }

    @Test
    fun `the unread jump with the seam already loaded lands without a fetch`() {
        val (scroll, built) = withDivider()
        assertTrue(scroll.jumpToFirstUnread())
        assertNull(scroll.requestAround(state(row(readStateKnown = true, lastReadId = 5), msgs(1L..10L)), channel))
        val current = build(scroll, state(row(readStateKnown = true, lastReadId = 5), msgs(1L..10L)), seq = 2)
        assertTrue(scroll.landing(current) is ConversationScroll.Landing.Converge)
        assertEquals(built.firstUnreadRow, scroll.jumpTargetRow(current))
    }

    @Test
    fun `the unread jump never peels back a clear - its target is a rendered row`() {
        val d = Drive()
        // Read up to 3; cleared up to 5. The fetch anchor (3) is behind the marker, the target isn't.
        val s = state(row(readStateKnown = true, lastReadId = 3, clearedBeforeId = 5, clearedAt = clearedAt), msgs(1L..10L))
        d.frame(s)
        assertTrue(d.scroll.jumpToFirstUnread())
        d.frame(s)
        val built = d.built()
        assertFalse(d.scroll.options.showsClearedHistory)
        assertTrue(d.scroll.landing(built) is ConversationScroll.Landing.Converge)
        assertEquals(6L, built.rows[d.scroll.jumpTargetRow(built)!!].message?.id)
    }

    @Test
    fun `there is no unread jump without a boundary`() {
        val scroll = machine()
        assertFalse(scroll.jumpToFirstUnread())
        scroll.onFrame(state(row(readStateKnown = true, lastReadId = 0)), channel, 1)
        assertFalse(scroll.jumpToFirstUnread())
        assertFalse(scroll.jumpPending)
    }

    // MARK: - The /clear reveal

    private val clearedAt = Instant.parse("2026-07-25T13:00:00Z")

    @Test
    fun `a jump onto a row the clear marker hides peels it back`() {
        val scroll = machine()
        val s = state(row(clearedBeforeId = 5, clearedAt = clearedAt), msgs(1L..10L))
        scroll.jumpTo(3)
        val hidden = build(scroll, s)
        assertNull(hidden.rowIndex(3))
        val step = scroll.onRows(hidden, wasNearBottom = true)
        assertTrue(step.optionsChanged)
        assertTrue(scroll.options.showsClearedHistory)
        // The build that drew it hidden is stale now; the next one draws it.
        assertEquals(ConversationScroll.Landing.Wait, scroll.landing(hidden))
        val shown = build(scroll, s)
        assertNotNull(shown.rowIndex(3))
        assertTrue(scroll.landing(shown) is ConversationScroll.Landing.Converge)
    }

    @Test
    fun `a new clear retires the reveal`() {
        val scroll = machine()
        scroll.jumpTo(3)
        val cleared = state(row(clearedBeforeId = 5, clearedAt = clearedAt), msgs(1L..10L))
        scroll.onRows(build(scroll, cleared), wasNearBottom = true)
        assertTrue(scroll.options.showsClearedHistory)
        // The jump lands on the revealed row and settles.
        val shown = build(scroll, cleared)
        val token = (scroll.landing(shown) as ConversationScroll.Landing.Converge).token
        scroll.finishJump(token, shown, interrupted = false)
        // A clear issued since — here or on another device — puts the marker back.
        val step = scroll.onRows(build(scroll, state(row(clearedBeforeId = 10, clearedAt = clearedAt), msgs(1L..12L))), wasNearBottom = true)
        assertTrue(step.optionsChanged)
        assertFalse(scroll.options.showsClearedHistory)
    }

    // MARK: - Jump to latest

    @Test
    fun `jump to latest from a detached slice re-attaches and lands at the new tail once it arrives`() {
        val d = Drive()
        d.frame(state(row(hasMoreNewer = true), msgs(1L..10L)))
        d.built()
        assertEquals(ConversationScroll.ToLatest.Reattach, d.scroll.jumpToLatest(hasRows = true, detached = true))
        assertTrue(d.scroll.landingPending)
        assertTrue(d.scroll.requestLatest(d.last, channel))
        // Asked: not again for the same burst.
        assertFalse(d.frame(d.last).loadLatest)
        // Still the old slice: wait.
        assertEquals(ConversationScroll.Landing.Wait, d.land())
        // A drag while the latest slice is in flight must not drop the asked-for landing.
        assertFalse(d.scroll.onUserDrag())
        d.frame(state(row(), msgs(500L..520L)))
        assertEquals(ConversationScroll.Landing.AtTail, d.land())
        assertFalse(d.scroll.landingPending)
    }

    @Test
    fun `a re-attach lost with its socket is asked for again`() {
        val d = Drive()
        val slice = msgs(1L..10L)
        d.frame(state(row(hasMoreNewer = true), slice))
        d.scroll.jumpToLatest(hasRows = true, detached = true)
        assertTrue(d.scroll.requestLatest(d.last, channel))
        assertFalse(d.frame(state(row(hasMoreNewer = true), slice, connection = SocketStatus.Reconnecting)).loadLatest)
        assertTrue(d.frame(state(row(hasMoreNewer = true), slice)).loadLatest)
        // Replaced under a steady Connected: the new burst asks again.
        assertTrue(d.frame(state(row(hasMoreNewer = true), slice, burstGeneration = 2)).loadLatest)
        assertFalse(d.frame(state(row(hasMoreNewer = true), slice, burstGeneration = 2)).loadLatest)
    }

    @Test
    fun `jump to latest cancels a pending jump when it rides down`() {
        val d = Drive()
        d.frame(state(messages = msgs(1L..10L)))
        d.scroll.jumpTo(4)
        assertEquals(ConversationScroll.ToLatest.ScrollDown, d.scroll.jumpToLatest(hasRows = true, detached = false))
        assertFalse(d.scroll.jumpPending)
        assertEquals(ConversationScroll.Landing.Idle, d.land())
    }

    @Test
    fun `jump to latest with a jump's slice still in the air re-attaches past it`() {
        val d = Drive()
        val live = msgs(100L..110L)
        d.frame(state(messages = live))
        d.scroll.jumpTo(4)
        assertEquals(4L, d.frame(state(messages = live)).loadAround)
        // The reader gives up on it and asks for the bottom.
        assertEquals(ConversationScroll.ToLatest.Reattach, d.scroll.jumpToLatest(hasRows = true, detached = false))
        assertFalse(d.scroll.jumpPending)
        assertTrue(d.scroll.requestLatest(d.last, channel))
        // The stray `around` reply lands anyway, detaching the buffer: nothing converges on it.
        d.frame(state(row(hasMoreNewer = true), msgs(1L..8L)))
        assertEquals(ConversationScroll.Landing.Wait, d.land())
        // The `latest` behind it re-attaches, and that's where the reader lands.
        d.frame(state(row(), msgs(100L..112L)))
        assertEquals(ConversationScroll.Landing.AtTail, d.land())
    }

    @Test
    fun `re-attaching puts a clear back too`() {
        val scroll = machine()
        scroll.jumpTo(3)
        scroll.onRows(build(scroll, state(row(clearedBeforeId = 5, clearedAt = clearedAt), msgs(1L..10L))), wasNearBottom = false)
        assertTrue(scroll.options.showsClearedHistory)
        assertEquals(ConversationScroll.ToLatest.Reattach, scroll.jumpToLatest(hasRows = true, detached = true))
        assertFalse(scroll.options.showsClearedHistory)
    }

    @Test
    fun `jump to latest on an attached buffer rides down, clears the badge, and puts a clear back`() {
        val scroll = machine()
        scroll.jumpTo(3)
        scroll.onRows(build(scroll, state(row(clearedBeforeId = 5, clearedAt = clearedAt), msgs(1L..10L))), wasNearBottom = false)
        assertTrue(scroll.options.showsClearedHistory)
        assertEquals(ConversationScroll.ToLatest.ScrollDown, scroll.jumpToLatest(hasRows = true, detached = false))
        assertFalse(scroll.options.showsClearedHistory)
        assertEquals(0, scroll.newWhileAway)
        assertEquals(ConversationScroll.ToLatest.Nothing, scroll.jumpToLatest(hasRows = false, detached = false))
    }

    @Test
    fun `a drag releases a converging jump, never one waiting on its fetch`() {
        val scroll = machine()
        scroll.jumpTo(4)
        scroll.requestAround(state(messages = msgs(100L..110L)), channel)
        assertFalse(scroll.onUserDrag())
        val held = machine()
        held.jumpTo(4)
        assertTrue(held.landing(build(held, state(messages = msgs(1L..10L)))) is ConversationScroll.Landing.Converge)
        assertTrue(held.onUserDrag())
    }

    // MARK: - Paging

    @Test
    fun `paging asks older near the top, and newer only near the bottom of a detached slice`() {
        val scroll = machine()
        val attached = build(scroll, state(messages = msgs(1L..10L)))
        scroll.onRows(attached, wasNearBottom = true)
        val near = facts(pagesOlder = true, pagesNewer = true)
        val step = scroll.onLayout(near, attached, detached = false, connectionBannerShown = false)
        assertTrue(step.loadOlder)
        assertFalse(step.loadNewer)
        assertTrue(scroll.onLayout(near, attached, detached = true, connectionBannerShown = false).loadNewer)
        assertFalse(scroll.onLayout(facts(), attached, detached = true, connectionBannerShown = false).loadOlder)
    }

    @Test
    fun `paging waits out a pending landing and an unlanded history`() {
        val scroll = machine()
        val near = facts(pagesOlder = true, pagesNewer = true)
        val stub = build(scroll, state(row(hydrated = false), msgs(100L..103L)))
        assertFalse(scroll.onLayout(near, stub, detached = false, connectionBannerShown = false).loadOlder)
        val built = build(scroll, state(messages = msgs(1L..10L)))
        scroll.jumpTo(200)
        val step = scroll.onLayout(near, built, detached = true, connectionBannerShown = false)
        assertFalse(step.loadOlder)
        assertFalse(step.loadNewer)
    }

    @Test
    fun `a window the filters thinned to nothing tops itself up`() {
        val scroll = machine()
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 1, mask = "spammer"))))
        val all = (1L..5L).map { msg(it, nick = "spammer") }
        val empty = build(scroll, state(messages = all, ignores = ignores))
        assertTrue(empty.rows.isEmpty())
        assertTrue(scroll.wantsTopUp(empty))
        // Not with history exhausted, not for an unhydrated buffer (the hydrate's), not mid-jump.
        assertFalse(scroll.wantsTopUp(build(scroll, state(row(hasMoreOlder = false), all, ignores = ignores))))
        assertFalse(scroll.wantsTopUp(build(scroll, state(row(hydrated = false), all, ignores = ignores))))
        scroll.jumpTo(3)
        assertFalse(scroll.wantsTopUp(empty))
    }

    // MARK: - Layout facts

    /** Items for a list of [heights] (newest first, item 0 at the bottom) scrolled up by [scrolled]. */
    private fun laidOut(built: BuiltRows, heights: Int, viewport: Int, scrolled: Int): List<VisibleItem> {
        val items = mutableListOf<VisibleItem>()
        var offset = -scrolled
        for (index in 0 until built.rows.size) {
            if (offset >= viewport) break
            if (offset + heights > 0) items += VisibleItem(built.keys[built.itemIndex(index)], index, offset, heights)
            offset += heights
        }
        return items
    }

    @Test
    fun `layout facts are refused for a layout of other rows`() {
        val scroll = machine()
        val old = build(scroll, state(messages = msgs(1L..10L)))
        val new = build(scroll, state(messages = msgs(1L..11L)))
        val items = laidOut(old, heights = 20, viewport = 100, scrolled = 0)
        assertNotNull(LayoutFacts.of(items, old, contentEnd = 100, nearBottomPx = 80, pagingPx = 300))
        assertNull(LayoutFacts.of(items, new, contentEnd = 100, nearBottomPx = 80, pagingPx = 300))
    }

    @Test
    fun `the divider is above only when wholly past the top edge, and visible when on screen`() {
        val (_, built) = withDivider()
        val divider = built.itemIndex(built.dividerRow!!)
        // 20px rows, 60px viewport: items 0..2 on screen.
        val atBottom = LayoutFacts.of(laidOut(built, 20, 60, 0), built, 60, 80, 300)!!
        assertEquals(divider > 2, atBottom.dividerAbove)
        assertFalse(atBottom.dividerVisible)
        // Scrolled so the divider straddles the top edge: visible, not above.
        val straddling = LayoutFacts.of(laidOut(built, 20, 60, divider * 20 - 50), built, 60, 80, 300)!!
        assertTrue(straddling.dividerVisible)
        assertFalse(straddling.dividerAbove)
        // Scrolled past it, up into older history: below the viewport — neither.
        val past = LayoutFacts.of(laidOut(built, 20, 60, (divider + 2) * 20), built, 60, 80, 300)!!
        assertFalse(past.dividerVisible)
        assertFalse(past.dividerAbove)
    }

    @Test
    fun `distances to an edge that isn't laid out are estimated from the rows that are`() {
        val scroll = machine()
        val built = build(scroll, state(messages = msgs(1L..100L)))
        // 20px rows: 100 rows ≈ 2000px; at the bottom, the oldest is far, the newest is here.
        val bottom = LayoutFacts.of(laidOut(built, 20, 200, 0), built, 200, 80, 300)!!
        assertTrue(bottom.nearBottom)
        assertFalse(bottom.pagesOlder)
        // Scrolled up to within ~200px of the oldest row (not laid out yet): page older.
        val total = built.rows.size * 20
        val nearTop = LayoutFacts.of(laidOut(built, 20, 200, total - 200 - 200), built, 200, 80, 300)!!
        assertTrue(nearTop.pagesOlder)
        assertFalse(nearTop.nearBottom)
    }

    @Test
    fun `a short buffer that fits is near both ends`() {
        val scroll = machine()
        val built = build(scroll, state(messages = msgs(1L..3L)))
        val fits = LayoutFacts.of(laidOut(built, 20, 400, 0), built, 400, 80, 300)!!
        assertTrue(fits.nearBottom)
        assertTrue(fits.pagesOlder)
    }

    // MARK: - Holding the reader's line

    @Test
    fun `an older page keeps every newer row at its item index and key - no prepend anchoring needed`() {
        val scroll = machine()
        val before = build(scroll, state(messages = msgs(101L..150L)))
        val after = build(scroll, state(messages = msgs(1L..150L)))
        // Every row of the old build (bar the date divider, which moves to the page's top) keeps its
        // key at the same reversed item index — which is all the lazy list's anchoring needs.
        for (row in before.rows.indices) {
            val key = before.keys[row]
            if (key.startsWith("d")) continue
            assertEquals(before.itemIndex(row), after.itemIndex(after.indexByKey.getValue(key)))
        }
        assertNull(ConversationScroll.hold(5, 3, laidOut(before, 20, 200, 103), before, after))
    }

    @Test
    fun `a newer page larger than the list's key window is held by hand, a small one is left to the list`() {
        val scroll = machine()
        val drawn = build(scroll, state(row(hasMoreNewer = true), msgs(1L..50L)))
        val small = build(scroll, state(row(hasMoreNewer = true), msgs(1L..80L)))
        assertNull(ConversationScroll.hold(0, 4, laidOut(drawn, 20, 200, 4), drawn, small))
        val big = build(scroll, state(row(hasMoreNewer = true), msgs(1L..250L)))
        val hold = ConversationScroll.hold(0, 4, laidOut(drawn, 20, 200, 4), drawn, big)!!
        val newest = drawn.keys[drawn.itemIndex(0)]
        assertEquals(big.itemIndex(big.indexByKey.getValue(newest)), hold.index)
        assertEquals(4, hold.scrollOffset)
    }

    @Test
    fun `a vanished first row holds the reader on the first survivor`() {
        val scroll = machine()
        val drawn = build(scroll, state(messages = msgs(1L..20L)))
        val firstKey = drawn.keys[drawn.itemIndex(0)]
        assertEquals("m20", firstKey)
        val next = build(scroll, state(messages = msgs(1L..19L)))
        val items = laidOut(drawn, 20, 200, 0)
        val hold = ConversationScroll.hold(0, 0, items, drawn, next)!!
        assertEquals(next.itemIndex(next.indexByKey.getValue("m19")), hold.index)
        assertEquals(-items.first { it.key == "m19" }.offset, hold.thenScrollBy)
    }

    @Test
    fun `the same build is never held`() {
        val scroll = machine()
        val drawn = build(scroll, state(messages = msgs(1L..20L)))
        assertNull(ConversationScroll.hold(3, 0, laidOut(drawn, 20, 200, 60), drawn, drawn))
    }

    @Test
    fun `hold refuses a layout that hasn't measured the drawn rows`() {
        val scroll = machine()
        val older = build(scroll, state(row(hasMoreNewer = true), msgs(1L..40L)))
        val drawn = build(scroll, state(row(hasMoreNewer = true), msgs(1L..50L)))
        val big = build(scroll, state(row(hasMoreNewer = true), msgs(1L..250L)))
        // Of the drawn rows: a big page below the reader is held by hand.
        assertNotNull(ConversationScroll.hold(0, 4, laidOut(drawn, 20, 200, 4), drawn, big))
        // Of an earlier build (the new rows were published, the pass that lays them out hasn't run):
        // its first visible index means nothing in the drawn rows.
        assertNull(ConversationScroll.hold(0, 4, laidOut(older, 20, 200, 4), drawn, big))
    }

    @Test
    fun `wasNearBottom falls back to the last measured answer when the layout is stale`() {
        val scroll = machine()
        val older = build(scroll, state(messages = msgs(1L..40L)))
        val drawn = build(scroll, state(messages = msgs(1L..50L)))
        // Measured: read off the layout.
        assertTrue(ConversationScroll.nearBottomBefore(laidOut(drawn, 20, 200, 0), drawn, 0, 0, 80, lastKnown = false))
        assertFalse(ConversationScroll.nearBottomBefore(laidOut(drawn, 20, 200, 400), drawn, 20, 0, 80, lastKnown = true))
        // A layout of other rows: the last answer stands.
        assertFalse(ConversationScroll.nearBottomBefore(laidOut(older, 20, 200, 0), drawn, 0, 0, 80, lastKnown = false))
        assertTrue(ConversationScroll.nearBottomBefore(emptyList(), drawn, 0, 0, 80, lastKnown = true))
    }
}
