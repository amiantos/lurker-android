// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.SocketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writes made with no socket (the client sweep's offline-writes batch, L02/L14/L16/L29). Nothing
 * queues a verb behind a dropped socket, so each of these has to say it went nowhere — a command
 * comes back to the composer, a close leaves its row — rather than looking like it worked.
 *
 * No test has a socket, so every send here goes nowhere: the state is what a phone in airplane
 * mode sees once "Reconnecting…" shows.
 */
class OfflineWritesTests {

    private val channel = BufferKey(networkId = 1, target = "#lurker")

    /** Seeded with one network and one channel, as the last snapshot left them. */
    private fun viewModel(): ChatViewModel {
        val model = testViewModel()
        model.handle(ServerFrame.SocketOpen)
        model.handle(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me",
                        channels = listOf(ChannelSnapshot(name = "#lurker", topic = null, members = emptyList())),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        return model
    }

    /**
     * L02: every command with a wire verb comes back to the composer, not only the ones that
     * carry a message. Before, `/topic`, `/nick`, `/part` and the rest were cleared and lost.
     */
    @Test
    fun testEveryWireCommandTypedOfflineComesBack() {
        for (line in listOf(
            "/topic a new topic", "/nick someoneelse", "/ns identify hunter2", "/kick bob",
            "/quote PING x", "/part", "/close", "/clear", "/away brb", "/back", "/ctcp bob VERSION",
        )) {
            val model = viewModel()
            model.send(channel, text = line)
            assertEquals(line, model.takeUnsent(channel)?.text, "$line went nowhere and must come back")
        }
    }

    /**
     * A write that would go out is still refused when it couldn't reach the server: a reconnect's
     * socket that hasn't opened (it takes writes before its upgrade, and loses them if the
     * attempt fails), or a device with no path while the old socket still reads connected.
     * `/msg` goes through the seam, which says "sent", so only the gate can hand it back.
     */
    @Test
    fun testAWriteThatWouldGoOutWaitsForTheConnection() {
        val unopened = testViewModel()
        unopened.sendMessageSeam = { _, _ -> true }
        unopened.send(channel, text = "/msg bob hi")
        assertEquals("/msg bob hi", unopened.takeUnsent(channel)?.text, "the socket hasn't opened")

        val unreachable = viewModel()
        unreachable.sendMessageSeam = { _, _ -> true }
        unreachable.setReachable(false)
        unreachable.send(channel, text = "/msg bob hi")
        assertEquals("/msg bob hi", unreachable.takeUnsent(channel)?.text, "the device has no path")

        val online = viewModel()
        online.sendMessageSeam = { _, _ -> true }
        online.send(channel, text = "/msg bob hi")
        assertNull(online.takeUnsent(channel), "connected and reachable: it went")
    }

    /**
     * A forced reconnect — the foreground's stale-socket check — replaces the socket while the
     * state still reads Connected. The new socket takes writes during its upgrade and loses them
     * if the attempt fails, so the user's writes wait for its first frame.
     */
    @Test
    fun testAForcedReconnectHoldsWritesUntilTheNewSocketOpens() {
        val model = viewModel()
        model.sendMessageSeam = { _, _ -> true }
        assertEquals(SocketStatus.Connected, model.state.connection)
        model.reconnectSocket()
        assertEquals(SocketStatus.Connected, model.state.connection, "the state hasn't moved; only the socket has")
        model.send(channel, text = "/msg bob hi")
        assertEquals("/msg bob hi", model.takeUnsent(channel)?.text, "the new socket hasn't opened")
        assertFalse(model.setNickNote(networkId = 1, nick = "bob", note = "lives in Berlin"))
        assertFalse(model.closeBuffer(channel))
        assertNotNull(model.state.buffers[channel.id])

        model.handle(ServerFrame.SocketOpen)
        model.send(channel, text = "/msg bob hi")
        assertNull(model.takeUnsent(channel), "open: it went")
    }

    /** A command that puts nothing on the wire holds nothing: there is nothing to have lost. */
    @Test
    fun testALocalCommandHoldsNothing() {
        val model = viewModel()
        model.send(channel, text = "/commands")
        assertNull(model.takeUnsent(channel))
    }

    /**
     * The server log can't be closed, so `/close` there is a no-op rather than a lost write —
     * it mustn't bounce back as if the connection were down.
     */
    @Test
    fun testCloseInTheServerLogIsNotARefusal() {
        val model = viewModel()
        val log = BufferKey(networkId = 1, target = ":server:1")
        model.send(log, text = "/close")
        assertNull(model.takeUnsent(log))
    }

    /** L16: a close that couldn't go out leaves the row where it is, and says so to its caller. */
    @Test
    fun testACloseThatWentNowhereKeepsTheRow() {
        val model = viewModel()
        assertNotNull(model.state.buffers[channel.id])
        assertFalse(model.closeBuffer(channel))
        assertNotNull(model.state.buffers[channel.id], "no PART went out, so the channel is still joined")
    }

    /** L14 and L29: the callers keep what was typed or dragged only if they're told. */
    @Test
    fun testNoteAndReorderReportWentNowhere() {
        val model = viewModel()
        assertFalse(model.setNickNote(networkId = 1, nick = "bob", note = "lives in Berlin"))
        assertFalse(model.reorderFavorites(bufferIds = listOf(3, 1, 2)))
    }

    /** L14: the cap is the server's, counted the way JavaScript counts — an emoji is two. */
    @Test
    fun testANoteFitsByUTF16Units() {
        assertTrue(NickNote.fits("a".repeat(NickNote.maxLength)))
        assertFalse(NickNote.fits("a".repeat(NickNote.maxLength + 1)))
        assertFalse(NickNote.fits("a".repeat(NickNote.maxLength - 1) + "🎉"))
    }
}
