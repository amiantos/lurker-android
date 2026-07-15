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
    fun `socket open and close toggle the connected flag`() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        assertTrue(store.state.value.connected)
        store.apply(ServerFrame.SocketClosed("bye", 1000))
        assertFalse(store.state.value.connected)
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
