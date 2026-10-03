// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.model.SettingValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings screen's writes: one per change, a stepper's run coalesced, nothing lost on close. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWriterTest {

    /** A server that answers each write when told to, and remembers what it was sent. */
    private class FakeServer {
        val sent = mutableListOf<Map<String, SettingValue>>()
        val replies = mutableListOf<CompletableDeferred<String?>>()

        suspend fun write(changes: Map<String, SettingValue>): String? {
            sent += changes
            val reply = CompletableDeferred<String?>()
            replies += reply
            return reply.await()
        }
    }

    private fun TestScope.writer(server: FakeServer) = SettingsWriter(backgroundScope) { server.write(it) }

    @Test
    fun aToggleShowsAtOnceAndSendsAtOnce() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        writer.set("k", SettingValue.Bool(true))
        assertEquals(listOf(mapOf("k" to SettingValue.Bool(true))), server.sent)
        assertEquals(mapOf("k" to SettingValue.Bool(true)), writer.edits.pending)
        server.replies.single().complete(null)
        runCurrent()
        assertTrue(writer.edits.pending.isEmpty())
        assertNull(writer.edits.error)
    }

    @Test
    fun aRefusalPinsTheServersReason() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        writer.set("k", SettingValue.String("bogus"))
        server.replies.single().complete("must be one of all, smart, none")
        runCurrent()
        assertEquals(WriteError("k", "must be one of all, smart, none"), writer.edits.error)
        assertTrue(writer.edits.pending.isEmpty())
    }

    @Test
    fun aStepperRunSendsOnceWithTheFinalValue() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        writer.step("n", SettingValue.Int(6))
        advanceTimeBy(SettingsModel.STEPPER_DEBOUNCE_MILLIS - 100)
        writer.step("n", SettingValue.Int(7))
        advanceTimeBy(SettingsModel.STEPPER_DEBOUNCE_MILLIS - 100)
        writer.step("n", SettingValue.Int(8))
        // The number moves with every tap, though nothing has gone yet.
        assertEquals(mapOf("n" to SettingValue.Int(8)), writer.edits.pending)
        assertTrue(server.sent.isEmpty())
        advanceTimeBy(SettingsModel.STEPPER_DEBOUNCE_MILLIS + 1)
        assertEquals(listOf(mapOf("n" to SettingValue.Int(8))), server.sent)
    }

    @Test
    fun closingSendsARunThatHasntSettled() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        writer.step("n", SettingValue.Int(9))
        writer.flush()
        assertEquals(listOf(mapOf("n" to SettingValue.Int(9))), server.sent)
        // And only once: the debounce it overtook doesn't fire as well.
        advanceTimeBy(SettingsModel.STEPPER_DEBOUNCE_MILLIS * 2)
        assertEquals(1, server.sent.size)
    }

    @Test
    fun aSetOvertakesAnUnsettledRunOnTheSameKey() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        writer.step("n", SettingValue.Int(9))
        writer.set("n", SettingValue.Int(3))
        advanceTimeBy(SettingsModel.STEPPER_DEBOUNCE_MILLIS * 2)
        assertEquals(listOf(mapOf("n" to SettingValue.Int(3))), server.sent)
    }

    @Test
    fun aSettingsChangeRetiresTheRejection() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        writer.set("k", SettingValue.Bool(true))
        server.replies.single().complete("nope")
        runCurrent()
        writer.settingsChanged()
        assertNull(writer.edits.error)
    }
}
