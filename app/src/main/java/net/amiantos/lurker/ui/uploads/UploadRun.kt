// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import net.amiantos.lurkerkit.client.ImageShrink
import net.amiantos.lurkerkit.client.UploadBatch
import net.amiantos.lurkerkit.client.UploadError
import net.amiantos.lurkerkit.client.UploadProgress
import net.amiantos.lurkerkit.client.UploadResponse
import net.amiantos.lurkerkit.client.UploadServerProgress
import net.amiantos.lurkerkit.support.Result
import java.io.File
import java.util.UUID

/**
 * One chosen item, **not yet on disk** — lurker-ios's `AttachmentPicker.Source`.
 *
 * **The pick and the copy are deliberately separate steps.** A pick returns handles at once, and each
 * is staged to a cache file only when its turn comes, immediately before that file uploads. Both
 * pickers are multi-select, and staging the whole selection up front would put all of it on disk at
 * once — fifteen 4K videos is ~10 GB copied before a single byte uploads, which ends in a failed copy.
 * Staged one at a time and deleted after each upload, peak cache use is one file however many were
 * picked. It also puts the whole run inside the one cancellable job: a cancel during the third file's
 * cloud download stops the remaining twelve.
 */
sealed interface AttachmentSource {
    /**
     * A `content://` address — from the photo picker, the document picker, a paste or a share. A string
     * rather than a `Uri`, so the pipeline stays plain Kotlin; the platform parses it at its turn.
     */
    data class Content(val uri: String) : AttachmentSource

    /**
     * Something offered that can't be read — kept in the list rather than filtered out of it, so it
     * fails loudly at its turn and is counted with the rest. Dropping it would make the batch quietly
     * smaller than the pick.
     */
    data object Unsupported : AttachmentSource

    /** Already staged by the caller. Passes through staging untouched, so one path covers every entry point. */
    data class Ready(val picked: Picked) : AttachmentSource
}

/**
 * A picked file, copied somewhere this app owns and deletes when done — lurker-ios's
 * `AttachmentPicker.Picked`.
 *
 * @property isVideo the one class compressed on the device before uploading. Audio passes straight
 *   through (it is already small), and images are only ever redrawn smaller — the server does the real
 *   re-encode (`ImageConverter`).
 */
data class Picked(val file: File, val filename: String, val mime: String, val isVideo: Boolean)

/** How staging one source went. */
sealed interface StageResult {
    data class Staged(val picked: Picked) : StageResult

    /** Couldn't get the bytes — in the provider's or the file system's own words. */
    data class Failed(val reason: String) : StageResult

    /** The staging itself was cancelled — the user, not a bad file. */
    data object Cancelled : StageResult
}

/** A redrawn image in a cache file the run owns (`ImageConverter`). */
data class PreparedImage(val file: File, val format: ImageShrink.Format)

/**
 * A video prepared for upload (`VideoCompressor`).
 *
 * @property isTemporary true when [file] is a fresh transcode the run must delete; false when it is the
 *   untouched original, already small enough to pass through.
 */
data class PreparedVideo(val file: File, val isTemporary: Boolean)

/**
 * The device work an upload run needs, behind one interface so the run's decisions are testable
 * without a device. Implemented by `AndroidUploadPlatform`.
 *
 * Every callback is delivered on the MAIN thread, and queued rather than run inline (as iOS hops each
 * one to the main actor) — the run's staleness gate depends on it.
 */
interface UploadPlatform {
    /** Copy one picked item into a cache file. Called only for [AttachmentSource.Content]. */
    suspend fun stage(source: AttachmentSource.Content): StageResult

    /** A redrawn image, or null to upload the original untouched — which is also what a failure returns. */
    suspend fun prepareImage(file: File, maxStaticImageDimension: Int?): PreparedImage?

    /**
     * Fit a video under [maxBytes]. Throws `UploadError.CannotCompressEnough` when even the smallest
     * rung stays over, `UploadError.CompressionFailed` when the transcode itself fails, and
     * `CancellationException` on a cancel.
     */
    suspend fun prepareVideo(file: File, maxBytes: Long, onProgress: (Double) -> Unit): PreparedVideo

    suspend fun upload(
        file: File,
        filename: String,
        mime: String,
        progressToken: String,
        onProgress: (Double) -> Unit,
        onServerProgress: (UploadServerProgress) -> Unit,
    ): Result<UploadResponse, UploadError>

    fun delete(file: File)

    /** The server's advertised cap, read at the moment it's used: it is refreshed every reconnect (#149). */
    val uploadCapBytes: Long

    /** Read at the moment it's used, like the cap (#155). */
    val maxStaticImageDimension: Int?
}

/**
 * What a finished run has to say, if anything: one dialog for the whole run, assembled from whatever it
 * has to say. Two in a row would bury the first, and a stack of them over the buffer list would be a
 * wall to dismiss either way.
 *
 * @property clipboard the links that had nowhere to go, already put on the clipboard.
 */
