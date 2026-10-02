// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.MessageRows
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `/clear` marker (lurker-ios#121): what the wire says, what the store keeps, and what the
 * row builder draws.
 *
 * `/clear` is not a local wipe. It is a per-user, per-buffer boundary the SERVER holds, so
 * the marker is shared with every other device and nothing is ever deleted — which is why
 * most of what matters here is that the hidden messages are still present and reachable.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Clear marker"), where each case carries a
 * display name. The method names are kept and the display name is the comment above each.
 */
class ClearMarkerTests {

    private val clearedAt: Instant = Instant.ofEpochSecond(1_784_548_800)

    private fun msg(id: Long): Message =
        Message(id = id, type = EventType.Message, nick = "alice", text = "hi")

    // MARK: - The wire

    // "a backlog frame carries the marker onto the buffer"
    @Test
    fun backlogCarriesTheMarker() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"backlog","networkId":1,"target":"#lurker","events":[],
             "hasMoreOlder":false,"clearedBeforeId":42,"clearedAt":"2026-07-20T12:00:00.000Z"}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.Backlog) fail("expected a backlog, got $frame")
        assertEquals(42L, frame.buffer.clearedBeforeId)
        assertEquals(clearedAt, frame.buffer.clearedAt)
    }

    // "a backlog for a buffer that was never cleared says so"
    @Test
    fun backlogWithoutAMarker() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":false}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected a backlog, got $frame")
        assertEquals(0L, frame.buffer.clearedBeforeId)
        assertNull(frame.buffer.clearedAt)
    }

    // "the buffer-cleared fan-out parses"
    @Test
    fun bufferClearedParses() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"buffer-cleared","networkId":1,"target":"#lurker","bufferId":7,
             "clearedBeforeId":42,"clearedAt":"2026-07-20T12:00:00.000Z"}
            """.trimIndent(),
        )
        assertEquals(
            ServerFrame.BufferCleared(networkId = 1, target = "#lurker", clearedBeforeId = 42, clearedAt = clearedAt),
            frame,
        )
    }

    // "an undo arrives as a zero boundary, not as a missing frame"
    @Test
    fun undoParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"buffer-cleared","networkId":1,"target":"#lurker","clearedBeforeId":0,"clearedAt":null}""",
        )
        assertEquals(
            ServerFrame.BufferCleared(networkId = 1, target = "#lurker", clearedBeforeId = 0, clearedAt = null),
            frame,
        )
    }

    // MARK: - The store

    private fun clearedBuffer(): ChatState {
        val buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel)
        var state = LurkerStore.reduce(
            ChatState(),
            ServerFrame.Backlog(buffer = buffer, messages = emptyList(), hydrated = true, append = false, speakers = null),
        )
        state = LurkerStore.reduce(
            state,
            ServerFrame.BufferCleared(networkId = 1, target = "#lurker", clearedBeforeId = 42, clearedAt = clearedAt),
        )
        return state
    }

    // "the fan-out moves the marker on a buffer we hold"
    @Test
    fun fanOutMovesTheMarker() {
        val buffer = clearedBuffer().buffers["1::#lurker"]
        assertEquals(42L, buffer?.clearedBeforeId)
        assertEquals(clearedAt, buffer?.clearedAt)
    }

    // "an undo drops both halves together"
    @Test
    fun undoDropsBothHalves() {
        // A boundary with no instant would hide rows and draw no divider — the one state that
        // leaves the user with no way back — so the two always move together.
        val state = LurkerStore.reduce(
            clearedBuffer(),
            ServerFrame.BufferCleared(networkId = 1, target = "#lurker", clearedBeforeId = 0, clearedAt = null),
        )
        assertEquals(0L, state.buffers["1::#lurker"]?.clearedBeforeId)
        assertNull(state.buffers["1::#lurker"]?.clearedAt)
    }

    // "a clear for a buffer we don't hold conjures nothing"
    @Test
    fun aClearForAnUnknownBufferIsIgnored() {
        val state = LurkerStore.reduce(
            ChatState(),
            ServerFrame.BufferCleared(networkId = 1, target = "#nope", clearedBeforeId = 42, clearedAt = clearedAt),
        )
        assertNull(state.buffers["1::#nope"])
    }

    // "⚠ a later backlog is authoritative and can retract the marker"
    @Test
    fun aBacklogRetractsTheMarker() {
        // Every backlog frame states the marker — the server's `bufferStateFields` is shared by
        // the shell builder and the snapshot loop too — so rescuing a prior value the way
        // `topic` is rescued would keep a marker an unclear elsewhere had already dropped.
        val state = LurkerStore.reduce(
            clearedBuffer(),
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel),
                messages = emptyList(), hydrated = true, append = false, speakers = null,
            ),
        )
        assertEquals(0L, state.buffers["1::#lurker"]?.clearedBeforeId)
    }

    // MARK: - The rows

    private fun rows(
        messages: List<Message>,
        clearedBeforeId: Long,
        clearedAt: Instant?,
        hasMoreOlder: Boolean = true,
        hasMoreNewer: Boolean = false,
        showsClearedHistory: Boolean = false,
    ): List<MessageRow> =
        MessageRows.build(
            messages = messages, dividerAfterId = null, hasMoreOlder = hasMoreOlder,
            hasMoreNewer = hasMoreNewer, clearedBeforeId = clearedBeforeId, clearedAt = clearedAt,
            showsClearedHistory = showsClearedHistory,
        )

    // "everything at or below the boundary is hidden, and the rest stays"
    @Test
    fun theBoundaryHides() {
        val built = rows(listOf(msg(1), msg(2), msg(3)), clearedBeforeId = 2, clearedAt = clearedAt)
        assertEquals(listOf(3L), built.mapNotNull { it.message?.id }, "the boundary itself is hidden, not kept")
    }

    // "the divider tops the visible region"
    @Test
    fun theDividerIsFirst() {
        val built = rows(listOf(msg(1), msg(2)), clearedBeforeId = 1, clearedAt = clearedAt)
        assertEquals(MessageRow.ClearedDivider(at = clearedAt), built.firstOrNull())
    }

    // "⚠⚠ a clear that hid everything still draws the divider"
    @Test
    fun anEmptyVisibleRegionKeepsTheUndo() {
        // The one outcome this feature must not have: a buffer that goes blank with no way
        // back but typing `/clear off` blind. The divider IS the undo affordance.
        val built = rows(listOf(msg(1), msg(2)), clearedBeforeId = 2, clearedAt = clearedAt)
        assertEquals(listOf<MessageRow>(MessageRow.ClearedDivider(at = clearedAt)), built)
    }

    // "no marker, no divider and no filtering"
    @Test
    fun anUnclearedBufferIsUntouched() {
        val built = rows(listOf(msg(1), msg(2)), clearedBeforeId = 0, clearedAt = null)
        assertEquals(listOf(1L, 2L), built.mapNotNull { it.message?.id })
        assertFalse(built.any { it is MessageRow.ClearedDivider })
    }

    // "⚠⚠ a detached buffer ignores the marker entirely"
    @Test
    fun detachedIgnoresTheMarker() {
        // A jump to a search hit or a highlight shows context around its anchor whether or not
        // it predates a clear. Answering that tap with an empty screen would obey the wrong
        // instruction — the user asked to see THAT message.
        val built = rows(
            listOf(msg(1), msg(2)), clearedBeforeId = 2, clearedAt = clearedAt, hasMoreNewer = true,
        )
        assertEquals(listOf(1L, 2L), built.mapNotNull { it.message?.id })
        assertFalse(
            built.any { it is MessageRow.ClearedDivider },
            "and no divider either — half-applying the marker would be worse than both",
        )
    }

    // "⚠ start-of-history is suppressed while a clear is in force"
    @Test
    fun startOfHistoryIsSuppressed() {
        // `hasMoreOlder` answers "is there more to FETCH", but the row SAYS "there is nothing
        // above this" — and above it sits a buffer's worth of hidden conversation.
        val built = rows(
            listOf(msg(1), msg(2)), clearedBeforeId = 1, clearedAt = clearedAt, hasMoreOlder = false,
        )
        assertFalse(built.any { it is MessageRow.StartOfHistory })
        // Still drawn when nothing is cleared, so this suppression is the marker's doing.
        val uncleared = rows(listOf(msg(1), msg(2)), clearedBeforeId = 0, clearedAt = null, hasMoreOlder = false)
        assertEquals(MessageRow.StartOfHistory, uncleared.firstOrNull())
    }

    // "a locally synthesized line survives a clear"
    @Test
    fun localLinesAreNeverHidden() {
        // `LurkerStore.appendLocal` synthesizes id-less system lines (an unrecognized command,
        // a refusal). They have no place in the server's ordering to be above or below a
        // boundary, and they postdate the clear by construction.
        val local = Message(id = 0, type = EventType.System, nick = null, text = "unknown command")
        val built = rows(listOf(msg(1), local), clearedBeforeId = 5, clearedAt = clearedAt)
        assertEquals(listOf("unknown command"), built.mapNotNull { it.message?.text })
    }

    // "⚠⚠ a boundary with no instant is discarded whole, at the parser"
    @Test
    fun aHalfStatedMarkerIsDiscarded() {
        // `cleared_at` is nullable server-side and the rename / case-fold merges carry the two
        // columns independently, so this reaches us from the wire rather than only from a bug
        // here. Taken at face value it hides every row and draws no divider — a blank buffer
        // whose only way out is a `/clear off` the reader was never told about.
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":false,"clearedBeforeId":42,"clearedAt":null}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected a backlog, got $frame")
        assertEquals(0L, frame.buffer.clearedBeforeId, "showing cleared messages is the safe failure")
        assertNull(frame.buffer.clearedAt)
    }

    // "⚠⚠ and the row builder refuses to half-apply one either, in both directions"
    @Test
    fun theRowBuilderRefusesAHalfMarker() {
        // Boundary with no instant: would hide every row and draw nothing to undo with.
        val noInstant = rows(listOf(msg(1), msg(2)), clearedBeforeId = 2, clearedAt = null)
        assertEquals(listOf(1L, 2L), noInstant.mapNotNull { it.message?.id }, "no instant, no filtering")
        assertFalse(noInstant.any { it is MessageRow.ClearedDivider })

        // Instant with no boundary: hides nothing, but would tell the reader their buffer is
        // cleared and offer a `/clear off` that does nothing at all.
        val noBoundary = rows(listOf(msg(1), msg(2)), clearedBeforeId = 0, clearedAt = clearedAt)
        assertEquals(listOf(1L, 2L), noBoundary.mapNotNull { it.message?.id })
        assertFalse(noBoundary.any { it is MessageRow.ClearedDivider })
    }

    // "⚠ a clear drops the local lines it predates"
    @Test
    fun aClearDropsLocalLines() {
        // `appendLocal` rows carry neither an id nor a date, so the row filter has nothing to
        // judge them by; left alone they outlive a clear and render UNDER the divider as though
        // they had arrived after it. They are ephemeral anyway, so the marker takes them.
        val store = LurkerStore()
        val key = BufferKey(networkId = 1, target = "#lurker")
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = listOf(msg(10)), hydrated = true, append = false, speakers = null,
            ),
        )
        store.appendLocal(key, text = "unknown command")
        assertEquals(2, store.state.messages[key.id]?.size)

        store.apply(
            ServerFrame.BufferCleared(networkId = 1, target = "#lurker", clearedBeforeId = 10, clearedAt = clearedAt),
        )
        assertEquals(listOf(10L), store.state.messages[key.id]?.map { it.id }, "the local line went with it")
    }

    // "an undo leaves local lines alone"
    @Test
    fun anUndoKeepsLocalLines() {
        val store = LurkerStore()
        val key = BufferKey(networkId = 1, target = "#lurker")
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = listOf(msg(10)), hydrated = true, append = false, speakers = null,
            ),
        )
        store.appendLocal(key, text = "unknown command")
        store.apply(ServerFrame.BufferCleared(networkId = 1, target = "#lurker", clearedBeforeId = 0, clearedAt = null))
        assertEquals(2, store.state.messages[key.id]?.size, "nothing was hidden, so nothing goes")
    }

    // MARK: - Paging past the boundary

    private fun cleared(beforeId: Long, detached: Boolean = false): Buffer =
        Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true)
            .copy(hasMoreNewer = detached)
            .applyCleared(beforeId = beforeId, at = clearedAt)

    // "⚠⚠ paging older stops once the cursor reaches the clear boundary"
    @Test
    fun pagingStopsAtTheBoundary() {
        // The ~5s "Loading messages…" QA saw after /clear on iOS. A cleared buffer builds to one
        // divider, which is unscrollable, which asks for another page, which is also entirely
        // hidden, which is still unscrollable — walking the whole buffer into memory behind a
        // stuck spinner.
        assertFalse(cleared(50).olderPageCouldBeVisible(oldestHeldId = 10))
        assertFalse(cleared(50).olderPageCouldBeVisible(oldestHeldId = 50), "the boundary is hidden too")
    }

    // "but it still pages while there is visible history between the two"
    @Test
    fun pagingContinuesAboveTheBoundary() {
        // A clear anchors at the tail, so new messages accumulate above it; once the held slice
        // starts above the boundary there is a visible gap worth fetching.
        assertTrue(cleared(50).olderPageCouldBeVisible(oldestHeldId = 51))
    }

    // "an uncleared buffer pages normally"
    @Test
    fun anUnclearedBufferPages() {
        assertTrue(cleared(0).olderPageCouldBeVisible(oldestHeldId = 1))
    }

    // "and a detached buffer pages, because it ignores the marker"
    @Test
    fun aDetachedBufferPages() {
        assertTrue(cleared(50, detached = true).olderPageCouldBeVisible(oldestHeldId = 10))
    }

    // "so does a revealed one — the reader can scroll up through what was hidden"
    @Test
    fun aRevealedBufferPages() {
        assertTrue(
            cleared(50).olderPageCouldBeVisible(oldestHeldId = 10, showingClearedHistory = true)
        )
    }

    // MARK: - Jumping to a hidden message

    // "revealing is what makes a hidden anchor renderable"
    @Test
    fun revealingShowsAHiddenAnchor() {
        // A bookmark or search hit from before a clear. The row is loaded and filtered out, so
        // there is nothing to fetch — only the filter to suppress.
        val hidden = rows(listOf(msg(10), msg(20)), clearedBeforeId = 50, clearedAt = clearedAt)
        assertEquals(emptyList(), hidden.mapNotNull { it.message?.id }, "hidden while the filter is in force")

        val revealed = rows(
            listOf(msg(10), msg(20)), clearedBeforeId = 50, clearedAt = clearedAt,
            showsClearedHistory = true,
        )
        assertEquals(listOf(10L, 20L), revealed.mapNotNull { it.message?.id }, "and visible once revealed")
        assertFalse(
            revealed.any { it is MessageRow.ClearedDivider },
            "no marker either — a revealed buffer is not a half-cleared one",
        )
    }

    // "⚠⚠ the marker itself is untouched by a reveal, so a reopen is cleared again"
    @Test
    fun aRevealNeverTouchesTheMarker() {
        // The reveal is SCREEN state (on iOS, `ChatViewController.showsClearedHistory`), which
        // is why nothing here can express it: `BufferNavigation` builds a fresh screen per open,
        // so it retires itself. A flag on the buffer had no natural retirement — one jump peeled
        // the buffer open for good and reopening never restored the clear, which is the bug
        // that moved it out of the store.
        val state = clearedBuffer()
        assertEquals(42L, state.buffers["1::#lurker"]?.clearedBeforeId)
        assertEquals(false, state.buffers["1::#lurker"]?.hasMoreNewer, "and paging state is its own")
    }

    // MARK: - The command

    // Port note: `CommandParser.parse` takes the expiry's formatter here (see
    // `IgnoreRule.summary`); no `/clear` line uses it.
    private fun parse(line: String): List<CommandEffect> {
        val parsed = CommandParser.parse(line, networkId = 1, target = "#lurker", formatted = { it.toString() })
        return (parsed as? ParsedInput.Command)?.effects ?: emptyList()
    }

    // "/clear hides, /clear off and /clear undo bring it back"
    @Test
    fun theCommandParses() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Clear(target = "#lurker", undo = false)), parse("/clear"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Clear(target = "#lurker", undo = true)), parse("/clear off"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Clear(target = "#lurker", undo = true)), parse("/clear undo"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Clear(target = "#lurker", undo = true)),
            parse("/clear OFF"),
            "case-insensitive",
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Clear(target = "#lurker", undo = true)),
            parse("/clear  off  "),
            "spacing",
        )
    }

    // "an unrecognised argument clears rather than refusing"
    @Test
    fun anUnknownArgumentStillClears() {
        // `/clear all` is what someone reaching for "clear everything" types; refusing it
        // would decline the thing they asked for on the grounds that they were too specific.
        assertEquals(listOf<CommandEffect>(CommandEffect.Clear(target = "#lurker", undo = false)), parse("/clear all"))
    }
}
