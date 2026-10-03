// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.settings

import net.amiantos.lurkerkit.model.EventFilter
import net.amiantos.lurkerkit.model.SettingDependency
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings screen's rules — each one a comment in lurker-ios's `SettingsViewController`. */
class SettingsModelTest {

    private fun bool(key: String, default: Boolean = false, dependsOn: List<SettingDependency> = emptyList()) =
        SettingOption(key, "registry label", "registry prose", SettingType.Bool, SettingValue.Bool(default), dependsOn = dependsOn)

    private fun int(key: String, default: Int, min: Int? = null, max: Int? = null) =
        SettingOption(key, "", "", SettingType.Int, SettingValue.Int(default), min = min, max = max)

    private val eventTier = SettingOption(
        EventFilter.modeKey, "", "", SettingType.Enum, SettingValue.String("all"),
        choices = listOf("all", "smart", "none"),
        choiceLabels = mapOf("all" to "Show all", "smart" to "Hide from quiet users"),
    )

    private val nickSuffix =
        SettingOption("input.completion.nick_suffix", "", "", SettingType.String, SettingValue.String(":"))

    /** Every key the screen can show, as a current server would describe them. */
    private fun fullRegistry(): Map<String, SettingOption> {
        val keys = SettingsModel.chatSettings + SettingsModel.eventSettings +
            SettingsModel.smartFilterSettings + SettingsModel.appearanceSettings
        return keys.associate { (key, _) ->
            key to when (key) {
                EventFilter.modeKey -> eventTier
                "input.completion.nick_suffix" -> nickSuffix
                "chat.consolidate_max_names" -> int(key, 5, min = 1, max = 20)
                "chat.smart_filter_delay", "chat.smart_filter_join_unmask" -> int(key, 15, min = 1, max = 120)
                else -> bool(key)
            }
        }
    }

    private fun inputs(
        registry: Map<String, SettingOption> = fullRegistry(),
        values: Map<String, SettingValue> = emptyMap(),
        linkPreviews: Boolean = true,
    ) = SettingsInputs(Settings(registry = registry, values = values), linkPreviews)

    private fun rows(section: SettingsSection): List<SettingRow> = when (section) {
        is SettingsSection.Chat -> section.rows
        is SettingsSection.Events -> section.rows
        is SettingsSection.SmartFilter -> section.rows
        is SettingsSection.Appearance -> section.rows
        else -> emptyList()
    }

    // MARK: - Sections

    @Test
    fun aFullRegistryGetsIosSectionsInIosOrder() {
        val sections = SettingsModel.sections(inputs())
        assertEquals(
            listOf("Networks", "Chat", "Events", "SmartFilter", "Appearance", "Device", "Account", "About"),
            sections.map { it::class.simpleName },
        )
    }

    @Test
    fun rowsAreIosKeysWithIosLabelsInIosOrder() {
        val sections = SettingsModel.sections(inputs())
        val labels = sections.flatMap { section -> rows(section).map { it.option.key to it.label } }
        assertEquals(
            listOf(
                "chat.send_typing_notifications" to "Send typing notifications",
                "chat.keep_position_on_send" to "Stay put when you send",
                "input.completion.nick_suffix" to "Address nicks with",
                "away.all_networks" to "Away on every network",
                "chat.events.mobile" to "Event filter",
                "chat.consolidate_joins" to "Consolidate events",
                "chat.consolidate_max_names" to "Max consolidated nicks",
                "chat.show_event_host" to "Show user@host on events",
                "chat.show_join_account" to "Show account on joins",
                "chat.smart_filter_join" to "Filter joins",
                "chat.smart_filter_quit" to "Filter parts and quits",
                "chat.smart_filter_nick" to "Filter nick changes",
                "chat.smart_filter_mode" to "Filter op and voice changes",
                "chat.smart_filter_delay" to "\"Recently spoke\" window (min)",
                "chat.smart_filter_join_unmask" to "Reveal join on speaking (min)",
                "look.nick.show_mode_prefix" to "Show mode prefix on nicks",
                "chat.inline_media.enabled" to "Inline media",
                "chat.link_previews.enabled" to "Link previews",
            ),
            labels,
        )
    }

