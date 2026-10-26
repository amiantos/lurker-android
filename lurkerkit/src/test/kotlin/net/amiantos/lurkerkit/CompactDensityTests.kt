// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.CompactDensity
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

/** The compact layout's three density settings (lurker#1186, Mobile lurker#1224). */
class CompactDensityTests {
    private fun density(values: Map<String, SettingValue> = emptyMap()): CompactDensity =
        CompactDensity.of(Settings(registry = emptyMap(), values = values))

    @Test fun testNothingStoredIsTheRegistryDefaults() {
        assertEquals(CompactDensity.of(groupGap = 10, lineHeightPercent = 155, indent = 1), density())
        assertEquals(CompactDensity.standard, density())
    }

    @Test fun testStoredValuesWin() {
        val d = density(
            mapOf(
                CompactDensity.groupGapKey to SettingValue.Int(0),
                CompactDensity.lineHeightKey to SettingValue.Int(120),
                CompactDensity.indentKey to SettingValue.Int(3),
            ),
        )
        assertEquals(0, d.groupGap)
        assertEquals(120, d.lineHeightPercent)
        assertEquals(3, d.indent)
    }

    @Test fun testOutOfRangeValuesClampToTheRegistrysRange() {
        val low = density(
            mapOf(
                CompactDensity.groupGapKey to SettingValue.Int(-4),
                CompactDensity.lineHeightKey to SettingValue.Int(50),
                CompactDensity.indentKey to SettingValue.Int(-1),
            ),
        )
        assertEquals(CompactDensity.of(groupGap = 0, lineHeightPercent = 100, indent = 0), low)
        val high = density(
            mapOf(
                CompactDensity.groupGapKey to SettingValue.Int(99),
                CompactDensity.lineHeightKey to SettingValue.Int(400),
                CompactDensity.indentKey to SettingValue.Int(20),
            ),
        )
        assertEquals(CompactDensity.of(groupGap = 48, lineHeightPercent = 250, indent = 8), high)
    }

    @Test fun testAValueOfTheWrongTypeFallsBackToTheDefault() {
        assertEquals(10, density(mapOf(CompactDensity.groupGapKey to SettingValue.String("10"))).groupGap)
    }

    @Test fun testTheRegistryDefaultIsUsedBeforeAnyOverride() {
        val registry = mapOf(
            CompactDensity.lineHeightKey to SettingOption(
                key = CompactDensity.lineHeightKey, label = "", description = "", type = SettingType.Int,
                default = SettingValue.Int(170),
            ),
        )
        val d = CompactDensity.of(Settings(registry = registry, values = emptyMap()))
        assertEquals(170, d.lineHeightPercent)
    }
}
