// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.client.UploadProgress
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What an upload is doing, in the order it happens — lurker-ios's `UploadStatusView.Phase`.
 *
 * [Preparing] covers the silent stretch before anything can report progress: the picked item being
 * copied out of its provider (a download, for a cloud file) and staged. Compression progress is
 * coarse; the device→server leg is a real fraction.
 *
 * The last two are the server's own legs, narrated over the WS (lurker-ios#47). A percentage appears
 * only where one is real: [Processing] is a native one-shot with nothing to count, and [Sending] has a
 * number only when the uploader driver reports bytes. An indeterminate label is honest about an
 * unmeasurable phase; the "Uploading… 100%" that used to sit there through both of these was not.
 */
sealed interface UploadPhase {
    data object Preparing : UploadPhase

    data class Compressing(val fraction: Double) : UploadPhase

    data class Uploading(val fraction: Double) : UploadPhase

    data object Processing : UploadPhase

    data class Sending(val fraction: Double?, val destination: String?) : UploadPhase

    /**
     * Cancelled, but not yet stopped. Neither a provider copy nor a compression pass ends on the
     * instant, and until the run really ends the paperclip stays disabled — so hiding the readout the
     * moment ✕ is tapped trades a beat of responsiveness for a stretch where the attach button does
     * nothing and nothing on screen says why.
     */
    data object Stopping : UploadPhase

    companion object {
        /**
         * The readout for an upload's folded progress. The two on-device phases ([Preparing],
         * [Compressing]) happen before an upload exists, so `UploadProgress` doesn't model them and this
         * never produces them.
         */
        fun of(progress: UploadProgress): UploadPhase =
            when (progress.stage) {
                UploadProgress.Stage.Uploading -> Uploading(progress.deviceFraction)
                UploadProgress.Stage.Processing -> Processing
                UploadProgress.Stage.Sending -> Sending(progress.sentFraction, progress.destination)
            }
    }
}

/**
 * Which file of how many, when a pick produced more than one. Shown as a prefix rather than folded into
 * each phase's wording, because it's true of every phase and the alternative is five labels that each
 * have to remember to say it.
 *
 * @property index 1-based, to be read aloud ("2 of 4"), not indexed with.
 */
data class UploadBatchPosition(val index: Int, val count: Int)

/**
 * The readout as drawn: a phase, where in the batch, and the stopping latch.
 *
 * ⚠ [stopping] is terminal for a run. The legs still unwinding behind a cancel keep reporting — a
 * compression pass ticks until it notices, and progress callbacks are already in flight to the main
 * thread — and any one of them would otherwise repaint a live percentage over "Stopping…" and hand back
 * the ✕ the user just pressed. Cleared only by [present], which is what starts a run.
 */
data class UploadReadout(
    val phase: UploadPhase,
    val batch: UploadBatchPosition?,
    val stopping: Boolean = false,
) {
    /** A later phase, unless this run is stopping — see [stopping]. */
    fun update(next: UploadPhase, batch: UploadBatchPosition? = null): UploadReadout {
        if (stopping) return this
        return UploadReadout(next, batch, stopping = next == UploadPhase.Stopping)
    }

    /** The label on screen — "2/4 · Uploading… 42%". */
    val label: String get() = UploadStatusWords.label(phase, batch)

    /** What TalkBack says — the position as a sentence of its own rather than the glyph. */
    val accessibility: String get() = UploadStatusWords.accessibility(phase, batch)

    /** Whether ✕ is offered: gone once it has been pressed, since pressing it again does nothing. */
    val showsCancel: Boolean get() = phase != UploadPhase.Stopping

    companion object {
        /** A new run: whatever the last one ended as no longer applies. */
        fun present(phase: UploadPhase, batch: UploadBatchPosition? = null) = UploadReadout(phase, batch, stopping = phase == UploadPhase.Stopping)
    }
}

/** The readout's words, character for character with lurker-ios's `UploadStatusView.update`. */
object UploadStatusWords {
    fun phase(phase: UploadPhase): String =
        when (phase) {
            UploadPhase.Preparing -> "Preparing…"
            is UploadPhase.Compressing -> if (phase.fraction > 0.01) "Compressing… ${percent(phase.fraction)}" else "Compressing…"
            is UploadPhase.Uploading -> "Uploading… ${percent(phase.fraction)}"
            UploadPhase.Processing -> "Processing…"
            is UploadPhase.Sending -> {
                // Name the provider when the server told us which one, so the long wait is legibly
                // "this is going to Catbox" rather than an anonymous stall.
                val sendingTo = phase.destination?.let { "Sending to $it" } ?: "Sending"
                phase.fraction?.let { "$sendingTo… ${percent(it)}" } ?: "$sendingTo…"
            }
            UploadPhase.Stopping -> "Stopping…"
        }

    /**
     * The batch prefix only when there is genuinely more than one — a lone "1/1" on every single-file
     * upload would be noise dressed as information.
     */
    fun label(phase: UploadPhase, batch: UploadBatchPosition?): String {
        val words = phase(phase)
        return if (batch != null && batch.count > 1) "${batch.index}/${batch.count} · $words" else words
    }

    /** Spoken in full: "1/4" reads as a fraction or a date, and the slash shouldn't survive into speech. */
    fun accessibility(phase: UploadPhase, batch: UploadBatchPosition?): String {
        val words = phase(phase)
        return if (batch != null && batch.count > 1) "File ${batch.index} of ${batch.count}. $words" else words
    }

    fun percent(fraction: Double): String = "${(max(0.0, min(1.0, fraction)) * 100).roundToInt()}%"
}