data class UploadReport(val title: String, val message: String, val clipboard: String?)

/** How a run ended, counted the way `UploadBatch.summary` reads it. */
data class UploadRunResult(
    val picked: Int,
    val uploaded: Int,
    val failures: List<UploadError>,
    /** Links that landed with no composer on screen to take them — they go to the clipboard. */
    val orphaned: List<String>,
    val unreadable: Int,
    val unreadableReason: String?,
    val cancelled: Boolean,
) {
    /** The run's one dialog, or null when there's nothing worth interrupting for — see [UploadReport]. */
    fun report(): UploadReport? {
        var title = "Upload finished"
        val sentences = mutableListOf<String>()
        val summary = UploadBatch.summary(
            picked = picked, uploaded = uploaded, failures = failures,
            unreadable = unreadable, unreadableReason = unreadableReason, cancelled = cancelled,
        )
        if (!summary.isNullOrEmpty()) {
            // "Upload failed" is a lie when four of five worked.
            title = if (uploaded > 0) "Upload incomplete" else "Upload failed"
            sentences.add(summary)
        }
        if (orphaned.isNotEmpty()) {
            // One clipboard write too: each would clobber the last.
            sentences.add(if (orphaned.size == 1) "The link is on your clipboard." else "${orphaned.size} links are on your clipboard.")
        }
        if (sentences.isEmpty()) return null
        return UploadReport(title = title, message = sentences.joinToString(" "), clipboard = orphaned.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }
}

/**
 * One upload run: a pick, one file at a time, each staged → shrunk or compressed → uploaded → its link
 * inserted — the pipeline of lurker-ios's `ChatViewController` MARK "Attachments (#14)"
 * (`beginUpload`, `performUpload`), with the device work behind [UploadPlatform].
 *
 * **Sequential rather than concurrent:** each upload is its own request (the server takes one file per
 * `POST`), the readout can only narrate one at a time, and a phone pushing five videos at once would
 * just make all five slower.
 *
 * **Cancelled by its job.** A cancel stops at the next suspension — a copy, a transcode, the upload —
 * and the run still returns, counted, so what already went wrong before the cancel is reported.
 * Nothing after the cancel suspends again.
 *
 * Main-thread: [readout] is drawn by the composer, and the platform delivers every callback here.
 *
 * @param deliver put a finished link in the composer on screen NOW (the user may have switched buffers
 *   since starting), claiming the caret only when [first]. False when there's no composer to take it.
 */
class UploadRun(
    private val platform: UploadPlatform,
    private val readout: MutableStateFlow<UploadReadout?>,
    private val deliver: (url: String, first: Boolean) -> Boolean,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    /** Where a file's upload lands — a failure is a case, not an exception. */
    private sealed interface Outcome {
        data class Inserted(val url: String) : Outcome

        data class Failed(val error: UploadError) : Outcome

        data object Cancelled : Outcome
    }

    /**
     * The upload's folded progress, shared by its two callbacks — the HTTP client's and the WS's — which
     * only their combination can turn into the leg the readout names.
     *
     * [isCurrent] is cleared once this file is done, whichever leg it reached: a callback queued just
     * before the upload returned can run AFTER the next file has claimed the readout — stamping
     * "1/3 · Uploading… 99%" over a file 2 already underway.
     */
    private class ProgressBox {
        var value = UploadProgress()
        var isCurrent = true
    }

    private fun update(phase: UploadPhase, batch: UploadBatchPosition) {
        readout.value = readout.value?.update(phase, batch)
    }

    suspend fun run(sources: List<AttachmentSource>): UploadRunResult {
        var uploaded = 0
        val failures = mutableListOf<UploadError>()
        val orphaned = mutableListOf<String>()
        var unreadable = 0
        var unreadableReason: String? = null
        var cancelled = false

        readout.value = UploadReadout.present(UploadPhase.Preparing, UploadBatchPosition(1, sources.size))
        try {
            files@ for ((index, source) in sources.withIndex()) {
                if (!currentCoroutineContext().isActive) {
                    cancelled = true
                    break
                }
                val batch = UploadBatchPosition(index + 1, sources.size)
                update(UploadPhase.Preparing, batch)
                val item = when (val staged = stage(source)) {
                    is StageResult.Staged -> staged.picked
                    StageResult.Cancelled -> {
                        cancelled = true
                        break@files
                    }
                    is StageResult.Failed -> {
                        // Couldn't even get the bytes: counted, so the summary can say the batch was
                        // smaller than the pick rather than letting a file quietly vanish — and quoted,
                        // because "No space left on device" is something the user can act on.
                        unreadable += 1
                        if (unreadableReason == null) unreadableReason = staged.reason
                        continue@files
                    }
                }
                // Cancelled while that file was being copied: stop here, before a redraw or a
                // compression pass starts on a file nobody wants, and delete the copy.
                if (!currentCoroutineContext().isActive) {
                    platform.delete(item.file)
                    cancelled = true
                    break@files
                }
                when (val outcome = perform(item, batch)) {
                    is Outcome.Inserted -> {
                        uploaded += 1
                        // Only the first link claims the caret and the keyboard; the rest of a long run
                        // append at the end, so they don't cut a caption in half or shove the keyboard
                        // back up minutes later. No composer on screen (the reader backed out to the
                        // list) and the link is held for the clipboard — a successful upload whose link
                        // goes nowhere, silently, is the worst of the outcomes.
                        if (!deliver(outcome.url, uploaded == 1)) orphaned.add(outcome.url)
                    }
                    is Outcome.Failed -> failures.add(outcome.error)
                    Outcome.Cancelled -> cancelled = true
                }
                // One bad file doesn't condemn the rest — but a dead session, a broken transport, or
                // the same refusal twice running will fail every remaining one identically, and slowly.
                if (cancelled || UploadBatch.shouldStop(failures)) break@files
            }
        } finally {
            readout.value = null
        }
        return UploadRunResult(
            picked = sources.size,
            uploaded = uploaded,
            failures = failures,
            orphaned = orphaned,
            unreadable = unreadable,
            unreadableReason = unreadableReason,
            cancelled = cancelled,
        )
    }

    private suspend fun stage(source: AttachmentSource): StageResult =
        when (source) {
            is AttachmentSource.Ready -> StageResult.Staged(source.picked)
            AttachmentSource.Unsupported -> StageResult.Failed("That item can't be uploaded.")
            is AttachmentSource.Content -> try {
                platform.stage(source)
            } catch (_: CancellationException) {
                StageResult.Cancelled
            } catch (error: Exception) {
                // A provider's refusal is one unreadable file, never the end of the app: whatever a
                // content provider throws for an address it won't serve, it's counted with the rest.
                StageResult.Failed(error.message ?: "Couldn't read the file.")
            }
        }

    /**
     * Compress a video or redraw an image, then upload, reporting each phase. The staged copy and any
     * derivative are deleted on every exit. A cancel is its own outcome, so a user-initiated stop never
     * becomes an error.
     */
    private suspend fun perform(picked: Picked, batch: UploadBatchPosition): Outcome {
        var file = picked.file
        var filename = picked.filename
        var mime = picked.mime
        var derived: File? = null
        val progress = ProgressBox()
        try {
            if (picked.isVideo) {
                update(UploadPhase.Compressing(0.0), batch)
                try {
                    // The cap read now, not when the screen was built: it moves on every reconnect.
                    val prepared = platform.prepareVideo(picked.file, platform.uploadCapBytes) { fraction ->
                        // Same staleness gate as the upload legs.
                        if (progress.isCurrent) update(UploadPhase.Compressing(fraction), batch)
                    }
                    if (prepared.isTemporary) {
                        derived = prepared.file
                        file = prepared.file
                        filename = AttachmentNaming.replacingExtension(filename, "mp4")
                        mime = "video/mp4"
                    }
                } catch (_: CancellationException) {
                    return Outcome.Cancelled
                } catch (error: UploadError) {
                    return Outcome.Failed(error)
                } catch (error: Exception) {
                    return Outcome.Failed(UploadError.CompressionFailed(error.message ?: error.toString()))
                }
            } else {
                val converted = try {
                    platform.prepareImage(file, platform.maxStaticImageDimension)
                } catch (_: CancellationException) {
                    return Outcome.Cancelled
                } catch (_: Exception) {
                    // A redraw that broke uploads the original instead, as a refused one does.
                    null
                }
                if (converted != null) {
                    // Pixels the server would have thrown away, or a HEIC its libheif can't decode —
                    // either way, a smaller ordinary image goes up in its place.
                    derived = converted.file
                    file = converted.file
                    filename = AttachmentNaming.replacingExtension(filename, converted.format.fileExtension)
                    mime = converted.format.mime
                }
            }

            if (!currentCoroutineContext().isActive) return Outcome.Cancelled
            update(UploadPhase.Uploading(0.0), batch)
            val result = try {
                platform.upload(
                    file = file,
                    filename = filename,
                    mime = mime,
                    progressToken = newToken(),
                    onProgress = { fraction ->
                        if (progress.isCurrent) {
                            progress.value = progress.value.apply(deviceFraction = fraction)
                            update(UploadPhase.of(progress.value), batch)
                        }
                    },
                    onServerProgress = { frame ->
                        if (progress.isCurrent) {
                            progress.value = progress.value.apply(server = frame)
                            update(UploadPhase.of(progress.value), batch)
                        }
                    },
                )
            } catch (_: CancellationException) {
                return Outcome.Cancelled
            }
            if (!currentCoroutineContext().isActive) return Outcome.Cancelled
            return when (result) {
                is Result.Success -> Outcome.Inserted(result.value.url)
                is Result.Failure -> Outcome.Failed(result.error)
            }
        } finally {
            progress.isCurrent = false
            platform.delete(picked.file)
            derived?.let(platform::delete)
        }
    }
}
