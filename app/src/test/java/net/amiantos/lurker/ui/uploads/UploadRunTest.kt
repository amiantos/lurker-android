// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.ImageShrink
import net.amiantos.lurkerkit.client.UploadError
import net.amiantos.lurkerkit.client.UploadResponse
import net.amiantos.lurkerkit.client.UploadServerProgress
import net.amiantos.lurkerkit.support.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The upload pipeline's decisions — lurker-ios's `beginUpload`/`performUpload`, against a fake device. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadRunTest {

    /** One upload the fake was asked for. */
    data class Asked(val file: File, val filename: String, val mime: String)

    /** The device, faked: staging, redraws, transcodes and uploads answer from scripts. */
    class FakePlatform : UploadPlatform {
        val deleted = mutableListOf<File>()
        val asked = mutableListOf<Asked>()
        val staged = ArrayDeque<StageResult>()
        /** When set, staging throws this — a content provider refusing an address its own way. */
        var stageThrows: Exception? = null
        var imageThrows: Exception? = null
        val answers = ArrayDeque<Result<UploadResponse, UploadError>>()
        var image: PreparedImage? = null
        var video: (suspend (File, (Double) -> Unit) -> PreparedVideo)? = null
        /** Each upload in turn waits on the next of these before answering (null: answers at once). */
        val gates = ArrayDeque<CompletableDeferred<Unit>?>()
        var progress: ((Double) -> Unit)? = null
        var server: ((UploadServerProgress) -> Unit)? = null
        override var uploadCapBytes: Long = 100
        override var maxStaticImageDimension: Int? = 2048

        override suspend fun stage(source: AttachmentSource.Content): StageResult {
            stageThrows?.let { throw it }
            return staged.removeFirst()
        }

        override suspend fun prepareImage(file: File, maxStaticImageDimension: Int?): PreparedImage? {
            imageThrows?.let { throw it }
            return image
        }

        override suspend fun prepareVideo(file: File, maxBytes: Long, onProgress: (Double) -> Unit): PreparedVideo =
            video?.invoke(file, onProgress) ?: PreparedVideo(file, isTemporary = false)

        override suspend fun upload(
            file: File,
            filename: String,
            mime: String,
            progressToken: String,
            onProgress: (Double) -> Unit,
            onServerProgress: (UploadServerProgress) -> Unit,
        ): Result<UploadResponse, UploadError> {
            asked += Asked(file, filename, mime)
            progress = onProgress
            server = onServerProgress
            gates.removeFirstOrNull()?.await()
            return answers.removeFirst()
        }

        override fun delete(file: File) {
            deleted += file
        }
    }

    private fun ok(url: String) = Result.Success(UploadResponse(id = 1, url = url, mime = null, canDelete = true, thumbnailUrl = null))

    private fun picked(name: String, mime: String = "image/png", video: Boolean = false) =
        AttachmentSource.Ready(Picked(File("/cache/$name"), name, mime, video))

    private class Delivery(var accepts: Boolean = true) {
        val delivered = mutableListOf<Pair<String, Boolean>>()

        fun deliver(url: String, first: Boolean): Boolean {
            if (accepts) delivered += url to first
            return accepts
        }
    }

    private fun run(platform: FakePlatform, delivery: Delivery, readout: MutableStateFlow<UploadReadout?> = MutableStateFlow(null)) =
        UploadRun(platform, readout, delivery::deliver, newToken = { "token" })

    @Test
    fun eachLinkLandsAndOnlyTheFirstClaimsTheCaret() = runTest {
        val platform = FakePlatform().apply { answers += ok("https://u/1"); answers += ok("https://u/2") }
        val delivery = Delivery()
        val result = run(platform, delivery).run(listOf(picked("a.png"), picked("b.png")))
        assertEquals(listOf("https://u/1" to true, "https://u/2" to false), delivery.delivered)
        assertEquals(2, result.uploaded)
        assertNull(result.report())
    }

    @Test
    fun aLinkWithNowhereToGoIsHeldForTheClipboard() = runTest {
        val platform = FakePlatform().apply { answers += ok("https://u/1"); answers += ok("https://u/2") }
        val result = run(platform, Delivery(accepts = false)).run(listOf(picked("a.png"), picked("b.png")))
        val report = result.report()!!
        assertEquals("Upload finished", report.title)
        assertEquals("2 links are on your clipboard.", report.message)
        assertEquals("https://u/1 https://u/2", report.clipboard)
    }

    @Test
    fun theStagedCopyAndItsRedrawAreDeletedOnEveryExit() = runTest {
        val redraw = File("/cache/redraw.jpg")
        val platform = FakePlatform().apply {
            image = PreparedImage(redraw, ImageShrink.Format.Jpeg)
            answers += Result.Failure(UploadError.TooLarge)
        }
        run(platform, Delivery()).run(listOf(picked("shot.heic", "image/heic")))
        assertEquals(listOf(File("/cache/shot.heic"), redraw), platform.deleted)
        // The redraw went up under the redraw's name and type.
        assertEquals(Asked(redraw, "shot.jpg", "image/jpeg"), platform.asked.single())
    }

    @Test
    fun aTranscodeGoesUpAsMp4() = runTest {
        val transcode = File("/cache/out.mp4")
        val platform = FakePlatform().apply {
            video = { _, progress ->
                progress(0.5)
                PreparedVideo(transcode, isTemporary = true)
            }
            answers += ok("https://u/v")
        }
        val readout = MutableStateFlow<UploadReadout?>(null)
        run(platform, Delivery(), readout).run(listOf(picked("IMG_1.MOV", "video/quicktime", video = true)))
        assertEquals(Asked(transcode, "IMG_1.mp4", "video/mp4"), platform.asked.single())
        assertTrue(transcode in platform.deleted)
        assertNull(readout.value)
    }

    @Test
    fun aVideoAlreadyUnderTheCapGoesUpUntouched() = runTest {
        val platform = FakePlatform().apply { answers += ok("https://u/v") }
        run(platform, Delivery()).run(listOf(picked("small.mp4", "video/mp4", video = true)))
        assertEquals(Asked(File("/cache/small.mp4"), "small.mp4", "video/mp4"), platform.asked.single())
    }

    @Test
    fun aVideoNoRungFitsIsAFailureNotAnUpload() = runTest {
        val platform = FakePlatform().apply { video = { _, _ -> throw UploadError.CannotCompressEnough } }
        val result = run(platform, Delivery()).run(listOf(picked("long.mov", "video/quicktime", video = true)))
        assertTrue(platform.asked.isEmpty())
        assertEquals("This video is too large to upload, even after compression.", result.report()!!.message)
        assertEquals("Upload failed", result.report()!!.title)
    }

    @Test
    fun anUnreadableItemIsCountedAndQuoted() = runTest {
        val platform = FakePlatform().apply {
            staged += StageResult.Failed("No space left on device")
            answers += ok("https://u/2")
        }
        val result = run(platform, Delivery()).run(listOf(AttachmentSource.Content("content://a"), picked("b.png")))
        assertEquals(1, result.unreadable)
        val report = result.report()!!
        assertEquals("Upload incomplete", report.title)
        assertEquals("Uploaded 1 of 2. 1 couldn't be read: No space left on device", report.message)
    }

    @Test
    fun anUnsupportedPickFailsLoudlyAtItsTurn() = runTest {
        val result = run(FakePlatform(), Delivery()).run(listOf(AttachmentSource.Unsupported))
        assertEquals("That item can't be uploaded.", result.report()!!.message)
    }

    @Test
    fun aBrokenPipeStopsTheBatch() = runTest {
        val platform = FakePlatform().apply { answers += Result.Failure(UploadError.Transport("offline")) }
        val result = run(platform, Delivery()).run(listOf(picked("a.png"), picked("b.png"), picked("c.png")))
        assertEquals(1, platform.asked.size)
        assertEquals("Uploaded 0 of 3. Upload failed: offline", result.report()!!.message)
    }

    @Test
    fun theSameRefusalTwiceStopsTheBatch() = runTest {
        val platform = FakePlatform().apply {
            answers += Result.Failure(UploadError.Server("Provider credentials expired"))
            answers += Result.Failure(UploadError.Server("Provider credentials expired"))
        }
        val result = run(platform, Delivery()).run(List(4) { picked("$it.png") })
        assertEquals(2, platform.asked.size)
        assertEquals(2, result.failures.size)
    }

    @Test
    fun oneBadFileDoesntCondemnTheRest() = runTest {
        val platform = FakePlatform().apply {
            answers += Result.Failure(UploadError.TooLarge)
            answers += ok("https://u/2")
        }
        val result = run(platform, Delivery()).run(listOf(picked("a.png"), picked("b.png")))
        assertEquals(2, platform.asked.size)
        assertEquals(1, result.uploaded)
    }

    @Test
    fun aCancelMidUploadStopsTheRunSilently() = runTest {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatform().apply {
            gates += gate
            answers += ok("https://u/1")
        }
        val delivery = Delivery()
        var result: UploadRunResult? = null
        val job = launch { result = run(platform, delivery).run(listOf(picked("a.png"), picked("b.png"))) }
        runCurrent()
        job.cancel()
        runCurrent()
        val done = result!!
        assertTrue(done.cancelled)
        assertEquals(0, done.uploaded)
        assertTrue(delivery.delivered.isEmpty())
        // The stop is the user's own doing: nothing to report.
        assertNull(done.report())
        assertEquals(listOf(File("/cache/a.png")), platform.deleted)
    }

    @Test
    fun aCancelKeepsWhatAlreadyWentWrong() = runTest {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatform().apply {
            answers += Result.Failure(UploadError.TooLarge)
            answers += ok("https://u/2")
        }
        platform.gates += null
        platform.gates += gate
        var result: UploadRunResult? = null
        val job = launch {
            result = run(platform, Delivery()).run(listOf(picked("a.png"), picked("b.png")))
        }
        runCurrent()
        job.cancel()
        runCurrent()
        val report = result!!.report()!!
        assertEquals("The server rejected this file for being too large.", report.message)
    }

    @Test
    fun theReadoutNarratesAndALateTickCantRepaintTheNextFile() = runTest {
        val gate = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val platform = FakePlatform().apply {
            gates += gate
            gates += second
            answers += ok("https://u/1")
            answers += ok("https://u/2")
        }
        val readout = MutableStateFlow<UploadReadout?>(null)
        launch { run(platform, Delivery(), readout).run(listOf(picked("a.png"), picked("b.png"))) }
        runCurrent()
        assertEquals(UploadReadout(UploadPhase.Uploading(0.0), UploadBatchPosition(1, 2)), readout.value)
        val firstFilesTick = platform.progress!!
        firstFilesTick(0.5)
        assertEquals("1/2 · Uploading… 50%", readout.value!!.label)
        platform.server!!(UploadServerProgress(UploadServerProgress.Phase.Sending, null, "Catbox"))
        assertEquals("1/2 · Sending to Catbox…", readout.value!!.label)
        // File 1 finishes; file 2 waits on its own gate.
        gate.complete(Unit)
        runCurrent()
        assertEquals(UploadReadout(UploadPhase.Uploading(0.0), UploadBatchPosition(2, 2)), readout.value)
        // A tick of file 1's, queued before it finished, lands now — and is dropped.
        firstFilesTick(0.99)
        assertEquals(UploadReadout(UploadPhase.Uploading(0.0), UploadBatchPosition(2, 2)), readout.value)
        second.complete(Unit)
        runCurrent()
        assertNull(readout.value)
    }

    @Test
    fun aStagingCancelIsTheUsersNotABadFile() = runTest {
        val platform = FakePlatform().apply { staged += StageResult.Cancelled }
        val result = run(platform, Delivery()).run(listOf(AttachmentSource.Content("content://a"), picked("b.png")))
        assertTrue(result.cancelled)
        assertEquals(0, result.unreadable)
        assertTrue(platform.asked.isEmpty())
        assertNull(result.report())
    }

    @Test
    fun aProviderThatThrowsIsOneUnreadableFileNotACrash() = runTest {
        val platform = FakePlatform().apply {
            stageThrows = IllegalArgumentException("Unknown URI: content://gone/1")
            answers += ok("https://u/2")
        }
        val delivery = Delivery()
        val result = run(platform, delivery).run(listOf(AttachmentSource.Content("content://gone/1")))
        assertEquals(1, result.unreadable)
        assertEquals("Unknown URI: content://gone/1", result.report()!!.message)
        assertTrue(platform.asked.isEmpty())
    }

    @Test
    fun aRedrawThatBreaksUploadsTheOriginal() = runTest {
        val platform = FakePlatform().apply {
            imageThrows = IllegalStateException("decoder")
            answers += ok("https://u/1")
        }
        run(platform, Delivery()).run(listOf(picked("shot.png")))
        assertEquals(Asked(File("/cache/shot.png"), "shot.png", "image/png"), platform.asked.single())
    }
}
