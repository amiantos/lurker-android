// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals(BufferRoute.of(key), first.copy(jump = null))
    }
}
