// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlin.math.min

/**
 * The video shrink's ladder and the arithmetic that picks a rung — lurker-ios's `VideoCompressor`
 * preset ladder and `plan(asset:maxBytes:)`, pure. `VideoCompressor` runs the transcodes.
 *
 * The server does **not** transcode video — it stores the bytes as-is, scrubbing only the container
 * metadata — so a raw phone clip has to be compressed here or it never fits: a 4K60 recording is
 * ~350 MB a minute, well past any instance's cap.
 *
 * **Port note:** iOS's rungs are AVFoundation export presets, each a roughly fixed bitrate the session
 * can estimate a size for (`estimateOutputFileLength`). media3's Transformer has neither presets nor an
 * estimate, so a rung here is the preset spelled out — a short side, a codec and a bitrate — and the
 * estimate is duration × bitrate, which is what the preset's own estimate is.
 */
object VideoLadder {
    /**
     * One rung: fit the short side to [shortSide] (never up), encode with [videoMime] at
     * [videoBitrate] bits a second. Every rung's output is MP4 (`video/mp4`), which every uploader
     * accepts and the server scrubs.
     */
    data class Rung(val shortSide: Int, val videoMime: String, val videoBitrate: Int)

    /** media3's `MimeTypes.VIDEO_H265` and `VIDEO_H264`, spelled out so this file stays plain Kotlin. */
    const val HEVC = "video/hevc"
    const val AVC = "video/avc"

    /**
     * Gentlest shrink first. HEVC 1080p is iOS's `AVAssetExportPresetHEVC1920x1080` (~50 MB a minute),
     * which clears a typical cap for all but the longest clips; below it H.264 at ever-lower sizes
     * (`1280x720`, `960x540`, `640x480`), as iOS has no HEVC preset under 1080p. The last rung is iOS's
     * `LowQuality` — a very aggressive small size — written out as 240p.
     *
     * ⚠ The floor is a floor, not a guarantee. The target is whatever the instance advertises (#149) and
     * can be far below what this ladder was tuned against — an instance may declare 25 MB, or a user may
     * cap themselves lower still — so a clip no rung gets under is a normal outcome, not a broken ladder.
     * That is what [Plan.Impossible] and `UploadError.CannotCompressEnough` are for.
     */
    val rungs: List<Rung> = listOf(
        Rung(shortSide = 1080, videoMime = HEVC, videoBitrate = 6_500_000),
        Rung(shortSide = 720, videoMime = AVC, videoBitrate = 4_000_000),
        Rung(shortSide = 540, videoMime = AVC, videoBitrate = 2_500_000),
        Rung(shortSide = 480, videoMime = AVC, videoBitrate = 1_500_000),
        Rung(shortSide = 240, videoMime = AVC, videoBitrate = 500_000),
    )

    /**
     * What the estimate allows for the audio track. Generous on purpose: a transcode passes a
     * compatible audio track through untouched, so it carries whatever bitrate the source recorded at.
     */
    const val AUDIO_ESTIMATE_BPS = 256_000

    /** Container overhead on top of the streams — the MP4 index and per-sample headers. */
    private const val CONTAINER_OVERHEAD = 1.03

    /**
     * The bitrate to ask of the encoder: the rung's, or the source's own video bitrate when that is
     * lower — re-encoding a lean clip at a rung's higher bitrate would make it BIGGER.
     */
    fun bitrate(rung: Rung, sourceVideoBitrate: Int?): Int =
        if (sourceVideoBitrate != null && sourceVideoBitrate > 0) min(rung.videoBitrate, sourceVideoBitrate) else rung.videoBitrate

    /** The short side to scale to, or null to leave the frame size alone (the source is no bigger). */
    fun outputShortSide(rung: Rung, sourceShortSide: Int?): Int? =
        if (sourceShortSide != null && sourceShortSide in 1..rung.shortSide) null else rung.shortSide

    /** The predicted size of [rung]'s output, in bytes. */
    fun estimate(rung: Rung, durationMs: Long, sourceVideoBitrate: Int?): Long {
        val bitsPerSecond = bitrate(rung, sourceVideoBitrate).toLong() + AUDIO_ESTIMATE_BPS
        return (bitsPerSecond * durationMs / 8_000.0 * CONTAINER_OVERHEAD).toLong()
    }

    /**
     * The source's video bitrate, roughly: its whole bitrate less the audio allowance. Null when the
     * duration is unknown.
     */
    fun sourceVideoBitrate(fileBytes: Long, durationMs: Long?): Int? {
        if (durationMs == null || durationMs <= 0) return null
        val total = fileBytes * 8_000.0 / durationMs
        return (total - AUDIO_ESTIMATE_BPS).toInt().takeIf { it > 0 }
    }

    /** The outcome of pre-flighting the ladder with size estimates. */
    sealed interface Plan {
        /** The highest-quality rung whose estimate fits — start transcoding here. */
        data class StartAt(val index: Int) : Plan

        /** Every rung is predicted over the cap — don't bother. */
        data object Impossible : Plan

        /** No usable estimate (no duration) — run the whole ladder and check real sizes. */
        data object Unknown : Plan
    }

    /**
     * Where — or whether — to transcode. Trusting the estimate is what lets this both jump straight to
     * the right rung (a high-res screen recording used to re-encode three times before one landed under
     * the cap) AND fail fast on a clip no rung can shrink enough, instead of transcoding a gigabyte to
     * confirm it.
     *
     * Estimates are approximate. If the smallest rung is only marginally over, it is still ATTEMPTED —
     * the real size check afterwards is the true gate; only a smallest estimate clearly (>10%) over the
     * cap gives up, so a borderline clip that would actually fit isn't rejected without trying.
     */
    fun plan(durationMs: Long?, maxBytes: Long, sourceVideoBitrate: Int?): Plan {
        if (durationMs == null || durationMs <= 0) return Plan.Unknown
        var smallestOverIndex: Int? = null
        var smallestOverEstimate = Long.MAX_VALUE
        for ((index, rung) in rungs.withIndex()) {
            val estimate = estimate(rung, durationMs, sourceVideoBitrate)
            if (estimate <= maxBytes) return Plan.StartAt(index)
            if (estimate < smallestOverEstimate) {
                smallestOverEstimate = estimate
                smallestOverIndex = index
            }
        }
        val index = smallestOverIndex ?: return Plan.Unknown
        return if (smallestOverEstimate <= (maxBytes * 1.1).toLong()) Plan.StartAt(index) else Plan.Impossible
    }
}
