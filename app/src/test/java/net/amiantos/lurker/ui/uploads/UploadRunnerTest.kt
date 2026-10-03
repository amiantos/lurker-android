// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.UploadError
import net.amiantos.lurkerkit.client.UploadResponse
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.support.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app's one run at a time: the busy gate, the report, the clipboard, a sign-out. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadRunnerTest {
    private fun ok(url: String) = Result.Success(UploadResponse(id = 1, url = url, mime = null, canDelete = true, thumbnailUrl = null))

    private fun picked(name: String) = AttachmentSource.Ready(Picked(File("/cache/$name"), name, "image/png", false))

    private class Harness(scope: TestScope) {
        val platform = UploadRunTest.FakePlatform()
        val inserts = ComposerInserts()
        val clipboard = mutableListOf<String>()
        val runner = UploadRunner(scope.backgroundScope, platform, inserts) { clipboard += it }
        val inserted = mutableListOf<Pair<String, Boolean>>()

        fun mount(key: BufferKey = BufferKey(1, "#lurker")): Any = inserts.mount(key) { text, atCaret -> inserted += text to atCaret }
    }

    @Test
    fun oneRunAtATime() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.platform.gates += gate
        h.platform.answers += ok("https://u/1")
        assertTrue(h.runner.start(listOf(picked("a.png"))))
        runCurrent()
        assertTrue(h.runner.busy.value)
        // A second pick or paste can't start atop the first.
        assertFalse(h.runner.start(listOf(picked("b.png"))))
        gate.complete(Unit)
        runCurrent()
        assertFalse(h.runner.busy.value)
        h.platform.answers += ok("https://u/3")
        assertTrue(h.runner.start(listOf(picked("c.png"))))
    }

    @Test
    fun nothingToUploadStartsNothing() = runTest {
        val h = Harness(this)
        assertFalse(h.runner.start(emptyList()))
        assertFalse(h.runner.busy.value)
    }

    @Test
    fun linksLandInTheComposerOnScreen() = runTest {
        val h = Harness(this)
        h.mount()
        h.platform.answers += ok("https://u/1")
        h.runner.start(listOf(picked("a.png")))
        runCurrent()
        assertEquals(listOf("https://u/1" to true), h.inserted)
        assertNull(h.runner.report.value)
        assertTrue(h.clipboard.isEmpty())
    }

    @Test
    fun withNoComposerTheLinksGoToTheClipboardInOneWrite() = runTest {
        val h = Harness(this)
        h.platform.answers += ok("https://u/1")
        h.platform.answers += ok("https://u/2")
        h.runner.start(listOf(picked("a.png"), picked("b.png")))
        runCurrent()
        assertEquals(listOf("https://u/1 https://u/2"), h.clipboard)
        assertEquals("2 links are on your clipboard.", h.runner.report.value!!.message)
        h.runner.acknowledge()
        assertNull(h.runner.report.value)
    }

    @Test
    fun cancelSaysStoppingUntilTheRunHasReallyEnded() = runTest {
        val h = Harness(this)
        h.platform.gates += CompletableDeferred()
        h.platform.answers += ok("https://u/1")
        h.runner.start(listOf(picked("a.png")))
        runCurrent()
        h.runner.cancel()
        assertEquals(UploadPhase.Stopping, h.runner.readout.value!!.phase)
        assertTrue(h.runner.busy.value)
        runCurrent()
        assertNull(h.runner.readout.value)
        assertFalse(h.runner.busy.value)
        assertNull(h.runner.report.value)
    }

    @Test
    fun aSignOutStopsTheRunAndItReportsNothingIntoTheNextSession() = runTest {
        val h = Harness(this)
        h.mount()
        h.platform.gates += CompletableDeferred()
        h.platform.answers += Result.Failure(UploadError.TooLarge)
        h.runner.start(listOf(picked("a.png")))
        runCurrent()
        h.runner.reset()
        runCurrent()
        assertNull(h.runner.report.value)
        assertTrue(h.inserted.isEmpty())
        assertFalse(h.runner.busy.value)
    }
}
