// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.model.SettingValue

/**
 * The settings screen's writes, and the [SettingsEdits] they leave on screen while they're out.
 *
 * One `PATCH` per change, each carrying one key: `ChatViewModel.updateSettings` returns the server's
 * reason on refusal, and a write of several keys would pin that reason under no row in particular.
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

    /** The store's settings moved — from anywhere. See [SettingsEdits.settingsChanged]. */
    fun settingsChanged() {
        edits = edits.settingsChanged()
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
     * Started undispatched, so the request is under way before this returns — a flush at disposal
     * must not depend on a dispatch that may never come.
     */
    private fun send(key: String, value: SettingValue) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = withContext(NonCancellable) { write(mapOf(key to value)) }
            edits = edits.finished(key, value, failure)
        }
    }
}
