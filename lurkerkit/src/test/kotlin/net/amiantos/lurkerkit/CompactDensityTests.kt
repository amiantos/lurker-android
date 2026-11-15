// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.CompactDensity
import net.amiantos.lurkerkit.model.SettingDependency
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.SettingsRenames
import net.amiantos.lurkerkit.model.TimestampFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // MARK: - A server from before the keys were renamed (its schema v28)

    private fun option(key: String, value: Int): SettingOption =
        SettingOption(key = key, label = "", description = "", type = SettingType.Int, default = SettingValue.Int(value))

    @Test fun testAnOlderServersValuesAreReadUnderTheKeysItStillUses() {
        val d = density(
            mapOf(
                "look.message.compact.group_gap" to SettingValue.Int(4),
                "look.message.compact.line_height" to SettingValue.Int(120),
                "look.message.compact.indent" to SettingValue.Int(3),
            ),
        )
        assertEquals(CompactDensity.of(groupGap = 4, lineHeightPercent = 120, indent = 3), d)
    }

    @Test fun testAnOlderServersRegistryDefaultIsReadTheSameWay() {
        val registry = mapOf("look.message.compact.line_height" to option("look.message.compact.line_height", 170))
        assertEquals(170, CompactDensity.of(Settings(registry = registry, values = emptyMap())).lineHeightPercent)
    }

    @Test fun testTheCurrentKeyWinsOverTheFormerOne() {
        // A stored value under the new key, and the new registry's default, both come before
        // anything under the old name.
        val both = mapOf(CompactDensity.indentKey to SettingValue.Int(2), "look.message.compact.indent" to SettingValue.Int(7))
        assertEquals(2, density(both).indent)
        val settings = Settings(
            registry = mapOf(CompactDensity.indentKey to option(CompactDensity.indentKey, 1)),
            values = mapOf("look.message.compact.indent" to SettingValue.Int(7)),
        )
        assertEquals(1, CompactDensity.of(settings).indent)
    }

    @Test fun testEveryRenamedKeyIsOneTheKitReads() {
        assertEquals(
            setOf(
                CompactDensity.groupGapKey, CompactDensity.lineHeightKey, CompactDensity.indentKey,
                TimestampFormat.compactTimeKey, TimestampFormat.compactDateKey,
            ),
            SettingsRenames.formerKeys.keys,
        )
        for ((now, former) in SettingsRenames.formerKeys) {
            assertNotEquals(now, former)
            assertFalse(now.contains("compact"), now)
            assertTrue(former.contains("compact"), former)
        }
        val old = Settings(registry = emptyMap(), values = mapOf("look.buffer.time_format_compact" to SettingValue.String("h:mm a")))
        assertEquals("h:mm a", old.string(TimestampFormat.compactTimeKey, default = "HH:mm"))
        assertNull(Settings(registry = emptyMap(), values = emptyMap()).effective(TimestampFormat.compactTimeKey))
    }

    /** A dependency is read off the registry's entry, which an older server keeps under the old key. */
    @Test
    fun testARenamedKeysRegistryEntryIsFoundUnderItsOldName() {
        val former = SettingsRenames.formerKeys.getValue(CompactDensity.indentKey)
        val entry = SettingOption(
            key = former, label = "Indent", description = "", type = SettingType.Int, default = SettingValue.Int(1),
            dependsOn = listOf(SettingDependency(key = "look.message.avatars", values = listOf(SettingValue.Bool(false)))),
        )
        val old = Settings(registry = mapOf(former to entry), values = mapOf("look.message.avatars" to SettingValue.Bool(true)))
        assertEquals(former, old.option(CompactDensity.indentKey)?.key)
        assertFalse(old.isActive(CompactDensity.indentKey), "the old entry's dependency still counts")
        assertNull(old.option("look.no.such.key"))
    }
}
