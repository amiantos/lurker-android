// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.client.UploadError
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Shrinks a picked video to fit the instance's upload cap before it goes over the wire — lurker-ios's
 * `VideoCompressor`, over androidx.media3's Transformer. The ladder and the rung choice are
 * [VideoLadder]'s; this runs the transcodes and checks the real sizes.
 *
 * The server does **not** transcode video — it stores the bytes as-is, scrubbing only the container
 * metadata — so a raw phone clip has to be compressed here or it never fits. Images need none of this.
 */
@OptIn(markerClass = [UnstableApi::class])
class VideoCompressor(private val context: Context, private val cacheDirectory: File) {

    /**
     * Prepare [file] to fit under [maxBytes]: the original untouched when it already fits, else the
     * first rung's output that does. [onProgress] reports the transcode as 0…1, on the main thread.
     *
     * Throws `UploadError.CannotCompressEnough` when even the smallest rung stays over the cap (or is
     * predicted to), `UploadError.CompressionFailed` when every transcode errors, and
     * `CancellationException` on a cancel — re-raised as cancellation, not a failure, so a user's stop
     * never pops an error.
     *
     * ⚠⚠ [maxBytes] is required, and is the server's advertised cap (`ChatViewModel.uploadCapBytes`). It
     * once defaulted to a hard-coded 90 MiB on iOS, which gave every instance that isn't ours the wrong
     * answer in one direction or the other (#149).
     */
    suspend fun prepare(file: File, maxBytes: Long, onProgress: (Double) -> Unit): PreparedVideo {
        if (file.length() <= maxBytes) return PreparedVideo(file, isTemporary = false)

        val facts = withContext(Dispatchers.IO) { measure(file) }
        val sourceBitrate = VideoLadder.sourceVideoBitrate(file.length(), facts.durationMs)
        val start = when (val plan = VideoLadder.plan(facts.durationMs, maxBytes, sourceBitrate)) {
            is VideoLadder.Plan.StartAt -> plan.index
            // No duration to estimate from — run the whole ladder and check real sizes.
            VideoLadder.Plan.Unknown -> 0
            // Even the smallest rung is predicted over the cap. Don't burn a full transcode of a huge
            // file to confirm what the estimate already said — fail now.
            VideoLadder.Plan.Impossible -> throw UploadError.CannotCompressEnough
        }

        var completedButTooBig = false
        var lastFailure = "the video format isn't supported"
        for (rung in VideoLadder.rungs.drop(start)) {
            val out = File(cacheDirectory, "lurker-video-${UUID.randomUUID()}.mp4")
            try {
                transcode(file, out, rung, facts, sourceBitrate, onProgress)
            } catch (cancelled: CancellationException) {
                out.delete()
                throw cancelled
            } catch (failure: ExportException) {
                out.delete()
                // A genuine export error on this rung (HEVC this device can't encode, say) — try the
                // next, smaller one.
                lastFailure = failure.message ?: failure.errorCodeName
                continue
            }
            if (out.length() in 1..maxBytes) {
                onProgress(1.0)
                return PreparedVideo(out, isTemporary = true)
            }
            // Under the cap was the whole point and this rung missed it. Drop to the next — but remember
            // a valid file came out, so a ladder that runs out says "too large", not "couldn't process".
            completedButTooBig = true
            out.delete()
        }
        throw if (completedButTooBig) UploadError.CannotCompressEnough else UploadError.CompressionFailed(lastFailure)
    }

    /** What the estimate and the resize need to know about the source. */
    private data class Facts(val durationMs: Long?, val shortSide: Int?)

    private fun measure(file: File): Facts {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            // The short side whatever the rotation: a portrait clip's is its width as displayed, which
            // rotation doesn't change the length of.
            val shortSide = if (width != null && height != null) minOf(width, height) else null
            Facts(duration, shortSide)
        } catch (_: RuntimeException) {
            Facts(null, null)
        } finally {
            retriever.release()
        }
    }

    /**
     * One rung's transcode, on the main looper (Transformer's calls belong to the thread that built it,
     * and its work runs on threads of its own). Progress is polled, as iOS polls the export's states;
     * cancelling the coroutine cancels the export, so a cancelled upload stops the transcode promptly
     * instead of running it out.
     */
    private suspend fun transcode(
        source: File,
        out: File,
        rung: VideoLadder.Rung,
        facts: Facts,
        sourceBitrate: Int?,
        onProgress: (Double) -> Unit,
    ): Unit = withContext(Dispatchers.Main) {
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(VideoLadder.bitrate(rung, sourceBitrate)).build(),
            )
            // A device that can't do what's asked (no HEVC encoder, a size its encoder refuses) gets the
            // nearest it can, rather than an error on the rung.
            .setEnableFallback(true)
            .build()
        val effects = VideoLadder.outputShortSide(rung, facts.shortSide)
            ?.let { Effects(emptyList(), listOf(Presentation.createForShortSide(it))) }
            ?: Effects.EMPTY
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).setEffects(effects).build()

        val poll = launch {
            val holder = ProgressHolder()
            while (true) {
                delay(PROGRESS_POLL_MS)
                if (transformerRef?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100.0)
                }
            }
        }
        try {
            suspendCancellableCoroutine<ExportException?> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(rung.videoMime)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(null)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resume(exportException)
                            }
                        },
                    )
                    .build()
                transformerRef = transformer
                continuation.invokeOnCancellation {
                    // Called on the canceller's thread; Transformer wants its own. Posted, not launched:
                    // a coroutine started in this scope would be cancelled before it ran.
                    mainHandler.post { transformer.cancel() }
                }
                transformer.start(item, out.absolutePath)
            }?.let { throw it }
        } finally {
            poll.cancel()
            transformerRef = null
        }
        ensureActive()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The export in flight, for the progress poll — main-thread only. */
    private var transformerRef: Transformer? = null

    private companion object {
        /** iOS polls its export every 0.2 s. */
        const val PROGRESS_POLL_MS = 200L
    }
}