    @Test
    fun theEventFilterIsTheMobileTier() {
        // Only the tier is device-split, and this device is never the desktop case.
        assertEquals("chat.events.mobile", SettingsModel.eventSettings.first().first)
    }

    @Test
    fun aKeyTheServerDoesntKnowGetsNoRow() {
        val registry = fullRegistry() - "chat.keep_position_on_send"
        val chat = SettingsModel.sections(inputs(registry)).filterIsInstance<SettingsSection.Chat>().single()
        assertFalse(chat.rows.any { it.option.key == "chat.keep_position_on_send" })
    }

    @Test
    fun previewKeysAreHiddenWhenTheInstanceHasNoPreviews() {
        val appearance = SettingsModel.sections(inputs(linkPreviews = false))
            .filterIsInstance<SettingsSection.Appearance>().single()
        assertEquals(listOf("look.nick.show_mode_prefix"), appearance.rows.map { it.option.key })
    }

    @Test
    fun emptyOptionalSectionsAreDroppedNotDrawnBlank() {
        // A server from before #63: every other event key, none of Smart Filter's.
        val smartKeys = SettingsModel.smartFilterSettings.map { it.first }.toSet()
        val registry = fullRegistry().filterKeys { it !in smartKeys }
        val sections = SettingsModel.sections(inputs(registry))
        assertTrue(sections.none { it is SettingsSection.SmartFilter })
        assertTrue(sections.any { it is SettingsSection.Events })
    }

    @Test
    fun noChatKeysMeansTheNoticeNotAnEmptyScreen() {
        // Appearance alone isn't a usable registry: Chat is the test.
        val registry = fullRegistry().filterKeys { it == "look.nick.show_mode_prefix" }
        assertEquals(
            listOf(
                SettingsSection.Networks,
                SettingsSection.Unavailable(loaded = true),
                SettingsSection.Device,
                SettingsSection.Account,
                SettingsSection.About,
            ),
            SettingsModel.sections(inputs(registry)),
        )
    }

    @Test
    fun beforeBootstrapTheNoticeSaysItCouldntLoad() {
        val sections = SettingsModel.sections(SettingsInputs(Settings(), linkPreviews = false))
        assertEquals(SettingsSection.Unavailable(loaded = false), sections[1])
        assertEquals("Couldn't load settings", SettingsModel.unavailableTitle(false))
        assertEquals("Check your connection and reopen Settings.", SettingsModel.unavailableSubtitle(false))
        assertEquals("No chat settings available on this server", SettingsModel.unavailableTitle(true))
        assertEquals("This server doesn't offer the settings this app can change.", SettingsModel.unavailableSubtitle(true))
    }

    @Test
    fun headersAndFootersAreIosWords() {
        assertNull(SettingsModel.header(SettingsSection.Networks))
        assertEquals("Chat", SettingsModel.header(SettingsSection.Chat(emptyList())))
        assertEquals("Chat", SettingsModel.header(SettingsSection.Unavailable(loaded = false)))
        assertEquals("Events", SettingsModel.header(SettingsSection.Events(emptyList())))
        assertEquals("Smart Filter", SettingsModel.header(SettingsSection.SmartFilter(emptyList())))
        assertEquals("Appearance", SettingsModel.header(SettingsSection.Appearance(emptyList())))
        assertEquals("This Device", SettingsModel.header(SettingsSection.Device))
        assertNull(SettingsModel.header(SettingsSection.Account))
        assertNull(SettingsModel.header(SettingsSection.About))
        assertEquals(
            "Applies to this device only — not shared with your other Lurker clients.",
            SettingsModel.footer(SettingsSection.Device),
        )
        assertEquals("Used when Event filter is set to Smart.", SettingsModel.footer(SettingsSection.SmartFilter(emptyList())))
        assertNull(SettingsModel.footer(SettingsSection.Chat(emptyList())))
    }

