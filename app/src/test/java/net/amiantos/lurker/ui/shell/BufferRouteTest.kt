// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import org.junit.Assert.assertEquals
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
}
