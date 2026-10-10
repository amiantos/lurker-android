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
import net.amiantos.lurkerkit.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun severalKeysGoInOneWriteAndARefusalPinsUnderTheNamedRow() = runTest {
        val server = FakeServer()
        val writer = writer(server)
        val values = mapOf("a.sound.enabled" to SettingValue.Bool(true), "a.sound.choice" to SettingValue.String("knock"))
        writer.setAll(values, errorKey = "a.sound.choice")
        assertEquals(listOf(values), server.sent)
        assertEquals(values, writer.edits.pending)
        server.replies.single().complete("no such sound")
        runCurrent()
        assertEquals(WriteError("a.sound.choice", "no such sound"), writer.edits.error)
        assertTrue(writer.edits.pending.isEmpty())
        // Success clears every key the write carried, and nothing a later write set since.
        writer.setAll(values, errorKey = "a.sound.choice")
        writer.set("b", SettingValue.Bool(true))
        server.replies[1].complete(null)
        runCurrent()
        assertEquals(mapOf("b" to SettingValue.Bool(true)), writer.edits.pending)
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
        writer.observe(Settings())
        writer.set("k", SettingValue.Bool(true))
        server.replies.single().complete("nope")
        runCurrent()
        writer.observe(Settings(registry = emptyMap(), values = mapOf("other" to SettingValue.Bool(true))))
        assertNull(writer.edits.error)
    }

    @Test
    fun aRefusalSurvivesTheSameSettingsReportedAgain() = runTest {
        // A rotation recomposes the dialog, which reports what it reads afresh — nothing changed.
        val server = FakeServer()
        val writer = writer(server)
        val settings = Settings(registry = emptyMap(), values = mapOf("k" to SettingValue.Bool(false)))
        writer.observe(settings)
        writer.set("k", SettingValue.Bool(true))
        server.replies.single().complete("nope")
        runCurrent()
        writer.observe(settings.copy())
        assertEquals(WriteError("k", "nope"), writer.edits.error)
    }

    // MARK: - Order

    /**
     * A server whose successful reply replaces the client's whole stored set, as `updateSettings`
     * does (`Settings.replaceValues`) — and whose replies can be released in any order.
     */
    private class SnapshotServer(private val refuse: (Map<String, SettingValue>) -> String? = { null }) {
        private var stored = mapOf<String, SettingValue>()
        var inFlight = 0
            private set
        var maxInFlight = 0
            private set

        /** Replies not yet released: each with the answer decided when its request was handled. */
        private val held = mutableListOf<Pair<CompletableDeferred<Unit>, String?>>()

        /** What the client's store holds, from the replies it has applied. */
        val client = mutableMapOf<String, SettingValue>()

        suspend fun write(changes: Map<String, SettingValue>): String? {
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            val reason = refuse(changes)
            if (reason == null) stored = stored + changes
            // The reply carries the full set as it stood when THIS request was handled.
            val snapshot = stored
            val release = CompletableDeferred<Unit>()
            held += release to reason
            release.await()
            inFlight -= 1
            if (reason == null) {
                client.clear()
                client.putAll(snapshot)
            }
            return reason
        }

        /** Release whatever is out, newest first — the order that breaks unqueued writes. */
        fun releaseNewestFirst() {
            val out = held.toList().asReversed()
            held.clear()
            for ((release, _) in out) release.complete(Unit)
        }
    }

    @Test
    fun writesGoOneAtATimeInTheOrderTheyWereMade() = runTest {
        val server = SnapshotServer()
        val writer = SettingsWriter(backgroundScope) { server.write(it) }
        writer.set("k", SettingValue.Bool(true))
        writer.set("k", SettingValue.Bool(false))
        writer.set("j", SettingValue.Int(4))
        // Released newest-first every time; queued, there is only ever one out to release.
        repeat(4) {
            server.releaseNewestFirst()
            runCurrent()
        }
        assertEquals(1, server.maxInFlight)
        // Unqueued, the first reply (k=true, no j) would have landed last and replaced both.
        assertEquals(mapOf("k" to SettingValue.Bool(false), "j" to SettingValue.Int(4)), server.client)
        assertTrue(writer.edits.pending.isEmpty())
        assertNull(writer.edits.error)
    }

    @Test
    fun anOlderRefusalCantLandAfterANewerSuccess() = runTest {
        val server = SnapshotServer(refuse = { if (it["k"] == SettingValue.Int(500)) "out of range" else null })
        val writer = SettingsWriter(backgroundScope) { server.write(it) }
        writer.set("k", SettingValue.Int(500))
        writer.set("k", SettingValue.Int(5))
        repeat(3) {
            server.releaseNewestFirst()
            runCurrent()
        }
        assertEquals(1, server.maxInFlight)
        // The latest write succeeded, and its answer is the one on screen.
        assertNull(writer.edits.error)
        assertTrue(writer.edits.pending.isEmpty())
        assertEquals(mapOf("k" to SettingValue.Int(5)), server.client)
    }

    // MARK: - The store

    @Test
    fun theStoreHandsBackTheSameWriterForATokenAndFlushesOnDiscard() = runTest {
        val server = FakeServer()
        val store = SettingsWriterStore()
        var built = 0
        fun make() = SettingsWriter(backgroundScope) { server.write(it) }.also { built += 1 }
        val first = store.writer("t") { make() }
        first.step("n", SettingValue.Int(7))
        // A rotation asks again with the saved token: same writer, same edits on screen.
        val again = store.writer("t") { make() }
        assertSame(first, again)
        assertEquals(1, built)
        assertEquals(mapOf("n" to SettingValue.Int(7)), again.edits.pending)
        assertTrue(server.sent.isEmpty())
        // Dismissed: the settling run goes out now, and the next open starts clean.
        store.discard("t")
        assertEquals(listOf(mapOf("n" to SettingValue.Int(7))), server.sent)
        val next = store.writer("u") { make() }
        assertTrue(next.edits.pending.isEmpty())
        assertEquals(2, built)
    }
}
