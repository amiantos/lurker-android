// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.VerbReply
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which socket endings sign the user out, with the values `URLSessionWebSocketTask` reports
 * for each (measured on iOS against lurker's own close and refusal bytes).
 *
 * Port note: here those values come from OkHttp's listener — the upgrade's status from
 * `onOpen` or `onFailure`'s response, the close code from `onClosing` — and are mapped onto the
 * same two numbers in `LurkerClient.SocketListener`.
 */
class SocketCloseTests {

    /**
     * Revoking the app in Settings closes its open socket with 4001. The upgrade's status is
     * still the 101 that opened it, which is why the status alone kept the app reconnecting.
     */
    @Test
    fun testARevokedSocketEndsTheSession() {
        assertTrue(LurkerClient.closeEndsSession(status = 101, closeCode = 4001))
    }

    @Test
    fun testARefusedUpgradeEndsTheSession() {
        assertTrue(LurkerClient.closeEndsSession(status = 401, closeCode = 0))
    }

    @Test
    fun testADroppedConnectionReconnects() {
        assertFalse(LurkerClient.closeEndsSession(status = 101, closeCode = 0))
        assertFalse(LurkerClient.closeEndsSession(status = 101, closeCode = 1001))
        assertFalse(LurkerClient.closeEndsSession(status = null, closeCode = 0))
        assertFalse(LurkerClient.closeEndsSession(status = 502, closeCode = 0))
    }

    // Port-only: the socket itself, end to end — OkHttp's listener, the hop to the client's
    // thread, and what LurkerKit gets for free from `URLSessionWebSocketTask`'s own reporting.
    // A WebSocket cannot be answered by an interceptor, so `TinySocketServer` below speaks just
    // enough of RFC 6455 on a loopback port; REST calls are answered by an interceptor. The
    // client runs on a single thread of its own, standing in for main.

    private class Harness(val server: TinySocketServer) : AutoCloseable {
        val main: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(main + SupervisorJob())
        val frames = Channel<ServerFrame>(Channel.UNLIMITED)
        private val rest = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/ws") return@addInterceptor chain.proceed(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body("[]".toResponseBody(null))
                    .build()
            }
            .build()
        val client = LurkerClient(scope = scope, onFrame = { frames.trySend(it) }, httpClient = rest)

        suspend fun <T> onMain(block: suspend LurkerClient.() -> T): T = withContext(main) { client.block() }

        suspend fun connect() = onMain {
            restore(server = "http://127.0.0.1:${server.port}", token = "tok")
            reconnect(since = 0)
        }

        /** The next frame of a kind, skipping what the REST answers produce. */
        suspend inline fun <reified T : ServerFrame> next(): T {
            while (true) {
                val frame = frames.receive()
                if (frame is T) return frame
            }
        }

        override fun close() {
            scope.cancel()
            main.close()
            server.close()
        }
    }

    @Test
    fun testASocketOpensOnItsFirstFrameReassertsPresenceAndEndsOnARevoke() = runBlocking<Unit> {
        Harness(TinySocketServer()).use { harness ->
            withTimeout(10_000) {
                harness.connect()
                harness.server.sendText("""{"kind":"not-a-frame-anyone-reads"}""")
                harness.next<ServerFrame.SocketOpen>()
                assertEquals(1, harness.onMain { socketGeneration })
                // The new socket is told the app's visibility before anything else.
                assertEquals(
                    Json.parseToJsonElement("""{"type":"presence","visible":false}"""),
                    Json.parseToJsonElement(harness.server.nextText()),
                )
                // Revoked in Settings: the server closes the open socket with 4001.
                harness.server.sendClose(4001)
                harness.next<ServerFrame.Unauthorized>()
            }
        }
    }

    @Test
    fun testAnUpgradeRefusedWith426IsIncompatible() = runBlocking<Unit> {
        Harness(TinySocketServer(upgradeStatus = 426)).use { harness ->
            withTimeout(10_000) {
                harness.connect()
                assertEquals(
                    ServerFrame.Incompatible(Incompatibility.AppTooOld),
                    harness.next<ServerFrame.Incompatible>(),
                )
            }
        }
    }

    @Test
    fun testAnUpgradeRefusedWith401EndsTheSession() = runBlocking<Unit> {
        Harness(TinySocketServer(upgradeStatus = 401)).use { harness ->
            withTimeout(10_000) {
                harness.connect()
                harness.next<ServerFrame.Unauthorized>()
            }
        }
    }

    /**
     * An acked verb is answered by its `send-result`, read in full and never passed to the
     * store; a question still out when its socket ends is settled as `connectionLost`; and a
     * deliberate write into the dead socket afterwards says so.
     */
    @Test
    fun testRepliesSettleOnTheirAnswerOrWithTheirSocket() = runBlocking<Unit> {
        Harness(TinySocketServer()).use { harness ->
            withTimeout(10_000) {
                harness.connect()
                harness.server.sendText("""{"kind":"not-a-frame-anyone-reads"}""")
                harness.next<ServerFrame.SocketOpen>()
                harness.server.nextText() // presence

                val answered = async(harness.main) { harness.client.setTopic(networkId = 1, channel = "#a", topic = "t") }
                val asked = Json.parseToJsonElement(harness.server.nextText()).jsonObject
                assertEquals("set-topic", asked["type"]?.jsonPrimitive?.content)
                val clientId = assertNotNull(asked["clientId"]?.jsonPrimitive?.content)
                harness.server.sendText("""{"kind":"send-result","clientId":"$clientId","ok":true}""")
                assertEquals(VerbReply(ok = true, error = null), answered.await())

                val stranded = async(harness.main) { harness.client.fetchModeList(networkId = 1, channel = "#a", letter = "b") }
                harness.server.nextText() // get-mode-list
                harness.server.sendClose(1001)
                assertEquals(VerbReply.connectionLost, stranded.await())
                assertEquals(ServerFrame.SocketClosed(reason = "", code = 101), harness.next<ServerFrame.SocketClosed>())

                assertTrue(harness.onMain { sendMessage(networkId = 1, target = "#a", text = "hi") })
                assertEquals(
                    ServerFrame.ServerError("Send failed: Socket is not connected"),
                    harness.next<ServerFrame.ServerError>(),
                )
            }
        }
    }
}

