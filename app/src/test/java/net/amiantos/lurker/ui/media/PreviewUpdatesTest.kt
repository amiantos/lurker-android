// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** The store's one callback, fanned out to every listening screen. */
class PreviewUpdatesTest {

    @Test
    fun everyListenerHearsUntilItStops() {
        val first = mutableListOf<Set<String>>()
        val second = mutableListOf<Set<String>>()
        val stopFirst = PreviewUpdates.listen { first += it }
        val stopSecond = PreviewUpdates.listen { second += it }
        PreviewUpdates.publish(setOf("a"))
        stopFirst()
        PreviewUpdates.publish(setOf("b"))
        stopSecond()
        PreviewUpdates.publish(setOf("c"))
        assertEquals(listOf(setOf("a")), first)
        assertEquals(listOf(setOf("a"), setOf("b")), second)
    }

    @Test
    fun aListenerMayStopFromInsideItsOwnCall() {
        val heard = mutableListOf<Set<String>>()
        var stop: () -> Unit = {}
        stop = PreviewUpdates.listen {
            heard += it
            stop()
        }
        PreviewUpdates.publish(setOf("a"))
        PreviewUpdates.publish(setOf("b"))
        assertEquals(listOf(setOf("a")), heard)
    }
}
