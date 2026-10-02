// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.UnsentCorrelator
import net.amiantos.lurkerkit.store.LurkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Giving back a line the server refused (lurker-ios#128).
 *
 * ⚠⚠ The failure this guards is SILENCE, which is the hardest kind to notice: before this, iOS
 * never put a `clientId` on a send, so `wsHub` never emitted `send-result`, so the composer
 * cleared and the message was simply gone. Every assertion here is about a line surviving a
 * round trip it used to disappear into.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Unsent sends"), where each case carries a
 * display name. The method names are kept and the display name is the comment above each.
 */
class UnsentCorrelatorTests {

    private val chat = BufferKey(networkId = 1, target = "#chat")
    private val dm = BufferKey(networkId = 1, target = "bob")

    // "a refused line comes back, addressed to where it was typed"
    @Test
    fun refusalReturnsTheOrigin() {
        val c = UnsentCorrelator()
        val id = c.track(chat, line = "hey there")
        assertEquals(UnsentCorrelator.Origin(key = chat, line = "hey there"), c.resolve(clientId = id, ok = false))
    }

    // "a `/msg` comes back to the buffer it was typed in, as typed"
    @Test
    fun slashMsgRestoresTheWholeLineToTheOrigin() {
        // ⚠⚠ The case that decides the whole design. `/msg bob hi` typed in #chat puts `hi` on
        // the wire addressed to `bob`, and ACTIVATES the DM before the ack lands. Restoring the
        // wire payload, or restoring to whatever buffer is in front of the user, both stranded a
        // fragment in the wrong conversation. The origin is not on the wire; it is only here.
        val c = UnsentCorrelator()
        val id = c.track(chat, line = "/msg bob hi")
        val origin = c.resolve(clientId = id, ok = false)
        assertEquals(chat, origin?.key, "the composer that lost the text was #chat's, not the DM's")
        assertEquals("/msg bob hi", origin?.line, "give back what was typed, not what went out")
    }

    // "a success gives nothing back, and is forgotten"
    @Test
    fun successRestoresNothing() {
        val c = UnsentCorrelator()
        val id = c.track(chat, line = "hey")
        assertNull(c.resolve(clientId = id, ok = true))
        // ⚠ Forgetting on success is what keeps this from growing for the life of the socket —
        // every send is acked, and only the refusals are interesting.
        assertEquals(0, c.pendingCount)
    }

    // "one line put on the wire several times comes back once"
    @Test
    fun oneLineRestoresOnce() {
        // ⚠⚠ A command can produce several sends sharing the line's id. There is ONE line in the
        // composer to give back, so a second refusal must find nothing — otherwise the restore
        // races itself and the no-clobber rule in the store decides the outcome by accident.
        val c = UnsentCorrelator()
        val id = c.track(chat, line = "/me waves")
        assertNotNull(c.resolve(clientId = id, ok = false))
        assertNull(c.resolve(clientId = id, ok = false))
    }

    // "an ack this client never asked for is ignored"
    @Test
    fun unknownIdsAreIgnored() {
        val c = UnsentCorrelator()
        c.track(chat, line = "hey")
        assertNull(c.resolve(clientId = "android-999", ok = false))
        assertNull(c.resolve(clientId = null, ok = false))
        assertEquals(1, c.pendingCount, "an unknown id must not disturb what IS outstanding")
    }

    // "ids do not collide across lines or buffers"
    @Test
    fun idsAreDistinct() {
        val c = UnsentCorrelator()
        val a = c.track(chat, line = "one")
        val b = c.track(dm, line = "two")
        assertNotEquals(a, b)
        assertEquals("two", c.resolve(clientId = b, ok = false)?.line)
        assertEquals("one", c.resolve(clientId = a, ok = false)?.line)
    }

    // "a dead socket abandons outstanding sends without restoring them"
    @Test
    fun abandonDoesNotRestore() {
        // ⚠⚠ Deliberate, and the reasoning is in `abandonAll`: a send the socket died under may
        // have reached IRC. Restoring risks the same line going to a channel twice with no way to
        // take it back, which is worse than losing one.
        val c = UnsentCorrelator()
        val id = c.track(chat, line = "hey")
        c.abandonAll()
        assertEquals(0, c.pendingCount)
        assertNull(c.resolve(clientId = id, ok = false))
    }
}

/**
 * Where a refused line waits until there is a composer to put it in.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Unsent holds"); see `UnsentCorrelatorTests`.
 */
class UnsentHoldTests {

    private val chat = BufferKey(networkId = 1, target = "#chat")

    // "a held line is handed over once, then gone"
    @Test
    fun takeIsReadAndClear() {
        val store = LurkerStore()
        store.holdUnsent(chat, text = "hey there")
        assertEquals("hey there", store.takeUnsent(chat))
        // ⚠ A mirror rather than a handoff would re-fill the field every time the buffer was
        // reopened, long after the user dealt with it.
        assertNull(store.takeUnsent(chat))
    }

    // "two refused lines both survive, oldest first"
    @Test
    fun holdsQueueRatherThanClobber() {
        // ⚠⚠ A single slot lost text, and this is the case that showed it: two sends outstanding
        // when the wire goes. The first refusal restores into the composer; the second is left
        // waiting; re-sending the first fails again and arrives to find the slot occupied. A slot
        // must then either overwrite the waiting line or discard the new one, and both throw away
        // something the user wrote. There is one composer, so they come back one at a time — but
        // they all come back.
        val store = LurkerStore()
        store.holdUnsent(chat, text = "first")
        store.holdUnsent(chat, text = "second")
        assertEquals("first", store.takeUnsent(chat))
        assertEquals("second", store.takeUnsent(chat))
        assertNull(store.takeUnsent(chat))
    }

    // "a rename carries the hold to the surviving buffer"
    @Test
    fun renameMovesTheHold() {
        // ⚠⚠ Left under the dead key this is unreachable forever — nothing reads that id again —
        // and the user's line is gone with no way to notice. Same class as the in-flight page
        // flags the view model rekeys beside it.
        val store = LurkerStore()
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "#chat", kind = BufferKind.Channel, hydrated = true),
                messages = emptyList(), hydrated = true, append = false, speakers = null,
            ),
        )
        store.holdUnsent(chat, text = "hey there")
        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "#chat", to = "#lounge", bufferId = null, merged = false,
                mergedFromBufferId = null,
            ),
        )
        assertNull(store.takeUnsent(chat))
        assertEquals("hey there", store.takeUnsent(BufferKey(networkId = 1, target = "#lounge")))
    }

    // "a line still awaiting its ack follows the rename too"
    @Test
    fun renameFollowsAnInFlightLine() {
        // ⚠⚠ The other half of the rename window, and the half a store-only fix misses entirely.
        // An Origin captured before the rename holds the OLD key, so the hold it writes when the
        // refusal lands goes under a key nothing reads again — the screen has already followed
        // the rename. `rekeyBuffer` moves holds already written; this moves the ones that are not
        // written yet.
        val c = UnsentCorrelator()
        val id = c.track(chat, line = "hey there")
        c.rekey(from = chat, to = BufferKey(networkId = 1, target = "#lounge"))
        val origin = c.resolve(clientId = id, ok = false)
        assertEquals(BufferKey(networkId = 1, target = "#lounge"), origin?.key)
        assertEquals("hey there", origin?.line)
    }

    // "a rename leaves other buffers' in-flight lines alone"
    @Test
    fun renameDoesNotTouchOtherBuffers() {
        val c = UnsentCorrelator()
        val elsewhere = c.track(BufferKey(networkId = 1, target = "#other"), line = "not mine")
        c.rekey(from = chat, to = BufferKey(networkId = 1, target = "#lounge"))
        assertEquals(
            BufferKey(networkId = 1, target = "#other"),
            c.resolve(clientId = elsewhere, ok = false)?.key,
        )
    }

    // "a refusal no longer raises a modal alert"
    @Test
    fun sendResultIsSilent() {
        // ⚠⚠ It used to set `state.error`, which presents an ALERT. That was written when this
        // frame never arrived, so the cost was invisible; since lurker#809's writable-connection
        // gate `ok:false` is the ordinary outcome of any reconnect, and the ConnectionBanner is
        // already saying "Reconnecting…". The failures that DO warrant words arrive as bare
        // `error` frames alongside, which this must not have disturbed.
        val store = LurkerStore()
        store.apply(ServerFrame.SendResult(clientId = "ios-1", ok = false, error = "not-connected"))
        assertNull(store.state.error)

        store.apply(ServerFrame.ServerError("account paused"))
        assertEquals("account paused", store.state.error)
        store.apply(ServerFrame.SendResult(clientId = "ios-2", ok = false, error = "account-paused"))
        assertEquals(
            "account paused", store.state.error,
            "the bare error frame's prose must not be overwritten by the ack's error code",
        )
    }
}
