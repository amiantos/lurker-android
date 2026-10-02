// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.TypingActivity
import net.amiantos.lurkerkit.session.AppBadge
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.SocketStatus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Shared by both suites below — one derivation of key and kind, so the two can't drift.
private fun buffer(target: String, highlights: Int, unread: Int = 0): Buffer =
    Buffer(
        networkId = 1,
        target = target,
        kind = BufferKind.of(networkId = 1, target = target),
        unread = unread,
        highlights = highlights,
    )

private fun state(buffers: List<Buffer>): ChatState = ChatState(buffers = buffers.associateBy { it.key.id })

/**
 * The app-icon badge total (lurker#490).
 *
 * This exists because a push only ever REVISES the badge — iOS applies `aps.badge` and
 * then nothing touches it again — so without a client-side number, reading your messages
 * leaves the icon stuck on whatever the last notification claimed. The arithmetic is
 * therefore load-bearing for a user-visible thing that has no other feedback loop: a
 * wrong total looks exactly like a right one until you count.
 */
class AppBadgeTests {

    @Test
    fun testEmptyStateBadgesNothing() {
        assertEquals(0, ChatState().totalHighlights)
    }

    @Test
    fun testSumsHighlightsAcrossBuffers() {
        val s = state(
            listOf(
                buffer("#lurker", highlights = 2),
                buffer("bob", highlights = 1),
                buffer("#other", highlights = 3),
            ),
        )
        assertEquals(6, s.totalHighlights)
    }

    @Test
    fun testCountsHighlightsNotUnread() {
        // The badge is mentions, not traffic. A busy channel you're not named in must not
        // light up the icon — that's the whole distinction between unread and highlights,
        // and it's what makes the badge worth looking at.
        val s = state(listOf(buffer("#lurker", highlights = 0, unread = 400)))
        assertEquals(0, s.totalHighlights)
    }

    @Test
    fun testGoesToZeroWhenEverythingIsRead() {
        // The case the bug this fixes gets wrong: push set the icon to 3, the user read
        // all three, and nothing ever told the icon.
        var s = state(listOf(buffer("#lurker", highlights = 2), buffer("bob", highlights = 1)))
        assertEquals(3, s.totalHighlights)
        s = s.copy(buffers = s.buffers.mapValues { it.value.copy(highlights = 0) })
        assertEquals(0, s.totalHighlights)
    }

    @Test
    fun testCountsDMsAndChannelsAlike() {
        // A DM's every unread line counts as a highlight server-side, so DMs contribute
        // their full count — the badge should reflect that rather than treating them as a
        // separate kind of thing.
        assertEquals(5, state(listOf(buffer("bob", highlights = 5))).totalHighlights)
    }

    @Test
    fun testIncludesTheSystemBuffer() {
        // The system buffer's notable lines double as highlights server-side (its unread
        // IS its highlight count), so an admin/error line lights the icon too. Pinned
        // because it's easy to assume "highlights" means "someone said your nick".
        val s = ChatState(
            buffers = mapOf(
                Buffer.system.key.id to Buffer(
                    networkId = null, target = Buffer.systemTarget, kind = BufferKind.System, unread = 2, highlights = 2,
                ),
            ),
        )
        assertEquals(2, s.totalHighlights)
    }
}

