// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings

/**
 * The settings screen's writes, and the [SettingsEdits] they leave on screen while they're out.
 *
 * One `PATCH` per change, each carrying one key: `ChatViewModel.updateSettings` returns the server's
 * reason on refusal, and a write of several keys would pin that reason under no row in particular.
 *
 * ⚠⚠ **One write at a time, in the order the user made them.** A successful `updateSettings` applies
 * the reply's `values` to the store as the authoritative FULL set (`Settings.replaceValues`), so two
 * writes answered out of order would let the older reply put back the value the newer one replaced —
 * and an older refusal landing last would pin a reason under a control whose latest write succeeded.
 * Queued behind a fair [Mutex], taken in input order, every reply is the newest the server has sent.
 *
 * ⚠ Each write runs `NonCancellable`. Closing Settings straight after flipping a switch must not
 * abort the request half-way — a write torn down mid-flight is one the server may or may not have
 * acted on, and the user saw the switch move.
 *
 * @param scope where writes and stepper debounces run — the kit's calls want the main thread.
 * @param write `ChatViewModel.updateSettings`: null on success, else the server's reason.
 */
internal class SettingsWriter(
    private val scope: CoroutineScope,
    private val write: suspend (Map<String, SettingValue>) -> String?,
) {
    /** What the screen draws over the store's values: the pending choices and the last refusal. */
    var edits by mutableStateOf(SettingsEdits())
        private set

    /** Stepper runs waiting to settle, by key, with the value each will send. */
    private val settling = mutableMapOf<String, Pair<Job, SettingValue>>()

    /** Held by the write in flight; the rest queue behind it, first come first served. */
    private val inFlight = Mutex()

    /** The store's settings as this writer last saw them — see [observe]. */
    private var seen: Settings? = null

    /** A toggle or a pull-down: shown at once, sent at once. */
    fun set(key: String, value: SettingValue) {
        // A run on the same key that hasn't settled is overtaken, not sent after this.
        settling.remove(key)?.first?.cancel()
        edits = edits.began(key, value)
        send(key, value)
    }

    /**
     * A stepper tap: the number moves now, the write goes once the run settles.
     *
     * A stepper held or tapped quickly would otherwise fire a `PATCH` per increment — a request each,
     * a reply each racing the next tap, and the same value sent twice on a fast double-tap. Waiting
     * for the run to settle sends one request carrying the final value.
     */
    fun step(key: String, value: SettingValue) {
        edits = edits.began(key, value)
        settling.remove(key)?.first?.cancel()
        val job = scope.launch {
            delay(SettingsModel.STEPPER_DEBOUNCE_MILLIS)
            settling.remove(key)
            send(key, value)
        }
        settling[key] = job to value
    }

    /**
     * The store's settings as the screen now reads them. A change from what was last seen — from
     * anywhere — retires a rejection ([SettingsEdits.settingsChanged]).
     *
     * Compared here rather than taken on faith from the caller, because the screen reports what it
     * reads every time it's composed afresh — a rotation included — and a refusal must survive a
     * rotation that changed nothing.
     */
    fun observe(settings: Settings) {
        val previous = seen
        seen = settings
        if (previous != null && previous != settings) edits = edits.settingsChanged()
    }

    /**
     * Send every run that hasn't settled, now — the screen is going. iOS's timer died with its screen
     * and dropped the last stepper value on the floor; a number the user left on screen is one they
     * meant.
     */
    fun flush() {
        val runs = settling.toList()
        settling.clear()
        for ((key, run) in runs) {
            run.first.cancel()
            send(key, run.second)
        }
    }

    /**
     * Started undispatched, so this write joins the queue before this returns — in the order the
     * changes were made, and without depending on a dispatch that may never come (a flush at close).
     */
    private fun send(key: String, value: SettingValue) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                inFlight.withLock {
                    val failure = write(mapOf(key to value))
                    edits = edits.finished(key, value, failure)
                }
            }
        }
    }
}

/**
 * The open Settings dialog's writer, kept across configuration changes. A rotation recreates the
 * activity and the dialog's composition with it; a writer living there would be rebuilt with empty
 * [SettingsWriter.edits] — every control with a write still out snapping back until the old request
 * answered — and a stepper run still settling would be flushed early. U4's `NetworksFlowStore`, for
 * the same reason.
 *
 * Keyed by a token the dialog saves, so a dialog restored after a rotation finds its writer, and one
 * restored after a process death (token saved, store empty) starts clean. Discarded when Settings is
 * actually dismissed: its settling runs go out then, and its writes still run to their replies.
 */
internal class SettingsWriterStore : ViewModel() {
    private val writers = mutableMapOf<String, SettingsWriter>()

    fun writer(token: String, create: () -> SettingsWriter): SettingsWriter = writers.getOrPut(token, create)

    fun discard(token: String) {
        writers.remove(token)?.flush()
    }

    override fun onCleared() {
        writers.values.forEach { it.flush() }
        writers.clear()
    }
}
