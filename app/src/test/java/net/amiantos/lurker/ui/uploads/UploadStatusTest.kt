// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.client.UploadProgress
import net.amiantos.lurkerkit.client.UploadServerProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The readout's words and its stopping latch — lurker-ios's `UploadStatusView.update`. */
class UploadStatusTest {
    @Test
    fun eachPhaseSaysWhatItIsDoing() {
        assertEquals("Preparing…", UploadStatusWords.phase(UploadPhase.Preparing))
        assertEquals("Uploading… 42%", UploadStatusWords.phase(UploadPhase.Uploading(0.42)))
        assertEquals("Processing…", UploadStatusWords.phase(UploadPhase.Processing))
        assertEquals("Stopping…", UploadStatusWords.phase(UploadPhase.Stopping))
    }

    @Test
    fun compressionShowsAPercentageOnlyOnceThereIsOne() {
        assertEquals("Compressing…", UploadStatusWords.phase(UploadPhase.Compressing(0.0)))
        assertEquals("Compressing…", UploadStatusWords.phase(UploadPhase.Compressing(0.01)))
        assertEquals("Compressing… 37%", UploadStatusWords.phase(UploadPhase.Compressing(0.37)))
    }

    @Test
    fun sendingNamesTheProviderAndCountsOnlyWhenItCan() {
        assertEquals("Sending…", UploadStatusWords.phase(UploadPhase.Sending(null, null)))
        assertEquals("Sending to Catbox…", UploadStatusWords.phase(UploadPhase.Sending(null, "Catbox")))
        assertEquals("Sending to Catbox… 70%", UploadStatusWords.phase(UploadPhase.Sending(0.7, "Catbox")))
        assertEquals("Sending… 5%", UploadStatusWords.phase(UploadPhase.Sending(0.05, null)))
    }

    @Test
    fun percentagesClampAndRound() {
        assertEquals("0%", UploadStatusWords.percent(-0.5))
        assertEquals("100%", UploadStatusWords.percent(1.7))
        assertEquals("100%", UploadStatusWords.percent(0.996))
        assertEquals("0%", UploadStatusWords.percent(0.004))
    }

    @Test
    fun theBatchPrefixAppearsOnlyForMoreThanOneFile() {
        assertEquals("Uploading… 10%", UploadStatusWords.label(UploadPhase.Uploading(0.1), UploadBatchPosition(1, 1)))
        assertEquals("Uploading… 10%", UploadStatusWords.label(UploadPhase.Uploading(0.1), null))
        assertEquals("2/4 · Uploading… 10%", UploadStatusWords.label(UploadPhase.Uploading(0.1), UploadBatchPosition(2, 4)))
    }

    @Test
    fun theBatchIsSpokenAsASentenceNotAFraction() {
        assertEquals("File 2 of 4. Processing…", UploadStatusWords.accessibility(UploadPhase.Processing, UploadBatchPosition(2, 4)))
        assertEquals("Processing…", UploadStatusWords.accessibility(UploadPhase.Processing, UploadBatchPosition(1, 1)))
    }

    @Test
    fun stoppingIsTerminalUntilTheNextRunPresents() {
        val stopping = UploadReadout.present(UploadPhase.Uploading(0.3), UploadBatchPosition(1, 3)).update(UploadPhase.Stopping)
        assertTrue(stopping.stopping)
        assertFalse(stopping.showsCancel)
        // A straggling tick from a leg still unwinding mustn't repaint a live percentage over it.
        val late = stopping.update(UploadPhase.Uploading(0.9), UploadBatchPosition(1, 3))
        assertEquals(stopping, late)
        assertEquals("Stopping…", late.label)
        // A new run gets its ✕ back.
        val next = UploadReadout.present(UploadPhase.Preparing)
        assertFalse(next.stopping)
        assertTrue(next.showsCancel)
    }

    @Test
    fun foldedProgressMapsOntoTheServerLegs() {
        var progress = UploadProgress()
        assertEquals(UploadPhase.Uploading(0.0), UploadPhase.of(progress))
        progress = progress.apply(deviceFraction = 1.0)
        assertEquals(UploadPhase.Processing, UploadPhase.of(progress))
        progress = progress.apply(server = UploadServerProgress(UploadServerProgress.Phase.Sending, 40, "Catbox"))
        assertEquals(UploadPhase.Sending(0.4, "Catbox"), UploadPhase.of(progress))
    }
}
