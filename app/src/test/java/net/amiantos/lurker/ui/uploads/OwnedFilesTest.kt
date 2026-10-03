// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/**
 * A file made on another thread is owned outside the hop: a cancel that lands as the work finishes —
 * when `withContext` resumes and discards the result naming it — still deletes it.
 */
class OwnedFilesTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val other = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdown()
    }

    private fun temp(): File = File.createTempFile("owned-${UUID.randomUUID()}", ".bin")

    @Test
    fun aCancelLandingAsTheWorkFinishesDeletesTheFile() = runBlocking<Unit> {
        var made: File? = null
        val caller = async {
            val outer = currentCoroutineContext()[Job]!!
            withOwnedFiles(other) { claim ->
                val file = claim(temp()).also { it.writeText("staged copy") }
                made = file
                // The cancel arrives on the caller just as the copy completes.
                outer.cancel()
                file
            }
        }
        val outcome = runCatching { caller.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertFalse("the copy nobody received is deleted", made!!.exists())
    }

    @Test
    fun aNormalReturnHandsTheFileOver() = runBlocking<Unit> {
        val file = withOwnedFiles(other) { claim -> claim(temp()).also { it.writeText("redraw") } }
        try {
            assertTrue(file.exists())
            assertEquals("redraw", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun aCancelInsideTheWorkDeletesTooAndFilesNotClaimedAreLeftAlone() = runBlocking<Unit> {
        val mine = temp()
        val unclaimed = temp()
        val outcome = runCatching {
            withOwnedFiles(other) { claim ->
                claim(mine)
                throw CancellationException("stopped mid-copy")
            }
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertFalse(mine.exists())
        assertTrue(unclaimed.exists())
        unclaimed.delete()
    }
}
