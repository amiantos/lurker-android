// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's one upload run at a time, for the life of the process — what the paperclip, a paste and a
 * share all start, and what the readout above the composer shows.
 *
 * App-long rather than the conversation's, unlike iOS's (whose `uploadTask` hangs off the chat
 * screen and holds it alive): a Compose screen's scope is cancelled when the activity is recreated, and
 * a rotation must not cancel a 200 MB upload. It also settles where the readout goes when the reader
 * switches buffers mid-run — above whichever composer is on screen, which is also where iOS delivers
 * the link ([ComposerInserts.insertIntoActive]).
 *
 * **One at a time** — the busy gate every entry point checks ([busy]): the readout narrates one run,
 * and two interleaved runs would deliver links in an order nobody chose.
 *
 * Main-thread, in the app's `Main.immediate` scope.
 *
 * @param clipboard put links that had nowhere to land on the clipboard, in one write.
 */
class UploadRunner(
    private val scope: CoroutineScope,
    private val platform: UploadPlatform,
    private val inserts: ComposerInserts,
    private val clipboard: (String) -> Unit,
) {
    private val readoutFlow = MutableStateFlow<UploadReadout?>(null)

    /** The readout while a run is under way, null otherwise. */
    val readout: StateFlow<UploadReadout?> = readoutFlow.asStateFlow()

    private val reportFlow = MutableStateFlow<UploadReport?>(null)

    /** The last run's dialog, until it's acknowledged ([acknowledge]). */
    val report: StateFlow<UploadReport?> = reportFlow.asStateFlow()

    private val busyFlow = MutableStateFlow(false)

    /**
     * Whether a run is under way — until it has really ended, cancel included, so the paperclip stays
     * off through "Stopping…" rather than starting a second run atop the first.
     */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private var job: Job? = null

    /** Bumped by [reset], so a run cancelled by a sign-out can't report into the next session. */
    private var session = 0

    /** Start a run over [sources]. False — and nothing started — while one is under way, or for none. */
    fun start(sources: List<AttachmentSource>): Boolean {
        if (job != null || sources.isEmpty()) return false
        val generation = session
        val run = UploadRun(platform, readoutFlow, deliver = { url, first -> generation == session && inserts.insertIntoActive(url, atCaret = first) })
        // Lazy, then started: under `Main.immediate` the body can run inline in `start()`, and a run that
        // ended before `job` was assigned would leave it set for good — a dead paperclip until relaunch.
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = run.run(sources)
                if (generation == session) result.report()?.let { report ->
                    report.clipboard?.let(clipboard)
                    reportFlow.value = report
                }
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    busyFlow.value = false
                }
            }
        }
        job = launched
        busyFlow.value = true
        launched.start()
        return true
    }

    /**
     * The readout's ✕. Cancels the run — a provider copy and the upload stop promptly, a transcode at its
     * next check — and says "Stopping…" rather than hiding the readout: until the run really ends the
     * paperclip stays off, and a readout that vanished would leave nothing on screen to say why. The
     * run's own unwind takes it down.
     */
    fun cancel() {
        val running = job ?: return
        running.cancel()
        readoutFlow.value = readoutFlow.value?.update(UploadPhase.Stopping)
    }

    fun acknowledge() {
        reportFlow.value = null
    }

    /** Sign-out: stop whatever is running, and forget a report about the previous session's uploads. */
    fun reset() {
        session += 1
        job?.cancel()
        reportFlow.value = null
    }
}