/**
 * Just enough of a WebSocket server (RFC 6455) for one client on a loopback port: the upgrade
 * (or a refusal with [upgradeStatus]), unmasked text and close frames out, masked frames in.
 */
private class TinySocketServer(private val upgradeStatus: Int = 101) : AutoCloseable {
    // 127.0.0.1 by name, not `InetAddress.getLoopbackAddress()`: that's IPv4 on OpenJDK but `::1` on
    // Android, where the client — dialling `http://127.0.0.1` — then finds nothing listening (#34).
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val received = LinkedBlockingQueue<String>()
    private val connected = CountDownLatch(1)

    @Volatile
    private var output: OutputStream? = null

    init {
        thread(isDaemon = true, name = "TinySocketServer") { serve() }
    }

    private fun serve() {
        val socket = try {
            server.accept()
        } catch (_: IOException) {
            return
        }
        socket.use {
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trimStart()
            }
            if (upgradeStatus != 101) {
                out.write("HTTP/1.1 $upgradeStatus Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val key = headers["sec-websocket-key"] ?: return
            val accept = (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encodeUtf8().sha1().base64()
            out.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()
            output = out
            connected.countDown()
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0 || b1 < 0) return
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
                if (length == 127L) length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or input.read().toLong() }
                val mask = if (b1 and 0x80 != 0) ByteArray(4) { input.read().toByte() } else null
                val payload = ByteArray(length.toInt()) { input.read().toByte() }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0F) {
                    0x1 -> received.put(String(payload, Charsets.UTF_8))
                    0x8 -> return
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(byte.toChar())
        }
    }

    /** The next text frame the client sent. */
    fun nextText(): String = assertNotNull(received.poll(5, TimeUnit.SECONDS), "no frame from the client")

    fun sendText(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size < 126)
        write(byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload)
    }

    fun sendClose(code: Int) {
        write(byteArrayOf(0x88.toByte(), 2, (code shr 8).toByte(), code.toByte()))
    }

    private fun write(bytes: ByteArray) {
        assertTrue(connected.await(5, TimeUnit.SECONDS), "the client never connected")
        val out = assertNotNull(output)
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        server.close()
    }
}
