// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.Uploads
import kotlin.test.Test
import kotlin.test.assertEquals

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
    // MARK: - Reaching the store

    // Waiting on FrameParser, ServerFrame: snapshotCarriesTheCap, anOldSnapshotSaysNothing,
    // nonsenseIsNotAnAnswer, settingsFrameCarriesTheCap, anUnrelatedSettingsFrameCarriesNoCap
    // (and their private `snapshotFrame` helper)
    // Waiting on LurkerStore, ChatState: snapshotSeedsTheStore, aSnapshotWithoutACapClearsIt,
    // aSettingsFrameRaisesTheCap, anUnrelatedSettingsFrameLeavesTheCapAlone, aFreshStateHasNoCap
}