    // MARK: - Controls

    private fun rowState(option: SettingOption, values: Map<String, SettingValue> = emptyMap(), edits: SettingsEdits = SettingsEdits()) =
        SettingsModel.rowState(SettingRow("Label", option), Settings(mapOf(option.key to option), values), edits)

    @Test
    fun aBoolReadsTheDefaultWhenNothingIsStored() {
        // Bootstrap sends stored values only; a fresh account's `{}` must not read every switch as off.
        assertEquals(SettingControl.Toggle(isOn = true), rowState(bool("k", default = true)).control)
        val stored = rowState(bool("k", default = true), values = mapOf("k" to SettingValue.Bool(false)))
        assertEquals(SettingControl.Toggle(isOn = false), stored.control)
    }

    @Test
    fun aPendingValueShowsUntilItsWriteSettles() {
        val option = bool("k", default = false)
        val edits = SettingsEdits().began("k", SettingValue.Bool(true))
        assertEquals(SettingControl.Toggle(isOn = true), rowState(option, edits = edits).control)
    }

    @Test
    fun anIntTakesTheRegistrysBounds() {
        val control = rowState(int("n", 5, min = 1, max = 20)).control as SettingControl.Stepper
        assertEquals(SettingControl.Stepper(value = 5, min = 1, max = 20), control)
        assertEquals(20, control.copy(value = 20).stepped(1))
        assertEquals(1, control.copy(value = 1).stepped(-1))
        assertFalse(control.copy(value = 20).canIncrement)
        assertFalse(control.copy(value = 1).canDecrement)
        assertTrue(control.canIncrement && control.canDecrement)
    }

    @Test
    fun anUnboundedIntFallsBackToIosRange() {
        assertEquals(SettingControl.Stepper(value = 3, min = 0, max = 100), rowState(int("n", 3)).control)
    }

    @Test
    fun anEnumOffersEveryChoiceInTheRegistrysWords() {
        val control = rowState(eventTier, values = mapOf(EventFilter.modeKey to SettingValue.String("smart"))).control
            as SettingControl.Menu
        assertEquals("smart", control.current)
        assertEquals("Hide from quiet users", control.title)
        // A choice with no label falls back to its raw value.
        assertEquals(listOf("Show all", "Hide from quiet users", "none"), control.choices.map { it.label })
        assertEquals(SettingValue.String("none"), SettingsModel.menuValue(control.choices[2]))
    }

    @Test
    fun theNickSuffixOffersTheFormsItProduces() {
        val control = rowState(nickSuffix).control as SettingControl.Menu
        assertEquals(listOf(":", ",", ";", ""), control.choices.map { it.value })
        assertEquals(listOf("nick:", "nick,", "nick;", "nick"), control.choices.map { it.label })
        assertEquals("nick:", control.title)
        // Read aloud by name: the samples differ only by a trailing mark.
        assertEquals("Colon", control.spokenTitle)
        assertEquals("Space only", control.choices.last().spoken)
    }

    @Test
    fun aSuffixTheWebStoredWithASpaceIsTheOfferedChoice() {
        val control = rowState(nickSuffix, values = mapOf(nickSuffix.key to SettingValue.String(", "))).control
            as SettingControl.Menu
        assertEquals(",", control.current)
        assertEquals(4, control.choices.size)
        assertEquals("nick,", control.title)
    }

    @Test
    fun aCustomSuffixIsShownAsItselfNotRoundedToANeighbour() {
        val control = rowState(nickSuffix, values = mapOf(nickSuffix.key to SettingValue.String(" -"))).control
            as SettingControl.Menu
        assertEquals(" -", control.current)
        assertEquals(MenuChoice(" -", "nick -", "Space hyphen-minus"), control.choices.last())
        assertEquals("nick -", control.title)
    }

