// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class BufferRouteTest {

    @Test
    fun aRouteKeepsTheTargetsCase() {
        val key = BufferKey(networkId = 3, target = "#Lurker")
        assertEquals(key, BufferRoute.of(key).key)
        assertEquals("#Lurker", BufferRoute.of(key).target)
        assertEquals(Buffer.system.key, BufferRoute.of(Buffer.system.key).key)
    }

    /** What the navigator's saved history needs: it round-trips through Java serialization. */
    @Test
    fun aRouteSurvivesSerialization() {
        val route = BufferRoute(networkId = null, target = Buffer.systemTarget)
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(route) } }.toByteArray()
        val back = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(route, back)
    }

    /** A jump rides the route into the saved history, nonce and all — so a rotation can't re-jump. */
    @Test
    fun `a route's jump survives serialization`() {
        val route = BufferRoute.of(BufferKey(networkId = 3, target = "#Lurker"), jump = JumpRequest(messageId = 42, nonce = 7))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(route) } }.toByteArray()
        val back = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(route, back)
    }

    /** Jumping to the same message twice is two requests, so it lands twice. */
    @Test
    fun `two jumps to one message are two requests on one conversation`() {
        val key = BufferKey(networkId = 3, target = "#lurker")
        val first = BufferRoute.of(key, jump = JumpRequest.to(42))
        val second = BufferRoute.of(key, jump = JumpRequest.to(42))
        assertNotEquals(first.jump, second.jump)
        assertEquals(first.key.id, second.key.id)
        assertEquals(BufferRoute.of(key).copy(visit = first.visit), first.copy(jump = null))
    }

    /** Each open is its own visit (sweep L11); an in-place jump amends the visit it was given on. */
    @Test
    fun `two opens of one buffer are two visits, and a copy keeps its visit`() {
        val key = BufferKey(networkId = 3, target = "#lurker")
        val first = BufferRoute.of(key)
        assertNotEquals(first.visit, BufferRoute.of(key).visit)
        assertEquals(first.visit, first.copy(jump = JumpRequest.to(42)).visit)
    }

    /** Newest first; a visit shown again moves to the front; past the capacity the oldest go. */
    @Test
    fun `recent visits keep the newest and hand back the ones to drop`() {
        val visits = RecentVisits(capacity = 3)
        assertEquals(emptyList<String>(), visits.touch("a#1"))
        assertEquals(emptyList<String>(), visits.touch("b#2"))
        assertEquals(emptyList<String>(), visits.touch("c#3"))
        // Back to a: it's newest again, and nothing falls off.
        assertEquals(emptyList<String>(), visits.touch("a#1"))
        assertEquals(listOf("b#2"), visits.touch("d#4"))
        assertEquals(listOf("d#4", "a#1", "c#3"), visits.saved())
        // Showing the newest again is nothing.
        assertEquals(emptyList<String>(), visits.touch("d#4"))
        // Restored after a rotation, it carries on from where it was.
        assertEquals(listOf("c#3"), RecentVisits(visits.saved(), capacity = 3).touch("e#5"))
    }

    /**
     * Notification-jump to #a, push #b, back: #a's screen is rebuilt from the same route, request
     * and all — and the scaffold's ledger, not the screen's own saved state, says it's spent.
     */
    @Test
    fun `a jump request is claimed once for the navigator's session, however often its screen is rebuilt`() {
        val ledger = JumpLedger()
        val route = BufferRoute.of(BufferKey(networkId = 1, target = "#a"), jump = JumpRequest(messageId = 9, nonce = 1))
        assertTrue(ledger.claim(route.jump!!))
        // #b over it, then back: a fresh screen asks again.
        assertFalse(ledger.claim(route.jump!!))
        // An in-place jump is its own request; once a navigation retires it, the route's original
        // request comes back into view — and is still spent.
        assertTrue(ledger.claim(JumpRequest(messageId = 12, nonce = 2)))
        assertFalse(ledger.claim(route.jump!!))
        // Saved with the history and restored after process death, it still knows.
        val restored = JumpLedger(ledger.saved().toList())
        assertFalse(restored.claim(route.jump!!))
        assertFalse(restored.claim(JumpRequest(messageId = 12, nonce = 2)))
        assertTrue(restored.claim(JumpRequest(messageId = 9, nonce = 3)))
    }

    /**
     * Routes saved by an earlier build must still restore after an update: the added nullable
     * `jump` is a compatible change, and Java rejects a stream whose UID moved.
     */
    @Test
    fun `a route keeps the serial version it shipped with`() {
        assertEquals(1L, java.io.ObjectStreamClass.lookup(BufferRoute::class.java).serialVersionUID)
    }
}
