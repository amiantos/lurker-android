// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadBatch
import net.amiantos.lurkerkit.client.UploadError
import net.amiantos.lurkerkit.client.UploadProgress
import net.amiantos.lurkerkit.client.UploadServerProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server→provider progress leg (lurker-ios#47): the `upload-progress` frame, and the state
 * machine that folds it together with the device leg the HTTP client reports.
 *
 * The bug this feature exists to kill is a readout that says "Uploading… 100%" and then sits
 * there through the two slowest phases of the upload. Most of what's locked here is the ways
 * that lie can come back: a stage that rewinds, a percentage claimed where none was reported,
 * a missing key read as a zero.
 */
class UploadProgressTests {

    // MARK: - The wire

    @Test
    fun testProcessingFrameParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"upload-progress","token":"abc","phase":"processing","destination":"Catbox","percent":null}""",
        )
        assertEquals(
            ServerFrame.UploadProgress(
                token = "abc",
                progress = UploadServerProgress(
                    phase = UploadServerProgress.Phase.Processing,
                    percent = null,
                    destination = "Catbox",
                ),
            ),
            frame,
        )
    }

    @Test
    fun testSendingFrameCarriesItsPercent() {
        val frame = FrameParser.parseWs(
            """{"kind":"upload-progress","token":"abc","phase":"sending","destination":"Catbox","percent":42}""",
        )
        assertEquals(
            ServerFrame.UploadProgress(
                token = "abc",
                progress = UploadServerProgress(
                    phase = UploadServerProgress.Phase.Sending,
                    percent = 42,
                    destination = "Catbox",
                ),
            ),
            frame,
        )
    }

    @Test
    fun testAbsentPercentIsNilRatherThanZero() {
        // ⚠ The whole point of the nullable percent. Read as 0, a driver that never counts a
        // byte (`local` renames a temp file — there is no wire) would freeze the readout at
        // "Sending… 0%" for the entire send: the same dead air one phase along.
        val frame = FrameParser.parseWs(
            """{"kind":"upload-progress","token":"abc","phase":"sending","destination":"Local disk"}""",
        )
        assertEquals(
            ServerFrame.UploadProgress(
                token = "abc",
                progress = UploadServerProgress(
                    phase = UploadServerProgress.Phase.Sending,
                    percent = null,
                    destination = "Local disk",
                ),
            ),
            frame,
        )
    }

    @Test
    fun testFrameWithoutATokenIsIgnored() {
        // Unmatchable: the token is the only thing tying a frame to the upload it describes,
        // and these fan out to every socket the account has open.
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"upload-progress","phase":"sending","percent":10}"""),
        )
    }

    @Test
    fun testUnknownPhaseIsIgnored() {
        // A phase this build has no rendering for. Falling back to the indeterminate readout
        // already on screen beats acting on a payload we can't read.
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"upload-progress","token":"abc","phase":"finalising"}"""),
        )
    }

    @Test
    fun testDestinationIsOptional() {
        val frame = FrameParser.parseWs(
            """{"kind":"upload-progress","token":"abc","phase":"processing","destination":null,"percent":null}""",
        )
        assertEquals(
            ServerFrame.UploadProgress(
                token = "abc",
                progress = UploadServerProgress(
                    phase = UploadServerProgress.Phase.Processing,
                    percent = null,
                    destination = null,
                ),
            ),
            frame,
        )
    }

    // MARK: - Folding the two legs

    @Test
    fun testStartsOnTheDeviceLeg() {
        val progress = UploadProgress()
        assertEquals(UploadProgress.Stage.Uploading, progress.stage)
        assertEquals(0.0, progress.deviceFraction)
        assertNull(progress.sentFraction)
        assertNull(progress.destination)
    }

    @Test
    fun testDeviceLegReportsItsFraction() {
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = 0.42)
        assertEquals(UploadProgress.Stage.Uploading, progress.stage)
        assertEquals(0.42, progress.deviceFraction, absoluteTolerance = 0.0001)
    }

    @Test
    fun testDeviceLegHittingOneAdvancesWithoutAnyServerFrame() {
        // Tier 1, and the whole reason this isn't gated on the server: an instance too old to
        // send these frames — or a socket that dropped mid-upload — still stops claiming to be
        // uploading the moment the last byte leaves the device.
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = 1.0)
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
    }

    @Test
    fun testALateDeviceTickCannotRewindTheStage() {
        // Those callbacks hop threads to reach the main thread, so one can land after the
        // server's first frame. Letting it through would put "Uploading…" back on screen after
        // the readout had already moved on.
        var progress = UploadProgress()
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 30, destination = "Catbox"),
        )
        progress = progress.apply(deviceFraction = 0.99)
        assertEquals(UploadProgress.Stage.Sending, progress.stage)
        assertEquals(0.3, progress.sentFraction)
    }

    @Test
    fun testARestartedBodyReturnsToTheDeviceLeg() {
        // The HTTP client can re-send the whole body (an HTTP/2 GOAWAY retry, a redirect), which
        // resets its byte count to zero. Latching on "Processing…" would sit there through the
        // entire second transmission — minutes, for a large video — and no server frame could
        // correct it, because the server hasn't got the file yet.
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = 1.0)
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
        progress = progress.apply(deviceFraction = 0.02)
        assertEquals(UploadProgress.Stage.Uploading, progress.stage)
        assertEquals(0.02, progress.deviceFraction, absoluteTolerance = 0.0001)
    }

    @Test
    fun testARepeatedFinalTickIsNotMistakenForARestart() {
        // Only a fraction that goes BACKWARDS is a restart. A duplicate 100% is just a tick.
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = 1.0)
        progress = progress.apply(deviceFraction = 1.0)
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
    }

    @Test
    fun testTheDeviceLegCannotReopenOnceTheServerHasSpoken() {
        // Past this point the server's account beats the device's: the bytes are there, so a
        // low tick is a straggler crossing the WS, not a restart. This is the guard that keeps
        // the re-entry above from reintroducing the rewind it's carved out of.
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = 1.0)
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Processing, percent = null, destination = "Catbox"),
        )
        progress = progress.apply(deviceFraction = 0.02)
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
    }

    @Test
    fun testSendingCarriesItsFraction() {
        var progress = UploadProgress()
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 75, destination = "Catbox"),
        )
        assertEquals(UploadProgress.Stage.Sending, progress.stage)
        assertEquals(0.75, progress.sentFraction)
        assertEquals("Catbox", progress.destination)
    }

    @Test
    fun testSendingWithoutAPercentStaysIndeterminate() {
        var progress = UploadProgress()
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = null, destination = "Local disk"),
        )
        assertEquals(UploadProgress.Stage.Sending, progress.stage)
        assertNull(progress.sentFraction)
    }

    @Test
    fun testProcessingNeverCarriesANumber() {
        // Only `sending` has one. The pipeline is a native one-shot with no seam to count, so
        // a percentage on this stage could only ever be a number borrowed from another phase.
        var progress = UploadProgress()
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Processing, percent = 50, destination = null),
        )
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
        assertNull(progress.sentFraction)
    }

    @Test
    fun testALateProcessingFrameCannotRewindTheSend() {
        // WS ordering makes this unlikely, not impossible, and the cost of being wrong is a
        // real percentage replaced by an indeterminate label — visibly a jump backwards.
        var progress = UploadProgress()
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 60, destination = "Catbox"),
        )
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Processing, percent = null, destination = "Catbox"),
        )
        assertEquals(UploadProgress.Stage.Sending, progress.stage)
        assertEquals(0.6, progress.sentFraction)
    }

    @Test
    fun testDestinationSticksOnceNamed() {
        // A later frame that omits it must not blank a label already on screen.
        var progress = UploadProgress()
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 10, destination = "Catbox"),
        )
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 20, destination = null),
        )
        assertEquals("Catbox", progress.destination)
        assertEquals(0.2, progress.sentFraction)
    }

    @Test
    fun testFractionsAreClamped() {
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = -0.5)
        assertEquals(0.0, progress.deviceFraction)
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 140, destination = null),
        )
        assertEquals(1.0, progress.sentFraction)
    }

    // MARK: - Batch policy

    @Test
    fun testConnectionLevelErrorsStopABatchAndFileLevelOnesDoNot() {
        // The split is "about the pipe" vs "about the file". Getting it backwards means either
        // four good uploads stranded behind one oversized photo, or nine more files each
        // waiting out a 300-second timeout on a connection that is already gone.
        assertTrue(UploadError.NotSignedIn.stopsABatch)
        assertTrue(UploadError.Unauthorized.stopsABatch)
        assertTrue(UploadError.Transport("offline").stopsABatch)
        assertFalse(UploadError.TooLarge.stopsABatch)
        assertFalse(UploadError.CannotCompressEnough.stopsABatch)
        assertFalse(UploadError.CompressionFailed("bad codec").stopsABatch)
        // The server ANSWERED, so the pipe works and it was this file it refused.
        assertFalse(UploadError.Server("unsupported type").stopsABatch)
    }

    @Test
    fun testARepeatedRefusalStopsTheBatch() {
        // How an instance-level refusal announces itself when it can't say so directly: the
        // server maps every uploader-driver failure onto one status, so a stale provider
        // credential arrives as a per-file rejection — the IDENTICAL per-file rejection, every
        // time. Ten videos on cellular each compressed and pushed in full before hearing it
        // again is most of a gigabyte spent learning what file two already said.
        val sameTwice: List<UploadError> = listOf(
            UploadError.Server("upstream rejected the upload"),
            UploadError.Server("upstream rejected the upload"),
        )
        assertTrue(UploadBatch.shouldStop(failures = sameTwice))
    }

    @Test
    fun testOneRefusalDoesNotStopTheBatch() {
        // Waiting for the repeat is the point: one file genuinely can be the wrong type with
        // the next nine perfectly fine.
        assertFalse(UploadBatch.shouldStop(failures = listOf(UploadError.Server("unsupported type"))))
        assertFalse(UploadBatch.shouldStop(failures = listOf(UploadError.TooLarge)))
        assertFalse(UploadBatch.shouldStop(failures = emptyList()))
    }

    @Test
    fun testDifferentRefusalsDoNotStopTheBatch() {
        // Two files rejected for two different reasons is two bad files, not a broken instance.
        assertFalse(
            UploadBatch.shouldStop(
                failures = listOf(
                    UploadError.Server("unsupported type"),
                    UploadError.Server("upstream rejected the upload"),
                ),
            ),
        )
    }

    @Test
    fun testAConnectionErrorStopsTheBatchOnItsFirstAppearance() {
        // No need to see this one twice.
        assertTrue(UploadBatch.shouldStop(failures = listOf(UploadError.TooLarge, UploadError.Unauthorized)))
    }

    @Test
    fun testTheRepeatMustBeConsecutive() {
        // A reason that recurs after something else intervened is a coincidence, not a
        // pattern — the file in between proves uploads still work.
        assertFalse(
            UploadBatch.shouldStop(
                failures = listOf(UploadError.Server("nope"), UploadError.TooLarge, UploadError.Server("nope")),
            ),
        )
    }

    // MARK: - What to say afterwards

    @Test
    fun testACleanBatchSaysNothing() {
        assertNull(
            UploadBatch.summary(picked = 4, uploaded = 4, failures = emptyList(), unreadable = 0, cancelled = false),
        )
    }

    @Test
    fun testACancelledBatchSaysNothingAboutTheCancelling() {
        // The user stopped it; they know. An alert confirming what someone just asked for is a
        // dialog to dismiss, not information.
        assertNull(
            UploadBatch.summary(
                picked = 5, uploaded = 2, failures = emptyList(), unreadable = 0, cancelled = true,
            ),
        )
    }

    @Test
    fun testACancelStillReportsWhatFailedBeforeIt() {
        // The shortfall is the user's own doing; the two files that failed on their way past
        // are not, and swallowing those leaves a composer holding fewer links than expected
        // with nothing anywhere saying why.
        assertEquals(
            UploadError.TooLarge.userMessage,
            UploadBatch.summary(
                picked = 5, uploaded = 2, failures = listOf(UploadError.TooLarge), unreadable = 0, cancelled = true,
            ),
        )
    }

    @Test
    fun testACancelDoesNotCountTheFilesItSkipped() {
        // No "Uploaded 2 of 5" — counting a shortfall back at the person who asked for it
        // reads as an accusation.
        val summary = UploadBatch.summary(
            picked = 5, uploaded = 2, failures = listOf(UploadError.TooLarge, UploadError.Server("nope")),
            unreadable = 0, cancelled = true,
        )
        assertEquals("2 failed — first error: ${UploadError.TooLarge.userMessage}", summary)
        assertFalse(summary?.contains("of 5") ?: true)
    }

    @Test
    fun testOneFileFailingAloneReadsAsAPlainError() {
        // Unchanged from before batches existed: "Uploaded 0 of 1" is a statistic where a
        // sentence will do.
        assertEquals(
            UploadError.TooLarge.userMessage,
            UploadBatch.summary(
                picked = 1, uploaded = 0, failures = listOf(UploadError.TooLarge), unreadable = 0, cancelled = false,
            ),
        )
    }

    @Test
    fun testAPartialBatchCountsWhatLanded() {
        val summary = UploadBatch.summary(
            picked = 5, uploaded = 4, failures = listOf(UploadError.TooLarge), unreadable = 0, cancelled = false,
        )
        assertEquals("Uploaded 4 of 5. ${UploadError.TooLarge.userMessage}", summary)
    }

    @Test
    fun testUnreadableFilesAreCountedRatherThanVanishing() {
        // The whole reason the picker reports its own failures: three of five uploading with
        // no mention of the other two reads as data loss.
        val summary = UploadBatch.summary(
            picked = 5, uploaded = 3, failures = emptyList(), unreadable = 2, cancelled = false,
        )
        assertEquals("Uploaded 3 of 5. 2 couldn't be read.", summary)
    }

    @Test
    fun testAnUnreadableFileQuotesItsOwnReason() {
        // "No space left on device" is something the user can act on. "Couldn't be read" is
        // something to shrug at.
        val summary = UploadBatch.summary(
            picked = 5, uploaded = 3, failures = emptyList(), unreadable = 2,
            unreadableReason = "No space left on device", cancelled = false,
        )
        assertEquals("Uploaded 3 of 5. 2 couldn't be read: No space left on device", summary)
    }

    @Test
    fun testASingleUnreadableFileReadsAsItsOwnReason() {
        assertEquals(
            "You don't have permission to open this file.",
            UploadBatch.summary(
                picked = 1, uploaded = 0, failures = emptyList(), unreadable = 1,
                unreadableReason = "You don't have permission to open this file.", cancelled = false,
            ),
        )
    }

    @Test
    fun testACancelStillReportsFilesThatCouldNotBeRead() {
        // Files that failed to stage are errors the user did NOT choose, so a cancel must not
        // swallow them — otherwise picking five, watching three fail off cloud storage, and
        // then stopping leaves an empty composer and no alert at all.
        assertEquals(
            "3 couldn't be read.",
            UploadBatch.summary(
                picked = 5, uploaded = 0, failures = emptyList(), unreadable = 3, cancelled = true,
            ),
        )
    }

    @Test
    fun testManyFailuresLeadWithACountAndOneExample() {
        val summary = UploadBatch.summary(
            picked = 4, uploaded = 1,
            failures = listOf(UploadError.TooLarge, UploadError.CannotCompressEnough, UploadError.Server("nope")),
            unreadable = 0, cancelled = false,
        )
        assertEquals(
            "Uploaded 1 of 4. 3 failed — first error: ${UploadError.TooLarge.userMessage}",
            summary,
        )
    }

    @Test
    fun testFilesNeverAttemptedAreCountedNotInvented() {
        // A `stopsABatch` error cuts the run short, so there are fewer errors than missing
        // files. The gap is accounted for by "1 of 5" rather than by attributing a reason to
        // files that were never tried.
        val summary = UploadBatch.summary(
            picked = 5, uploaded = 1, failures = listOf(UploadError.Unauthorized), unreadable = 0, cancelled = false,
        )
        assertEquals("Uploaded 1 of 5. ${UploadError.Unauthorized.userMessage}", summary)
    }

    @Test
    fun testTheWholeHappyPathInOrder() {
        var progress = UploadProgress()
        progress = progress.apply(deviceFraction = 0.5)
        assertEquals(UploadProgress.Stage.Uploading, progress.stage)
        progress = progress.apply(deviceFraction = 1.0)
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Processing, percent = null, destination = "Catbox"),
        )
        assertEquals(UploadProgress.Stage.Processing, progress.stage)
        assertEquals("Catbox", progress.destination)
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 0, destination = "Catbox"),
        )
        assertEquals(0.0, progress.sentFraction)
        progress = progress.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 100, destination = "Catbox"),
        )
        assertEquals(UploadProgress.Stage.Sending, progress.stage)
        assertEquals(1.0, progress.sentFraction)
    }

    // Port-only: Swift's `mutating func` on a `var` copy gets this for free. Here `apply`
    // returns the updated copy, and a value already handed to the UI must not move under it.
    @Test
    fun testApplyReturnsACopyAndLeavesTheOriginalAlone() {
        val before = UploadProgress()
        val afterDevice = before.apply(deviceFraction = 0.5)
        val afterServer = afterDevice.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Sending, percent = 40, destination = "Catbox"),
        )
        assertEquals(UploadProgress(), before)
        assertEquals(UploadProgress.Stage.Uploading, afterDevice.stage)
        assertEquals(0.5, afterDevice.deviceFraction)
        assertNull(afterDevice.destination)
        assertEquals(UploadProgress.Stage.Sending, afterServer.stage)
        // "Heard from the server" is part of the value, as it is of the Swift struct's `==`.
        val unheard = UploadProgress().apply(deviceFraction = 1.0)
        val heard = unheard.apply(
            server = UploadServerProgress(phase = UploadServerProgress.Phase.Processing, percent = null, destination = null),
        )
        assertEquals(unheard.stage, heard.stage)
        assertNotEquals(unheard, heard)
    }
}