    @Test
    fun aStringWithNoCuratedChoicesIsAPlainRow() {
        val option = SettingOption("look.theme", "", "", SettingType.String, SettingValue.String("x"))
        assertSame(SettingControl.Plain, rowState(option).control)
        val color = SettingOption("look.color", "", "", SettingType.Color, SettingValue.String("#fff"))
        assertSame(SettingControl.Plain, rowState(color).control)
    }

    @Test
    fun liveFollowsTheRegistrysDependsOn() {
        val joins = bool("chat.consolidate_joins", default = false)
        val max = SettingOption(
            "chat.consolidate_max_names", "", "", SettingType.Int, SettingValue.Int(5),
            dependsOn = listOf(SettingDependency("chat.consolidate_joins", listOf(SettingValue.Bool(true)))),
        )
        val registry = mapOf(joins.key to joins, max.key to max)
        val off = SettingsModel.rowState(SettingRow("Max", max), Settings(registry, emptyMap()), SettingsEdits())
        assertFalse(off.enabled)
        val on = SettingsModel.rowState(
            SettingRow("Max", max),
            Settings(registry, mapOf(joins.key to SettingValue.Bool(true))),
            SettingsEdits(),
        )
        assertTrue(on.enabled)
    }

    @Test
    fun aRefusalIsPinnedUnderItsOwnRowOnly() {
        val edits = SettingsEdits(error = WriteError("k", "out of range"))
        assertEquals("out of range", rowState(bool("k"), edits = edits).error)
        assertNull(rowState(bool("other"), edits = edits).error)
    }

    // MARK: - The optimism rule

    @Test
    fun aWriteThatLandsDropsItsPendingValue() {
        val edits = SettingsEdits().began("k", SettingValue.Bool(true)).finished("k", SettingValue.Bool(true), failure = null)
        assertEquals(SettingsEdits(), edits)
    }

    @Test
    fun aRefusedWriteRevertsAndSaysWhy() {
        val edits = SettingsEdits().began("k", SettingValue.Int(500)).finished("k", SettingValue.Int(500), failure = "out of range")
        assertTrue(edits.pending.isEmpty())
        assertEquals(WriteError("k", "out of range"), edits.error)
    }

    @Test
    fun anOlderReplyLeavesANewerTapAlone() {
        val edits = SettingsEdits()
            .began("k", SettingValue.Bool(true))
            .began("k", SettingValue.Bool(false))
            .finished("k", SettingValue.Bool(true), failure = null)
        assertEquals(mapOf("k" to SettingValue.Bool(false)), edits.pending)
    }

    @Test
    fun anySuccessRetiresARejectionEvenOneThatChangedNothing() {
        val refused = SettingsEdits(error = WriteError("a", "nope"))
        assertNull(refused.finished("b", SettingValue.Bool(true), failure = null).error)
    }

    @Test
    fun aSettingsChangeRetiresARejection() {
        val refused = SettingsEdits(pending = mapOf("b" to SettingValue.Bool(true)), error = WriteError("a", "nope"))
        val changed = refused.settingsChanged()
        assertNull(changed.error)
        assertEquals(refused.pending, changed.pending)
        val clean = SettingsEdits()
        assertSame(clean, clean.settingsChanged())
    }

    // MARK: - About

    @Test
    fun theVersionLineIsIosShape() {
        assertEquals("Version 1.0 (1)", SettingsModel.versionString("1.0", 1))
        assertEquals("Version — (—)", SettingsModel.versionString(null, null))
    }

    // Port-only (review round):

    @Test
    fun aDependentRowFollowsAPendingToggle() {
        val joins = bool("chat.consolidate_joins", default = false)
        val max = SettingOption(
            "chat.consolidate_max_names", "", "", SettingType.Int, SettingValue.Int(5),
            dependsOn = listOf(SettingDependency("chat.consolidate_joins", listOf(SettingValue.Bool(true)))),
        )
        val registry = mapOf(joins.key to joins, max.key to max)
        val flipped = SettingsEdits().began(joins.key, SettingValue.Bool(true))
        assertTrue(SettingsModel.rowState(SettingRow("Max", max), Settings(registry, emptyMap()), flipped).enabled)
    }
}
