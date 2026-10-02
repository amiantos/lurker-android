// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.HistoryMode
import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.model.TypingActivity
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.SocketStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's frame-folding is the tricky, pure-logic core of the client — shell vs.
 * hydrated backlog, live de-dupe, snapshot merge, name merge. Drive it with hand-built
 * frames (no JSON), asserting the folded state. Ported from the Android LurkerStoreTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LurkerStoreTests {

    private val chanKey = "1::#lurker"

    private fun msg(id: Long, text: String, isSelf: Boolean = false): Message =
        Message(id = id, type = EventType.Message, nick = "alice", text = text, isSelf = isSelf)

    private fun channelBuffer(hydrated: Boolean, messages: List<Message>): ServerFrame =
        ServerFrame.Backlog(
            buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = hydrated),
            messages = messages,
            hydrated = hydrated,
            append = false, speakers = null,
        )

    private fun live(target: String, message: Message, networkId: Int? = 1): ServerFrame =
        ServerFrame.Live(networkId = networkId, target = target, message = message)

    private fun history(
        events: List<Message>,
        mode: HistoryMode,
        hasMoreOlder: Boolean,
        hasMoreNewer: Boolean,
        speakers: List<Speaker>? = null,
    ): ServerFrame =
        ServerFrame.History(
            networkId = 1, target = "#lurker", events = events,
            mode = mode, hasMoreOlder = hasMoreOlder, hasMoreNewer = hasMoreNewer, speakers = speakers,
        )

    private val emptySnapshot: ServerFrame =
        ServerFrame.Snapshot(networks = emptyList(), globalIgnores = emptyList(), maxUploadBytes = null)

    private fun texts(store: LurkerStore, key: String = chanKey): List<String?>? = store.state.messages[key]?.map { it.text }

    @Test
    fun testShellRegistersTheBufferButLeavesItEmptyAndUnhydrated() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))

        val state = store.state
        assertNotNull(state.buffers[chanKey], "buffer should be listed")
        assertFalse(state.buffers[chanKey]!!.hydrated, "shell is not hydrated")
        assertEquals(emptyList(), state.messages[chanKey])
    }

    @Test
    fun testHydratedBacklogFillsTheBufferAndReplacesMessagesWholesale() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"), msg(2, "there"))))

        val state = store.state
        assertTrue(state.buffers[chanKey]!!.hydrated)
        assertEquals(listOf("hi", "there"), state.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun testHydrationPreservesLiveEventsNewerThanTheBacklogTail() {
        val store = LurkerStore()
        // Shell, then a live event arrives before the user opens the buffer.
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))
        store.apply(live("#lurker", msg(50, "arrived-before-open")))
        // Open → the hydrated backlog was built a moment earlier and tops out at id 40,
        // so it doesn't contain id 50. The live event must survive the replace.
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(38, "a"), msg(40, "b"))))

        assertEquals(listOf("a", "b", "arrived-before-open"), texts(store))
    }

    @Test
    fun testALaterShellNeverUnhydratesOrWipesAnAlreadyReadBuffer() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        // A resync ships the buffer again as a shell.
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))

        val state = store.state
        assertTrue(state.buffers[chanKey]!!.hydrated, "hydration must stick")
        assertEquals(listOf("hi"), state.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun testLiveEventsAppendButDedupeAgainstAPersistedIdAlreadyPresent() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(5, "hi"))))
        // The same id arrives live (backlog/live overlap) — must not double.
        store.apply(live("#lurker", msg(5, "hi")))
        store.apply(live("#lurker", msg(6, "new")))

        assertEquals(listOf("hi", "new"), texts(store))
    }

    @Test
    fun testEphemeralLiveEventsAlwaysAppendEvenWhenIdentical() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", msg(0, "poke")))
        store.apply(live("#lurker", msg(0, "poke")))

        assertEquals(2, store.state.messages[chanKey]!!.size)
    }

    @Test
    fun testALiveEventForAnUnknownTargetMaterializesABufferRow() {
        val store = LurkerStore()
        // A DM from bob arrives with no prior buffer (no snapshot, no backlog).
        store.apply(live("bob", msg(7, "hey")))

        val state = store.state
        val key = "1::bob"
        assertNotNull(state.buffers[key], "the new DM must appear in the buffer list")
        assertEquals(BufferKind.Dm, state.buffers[key]!!.kind)
        assertFalse(state.buffers[key]!!.hydrated, "unhydrated so tapping fetches history")
        assertEquals(listOf("hey"), state.messages[key]!!.map { it.text })
    }

    @Test
    fun testDifferentlyCasedTargetsFoldToTheSameBuffer() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi")))) // "#lurker"
        store.apply(live("#LURKER", msg(2, "yo"))) // upper-cased

        val state = store.state
        assertEquals(1, state.buffers.size, "must not split into a second buffer")
        assertEquals(listOf("hi", "yo"), state.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun testAResumeGapSliceAppendsAndDedupesAResetReplaces() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "a"), msg(2, "b"))))

        // reset:false gap — id 2 overlaps (drop), id 3 is new (append).
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = listOf(msg(2, "b"), msg(3, "c")),
                hydrated = true,
                append = true, speakers = null,
            ),
        )
        assertEquals(listOf("a", "b", "c"), texts(store))

        // reset (oversized gap) replaces wholesale.
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = listOf(msg(9, "z")),
                hydrated = true,
                append = false, speakers = null,
            ),
        )
        assertEquals(listOf("z"), texts(store))
    }

    @Test
    fun testHistoryBeforePrependsOlderAndDedupes() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(5, "e"), msg(6, "f"))))
        // A before-page brings 3,4 and re-sends 5 (overlap): prepend 3,4, drop the dup 5.
        store.apply(
            history(
                events = listOf(msg(3, "c"), msg(4, "d"), msg(5, "e")),
                mode = HistoryMode.Before, hasMoreOlder = false, hasMoreNewer = false,
            ),
        )

        assertEquals(listOf("c", "d", "e", "f"), texts(store))
        assertFalse(store.state.buffers[chanKey]!!.hasMoreOlder, "hasMoreOlder:false stops paging")
    }

    @Test
    fun testHistoryAroundReplacesRatherThanSplicingOntoFarNewerMessages() {
        val store = LurkerStore()
        // The buffer holds recent messages; a jump to an OLD message fetches a slice centered
        // far below them. Keeping the recent ones would render a hole — around replaces.
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(500, "recent1"), msg(501, "recent2"))))
        store.apply(
            history(
                events = listOf(msg(9, "old1"), msg(10, "anchor"), msg(11, "old2")),
                mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true,
            ),
        )
        assertEquals(listOf("old1", "anchor", "old2"), texts(store))
        assertTrue(store.state.buffers[chanKey]!!.hydrated)
    }

    @Test
    fun testAroundBelowTheTailDetachesAndHoldsBackLiveEvents() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(500, "recent"))))
        // Jump to an old message: the slice reports newer messages remain → detached.
        store.apply(
            history(events = listOf(msg(10, "anchor")), mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true),
        )
        assertTrue(store.state.buffers[chanKey]!!.hasMoreNewer, "an around slice below the tail detaches")
        // A live message must NOT splice onto the old slice.
        store.apply(live("#lurker", msg(999, "live")))
        assertEquals(listOf("anchor"), texts(store), "live is held back while detached")
        assertEquals(999L, store.state.maxEventId, "but the resume cursor still advances past it")
    }

    /** The reconnect bug: a resume frame must not stitch the present onto a jump slice. */
    @Test
    fun testResumeBacklogLeavesADetachedBufferAloneRatherThanSplicingIt() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(500, "recent"))))
        // Jump to a much older message (a bookmark, lurker-ios#42) — the buffer detaches.
        store.apply(
            history(events = listOf(msg(10, "anchor")), mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true),
        )
        // Traffic arrives while detached: held out of the log, but it advances the resume
        // cursor, so the server will never re-send it in a gap.
        store.apply(live("#lurker", msg(600, "missed")))
        // Now the socket drops and comes back (backgrounding the app is enough). The resume
        // frame carries only what landed while we were away — everything from 601 up.
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = listOf(msg(700, "while away")),
                hydrated = true, append = true, speakers = null,
            ),
        )
        assertEquals(
            listOf("anchor"), texts(store),
            "the gap must not append onto the jump slice — 600 was never delivered, so it'd be a hole",
        )
        assertTrue(
            store.state.buffers[chanKey]!!.hasMoreNewer,
            "and the buffer stays detached: the jump-to-latest pill is the only way back to live",
        )
        // Which still works, and is where the missing rows come from.
        store.apply(
            history(
                events = listOf(msg(600, "missed"), msg(700, "while away")),
                mode = HistoryMode.Latest, hasMoreOlder = true, hasMoreNewer = false,
            ),
        )
        assertEquals(listOf("missed", "while away"), texts(store))
        assertFalse(store.state.buffers[chanKey]!!.hasMoreNewer)
    }

    /**
     * The bug Brad hit: no reconnect, just a jump and a walk back to the present.
     *
     * Re-entering a detached buffer lands at the bottom of its slice, which fires `loadNewer`,
     * which appends and lands at the bottom again — the reader walks forward to live one
     * `after` page per round trip. Traffic that arrives during the LAST of those round trips
     * is the hole: the buffer is still detached so the log won't take it, and the reply that
     * re-attaches was built by the server before it was said.
     */
    @Test
    fun testTrafficDuringTheReattachRoundTripSurvivesRatherThanVanishing() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(500, "recent"))))
        store.apply(
            history(events = listOf(msg(10, "anchor")), mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true),
        )
        // Walking forward. This page still reports more ahead, so we stay detached.
        store.apply(
            history(
                events = listOf(msg(11, "b"), msg(12, "c")),
                mode = HistoryMode.After, hasMoreOlder = true, hasMoreNewer = true,
            ),
        )
        // The final page is now in flight. The server has already read up to 13 — so 14 and 15,
        // said while it's on the wire, cannot be in it.
        store.apply(live("#lurker", msg(14, "said mid-flight")))
        store.apply(live("#lurker", msg(15, "and again")))
        assertEquals(listOf("anchor", "b", "c"), texts(store), "still held out of the log while detached")
        store.apply(
            history(events = listOf(msg(13, "d")), mode = HistoryMode.After, hasMoreOlder = true, hasMoreNewer = false),
        )
        assertFalse(store.state.buffers[chanKey]!!.hasMoreNewer, "reaching the tail re-attaches")
        assertEquals(
            listOf("anchor", "b", "c", "d", "said mid-flight", "and again"),
            texts(store),
            "and the held events land behind the slice they couldn't have been in",
        )
        // Live resumes normally, with nothing left over to double up.
        store.apply(live("#lurker", msg(16, "live")))
        assertEquals(6 + 1, store.state.messages[chanKey]!!.size)
    }

    /**
     * The same seam on the jump-to-latest pill, which fetches a slice rather than walking to
     * it — and where the held events overlap what the fetch returns.
     */
    @Test
    fun testReattachingViaLatestKeepsHeldTrafficAndDoesNotDoubleIt() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(10, "old"))))
        store.apply(
            history(events = listOf(msg(10, "old")), mode = HistoryMode.Around, hasMoreOlder = false, hasMoreNewer = true),
        )
        store.apply(live("#lurker", msg(500, "during")))
        store.apply(live("#lurker", msg(501, "after the query")))
        // The latest slice the server built includes 500 (it existed when the query ran) but
        // not 501 (it didn't).
        store.apply(
            history(
                events = listOf(msg(499, "x"), msg(500, "during")),
                mode = HistoryMode.Latest, hasMoreOlder = true, hasMoreNewer = false,
            ),
        )
        assertEquals(
            listOf("x", "during", "after the query"), texts(store),
            "the overlap de-dupes by id; only the genuinely-missing tail is added",
        )
        assertNull(store.state.heldLive[chanKey], "and the hold is spent")
    }

    /**
     * An ephemeral held during a detach has no id to place it by and no fetch that could ever
     * return it — a `/ctcp` or `/e2e` status line is never persisted. It rides out the
     * re-attach on the same terms it lives everywhere else: kept, and appended last.
     */
    @Test
    fun testHeldEphemeralsSurviveReattachDespiteHavingNoId() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(10, "old"))))
        store.apply(
            history(events = listOf(msg(10, "old")), mode = HistoryMode.Around, hasMoreOlder = false, hasMoreNewer = true),
        )
        store.apply(live("#lurker", msg(0, "CTCP reply")))
        store.apply(
            history(events = listOf(msg(500, "latest")), mode = HistoryMode.Latest, hasMoreOlder = true, hasMoreNewer = false),
        )
        assertEquals(listOf("latest", "CTCP reply"), texts(store))
    }

    /**
     * A second jump abandons the first window, so what was held for it goes too — those
     * events are older than anything the next re-attach will fetch.
     */
    @Test
    fun testANewJumpStartsTheHoldOver() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(500, "recent"))))
        store.apply(
            history(events = listOf(msg(10, "anchor")), mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true),
        )
        store.apply(live("#lurker", msg(600, "held")))
        store.apply(
            history(
                events = listOf(msg(80, "second anchor")),
                mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true,
            ),
        )
        assertEquals(emptyList(), store.state.heldLive[chanKey])
        store.apply(
            history(events = listOf(msg(700, "tail")), mode = HistoryMode.Latest, hasMoreOlder = true, hasMoreNewer = false),
        )
        assertEquals(listOf("tail"), texts(store), "600 is the fetch's business now, not the hold's")
    }

    /**
     * The same frame must not blank buffer state it doesn't carry: the connect burst sends
     * the snapshot (which has the topic) BEFORE the per-buffer backlogs (which don't).
     */
    @Test
    fun testBacklogKeepsTheTopicItDoesntCarry() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#lurker", topic = "the topic"))
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"), msg(2, "there"))))
        assertEquals("the topic", store.state.buffers[chanKey]!!.topic)
    }

    @Test
    fun testLoadingLatestReattachesAndResumesLiveAppends() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(10, "old"))))
        store.apply(
            history(events = listOf(msg(10, "old")), mode = HistoryMode.Around, hasMoreOlder = false, hasMoreNewer = true),
        )
        assertTrue(store.state.buffers[chanKey]!!.hasMoreNewer)
        // Return to live: the latest slice re-attaches (clears the detached flag).
        store.apply(
            history(events = listOf(msg(500, "latest")), mode = HistoryMode.Latest, hasMoreOlder = true, hasMoreNewer = false),
        )
        assertFalse(store.state.buffers[chanKey]!!.hasMoreNewer, "latest re-attaches to the tail")
        // Live appends resume now that we're attached.
        store.apply(live("#lurker", msg(501, "live")))
        assertEquals(listOf("latest", "live"), texts(store))
    }

    @Test
    fun testAfterPagingAppendsNewerThenReattachesAtTheTail() {
        val store = LurkerStore()
        // Jump lands a detached slice below the tail (lurker-ios#42).
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(500, "recent"))))
        store.apply(
            history(
                events = listOf(msg(10, "anchor"), msg(11, "b")),
                mode = HistoryMode.Around, hasMoreOlder = false, hasMoreNewer = true,
            ),
        )
        assertTrue(store.state.buffers[chanKey]!!.hasMoreNewer, "detached after the around jump")
        // Read forward: an `after` page appends newer and, with more still ahead, stays detached.
        store.apply(
            history(
                events = listOf(msg(11, "b"), msg(12, "c"), msg(13, "d")),
                mode = HistoryMode.After, hasMoreOlder = true, hasMoreNewer = true,
            ),
        )
        assertEquals(listOf("anchor", "b", "c", "d"), texts(store), "appends, dedupes the overlap")
        assertTrue(store.state.buffers[chanKey]!!.hasMoreNewer, "still detached while newer remains")
        // The final page reaches the tail → re-attach, and live appends resume.
        store.apply(
            history(events = listOf(msg(14, "e")), mode = HistoryMode.After, hasMoreOlder = true, hasMoreNewer = false),
        )
        assertFalse(store.state.buffers[chanKey]!!.hasMoreNewer, "reaching the tail re-attaches")
        store.apply(live("#lurker", msg(15, "live")))
        assertEquals(listOf("anchor", "b", "c", "d", "e", "live"), texts(store))
    }

    @Test
    fun testHistoryTracksHasMoreOlderForThePagingGate() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(5, "e"))))
        store.apply(
            history(events = listOf(msg(4, "d")), mode = HistoryMode.Before, hasMoreOlder = true, hasMoreNewer = false),
        )
        assertTrue(store.state.buffers[chanKey]!!.hasMoreOlder)
    }

    /**
     * ⚠⚠ The trap that made the first version of the unread-divider fix inert. `hydrateIfNeeded`
     * asks for `history mode:latest`, and that reply hydrates the buffer while carrying no read
     * fields at all (`parseHistory` reads none). So anything that treats `hydrated` as "the
     * server has stated the read boundary" opens its gate on a `lastReadId` that is still this
     * struct's default 0 — and a screen that then marks the buffer read destroys the real
     * pointer before the `backlog` frame carrying it ever lands.
     */
    @Test
    fun testHistoryHydratesWithoutClaimingToKnowTheReadState() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))
        store.apply(
            history(events = listOf(msg(5, "e")), mode = HistoryMode.Latest, hasMoreOlder = false, hasMoreNewer = false),
        )

        val buffer = store.state.buffers[chanKey]!!
        assertTrue(buffer.hydrated, "a history reply is what hydrates a buffer")
        assertFalse(buffer.readStateKnown, "…but it carries no pointer, so it states nothing")
        assertEquals(0L, buffer.lastReadId, "still the default — not a boundary anyone gave us")
    }

    /** The other half: `read-state` carries the pointer by definition, so it may say so. */
    @Test
    fun testReadStateMarksTheBoundaryKnown() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))
        assertFalse(store.state.buffers[chanKey]!!.readStateKnown, "a pointer-less shell states nothing")

        store.apply(ServerFrame.ReadState(networkId = 1, target = "#lurker", lastReadId = 10, unread = 4, highlights = 2))
        assertTrue(store.state.buffers[chanKey]!!.readStateKnown)
    }

    /**
     * A reconnect resyncs buffers as shells. One that carries no pointer hasn't retracted the
     * one we already have — and must not overwrite its value with the field's default either,
     * which would silently move a reader's divider to the top of the buffer.
     */
    @Test
    fun testAPointerlessResyncNeitherRetractsNorZeroesAKnownReadState() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "a"))))
        store.apply(ServerFrame.ReadState(networkId = 1, target = "#lurker", lastReadId = 10, unread = 4, highlights = 2))
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))

        val buffer = store.state.buffers[chanKey]!!
        assertTrue(buffer.readStateKnown)
        assertEquals(10L, buffer.lastReadId)
        assertEquals(4, buffer.unread)
        assertEquals(2, buffer.highlights)
    }

    @Test
    fun testReadStateMirrorsCountsOntoTheBuffer() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "a"))))
        store.apply(ServerFrame.ReadState(networkId = 1, target = "#lurker", lastReadId = 10, unread = 4, highlights = 2))

        val buffer = store.state.buffers[chanKey]!!
        assertEquals(10L, buffer.lastReadId)
        assertEquals(4, buffer.unread)
        assertEquals(2, buffer.highlights)
    }

    /**
     * The app answering the user in place (the web's `localInfo`) — used by the system
     * buffer's composer until commands land (lurker-ios#10). Ephemeral by construction: id 0,
     * so a backlog replace drops it like any other unpersisted line.
     */
    @Test
    fun testAppendLocalAddsAnEphemeralSystemLine() {
        val store = LurkerStore()
        // The production scenario: answering the system buffer's composer, which may not
        // even have a messages entry yet — appendLocal must create one.
        store.appendLocal(Buffer.system.key, text = "not yet")

        val appended = store.state.messages[Buffer.system.key.id]!!.last()
        assertEquals(0L, appended.id, "local lines never claim a persisted id")
        assertEquals(EventType.System, appended.type)
        assertEquals("not yet", appended.text)
    }

    @Test
    fun testRemoveBufferDropsItAndItsMessages() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "a"))))
        assertNotNull(store.state.buffers[chanKey])

        store.removeBuffer(BufferKey(networkId = 1, target = "#lurker"))

        assertNull(store.state.buffers[chanKey])
        assertNull(store.state.messages[chanKey])
    }

    @Test
    fun testReadStateForAnUnknownBufferIsANoOp() {
        val store = LurkerStore()
        store.apply(ServerFrame.ReadState(networkId = 1, target = "#nope", lastReadId = 5, unread = 1, highlights = 0))
        assertNull(store.state.buffers["1::#nope"])
    }

    @Test
    fun testSnapshotSeedsChannelBuffersMembersAndNetworkLiveState() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1,
                        state = ConnectionState.Connected,
                        nick = "me",
                        channels = listOf(
                            ChannelSnapshot(
                                name = "#lurker",
                                topic = "welcome",
                                members = listOf(Member(nick = "alice", modes = listOf("o")), Member(nick = "bob")),
                            ),
                        ),
                    ),
                ),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )

        val state = store.state
        assertEquals(ConnectionState.Connected, state.networks[1]!!.state)
        assertEquals("me", state.networks[1]!!.nick)
        assertTrue(state.buffers[chanKey]!!.joined)
        assertEquals(BufferKind.Channel, state.buffers[chanKey]!!.kind)
        assertEquals("welcome", state.buffers[chanKey]!!.topic)
        assertEquals(listOf("alice", "bob"), state.members[chanKey]!!.map { it.nick })
    }

    // MARK: - Members (lurker-ios#30)
    //
    // The snapshot seeds the list (above); live join/part/quit/kick/nick churn folds into
    // it, `names` replaces it wholesale, and `member-update` patches one entry. Before
    // this the list was accurate as of the last connect and rotted from there.

    private fun seedMembers(store: LurkerStore, members: List<Member>) {
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me",
                        channels = listOf(ChannelSnapshot(name = "#lurker", topic = null, members = members)),
                    ),
                ),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
    }

    @Test
    fun testAJoinAddsAMemberAndAPartRemovesThem() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "alice")))
        store.apply(live("#lurker", Message(id = 10, type = EventType.Join, nick = "bob", text = null)))
        assertEquals(listOf("alice", "bob"), store.state.members[chanKey]!!.map { it.nick })

        store.apply(live("#lurker", Message(id = 11, type = EventType.Part, nick = "alice", text = null)))
        assertEquals(listOf("bob"), store.state.members[chanKey]!!.map { it.nick })
    }

    /**
     * The one the id de-dupe exists for: a backlog/live overlap replaying an old join
     * must not resurrect a member who has since left — so the membership fold sits
     * below the de-dupe, exactly like the topic mutation.
     */
    @Test
    fun testAReplayedJoinDoesNotResurrectAPartedMember() {
        val store = LurkerStore()
        seedMembers(store, emptyList())
        store.apply(live("#lurker", Message(id = 10, type = EventType.Join, nick = "bob", text = null)))
        store.apply(live("#lurker", Message(id = 11, type = EventType.Quit, nick = "bob", text = null)))

        store.apply(live("#lurker", Message(id = 10, type = EventType.Join, nick = "bob", text = null)))

        assertEquals(emptyList(), store.state.members[chanKey], "a replay must not re-add bob")
    }

    @Test
    fun testMembershipMatchesNicksCaseInsensitively() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "Alice"), Member(nick = "bob")))
        // The server echoes the part with different casing than NAMES gave us.
        store.apply(live("#lurker", Message(id = 10, type = EventType.Part, nick = "ALICE", text = null)))

        assertEquals(listOf("bob"), store.state.members[chanKey]!!.map { it.nick })
    }

    @Test
    fun testAJoinForANickAlreadyListedKeepsTheExistingEntry() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "alice", modes = listOf("o"))))
        store.apply(live("#lurker", Message(id = 10, type = EventType.Join, nick = "ALICE", text = null)))

        val members = store.state.members[chanKey]!!
        assertEquals(1, members.size, "must not duplicate")
        assertEquals(listOf("o"), members[0].modes, "and must not wipe what we know")
    }

    @Test
    fun testAKickRemovesTheKickedNotTheKicker() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "alice"), Member(nick = "bob")))
        // alice kicks bob: `nick` is the actor, `kicked` is who left.
        store.apply(
            live("#lurker", Message(id = 10, type = EventType.Kick, nick = "alice", text = null, kicked = "bob")),
        )

        assertEquals(listOf("alice"), store.state.members[chanKey]!!.map { it.nick })
    }

    @Test
    fun testANickEventRenamesInPlacePreservingModesAndAway() {
        val store = LurkerStore()
        seedMembers(
            store,
            listOf(Member(nick = "alice", modes = listOf("o"), away = true, user = "al", host = "example.org")),
        )
        store.apply(
            live("#lurker", Message(id = 10, type = EventType.Nick, nick = "alice", text = null, newNick = "alicia")),
        )

        val member = store.state.members[chanKey]!![0]
        assertEquals("alicia", member.nick)
        assertEquals(listOf("o"), member.modes)
        assertTrue(member.away)
        assertEquals("example.org", member.host)
    }

    /**
     * Our own join precedes the `names` broadcast, so a join must be able to seed a
     * list from nothing — the roster lands a moment later and replaces it.
     */
    @Test
    fun testAJoinSeedsAListWhereNoneExistsYet() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", Message(id = 10, type = EventType.Join, nick = "me", text = null, isSelf = true)))

        assertEquals(listOf("me"), store.state.members[chanKey]!!.map { it.nick })
    }

    /**
     * A quit fans out to every shared buffer, including DMs — which have no member
     * list. Removing from nothing must stay nothing, not conjure an empty list.
     */
    @Test
    fun testAQuitAgainstABufferWithNoListStaysListless() {
        val store = LurkerStore()
        store.apply(live("bob", msg(7, "hey")))
        store.apply(live("bob", Message(id = 8, type = EventType.Quit, nick = "bob", text = null)))

        assertNull(store.state.members["1::bob"])
    }

    @Test
    fun testANamesBroadcastReplacesTheListWholesale() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "alice"), Member(nick = "bob")))
        // A prefix-mode change re-broadcasts the whole roster: alice is now opped, bob gone.
        store.apply(
            ServerFrame.ChannelMembers(
                networkId = 1, target = "#lurker",
                members = listOf(Member(nick = "alice", modes = listOf("o")), Member(nick = "carol")),
            ),
        )

        assertEquals(listOf("alice", "carol"), store.state.members[chanKey]!!.map { it.nick })
        assertEquals(listOf("o"), store.state.members[chanKey]!![0].modes)
        assertEquals(emptyList(), store.state.messages[chanKey] ?: emptyList(), "names is silent — it prints nothing")
    }

    @Test
    fun testAMemberUpdatePatchesTheMatchingMemberInPlace() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "alice"), Member(nick = "Bob", modes = listOf("v"))))
        // A chghost snapshot for bob — matched case-insensitively, replaced wholesale.
        store.apply(
            ServerFrame.MemberUpdate(
                networkId = 1, target = "#lurker",
                member = Member(nick = "Bob", modes = listOf("v"), away = true, user = "rob", host = "new.example.org"),
            ),
        )

        val members = store.state.members[chanKey]!!
        assertEquals(listOf("alice", "Bob"), members.map { it.nick }, "patched in place, not re-appended")
        assertTrue(members[1].away)
        assertEquals("new.example.org", members[1].host)
    }

    @Test
    fun testAMemberUpdateForAnUnknownMemberOrBufferCreatesNothing() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "alice")))
        store.apply(ServerFrame.MemberUpdate(networkId = 1, target = "#lurker", member = Member(nick = "nobody")))
        store.apply(ServerFrame.MemberUpdate(networkId = 1, target = "#nowhere", member = Member(nick = "alice")))

        assertEquals(listOf("alice"), store.state.members[chanKey]!!.map { it.nick }, "resolve, never create")
        assertNull(store.state.buffers["1::#nowhere"], "no row should be conjured for a patch alone")
    }

    // MARK: - Speakers (lurker-ios#63)
    //
    // Who has spoken here and when. Seeded from the server's list — the only source that can
    // see past the loaded window — and kept current from live traffic, which is the only source
    // that can see past the seed. The `.smart` event tier reads it, so an entry that is missing
    // or stale is a line the reader never sees.

    private val t0: Instant = Instant.ofEpochSecond(1_784_548_800)

    private fun speech(id: Long, nick: String, date: Instant, isSelf: Boolean = false): Message =
        Message(id = id, type = EventType.Message, nick = nick, text = "hi", isSelf = isSelf, date = date)

    @Test
    fun testABacklogSeedsTheSpeakerMap() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = emptyList(), hydrated = true, append = false,
                speakers = listOf(Speaker(nick = "Alice", lastSpoke = t0)),
            ),
        )
        // Keyed case-insensitively, like every other nick lookup in the store.
        assertEquals(t0, store.state.speakers[chanKey]?.get("alice"))
    }

    /**
     * A history reply is the *primary* seed: a fresh connect ships shells, so a buffer's first
     * real speaker list arrives with the `latest` fetch its first open makes.
     */
    @Test
    fun testAHistoryReplySeedsTheSpeakerMap() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(
            history(
                events = emptyList(), mode = HistoryMode.Latest, hasMoreOlder = false, hasMoreNewer = false,
                speakers = listOf(Speaker(nick = "bob", lastSpoke = t0)),
            ),
        )
        assertEquals(t0, store.state.speakers[chanKey]?.get("bob"))
    }

    /**
     * A frame that never mentioned speakers says nothing about them. A shell deliberately omits
     * the field so a re-snapshot can't wipe a map the client already has — read as an empty
     * list it would instead claim nobody has spoken, hiding every filterable event.
     */
    @Test
    fun testAFrameWithoutSpeakersLeavesTheMapAlone() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = true),
                messages = emptyList(), hydrated = true, append = false,
                speakers = listOf(Speaker(nick = "alice", lastSpoke = t0)),
            ),
        )
        store.apply(channelBuffer(hydrated = false, messages = emptyList())) // a shell: speakers null
        assertEquals(t0, store.state.speakers[chanKey]?.get("alice"))
    }

    /**
     * The seed merges rather than replaces: the server's list was computed when it built the
     * frame, and anything said since arrived here as a live event. On a `history` reply — which
     * is fetched while the buffer is open — a wholesale replace would roll those back
     * mid-conversation.
     */
    @Test
    fun testASeedNeverWalksALiveEntryBackwards() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        val later = t0.plusSeconds(600)
        store.apply(live("#lurker", speech(1, "alice", date = later)))
        store.apply(
            history(
                events = emptyList(), mode = HistoryMode.Latest, hasMoreOlder = false, hasMoreNewer = false,
                speakers = listOf(Speaker(nick = "alice", lastSpoke = t0)),
            ),
        )
        assertEquals(later, store.state.speakers[chanKey]?.get("alice"))
    }

    /**
     * Live speech is recorded — the half no fetch supplies, and the whole basis of the
     * join-unmask rule. Notices and our own messages don't count, matching what the server's
     * `listSpeakers` selects: the question the filter asks is whether anyone *else* was talking.
     */
    @Test
    fun testOnlySomebodyElsesRealSpeechIsRecorded() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", speech(1, "alice", date = t0)))
        store.apply(live("#lurker", Message(id = 2, type = EventType.Action, nick = "bob", text = "waves", date = t0)))
        store.apply(live("#lurker", Message(id = 3, type = EventType.Notice, nick = "botty", text = "ad", date = t0)))
        store.apply(live("#lurker", speech(4, "me", date = t0, isSelf = true)))
        assertEquals(setOf("alice", "bob"), store.state.speakers[chanKey]?.nicks)
    }

    /**
     * Same seat below the id de-dupe as the membership fold, and for the same reason: a
     * backlog/live overlap replaying an old line must not restate a speaker's recency.
     */
    @Test
    fun testAReplayedMessageDoesNotWalkASpeakerBackwards() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        val later = t0.plusSeconds(600)
        store.apply(live("#lurker", speech(1, "alice", date = later)))
        // The same id, re-sent with the older timestamp it originally carried.
        store.apply(live("#lurker", speech(1, "alice", date = t0)))
        assertEquals(later, store.state.speakers[chanKey]?.get("alice"))
    }

    /**
     * A rename carries the entry with it, so someone who spoke and then went `_afk` doesn't
     * read as a stranger when they quit ten seconds later.
     * Our own nick has to follow a `/nick`, or everything that asks "is this me?" answers with
     * a nick we no longer have — including the `.smart` tier's own-churn exemption, which would
     * then hide our own part and quit lines. Target-less and silent: the visible line is the
     * ordinary `nick` event fanned out per channel.
     */
    @Test
    fun testAnOwnNickEventUpdatesTheNetworksNick() {
        val store = LurkerStore()
        seedMembers(store, emptyList())
        assertEquals("me", store.state.networks[1]?.nick)
        store.apply(FrameParser.parseWs("""{"kind":"irc","type":"own-nick","networkId":1,"nick":"me_afk"}"""))
        assertEquals("me_afk", store.state.networks[1]?.nick)
        assertEquals(emptyList(), store.state.messages["1::#lurker"] ?: emptyList(), "state, not a line")
    }

    @Test
    fun testANickChangeCarriesTheSpeakerEntry() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", speech(1, "alice", date = t0)))
        store.apply(
            live(
                "#lurker",
                Message(id = 2, type = EventType.Nick, nick = "alice", text = null, date = t0, newNick = "alice_afk"),
            ),
        )
        assertEquals(setOf("alice_afk"), store.state.speakers[chanKey]?.nicks)
        assertEquals(t0, store.state.speakers[chanKey]?.get("alice_afk"))
    }

    /**
     * Closed is absent: the reopen's hydrate brings a fresh server list, and a stale map would
     * decide which of the new backlog's events render.
     */
    @Test
    fun testClosingABufferForgetsItsSpeakers() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", speech(1, "alice", date = t0)))
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"))
        assertNull(store.state.speakers[chanKey])
    }

    // MARK: - Topic
    //
    // The server has three ways of saying what a channel's topic is, and the client needs
    // all three: the snapshot (above), a `channel-topic` ephemeral on join, and a `topic`
    // event when someone changes it. Miss one and the topic is right until it isn't.

    private fun topicEvent(id: Long, topic: String): Message =
        Message(id = id, type = EventType.Topic, nick = "alice", text = topic)

    @Test
    fun testAChannelTopicEventSetsTheTopicWithoutAddingALine() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#lurker", topic = "on join"))

        assertEquals("on join", store.state.buffers[chanKey]!!.topic)
        assertEquals(emptyList(), store.state.messages[chanKey], "RPL_TOPIC is silent — it prints nothing")
    }

    @Test
    fun testAChannelTopicForAnUnknownBufferIsANoOp() {
        val store = LurkerStore()
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#nowhere", topic = "x"))

        assertNull(store.state.buffers["1::#nowhere"], "no row should be conjured for a topic alone")
    }

    @Test
    fun testAChannelTopicFoldsTargetCaseLikeEveryOtherTarget() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#LURKER", topic = "cased"))

        assertEquals("cased", store.state.buffers[chanKey]!!.topic)
    }

    @Test
    fun testALiveTopicEventBothPrintsALineAndUpdatesTheTopic() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", topicEvent(5, "changed")))

        assertEquals("changed", store.state.buffers[chanKey]!!.topic)
        assertEquals(1, store.state.messages[chanKey]?.size, "a topic change is also a line")
    }

    /**
     * The one that bites. A `topic` event replayed by a backlog/live overlap must not
     * re-apply its stale topic over the current one — so the topic mutation has to sit
     * below the id de-dupe, not beside the parse. The Vue client documents the same trap.
     */
    @Test
    fun testAReplayedTopicEventDoesNotRevertTheTopic() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(live("#lurker", topicEvent(5, "old")))
        store.apply(live("#lurker", topicEvent(6, "new")))

        // id 5 arrives a second time (the overlap), carrying the topic it had back then.
        store.apply(live("#lurker", topicEvent(5, "old")))

        assertEquals("new", store.state.buffers[chanKey]!!.topic, "a replay must not revert the topic")
        assertEquals(2, store.state.messages[chanKey]?.size, "and must not re-print the line")
    }

    @Test
    fun testAClearedTopicReadsAsNoTopicRatherThanTheLastOne() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#lurker", topic = "something"))
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#lurker", topic = null))

        assertNull(store.state.buffers[chanKey]!!.topic)
    }

    // MARK: - Channel membership (lurker-ios#163)
    //
    // `channel-joined` and `channel-parted` are the server's word on whether we're in a channel
    // (§9.1). A part resolves and never creates; a join creates when it must and marks either way.
    // A backlog's `joined` says the same thing about a part this device never heard.

    private fun shell(joined: Boolean): ServerFrame =
        ServerFrame.Backlog(
            buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, joined = joined),
            messages = emptyList(), hydrated = false, append = false, speakers = null,
        )

    @Test
    fun testChannelPartedMarksTheRowPartedAndDropsItsMembersButKeepsItsHistory() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "me"), Member(nick = "alice")))
        store.apply(live("#lurker", msg(1, "hi")))

        store.apply(ServerFrame.ChannelParted(networkId = 1, target = "#lurker"))

        val state = store.state
        assertFalse(state.buffers[chanKey]!!.joined)
        assertNull(state.members[chanKey], "not in the channel, so nobody's list")
        assertEquals(listOf("hi"), state.messages[chanKey]?.map { it.text }, "history stays, and no line is added")
    }

    @Test
    fun testChannelPartedForAChannelWeHoldNoRowForCreatesNothing() {
        // A 470 forward parts the name we asked for, which never had a buffer.
        val store = LurkerStore()
        store.apply(ServerFrame.ChannelParted(networkId = 1, target = "#asked-for"))

        assertNull(store.state.buffers["1::#asked-for"])
        assertNull(store.state.messages["1::#asked-for"])
    }

    @Test
    fun testChannelJoinedMaterializesAJoinedRowWithoutALine() {
        val store = LurkerStore()
        store.apply(ServerFrame.ChannelJoined(networkId = 1, target = "#new"))

        val buffer = store.state.buffers["1::#new"]
        assertEquals(BufferKind.Channel, buffer?.kind)
        assertEquals(true, buffer?.joined)
        assertEquals(emptyList(), store.state.messages["1::#new"] ?: emptyList(), "membership is state, not a line")
    }

    /**
     * The case that matters most. A reconnect re-sends the network's buffers with `joined` read
     * before its rejoins land, so every channel arrives parted, and the rejoin's
     * `channel-joined` is the only thing that marks it joined again.
     */
    @Test
    fun testChannelJoinedMarksARowAReconnectShippedAsParted() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "me"), Member(nick = "alice")))

        store.apply(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
        store.apply(shell(joined = false))
        assertFalse(store.state.buffers[chanKey]!!.joined)
        assertNull(store.state.members[chanKey], "a backlog saying we're not in it drops the old list")

        store.apply(ServerFrame.ChannelJoined(networkId = 1, target = "#lurker"))
        assertTrue(store.state.buffers[chanKey]!!.joined)
    }

    @Test
    fun testABacklogSayingJoinedKeepsTheMemberList() {
        // The other direction: only a frame saying we're NOT in the channel drops the list. The
        // connect burst ships a live channel's backlog right after the snapshot that seeded it.
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "me"), Member(nick = "alice")))
        store.apply(shell(joined = true))

        assertEquals(listOf("me", "alice"), store.state.members[chanKey]?.map { it.nick })
    }

    @Test
    fun testAChannelJoinedMidBurstSurvivesTheClosingPrune() {
        // A rejoin landing inside a burst the server built before it: the burst can't name the
        // row, and the prune mustn't take it.
        val store = LurkerStore()
        store.apply(emptySnapshot)
        store.apply(ServerFrame.ChannelJoined(networkId = 1, target = "#new"))
        store.apply(ServerFrame.BacklogComplete)

        assertNotNull(store.state.buffers["1::#new"])
    }

    @Test
    fun testChannelMembershipFoldsTargetCaseAndKnowsEveryChannelSigil() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "me")))
        store.apply(ServerFrame.ChannelParted(networkId = 1, target = "#LURKER"))
        assertFalse(store.state.buffers[chanKey]!!.joined, "a part resolves case-insensitively")

        // `&local` is a channel too — never just `#`.
        store.apply(ServerFrame.ChannelJoined(networkId = 1, target = "&local"))
        assertEquals(BufferKind.Channel, store.state.buffers["1::&local"]?.kind)
    }

    /**
     * §9.1: a channel row comes from a persisted line or not at all. An ephemeral event can name
     * a channel we aren't in — a refused join's `join-error` targets the channel it refused —
     * and the row minted for it read joined, so `/join`'s wait switched the user into a channel
     * they'd been refused. (A persisted line still creates one: see
     * `testAChannelRowMintedByALiveLineReadsJoined`.)
     */
    @Test
    fun testAnEphemeralLineForAChannelWithNoRowCreatesNothing() {
        val store = LurkerStore()
        store.apply(
            live(
                "#secret",
                Message(id = 0, type = EventType.Other, nick = "irc.example.org", text = "Cannot join #secret"),
            ),
        )

        assertNull(store.state.buffers["1::#secret"])
        assertNull(store.state.messages["1::#secret"], "and no orphan line waiting for a row")
    }

    /**
     * The refusal's own frame (lurker-ios#57) keeps the lurker-ios#168 guarantee: nothing for a
     * channel we're not in.
     */
    @Test
    fun testAJoinErrorCreatesNoRow() {
        val store = LurkerStore()
        store.apply(ServerFrame.JoinError(networkId = 1, target = "#secret", reason = "This channel is invite-only."))

        assertNull(store.state.buffers["1::#secret"])
        assertNull(store.state.messages["1::#secret"])
    }

    @Test
    fun testIsPartedAsksTheStoredRowAndOnlyForChannels() {
        val store = LurkerStore()
        seedMembers(store, listOf(Member(nick = "me")))
        store.apply(live("bob", msg(3, "hey")))
        val lurker = BufferKey(networkId = 1, target = "#lurker")
        val bob = BufferKey(networkId = 1, target = "bob")
        val unknown = BufferKey(networkId = 1, target = "#favorite-with-no-row")

        assertFalse(store.state.isParted(lurker), "joined")
        assertFalse(store.state.buffers[bob.id]!!.joined, "precondition: a DM row keeps the default")
        assertFalse(store.state.isParted(bob), "a DM has no membership to lose")
        assertFalse(store.state.buffer(unknown).joined, "precondition: synthesized as false")
        assertFalse(store.state.isParted(unknown), "a synthesized default says nothing")

        store.apply(ServerFrame.ChannelParted(networkId = 1, target = "#lurker"))
        assertTrue(store.state.isParted(lurker))
    }

    private val aliceTyping: ServerFrame
        get() = ServerFrame.Typing(
            networkId = 1, target = "#lurker", nick = "alice", activity = TypingActivity.Active, userhost = null,
        )

    @Test
    fun testAPartClearsTheChannelsTypists() {
        // A typist from before the part stayed on screen until their lease ran out.
        val t0 = Instant.ofEpochSecond(1_000_000)
        val lurker = BufferKey(networkId = 1, target = "#lurker")
        var state = LurkerStore.reduce(ChatState(), shell(joined = true), now = t0)
        state = LurkerStore.reduce(state, aliceTyping, now = t0)
        assertEquals(listOf("alice"), state.typists(lurker, now = t0), "precondition")

        state = LurkerStore.reduce(state, ServerFrame.ChannelParted(networkId = 1, target = "#lurker"), now = t0)
        assertEquals(emptyList(), state.typists(lurker, now = t0))
    }

    @Test
    fun testOnlyABacklogSayingNotJoinedClearsTheChannelsTypists() {
        val t0 = Instant.ofEpochSecond(1_000_000)
        val lurker = BufferKey(networkId = 1, target = "#lurker")
        var state = LurkerStore.reduce(ChatState(), shell(joined = true), now = t0)
        state = LurkerStore.reduce(state, aliceTyping, now = t0)

        state = LurkerStore.reduce(state, shell(joined = true), now = t0)
        assertEquals(listOf("alice"), state.typists(lurker, now = t0), "a joined backlog keeps them")

        state = LurkerStore.reduce(state, shell(joined = false), now = t0)
        assertEquals(emptyList(), state.typists(lurker, now = t0))
    }

    /**
     * Our own join's line reaches us before its `channel-joined` and mints the row. At the
     * initializer's `false`, a fresh join read as parted until `channel-joined` landed.
     */
    @Test
    fun testAChannelRowMintedByALiveLineReadsJoined() {
        val store = LurkerStore()
        store.apply(live("#new", Message(id = 9, type = EventType.Join, nick = "me", text = null, isSelf = true)))

        val new = BufferKey(networkId = 1, target = "#new")
        assertEquals(true, store.state.buffers[new.id]?.joined)
        assertFalse(store.state.isParted(new))
    }

    @Test
    fun testRestNamesMergeOntoSnapshotCreatedNetworksWithoutDroppingLiveState() {
        val store = LurkerStore()
        // Snapshot arrives first (name unknown), then the REST roster supplies it.
        store.apply(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera", blocked = true))))

        val network = store.state.networks[1]!!
        assertEquals("Libera", network.name)
        assertEquals(ConnectionState.Connected, network.state, "live state must survive the name merge")
        assertTrue(network.blocked, "blocked is REST-only like the name, and merges in beside it")

        // Authoritative in both directions, like the name: an admin widening the allowlist
        // shows up on the next roster read rather than sticking for the life of the process.
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera"))))
        assertFalse(store.state.networks[1]!!.blocked)
        assertEquals(ConnectionState.Connected, store.state.networks[1]!!.state)
    }

    @Test
    fun testConnectionStatusMovesConnectingToConnectedToReconnecting() {
        val store = LurkerStore()
        assertEquals(SocketStatus.Connecting, store.state.connection)
        store.apply(ServerFrame.SocketOpen)
        assertEquals(SocketStatus.Connected, store.state.connection)
        // A drop after being connected is a reconnect, not a first connect.
        store.apply(ServerFrame.SocketClosed(reason = "bye", code = 1000))
        assertEquals(SocketStatus.Reconnecting, store.state.connection)
        // Still reconnecting across further failed attempts.
        store.apply(ServerFrame.SocketClosed(reason = "again", code = null))
        assertEquals(SocketStatus.Reconnecting, store.state.connection)
    }

    @Test
    fun testBufferForKeyReturnsTheStoredRowWhenThereIsOne() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))

        val found = store.state.buffer(BufferKey(networkId = 1, target = "#LURKER"))
        assertTrue(found.hydrated, "the real row, not a fresh synthetic one")
    }

    /**
     * One classification, shared with the server: all FOUR channel sigils (`#&+!`) count,
     * matching `kindForTarget` on the wire's other end. This used to pin `!foo` as `.dm`
     * (the two-sigil rule) — which meant a favorited `!foo` filed under Friends with a
     * presence dot while the server's buffer row said channel. Agreement is the contract
     * now; a site that diverges gives its screen a member list the store row (and the
     * server) would never agree with.
     */
    @Test
    fun testBufferForKeySynthesizesWithTheSameKindClassificationTheStoreUses() {
        val state = ChatState()
        assertEquals(BufferKind.Channel, state.buffer(BufferKey(networkId = 1, target = "#lurker")).kind)
        assertEquals(BufferKind.Channel, state.buffer(BufferKey(networkId = 1, target = "&local")).kind)
        assertEquals(BufferKind.Channel, state.buffer(BufferKey(networkId = 1, target = "+loose")).kind)
        assertEquals(BufferKind.Channel, state.buffer(BufferKey(networkId = 1, target = "!foo")).kind)
        assertEquals(BufferKind.Dm, state.buffer(BufferKey(networkId = 1, target = "bob")).kind)
        assertEquals(BufferKind.System, state.buffer(BufferKey(networkId = null, target = Buffer.systemTarget)).kind)
    }

    /**
     * A synthesized buffer must be unhydrated, or the screen built from it would skip
     * asking for history and sit empty forever.
     */
    @Test
    fun testASynthesizedBufferIsUnhydratedSoItsScreenStillFetches() {
        val buffer = ChatState().buffer(BufferKey(networkId = 1, target = "#lurker"))
        assertFalse(buffer.hydrated)
        assertEquals("#lurker", buffer.target, "case is preserved, unlike BufferKey.id")
    }

    @Test
    fun testADropBeforeTheFirstOpenStaysConnectingNotReconnecting() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketClosed(reason = "refused", code = null))
        assertEquals(SocketStatus.Connecting, store.state.connection)
    }

    /**
     * lurker-ios#17: the close that follows a refusal must not read as a drop, or the banner
     * would say "Reconnecting…" over a server that will never take this build.
     */
    @Test
    fun testASocketClosedOverAnIncompatibleServerStaysIncompatible() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        store.setIncompatible(Incompatibility.AppTooOld)
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertEquals(SocketStatus.Incompatible(Incompatibility.AppTooOld), store.state.connection)
    }

    @Test
    fun testAServerThatTakesThisBuildAgainStartsAFreshConnect() {
        val store = LurkerStore()
        store.setIncompatible(Incompatibility.ServerTooOld)
        store.clearIncompatible()
        assertEquals(SocketStatus.Connecting, store.state.connection)
        // Only an incompatible socket is touched.
        store.apply(ServerFrame.SocketOpen)
        store.clearIncompatible()
        assertEquals(SocketStatus.Connected, store.state.connection)
    }

    @Test
    fun testSignOutForgetsAnIncompatibleServer() {
        val store = LurkerStore()
        store.setIncompatible(Incompatibility.AppTooOld)
        store.reset()
        assertEquals(SocketStatus.Connecting, store.state.connection, "the next sign-in may be another server")
    }

    @Test
    fun testMaxEventIdTracksTheHighestPersistedIdButIgnoresTheSystemBuffer() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(10, "a"), msg(7, "b"))))
        assertEquals(10L, store.state.maxEventId)

        store.apply(live("#lurker", msg(15, "c")))
        assertEquals(15L, store.state.maxEventId)

        // System-buffer ids are a separate space and must not move the resume cursor.
        store.apply(live(":system:", msg(9999, "sys"), networkId = null))
        assertEquals(15L, store.state.maxEventId)

        // An older id doesn't lower the watermark.
        store.apply(live("#lurker", msg(3, "old")))
        assertEquals(15L, store.state.maxEventId)
    }

    /**
     * ⚠⚠ This asserted the opposite until lurker-ios#128, and it was never exercised in the
     * field: iOS put no `clientId` on a send, so `wsHub` never emitted `send-result` and this
     * reducer never ran. The cost of routing it to `state.error` — which presents a modal ALERT
     * — was invisible for exactly that reason. Since lurker#809's writable-connection gate,
     * `ok:false` is the ordinary outcome of any reconnect, so that would now be a modal per send
     * for the length of an outage, on top of a ConnectionBanner already saying "Reconnecting…".
     *
     * The frame's job is now the restore (see `UnsentCorrelator`), and announcing is left to the
     * bare `error` frames the server sends alongside for the failures that warrant words.
     */
    @Test
    fun testASendResultDoesNotRaiseAnAlertOfItsOwn() {
        val store = LurkerStore()
        store.apply(ServerFrame.SendResult(clientId = "c1", ok = false, error = "not-connected"))
        assertNull(store.state.error)

        // ⚠ And it does not disturb an error already standing. `account-paused` arrives as BOTH a
        // bare `error` frame (prose, shown to the user) and a `send-result` (a code) — letting the
        // ack win would replace the sentence with the slug.
        store.apply(ServerFrame.ServerError("account paused"))
        store.apply(ServerFrame.SendResult(clientId = "c2", ok = false, error = "account-paused"))
        assertEquals("account paused", store.state.error)

        // ⚠ Nor does a SUCCESS clear one. It used to, which meant an unrelated error frame was
        // wiped off the screen by the next send that happened to work.
        store.apply(ServerFrame.SendResult(clientId = "c3", ok = true, error = null))
        assertEquals("account paused", store.state.error)
    }

    // MARK: - Reachability

    @Test
    fun testReachabilityDefaultsToTrue() {
        // Assuming offline until told otherwise would paint every fresh launch red.
        assertTrue(LurkerStore().state.reachable)
    }

    @Test
    fun testReachabilitySurvivesReset() {
        // It's a fact about the device, not the session, and nothing re-reports it on
        // sign-out — so a reset back to the `true` default would leave an offline phone
        // claiming it's online, with no path monitor callback coming to correct it.
        val store = LurkerStore()
        store.setReachable(false)
        store.reset()
        assertFalse(store.state.reachable)
    }

    @Test
    fun testResetStillClearsEverythingSessionScoped() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        store.setReachable(false)
        store.reset()
        assertTrue(store.state.buffers.isEmpty())
        assertTrue(store.state.messages.isEmpty())
        assertEquals(0L, store.state.maxEventId)
    }

    @Test
    fun testBufferClosedDropsTheBufferAndEverythingKeyedToIt() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        store.apply(ServerFrame.ChannelMembers(networkId = 1, target = "#lurker", members = listOf(Member(nick = "alice"))))
        assertNotNull(store.state.buffers[chanKey])

        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"))

        // Closed is absent, not flagged: the server keeps the history, so a reopen restores
        // it in full and there's nothing local worth holding on to.
        assertNull(store.state.buffers[chanKey], "the row is gone")
        assertNull(store.state.messages[chanKey], "and so are its messages")
        assertNull(store.state.members[chanKey], "a reopen must not inherit a stale nicklist")
    }

    @Test
    fun testBufferClosedFoldsCaseLikeEveryOtherTargetLookup() {
        // The server echoes whatever casing it holds, which needn't match what we stored.
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))

        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#LURKER"))

        assertNull(store.state.buffers[chanKey], "differently-cased close still lands")
    }

    @Test
    fun testBufferClosedLeavesOtherBuffersAlone() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#other", kind = BufferKind.Channel, hydrated = true),
                messages = listOf(msg(2, "elsewhere")),
                hydrated = true,
                append = false, speakers = null,
            ),
        )

        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"))

        assertNull(store.state.buffers[chanKey])
        assertNotNull(store.state.buffers["1::#other"], "an unrelated buffer survives")
        assertEquals(listOf("elsewhere"), store.state.messages["1::#other"]?.map { it.text })
    }

    // MARK: - buffer-renamed (§9.7)

    /** A hydrated DM with an id, the fixture every rename test starts from. */
    private fun dmBuffer(
        target: String,
        bufferId: Int?,
        hydrated: Boolean = true,
        messages: List<Message> = emptyList(),
    ): ServerFrame =
        ServerFrame.Backlog(
            buffer = Buffer(networkId = 1, target = target, kind = BufferKind.Dm, hydrated = hydrated, bufferId = bufferId),
            messages = messages,
            hydrated = hydrated,
            append = false, speakers = null,
        )

    @Test
    fun testBufferRenamedMovesEverythingToTheNewKeyAndKeepsTheId() {
        val store = LurkerStore()
        store.apply(dmBuffer("alice", bufferId = 7, messages = listOf(msg(1, "hi"))))
        store.apply(
            ServerFrame.Typing(
                networkId = 1, target = "alice", nick = "alice",
                activity = TypingActivity.from("active"), userhost = null,
            ),
        )

        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "alice2", bufferId = 7,
                merged = false, mergedFromBufferId = null,
            ),
        )

        val state = store.state
        assertNull(state.buffers["1::alice"], "the old key is gone")
        val moved = state.buffers["1::alice2"]
        assertEquals("alice2", moved?.target)
        assertEquals(7, moved?.bufferId, "identity survives the rename — that's the point")
        assertEquals(true, moved?.hydrated, "a plain rename moved history, it didn't change it")
        assertEquals(listOf("hi"), state.messages["1::alice2"]?.map { it.text })
        assertNull(state.messages["1::alice"])
        assertNull(state.typing["1::alice"], "typing follows too — nothing stays under the dead key")
        assertEquals("1::alice2", state.keysById[7], "the id index follows the move")
    }

    @Test
    fun testBufferRenamedMergeDropsTheAbsorbedRowAndDehydratesTheSurvivor() {
        val store = LurkerStore()
        // The stale side already holds the new name; the live side is being renamed onto it.
        store.apply(dmBuffer("alice_away", bufferId = 9, messages = listOf(msg(1, "ancient"))))
        store.apply(dmBuffer("alice", bufferId = 7, messages = listOf(msg(5, "current"))))

        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "alice_away", bufferId = 7,
                merged = true, mergedFromBufferId = 9,
            ),
        )

        val state = store.state
        assertNull(state.buffers["1::alice"])
        val survivor = state.buffers["1::alice_away"]
        assertEquals(7, survivor?.bufferId, "the SOURCE survives — the absorbed id is dead")
        // The merged history interleaved server-side; guessing at it locally would show a
        // wrong order until the next fetch corrected it. Wipe and let hydrate refetch.
        assertEquals(false, survivor?.hydrated, "de-hydrated so the ordinary path refetches")
        assertEquals(true, survivor?.hasMoreOlder)
        assertEquals(emptyList(), state.messages["1::alice_away"], "local slice wiped, not guessed at")
        assertNull(state.keysById[9], "the absorbed id un-indexes")
        assertEquals("1::alice_away", state.keysById[7])
    }

    @Test
    fun testBufferRenamedCasingOnlyAdoptsTheDisplayNameInPlace() {
        // Same storage key (BufferKey folds case), so nothing moves — but the display
        // name is the whole frame, and dropping it would leave the title stale.
        val store = LurkerStore()
        store.apply(dmBuffer("alice", bufferId = 7, messages = listOf(msg(1, "hi"))))

        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "Alice", bufferId = 7,
                merged = false, mergedFromBufferId = null,
            ),
        )

        val state = store.state
        assertEquals("Alice", state.buffers["1::alice"]?.target)
        assertEquals(true, state.buffers["1::alice"]?.hydrated, "nothing else changed")
        assertEquals(listOf("hi"), state.messages["1::alice"]?.map { it.text }, "history untouched")
    }

    @Test
    fun testBufferRenamedMergeWithoutTheSourceConvertsTheHeldRowInsteadOfDroppingIt() {
        // The out-of-order window: a live rename can beat the source DM's backlog frame
        // mid-burst, so this device holds only the row under the NEW name — the absorbed
        // one. Dropping it would vanish the buffer under a reader; converting it into the
        // survivor is what the absorbed-seat self-heal produces when both rows are held.
        val store = LurkerStore()
        store.apply(dmBuffer("alice_away", bufferId = 9, messages = listOf(msg(1, "ancient"))))

        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "alice_away", bufferId = 7,
                merged = true, mergedFromBufferId = 9,
            ),
        )

        val state = store.state
        val survivor = state.buffers["1::alice_away"]
        assertNotNull(survivor, "the held row converts — it must not vanish")
        assertEquals(7, survivor.bufferId, "it adopts the survivor's id, not the dead one")
        assertEquals(false, survivor.hydrated, "merged history still means refetch")
        assertEquals(emptyList(), state.messages["1::alice_away"])
        assertNull(state.keysById[9], "the dead id un-indexes")
        assertEquals("1::alice_away", state.keysById[7])
    }

    @Test
    fun testBufferRenamedForAnUnknownSourceIsANoOp() {
        val store = LurkerStore()
        store.apply(dmBuffer("bob", bufferId = 3))

        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "nobody", to = "somebody", bufferId = 99,
                merged = false, mergedFromBufferId = null,
            ),
        )

        assertNull(store.state.buffers["1::somebody"], "nothing to rename, nothing minted")
        assertNotNull(store.state.buffers["1::bob"], "bystanders untouched")
    }

    @Test
    fun testBufferRenamedMidBurstKeepsTheSurvivorThroughTheClosingPrune() {
        // The burst named the OLD key; if the rename didn't swap the burst record, the
        // terminal prune would read the new key as "unlisted" and delete the buffer the
        // user is looking at.
        val store = LurkerStore()
        store.apply(emptySnapshot)
        store.apply(dmBuffer("alice", bufferId = 7, messages = listOf(msg(1, "hi"))))
        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "alice2", bufferId = 7,
                merged = false, mergedFromBufferId = null,
            ),
        )
        store.apply(ServerFrame.BacklogComplete)

        assertNotNull(store.state.buffers["1::alice2"], "renamed mid-burst still stands")
        assertEquals(listOf("hi"), store.state.messages["1::alice2"]?.map { it.text })
    }

    @Test
    fun testBacklogNeverUnlearnsABufferId() {
        // A frame from a pre-id server (or a synthesized resync shell) carries no id;
        // that's not a retraction of the id a real frame stated.
        val store = LurkerStore()
        store.apply(dmBuffer("alice", bufferId = 7))
        store.apply(dmBuffer("alice", bufferId = null, hydrated = false))

        assertEquals(7, store.state.buffers["1::alice"]?.bufferId)
        assertEquals("1::alice", store.state.keysById[7])
    }

    @Test
    fun testBufferClosedPrunesTheIdIndex() {
        // A stale id→key entry would let a later rename "follow" a buffer to a key that
        // no longer holds a row.
        val store = LurkerStore()
        store.apply(dmBuffer("alice", bufferId = 7))
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "alice"))

        assertNull(store.state.keysById[7])
    }

    @Test
    fun testBacklogCompletePrunesABufferTheBurstNoLongerLists() {
        // The offline half of buffer-closed, and the common one on a phone: the close
        // happened while this device wasn't connected, so no `buffer-closed` ever arrived.
        // The burst enumerates only OPEN buffers, so the omission is the signal.
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        assertNotNull(store.state.buffers[chanKey])

        // A fresh burst that doesn't mention #lurker.
        store.apply(emptySnapshot)
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#other", kind = BufferKind.Channel, hydrated = true),
                messages = emptyList(), hydrated = true, append = false, speakers = null,
            ),
        )
        store.apply(ServerFrame.BacklogComplete)

        assertNull(store.state.buffers[chanKey], "unlisted buffer is no longer open")
        assertNull(store.state.messages[chanKey])
        assertNotNull(store.state.buffers["1::#other"], "the listed one survives")
    }

    @Test
    fun testBacklogCompleteKeepsEverythingTheBurstDidList() {
        val store = LurkerStore()
        store.apply(emptySnapshot)
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        store.apply(ServerFrame.BacklogComplete)

        assertNotNull(store.state.buffers[chanKey])
        assertEquals(listOf("hi"), texts(store))
    }

    @Test
    fun testBacklogCompleteWithNoBurstBehindItPrunesNothing() {
        // A stray terminal frame must not read as "the server listed nothing" and wipe the
        // roster — only a burst that actually started (a `snapshot` frame) opens the window.
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        store.apply(ServerFrame.BacklogComplete)

        assertNotNull(store.state.buffers[chanKey], "no snapshot, no prune")
    }

    @Test
    fun testADmMaterializedMidBurstSurvivesTheClosingPrune() {
        // The row was created after the server enumerated the roster, so the burst can't
        // name it. Pruning it would drop a DM the moment it arrived.
        val store = LurkerStore()
        store.apply(emptySnapshot)
        store.apply(live("bob", msg(7, "hey")))
        store.apply(ServerFrame.BacklogComplete)

        assertNotNull(store.state.buffers["1::bob"], "a DM that landed mid-burst stays")
        assertEquals(listOf("hey"), store.state.messages["1::bob"]?.map { it.text })
    }

    @Test
    fun testRosterSettledOnlyOnceABurstHasFinishedAndNoneIsInFlight() {
        // What a by-key screen (launch restore, notification tap) needs before it can read
        // absence as "this buffer isn't open" instead of "its frame hasn't arrived".
        val store = LurkerStore()
        assertFalse(store.state.rosterSettled, "nothing has arrived yet")

        store.apply(emptySnapshot)
        assertFalse(store.state.rosterSettled, "burst in flight")

        store.apply(ServerFrame.BacklogComplete)
        assertTrue(store.state.rosterSettled, "the server listed everything it has")

        // A later burst reopens the window: `backlogComplete` latches for the session, so
        // it alone would keep claiming proof while `buffers` is mid-rebuild.
        store.apply(emptySnapshot)
        assertTrue(store.state.backlogComplete, "still latched...")
        assertFalse(store.state.rosterSettled, "...but the roster is being rebuilt")
    }

    @Test
    fun testBurstGenerationAdvancesOncePerSnapshot() {
        // What a one-shot request keys off instead of `connection`. A socket can die and be
        // replaced without `connection` ever leaving `.connected` (a close arriving after
        // the replacement is dropped so it can't clobber the live socket), which loses any
        // request written to the dying socket with no observable state change. A burst is
        // unmissable — every reconnect produces one.
        val store = LurkerStore()
        assertEquals(0, store.state.burstGeneration)

        store.apply(emptySnapshot)
        assertEquals(1, store.state.burstGeneration)
        store.apply(ServerFrame.BacklogComplete)
        assertEquals(1, store.state.burstGeneration, "only a snapshot advances it")

        // A reconnect's burst — the moment anything asked over the old socket is void.
        store.apply(emptySnapshot)
        assertEquals(2, store.state.burstGeneration)
    }

    @Test
    fun testReachabilityIsIndependentOfTheSocket() {
        // Two different truths: the socket only ever reports connecting/connected/
        // reconnecting, so it can never say "there is no internet".
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        store.setReachable(false)
        assertEquals(SocketStatus.Connected, store.state.connection, "the socket doesn't know yet")
        assertFalse(store.state.reachable)
        assertEquals(
            StatusLight.Bad,
            StatusLight.of(reachable = store.state.reachable, connection = store.state.connection, network = null),
            "and the light believes the device over the stale socket",
        )
    }

    // MARK: - Bookmarks

    /**
     * The set is seeded from the message rows themselves, because there is no bookmark
     * snapshot in the connect burst — the server used to send every saved id on every
     * connect, which is the one piece of connect state that grows without bound.
     */
    @Test
    fun testBookmarkedFlagOnBacklogRowsSeedsTheSet() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "plain"), saved(msg(2, "kept")))))
        assertTrue(store.state.isBookmarked(2))
        assertFalse(store.state.isBookmarked(1))
    }

    /**
     * A later page knows only its own slice, so its silence about an id must not evict what
     * an earlier one established — otherwise scrolling up would quietly unlight every
     * bookmark above the fold.
     */
    @Test
    fun testALaterPageDoesNotEvictBookmarksItDoesNotMention() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(saved(msg(2, "kept")))))
        store.apply(
            history(events = listOf(msg(1, "older")), mode = HistoryMode.Before, hasMoreOlder = false, hasMoreNewer = false),
        )
        assertTrue(store.state.isBookmarked(2), "the older page said nothing about id 2")
    }

    /**
     * A row that comes back UNFLAGGED is authoritative for itself — the server omits the
     * field when false. This is the only way an unsave made elsewhere while this client was
     * offline ever lands: the echo was missed, and the reconnect backlog carries the truth.
     */
    @Test
    fun testAnUnflaggedRowClearsItsOwnBookmark() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(saved(msg(2, "kept")))))
        assertTrue(store.state.isBookmarked(2))
        // The same line comes back on reconnect, no longer saved.
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(2, "kept"))))
        assertFalse(store.state.isBookmarked(2))
    }

    /**
     * System-buffer rows have their own id sequence, overlapping the message ids this set is
     * keyed by, and never carry the flag — so reconciling against them would clear a real
     * bookmark that happens to share an id.
     */
    @Test
    fun testSystemBufferPagesCannotClearBookmarks() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(saved(msg(2, "kept")))))
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = null, target = ":system:", kind = BufferKind.System, hydrated = true),
                messages = listOf(msg(2, "an unrelated system line that happens to be id 2")),
                hydrated = true,
                append = false, speakers = null,
            ),
        )
        assertTrue(store.state.isBookmarked(2))
    }

    /**
     * The saved-messages feed carries no per-row flag — every row in it is saved — so it
     * folds in additively, and in one mutation rather than one per row.
     */
    @Test
    fun testNoteBookmarkedIdsSeedsTheSet() {
        val store = LurkerStore()
        store.noteBookmarked(ids = listOf(4, 5))
        assertTrue(store.state.isBookmarked(4))
        assertTrue(store.state.isBookmarked(5))
    }

    /**
     * The echo is the source of truth for a toggle — it's fanned to every socket including
     * the one that asked, so nothing renders optimistically.
     */
    @Test
    fun testBookmarkUpdatedAddsAndRemoves() {
        val store = LurkerStore()
        store.apply(ServerFrame.BookmarkUpdated(messageId = 7, saved = true))
        assertTrue(store.state.isBookmarked(7))
        store.apply(ServerFrame.BookmarkUpdated(messageId = 7, saved = false))
        assertFalse(store.state.isBookmarked(7))
    }

    /**
     * Removing an id the set never held is normal, not a lost update: without a connect
     * snapshot the set only knows the lines this session has loaded, so an unsave made on
     * another device for an unloaded buffer arrives as exactly this.
     */
    @Test
    fun testRemovingAnUnknownBookmarkIsHarmless() {
        val store = LurkerStore()
        store.apply(ServerFrame.BookmarkUpdated(messageId = 999, saved = false))
        assertFalse(store.state.isBookmarked(999))
        assertTrue(store.state.bookmarkedIds.isEmpty())
    }

    private fun saved(message: Message): Message =
        Message(
            id = message.id, type = message.type, nick = message.nick, text = message.text,
            isSelf = message.isSelf, bookmarked = true,
        )

    // Port-only: what the flow does that LurkerKit's subject does not, and the value semantics a
    // Swift struct gets for free.

    /**
     * A `StateFlow` drops a state equal to the one it holds, so a frame that changes nothing
     * wakes no subscriber — where LurkerKit's `CurrentValueSubject` re-sends the same state for
     * every frame. Pinned so a screen written against one behaviour can't quietly be relying on
     * the other.
     */
    @Test
    fun testAFrameThatChangesNothingPublishesNothing() = runTest {
        val store = LurkerStore()
        val seen = mutableListOf<ChatState>()
        backgroundScope.launch { store.statePublisher.collect { seen.add(it) } }
        runCurrent()
        assertEquals(1, seen.size, "the current state, on subscribe")

        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        runCurrent()
        assertEquals(2, seen.size)

        store.apply(ServerFrame.SendResult(clientId = "c1", ok = false, error = "not-connected"))
        runCurrent()
        store.apply(ServerFrame.JoinError(networkId = 1, target = "#secret", reason = "This channel is invite-only."))
        runCurrent()
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#nowhere", topic = "x"))
        runCurrent()
        store.clearError()
        runCurrent()
        assertEquals(2, seen.size, "nothing moved, so nothing was published")
    }

    /**
     * Swift hands `reduce` a copy of the state; Kotlin hands it a reference. Every fold builds
     * new maps and lists rather than writing into the ones it was given, so a state a screen is
     * still holding never changes underneath it.
     */
    @Test
    fun testReducingNeverChangesTheStateItWasHanded() {
        val t0 = Instant.ofEpochSecond(1_000_000)
        var start = LurkerStore.reduce(ChatState(), emptySnapshot, now = t0)
        start = LurkerStore.reduce(start, channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))), now = t0)
        start = LurkerStore.reduce(start, aliceTyping, now = t0)
        val before = start.toString()

        val frames = listOf(
            live("#lurker", msg(2, "new")),
            channelBuffer(hydrated = true, messages = listOf(saved(msg(1, "hi")))),
            history(events = listOf(msg(0, "x")), mode = HistoryMode.Around, hasMoreOlder = true, hasMoreNewer = true),
            ServerFrame.ReadState(networkId = 1, target = "#lurker", lastReadId = 1, unread = 0, highlights = 0),
            ServerFrame.ChannelMembers(networkId = 1, target = "#lurker", members = listOf(Member(nick = "alice"))),
            ServerFrame.Typing(networkId = 1, target = "#lurker", nick = "alice", activity = null, userhost = null),
            ServerFrame.BufferRenamed(
                networkId = 1, from = "#lurker", to = "#lounge", bufferId = null, merged = false, mergedFromBufferId = null,
            ),
            ServerFrame.BufferClosed(networkId = 1, target = "#lurker"),
            ServerFrame.Networks(emptyList()),
            ServerFrame.BacklogComplete,
            ServerFrame.SocketClosed(reason = null, code = null),
        )
        for (frame in frames) {
            LurkerStore.reduce(start, frame, now = t0)
            assertEquals(before, start.toString(), "$frame wrote into the state it was handed")
        }
    }
}
