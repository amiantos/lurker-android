// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.HistoryCountBy
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule that decides what unit we ask history pages to be sized in (lurker-ios#10).
 *
 * One rule, one place, because getting it backwards is silent in both directions: asking for
 * `Event` while we consolidate is the blank-first-screenful bug this exists to fix, and asking
 * for `Renderable` while we don't drags the server's whole scan window into a page the reader
 * then sees in full. Neither shows up as an error anywhere.
 */
class HistoryCountByTests {

    private fun settings(consolidate: Boolean?): Settings {
        val s = Settings()
        if (consolidate == null) return s
        return s.replaceValues(mapOf("chat.consolidate_joins" to SettingValue.Bool(consolidate)))
    }

    @Test
    fun testAsksForRenderablePagesWhileConsolidating() {
        assertEquals(HistoryCountBy.Renderable, HistoryCountBy.forRendering(settings(consolidate = true)))
    }

    @Test
    fun testFallsBackToEventCountingWhenConsolidationIsOff() {
        assertEquals(HistoryCountBy.Event, HistoryCountBy.forRendering(settings(consolidate = false)))
    }

    /**
     * Before settings bootstrap lands — and against a server too old to know the key — we
     * consolidate by default, so the unit we ask for has to default the same way. A mismatch
     * here would only show on the very first buffer the user opens after launch, which is
     * exactly the fetch this feature is about.
     */
    @Test
    fun testDefaultsToRenderableBeforeBootstrap() {
        assertEquals(HistoryCountBy.Renderable, HistoryCountBy.forRendering(settings(consolidate = null)))
    }

    /** It travels on the wire as its raw value, so the spelling is protocol, not an enum name. */
    @Test
    fun testWireSpelling() {
        assertEquals("renderable", HistoryCountBy.Renderable.rawValue)
        assertEquals("event", HistoryCountBy.Event.rawValue)
    }
}
