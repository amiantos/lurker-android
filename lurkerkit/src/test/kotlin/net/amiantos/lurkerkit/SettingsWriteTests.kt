// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.SettingValue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A REST reply lands only in the session that sent it (`LurkerClient.deliver`). A sign-out while it
 * was out cleared the store and the settings cache; the departing account's settings, values and
 * roster must not come back into them for whoever signs in next.
 *
 * Port note: a client on a thread of its own stands in for main, answered by [Answering].
 */
class SettingsWriteTests {

    private class Harness(val server: Answering, token: String) : AutoCloseable {
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(main + SupervisorJob())
        val frames: MutableList<ServerFrame> = Collections.synchronizedList(mutableListOf())
        val client = LurkerClient(scope = scope, onFrame = { frames.add(it) }, httpClient = server.http)

        init {
            runBlocking { onMain { client.restore(server = "https://lurker.test", token = token) } }
        }

        suspend fun <T> onMain(block: suspend () -> T): T = withContext(main) { block() }

        fun <T> inFlight(block: suspend LurkerClient.() -> T) = scope.async { client.block() }

        suspend fun waitUntil(condition: () -> Boolean) {
            withTimeout(5_000) { while (!condition()) delay(20) }
        }

        fun count(matches: (ServerFrame) -> Boolean): Int = synchronized(frames) { frames.count(matches) }

        override fun close() {
            scope.cancel()
            main.close()
        }
    }

    private val isValues: (ServerFrame) -> Boolean = { it is ServerFrame.SettingsValues }

    @Test
    fun testAReplyLandsInTheSessionThatAsked() = runBlocking {
        Harness(Answering(200, """{"values":{"chat.smart_filter":true}}"""), token = "live").use { h ->
            val error = h.onMain { h.client.updateSettings(mapOf("chat.smart_filter" to SettingValue.Bool(true))) }
            assertNull(error)
            assertEquals(1, h.count(isValues))
        }
    }

    @Test
    fun testAWriteReplyForASignedOutSessionIsDropped() = runBlocking {
        val gate = CountDownLatch(1)
        Harness(Answering(200, """{"values":{"chat.smart_filter":true}}""", gate), token = "departing").use { h ->
            val write = h.inFlight { updateSettings(mapOf("system.timezone" to SettingValue.String("Asia/Tokyo"))) }
            h.waitUntil { h.server.requests.size == 1 }
            h.onMain { h.client.logout() }
            gate.countDown()
            // Nothing to say to a screen that's gone: no error, and nothing applied.
            assertNull(write.await())
            assertEquals(0, h.count(isValues))
        }
    }

    @Test
    fun testAFailedWriteForASignedOutSessionSaysNothing() = runBlocking {
        val gate = CountDownLatch(1)
        Harness(Answering(400, """{"error":"no"}""", gate), token = "departing").use { h ->
            val write = h.inFlight { updateSettings(mapOf("system.timezone" to SettingValue.String("Asia/Tokyo"))) }
            h.waitUntil { h.server.requests.size == 1 }
            h.onMain { h.client.logout() }
            gate.countDown()
            assertNull(write.await())
        }
    }

    @Test
    fun testABootstrapForASignedOutSessionIsDropped() = runBlocking {
        val gate = CountDownLatch(1)
        Harness(Answering(200, """{"registry":[],"values":{}}""", gate), token = "departing").use { h ->
            val read = h.inFlight { fetchSettings() }
            h.waitUntil { h.server.requests.size == 1 }
            h.onMain { h.client.logout() }
            gate.countDown()
            read.await()
            assertEquals(0, h.count { it is ServerFrame.SettingsBootstrap })
        }
    }

    @Test
    fun testARosterForASignedOutSessionIsDropped() = runBlocking {
        val gate = CountDownLatch(1)
        Harness(Answering(200, """{"networks":[]}""", gate), token = "departing").use { h ->
            val read = h.inFlight { fetchNetworks() }
            h.waitUntil { h.server.requests.size == 1 }
            h.onMain { h.client.logout() }
            gate.countDown()
            read.await()
            assertEquals(0, h.count { it is ServerFrame.Networks })
        }
    }

    /** The control for the two above: the same reads, answered while the session lives, do land. */
    @Test
    fun testReadsLandInTheSessionThatAsked() = runBlocking {
        Harness(Answering(200, """{"registry":[],"values":{},"networks":[]}"""), token = "live").use { h ->
            h.onMain { h.client.fetchSettings() }
            h.onMain { h.client.fetchNetworks() }
            assertEquals(1, h.count { it is ServerFrame.SettingsBootstrap })
            assertEquals(1, h.count { it is ServerFrame.Networks })
        }
    }
}
