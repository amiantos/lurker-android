// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.Uploads
import net.amiantos.lurkerkit.model.SettingValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The server's advertised upload cap: how it is read off the wire, how it reaches the store,
 * and what stands in before it has (lurker-ios#149 — the iOS half of lurker#627).
 *
 * The whole point of the feature is that "the server didn't say" and "the server said a
 * number" are different states, so most of what is worth testing here is the silence.
 */
class UploadCapTests {

    // MARK: - What to compress against

    /** an advertised cap is used verbatim, envelope included */
    @Test
    fun advertisedCapIsUsedVerbatim() {
        // ⚠⚠ A FILE cap, not a body limit: the server has already subtracted the multipart
        // envelope (ENVELOPE_HEADROOM_BYTES, 64 KiB). Shaving anything off here would be the
        // same over-compression this replaced, just smaller.
        assertEquals(25L * 1024 * 1024, Uploads.compressionTarget(advertised = 25L * 1024 * 1024))
        assertEquals(200L * 1024 * 1024, Uploads.compressionTarget(advertised = 200L * 1024 * 1024))
    }

    /** no answer falls back to the Cloudflare-safe guess, rather than to no limit */
    @Test
    fun silenceFallsBackToTheGuess() {
        // The pre-snapshot window, and a self-hosted instance older than this app — both
        // normal. Neither may read as "uncapped", which would put a 400 MB clip on the wire.
        assertEquals(Uploads.fallbackMaxBytes, Uploads.compressionTarget(advertised = null))
        assertEquals(90L * 1024 * 1024, Uploads.fallbackMaxBytes)
    }

    // MARK: - Reading it off the wire

    private fun snapshotFrame(body: String): ServerFrame = FrameParser.parseWs(body)

    /** the snapshot's cap is parsed */
    @Test
    fun snapshotCarriesTheCap() {
        val frame = snapshotFrame(
            """{"kind":"snapshot","networks":[],"globalIgnores":[],"maxUploadBytes":26214400}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot, got $frame")
        assertEquals(26_214_400L, frame.maxUploadBytes)
    }

    /** a snapshot from a server too old to advertise says nothing, not zero */
    @Test
    fun anOldSnapshotSaysNothing() {
        val frame = snapshotFrame("""{"kind":"snapshot","networks":[],"globalIgnores":[]}""")
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot, got $frame")
        assertNull(frame.maxUploadBytes)
        assertEquals(Uploads.fallbackMaxBytes, Uploads.compressionTarget(advertised = frame.maxUploadBytes))
    }

    /** a non-positive cap is read as no answer */
    @Test
    fun nonsenseIsNotAnAnswer() {
        // The server never sends one. Taken at face value it would send every video down the
        // whole preset ladder to `.cannotCompressEnough`, which reads to the user as the app
        // refusing to upload rather than as a server that answered nonsense.
        for (value in listOf("0", "-1")) {
            val frame = snapshotFrame(
                """{"kind":"snapshot","networks":[],"globalIgnores":[],"maxUploadBytes":""" + value + "}",
            )
            if (frame !is ServerFrame.Snapshot) fail("expected a snapshot")
            assertNull(frame.maxUploadBytes, "$value is not a cap any file could satisfy")
        }
    }

    /** the settings frame carries the cap when the user changed it */
    @Test
    fun settingsFrameCarriesTheCap() {
        val frame = FrameParser.parseWs(
            """{"kind":"settings","changes":{"uploads.image.max_upload_mb":12},"maxUploadBytes":12582912}""",
        )
        if (frame !is ServerFrame.SettingsChanged) fail("expected a settings frame, got $frame")
        val (changes, maxUploadBytes) = frame
        assertEquals(SettingValue.Int(12), changes["uploads.image.max_upload_mb"])
        assertEquals(12_582_912L, maxUploadBytes)
    }

    /** a settings frame about anything else carries no cap */
    @Test
    fun anUnrelatedSettingsFrameCarriesNoCap() {
        val frame = FrameParser.parseWs(
            """{"kind":"settings","changes":{"chat.consolidate_joins":true}}""",
        )
        if (frame !is ServerFrame.SettingsChanged) fail("expected a settings frame, got $frame")
        assertNull(frame.maxUploadBytes, "absent here means unchanged, not uncapped")
    }

    // MARK: - Reaching the store

    // Waiting on LurkerStore, ChatState: snapshotSeedsTheStore, aSnapshotWithoutACapClearsIt,
    // aSettingsFrameRaisesTheCap, anUnrelatedSettingsFrameLeavesTheCapAlone, aFreshStateHasNoCap
}
