// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A version per URL, bumped when that URL's preview state moves — what a row reads to re-plan. */
class PreviewUpdatesTest {

    @After
    fun forget() = PreviewUpdates.reset()

    @Test
    fun onlyTheUrlsThatMovedChangeVersion() {
        assertEquals(0, PreviewUpdates.version("a"))
        assertEquals(0, PreviewUpdates.version("b"))
        PreviewUpdates.publish(setOf("a"))
        assertEquals(1, PreviewUpdates.version("a"))
        assertEquals(0, PreviewUpdates.version("b"))
        PreviewUpdates.publish(setOf("a", "b"))
        assertEquals(2, PreviewUpdates.version("a"))
        assertEquals(1, PreviewUpdates.version("b"))
    }

    @Test
    fun aUrlNobodyReadHasNobodyToTell() {
        PreviewUpdates.publish(setOf("unread"))
        assertEquals(0, PreviewUpdates.version("unread"))
    }

    @Test
    fun signingOutForgetsEveryVersion() {
        PreviewUpdates.version("a")
        PreviewUpdates.publish(setOf("a"))
        PreviewUpdates.reset()
        assertEquals(0, PreviewUpdates.version("a"))
    }
}