/**
 * When the badge is WRITTEN (lurker-ios#134), as distinct from what the number is.
 *
 * A push paints the icon behind the app's back, and it is right to while the app is
 * closed. Every write here therefore has to be one the app can vouch for: a count the
 * server has actually stated this session, written when it changes, when the roster is
 * re-stated in full, or on demand — and refused when it's a leftover. The states are
 * driven through the real reducer, because what "settled" and "the server has spoken"
 * mean is the reducer's business and this must break when that changes.
 *
 * Port note: LurkerKit drives a `CurrentValueSubject`; here a `MutableStateFlow` collected on a
 * `StandardTestDispatcher`, run to idle after every send. A `StateFlow` drops a state equal to
 * the one it holds, where the subject re-sends it; every expectation here is about a state
 * that writes nothing either way, so the two agree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBadgeWriteTests {

    private val writes = mutableListOf<Int>()
    private lateinit var badge: AppBadge
    private val states = MutableStateFlow(ChatState())
    private val scope = TestScope(StandardTestDispatcher())

    @BeforeTest
    fun setUp() {
        writes.clear()
        badge = AppBadge { writes.add(it) }
        badge.follow(states, scope)
        scope.runCurrent()
    }

    private fun send(state: ChatState) {
        states.value = state
        scope.runCurrent()
    }

    private fun apply(frame: ServerFrame) {
        send(LurkerStore.reduce(states.value, frame))
    }

    private val snapshot = ServerFrame.Snapshot(emptyList(), globalIgnores = emptyList(), maxUploadBytes = null)

    /** The connect-burst frame that carries a buffer's server-side counts. */
    private fun backlog(target: String, highlights: Int, unread: Int = 0): ServerFrame =
        ServerFrame.Backlog(
            buffer = buffer(target, highlights = highlights, unread = unread),
            messages = emptyList(), hydrated = true, append = false, speakers = null,
        )

    @Test
    fun testNothingIsWrittenUntilTheServerHasSpoken() {
        // Cold launch: the replayed store is EMPTY, not zero. Pushes painted a true number
        // while the app was closed, and writing 0 over it would hide real highlights for as
        // long as the connect takes — forever, offline. The snapshot is the first frame
        // every server version sends; from there on the store's count is the store's.
        assertEquals(emptyList(), writes, "an empty store says nothing about the icon")
        badge.reassert(states.value)
        assertEquals(emptyList(), writes, "and can't be re-asserted into saying something")
        apply(snapshot)
        assertEquals(listOf(0), writes)
    }

    @Test
    fun testTransitionsWriteAndEverythingElseDoesNot() {
        apply(snapshot)
        apply(backlog("#lurker", highlights = 2))
        assertEquals(listOf(0, 2), writes)
        // Folds that leave the count alone — traffic you're not named in, a typing frame —
        // are not writes. The steady-state path is deduped on the COUNT, not on state
        // identity, so a busy channel doesn't turn into an OS call per line.
        apply(backlog("#other", highlights = 0, unread = 400))
        apply(
            ServerFrame.Typing(
                networkId = 1, target = "#lurker", nick = "alice",
                activity = TypingActivity.from("active"), userhost = null,
            ),
        )
        assertEquals(listOf(0, 2), writes)
        apply(backlog("#lurker", highlights = 0))
        assertEquals(listOf(0, 2, 0), writes)
    }

    @Test
    fun testBurstCompletionWritesAnUnchangedCountButBurstStartDoesNot() {
        apply(snapshot)
        apply(backlog("bob", highlights = 2))
        assertEquals(listOf(0, 2), writes)
        // The terminator: the roster was just re-stated in full, so the count is as fresh
        // as it gets — written even though it didn't move. This is the write a stale
        // push-painted number gets corrected by on every reconnect.
        apply(ServerFrame.BacklogComplete)
        assertEquals(listOf(0, 2, 2), writes)

        // A reconnect. The snapshot frame unsettles the roster but re-states no counts —
        // the store still holds the pre-reconnect number — so it must NOT write: that
        // would paint a leftover over a push's true one. The buffers land, then the
        // terminator writes the now-confirmed count.
        apply(snapshot)
        assertEquals(listOf(0, 2, 2), writes, "burst start is not a write")
        apply(backlog("bob", highlights = 2))
        assertEquals(listOf(0, 2, 2), writes)
        apply(ServerFrame.BacklogComplete)
        assertEquals(listOf(0, 2, 2, 2), writes)
    }

    @Test
    fun testReassertWritesAnUnchangedCount() {
        // The lurker-ios#134 shape: everything read, 0 already written, then a stale push
        // paints 3 on the icon. Nothing in state moves, so the transition path would swallow
        // every write forever; a re-assert is what gets 0 back onto the icon.
        apply(snapshot)
        apply(ServerFrame.SocketOpen)
        apply(ServerFrame.BacklogComplete)
        assertEquals(listOf(0, 0), writes)
        badge.reassert(states.value)
        assertEquals(listOf(0, 0, 0), writes)
    }

    @Test
    fun testReassertRefusesACountItCannotVouchFor() {
        apply(snapshot)
        apply(ServerFrame.SocketOpen)
        apply(backlog("bob", highlights = 1))
        assertEquals(listOf(0, 1), writes)

        // No network path: whatever pushes painted is newer than anything we hold.
        val offline = states.value.copy(reachable = false)
        badge.reassert(offline)
        assertEquals(listOf(0, 1), writes, "unreachable: the count can't be current")

        // A socket known to be down: the reconnect's burst will re-state the count; a
        // write now would be the pre-drop leftover.
        apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertEquals(SocketStatus.Reconnecting, states.value.connection)
        badge.reassert(states.value)
        assertEquals(listOf(0, 1), writes, "reconnecting: the count is a leftover")
    }

    @Test
    fun testSignOutClearsTheIcon() {
        apply(snapshot)
        apply(backlog("bob", highlights = 3))
        assertEquals(listOf(0, 3), writes)
        // `reset()` publishes a fresh state — the server-has-spoken marker gone with the
        // rest. That is a drop to nothing, and the icon was the old account's.
        send(ChatState())
        assertEquals(listOf(0, 3, 0), writes)
        send(ChatState())
        assertEquals(listOf(0, 3, 0), writes, "nothing-to-nothing is not a write")
    }
}
