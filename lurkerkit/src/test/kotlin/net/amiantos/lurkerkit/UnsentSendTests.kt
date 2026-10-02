// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.UnsentCorrelator
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

    // Waiting on LurkerStore, ServerFrame: takeIsReadAndClear, holdsQueueRatherThanClobber,
    // renameMovesTheHold, sendResultIsSilent
}
