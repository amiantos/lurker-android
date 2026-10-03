// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadError
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Port-only: the upload's disk work — assembling the multipart body, and deleting it — runs on the
 * client's IO dispatcher, while what the client keeps on the caller's thread (the response handling,
 * `reportUnauthorized`) comes back there. LurkerKit does all of it on the main actor.
 */
class UploadDispatchTests {

    /** A real thread of its own, recording every hop onto it. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, THREAD) }
        val hops: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.execute {
                hops.add(Thread.currentThread().name)
                block.run()
            }
        }

        fun close() = executor.shutdown()

        companion object {
            const val THREAD = "kit-test-io"
        }
    }

    private fun answering(code: Int, json: String, sawBody: (String) -> Unit = {}) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                sawBody(buffer.readUtf8())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(json.toResponseBody(null))
                    .build()
            }
            .build()

    private fun source(): File =
        File(System.getProperty("java.io.tmpdir"), "upload-src-${UUID.randomUUID()}.bin").apply { writeText("PAYLOADBYTES") }

    private fun bodies(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("lurker-upload-") && name.endsWith(".multipart") }
            .orEmpty().toSet()

    @Test
    fun testTheBodyIsAssembledAndDeletedOnTheIoDispatcher() = runTest {
        val io = RecordingDispatcher()
        val file = source()
        val before = bodies()
        var sent = ""
        try {
            val client = LurkerClient(
                scope = this,
                onFrame = {},
                httpClient = answering(200, """{"id":7,"url":"https://u/x"}""") { sent = it },
                ioDispatcher = io,
            )
            client.restore(server = "https://app.lurker.chat", token = "current")
            val response = client.upload(
                fileURL = file, filename = "a.bin", mime = "application/octet-stream",
                progressToken = "tok", onProgress = {}, onServerProgress = {},
            )
            assertEquals("https://u/x", response.url)
            assertTrue(sent.contains("PAYLOADBYTES"), "the assembled body is what went out")
            // Once to assemble, once to delete.
            assertEquals(listOf(RecordingDispatcher.THREAD, RecordingDispatcher.THREAD), io.hops.toList())
            assertEquals(before, bodies(), "the multipart body is deleted afterwards")
        } finally {
            io.close()
            file.delete()
        }
    }

    @Test
    fun testA401IsReportedOnTheCallersThreadNotTheIoOne() = runTest {
        val io = RecordingDispatcher()
        val file = source()
        val caller = Thread.currentThread()
        val frameThreads = mutableListOf<Thread>()
        try {
            val client = LurkerClient(
                scope = this,
                onFrame = { frame -> if (frame == ServerFrame.Unauthorized) frameThreads.add(Thread.currentThread()) },
                httpClient = answering(401, """{"error":"unauthorized"}"""),
                ioDispatcher = io,
            )
            client.restore(server = "https://app.lurker.chat", token = "current")
            assertFailsWith<UploadError.Unauthorized> {
                client.upload(
                    fileURL = file, filename = "a.bin", mime = "application/octet-stream",
                    progressToken = "tok", onProgress = {}, onServerProgress = {},
                )
            }
            assertEquals(listOf(caller), frameThreads)
            assertTrue(io.hops.isNotEmpty())
        } finally {
            io.close()
            file.delete()
        }
    }
}
