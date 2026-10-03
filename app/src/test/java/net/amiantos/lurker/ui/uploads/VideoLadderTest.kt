// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ladder's rung choice — lurker-ios's `VideoCompressor.plan`, with the estimate spelled out. */
class VideoLadderTest {
    private val mb = 1_000_000L

    @Test
    fun aShortClipStartsAtTheTopRung() {
        // 60 s at ~6.76 Mbps ≈ 52 MB, under 100 MB.
        assertEquals(VideoLadder.Plan.StartAt(0), VideoLadder.plan(durationMs = 60_000, maxBytes = 100 * mb, sourceVideoBitrate = null))
    }

    @Test
    fun aLongerClipJumpsStraightToTheRungThatFits() {
        // 10 min: 1080p ≈ 522 MB, 720p ≈ 329 MB, 540p ≈ 213 MB, 480p ≈ 135 MB.
        assertEquals(VideoLadder.Plan.StartAt(3), VideoLadder.plan(durationMs = 600_000, maxBytes = 150 * mb, sourceVideoBitrate = null))
    }

    @Test
    fun aBorderlineSmallestRungIsStillAttempted() {
        // 10 min at the 240p rung ≈ 58 MB; a 55 MB cap is within the 10% the estimate is allowed.
        val smallest = VideoLadder.rungs.lastIndex
        assertEquals(VideoLadder.Plan.StartAt(smallest), VideoLadder.plan(durationMs = 600_000, maxBytes = 55 * mb, sourceVideoBitrate = null))
    }

    @Test
    fun aClipNoRungCanFitFailsWithoutTranscoding() {
        assertEquals(VideoLadder.Plan.Impossible, VideoLadder.plan(durationMs = 3_600_000, maxBytes = 25 * mb, sourceVideoBitrate = null))
    }

    @Test
    fun noDurationRunsTheWholeLadder() {
        assertEquals(VideoLadder.Plan.Unknown, VideoLadder.plan(durationMs = null, maxBytes = 25 * mb, sourceVideoBitrate = null))
        assertEquals(VideoLadder.Plan.Unknown, VideoLadder.plan(durationMs = 0, maxBytes = 25 * mb, sourceVideoBitrate = null))
    }

    @Test
    fun aLeanSourceIsNeverReEncodedAtAHigherBitrate() {
        val top = VideoLadder.rungs.first()
        assertEquals(2_000_000, VideoLadder.bitrate(top, sourceVideoBitrate = 2_000_000))
        assertEquals(top.videoBitrate, VideoLadder.bitrate(top, sourceVideoBitrate = null))
        assertEquals(top.videoBitrate, VideoLadder.bitrate(top, sourceVideoBitrate = 40_000_000))
    }

    @Test
    fun theSourceBitrateLeavesRoomForAudio() {
        // 100 MB over 100 s is 8 Mbps, less the audio allowance.
        assertEquals(8_000_000 - VideoLadder.AUDIO_ESTIMATE_BPS, VideoLadder.sourceVideoBitrate(100 * mb, 100_000))
        assertNull(VideoLadder.sourceVideoBitrate(100 * mb, null))
    }

    @Test
    fun aRungNeverScalesUp() {
        val top = VideoLadder.rungs.first()
        assertNull(VideoLadder.outputShortSide(top, sourceShortSide = 720))
        assertNull(VideoLadder.outputShortSide(top, sourceShortSide = 1080))
        assertEquals(1080, VideoLadder.outputShortSide(top, sourceShortSide = 2160))
        assertEquals(1080, VideoLadder.outputShortSide(top, sourceShortSide = null))
    }

    @Test
    fun theLadderGetsGentlerToHarsher() {
        val rungs = VideoLadder.rungs
        assertTrue(rungs.zipWithNext().all { (a, b) -> a.videoBitrate > b.videoBitrate && a.shortSide > b.shortSide })
        assertEquals(VideoLadder.HEVC, rungs.first().videoMime)
    }
}
