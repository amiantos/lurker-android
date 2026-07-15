// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.store

import net.amiantos.lurker.client.ChannelSnapshot
import net.amiantos.lurker.client.NetworkSnapshot
import net.amiantos.lurker.client.ServerFrame
import net.amiantos.lurker.model.Buffer
import net.amiantos.lurker.model.BufferKind
import net.amiantos.lurker.model.ConnectionState
import net.amiantos.lurker.model.EventType
import net.amiantos.lurker.model.Member
import net.amiantos.lurker.model.Message
import net.amiantos.lurker.model.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's frame-folding is the tricky, pure-logic core of the client — shell
 * vs. hydrated backlog, live de-dupe, snapshot merge, name merge. Drive it with
 * hand-built frames (no JSON), asserting the folded state.
 */
class LurkerStoreTest {

    private fun msg(id: Long, text: String, self: Boolean = false) =
        Message(id = id, type = EventType.Message, nick = "alice", text = text, self = self)

    private val chanKey = "1::#lurker"

    private fun channelBuffer(hydrated: Boolean, messages: List<Message>) =
        ServerFrame.Backlog(
            buffer = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, hydrated = hydrated),
            messages = messages,
            hydrated = hydrated,
        )

    @Test
    fun `shell registers the buffer but leaves it empty and unhydrated`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))

        val s = store.state.value
        assertTrue("buffer should be listed", s.buffers.containsKey(chanKey))
        assertFalse("shell is not hydrated", s.buffers[chanKey]!!.hydrated)
        assertEquals(emptyList<Message>(), s.messages[chanKey])
    }

    @Test
    fun `hydrated backlog fills the buffer and replaces messages wholesale`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"), msg(2, "there"))))

        val s = store.state.value
        assertTrue(s.buffers[chanKey]!!.hydrated)
        assertEquals(listOf("hi", "there"), s.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun `a later shell never un-hydrates or wipes an already-read buffer`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi"))))
        // A resync ships the buffer again as a shell.
        store.apply(channelBuffer(hydrated = false, messages = emptyList()))

        val s = store.state.value
        assertTrue("hydration must stick", s.buffers[chanKey]!!.hydrated)
        assertEquals(listOf("hi"), s.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun `live events append but de-dupe against a persisted id already present`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(5, "hi"))))
        // The same id arrives live (backlog/live overlap) — must not double.
        store.apply(ServerFrame.Live(1, "#lurker", msg(5, "hi")))
        store.apply(ServerFrame.Live(1, "#lurker", msg(6, "new")))

        val texts = store.state.value.messages[chanKey]!!.map { it.text }
        assertEquals(listOf("hi", "new"), texts)
    }

    @Test
    fun `ephemeral live events (id 0) always append even when identical`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = emptyList()))
        store.apply(ServerFrame.Live(1, "#lurker", msg(0, "poke")))
        store.apply(ServerFrame.Live(1, "#lurker", msg(0, "poke")))

        assertEquals(2, store.state.value.messages[chanKey]!!.size)
    }

    @Test
    fun `a live event for an unknown target materializes a buffer row`() {
        val store = LurkerStore()
        // A DM from bob arrives with no prior buffer (no snapshot, no backlog).
        store.apply(ServerFrame.Live(1, "bob", msg(7, "hey")))

        val s = store.state.value
        val key = "1::bob"
        assertTrue("the new DM must appear in the buffer list", s.buffers.containsKey(key))
        assertEquals(BufferKind.Dm, s.buffers[key]!!.kind)
        assertFalse("unhydrated so tapping fetches history", s.buffers[key]!!.hydrated)
        assertEquals(listOf("hey"), s.messages[key]!!.map { it.text })
    }

    @Test
    fun `differently-cased targets fold to the same buffer`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "hi")))) // "#lurker"
        store.apply(ServerFrame.Live(1, "#LURKER", msg(2, "yo"))) // upper-cased target

        val s = store.state.value
        assertEquals("must not split into a second buffer", 1, s.buffers.size)
        assertEquals(listOf("hi", "yo"), s.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun `a resume gap slice appends and de-dupes, a reset replaces`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(1, "a"), msg(2, "b"))))

        // reset:false gap — id 2 overlaps (drop), id 3 is new (append).
        store.apply(
            ServerFrame.Backlog(
                Buffer(1, "#lurker", BufferKind.Channel, hydrated = true),
                messages = listOf(msg(2, "b"), msg(3, "c")),
                hydrated = true,
                append = true,
            ),
        )
        assertEquals(listOf("a", "b", "c"), store.state.value.messages[chanKey]!!.map { it.text })

        // reset (oversized gap) replaces wholesale.
        store.apply(
            ServerFrame.Backlog(
                Buffer(1, "#lurker", BufferKind.Channel, hydrated = true),
                messages = listOf(msg(9, "z")),
                hydrated = true,
                append = false,
            ),
        )
        assertEquals(listOf("z"), store.state.value.messages[chanKey]!!.map { it.text })
    }

    @Test
    fun `snapshot seeds channel buffers, members, and network live state`() {
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
                                members = listOf(Member("alice", modes = listOf("o")), Member("bob")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val s = store.state.value
        assertEquals(ConnectionState.Connected, s.networks[1]!!.state)
        assertEquals("me", s.networks[1]!!.nick)
        assertTrue(s.buffers[chanKey]!!.joined)
        assertEquals(BufferKind.Channel, s.buffers[chanKey]!!.kind)
        assertEquals(listOf("alice", "bob"), s.members[chanKey]!!.map { it.nick })
    }

    @Test
    fun `REST names merge onto snapshot-created networks without dropping live state`() {
        val store = LurkerStore()
        // Snapshot arrives first (name unknown), then the REST roster supplies it.
        store.apply(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(1, ConnectionState.Connected, "me", channels = emptyList())),
            ),
        )
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera"))))

        val net = store.state.value.networks[1]!!
        assertEquals("Libera", net.name)
        assertEquals("live state must survive the name merge", ConnectionState.Connected, net.state)
    }

    @Test
    fun `connection status moves connecting to connected to reconnecting`() {
        val store = LurkerStore()
        assertEquals(SocketStatus.Connecting, store.state.value.connection)
        store.apply(ServerFrame.SocketOpen)
        assertEquals(SocketStatus.Connected, store.state.value.connection)
        // A drop after being connected is a reconnect, not a first connect.
        store.apply(ServerFrame.SocketClosed("bye", 1000))
        assertEquals(SocketStatus.Reconnecting, store.state.value.connection)
        // Still reconnecting across further failed attempts.
        store.apply(ServerFrame.SocketClosed("again", null))
        assertEquals(SocketStatus.Reconnecting, store.state.value.connection)
    }

    @Test
    fun `a drop before the first open stays Connecting, not Reconnecting`() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketClosed("refused", null))
        assertEquals(SocketStatus.Connecting, store.state.value.connection)
    }

    @Test
    fun `maxEventId tracks the highest persisted id but ignores the system buffer`() {
        val store = LurkerStore()
        store.apply(channelBuffer(hydrated = true, messages = listOf(msg(10, "a"), msg(7, "b"))))
        assertEquals(10L, store.state.value.maxEventId)

        store.apply(ServerFrame.Live(1, "#lurker", msg(15, "c")))
        assertEquals(15L, store.state.value.maxEventId)

        // System-buffer ids are a separate space and must not move the resume cursor.
        store.apply(ServerFrame.Live(null, ":system:", msg(9999, "sys")))
        assertEquals(15L, store.state.value.maxEventId)

        // An older id doesn't lower the watermark.
        store.apply(ServerFrame.Live(1, "#lurker", msg(3, "old")))
        assertEquals(15L, store.state.value.maxEventId)
    }

    @Test
    fun `a failed send-result surfaces its error, an ok one does not`() {
        val store = LurkerStore()
        store.apply(ServerFrame.SendResult(clientId = "c1", ok = false, error = "account-paused"))
        assertEquals("account-paused", store.state.value.error)

        store.apply(ServerFrame.SocketOpen) // clears error
        assertNull(store.state.value.error)
        store.apply(ServerFrame.SendResult(clientId = "c2", ok = true, error = null))
        assertNull(store.state.value.error)
    }
}
