// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurkerkit.model.BuiltinNetworks
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkPreset
import net.amiantos.lurkerkit.model.NetworkPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preset picker's rules — lurker-ios's `NetworkPickerViewController`. */
class NetworkPickerModelTest {

    private val libera = NetworkPreset(name = "Libera.Chat", host = "irc.libera.chat", port = 6697, tls = true)
    private val oftc = NetworkPreset(name = "OFTC", host = "irc.oftc.net", port = 6697, tls = true)
    private val home = NetworkPreset(name = "Home", host = "irc.home.example", port = 6697, tls = true, isInstance = true, instanceID = 1)

    @Test
    fun theWholeCatalogueIsOfferedBeforeAnyoneTypes() {
        val rows = NetworkPickerModel.rows(BuiltinNetworks.all, allowsCustom = true, query = "")
        assertEquals(BuiltinNetworks.all.size + 1, rows.size)
        assertEquals(PickerRow.Custom, rows.last())
    }

    @Test
    fun searchMatchesNameOrHostIgnoringCaseAndSurroundingSpaces() {
        val offered = listOf(libera, oftc)
        assertEquals(listOf(oftc), NetworkPickerModel.matches(offered, "oftc"))
        // Host as well as name: someone pasting a hostname from a wiki should find it.
        assertEquals(listOf(oftc), NetworkPickerModel.matches(offered, "oftc.net"))
        assertEquals(listOf(libera), NetworkPickerModel.matches(offered, "  LIBERA "))
        assertEquals(offered, NetworkPickerModel.matches(offered, "   "))
    }

    @Test
    fun aLockedDownInstanceOffersNoCustomServer() {
        // ⚠⚠ With user-defined networks off, a custom server is a form whose save can only 403.
        val rows = NetworkPickerModel.rows(listOf(home), allowsCustom = false, query = "")
        assertEquals(listOf<PickerRow>(PickerRow.Preset(home)), rows)
        assertEquals("This server's administrator chooses which networks can be added.", NetworkPickerModel.footer(false, rows))
    }

    @Test
    fun anOpenInstanceHasNoFooter() {
        assertNull(NetworkPickerModel.footer(true, NetworkPickerModel.rows(listOf(home), allowsCustom = true, query = "")))
    }

    @Test
    fun aLockedDownInstanceOffersOnlyItsOwnPresets() {
        // The kit's rule, which the picker's state reads (`NetworkPresets.offered`).
        val presets = NetworkPresets(instance = listOf(home), allowUserDefined = false)
        assertEquals(listOf(home), presets.offered)
        val open = NetworkPresets(instance = listOf(home), allowUserDefined = true)
        assertEquals(home, open.offered.first())
        assertTrue(open.offered.size > 1)
    }

    @Test
    fun nothingOnOfferExplainsItselfAndTheFooterStaysQuiet() {
        // ⚠ Reachable: a locked-down instance whose admin enabled nothing.
        val rows = NetworkPickerModel.rows(emptyList(), allowsCustom = false, query = "")
        assertTrue(rows.isEmpty())
        assertEquals(
            PickerPlaceholder(
                "No networks available",
                "This server's administrator chooses which networks can be added.",
                StateSymbol.NoNetworks,
            ),
            NetworkPickerModel.placeholder(emptyList(), allowsCustom = false, query = ""),
        )
        // Both at once would be the same sentence twice.
        assertNull(NetworkPickerModel.footer(false, rows))
    }

    @Test
    fun aSearchThatMissesSaysSoRatherThanBlamingTheAdmin() {
        assertEquals(PickerPlaceholder("No matches", symbol = StateSymbol.Search), NetworkPickerModel.placeholder(listOf(home), allowsCustom = false, query = "zzz"))
        // With the custom row there's always a row, so never a placeholder.
        assertNull(NetworkPickerModel.placeholder(listOf(home), allowsCustom = true, query = "zzz"))
    }

    @Test
    fun theAdminsOwnNetworksSaySo() {
        assertEquals("irc.home.example · offered by this server", NetworkPickerModel.subtitle(home))
        assertEquals("irc.libera.chat", NetworkPickerModel.subtitle(libera))
    }

    @Test
    fun aPresetFillsTheDraftAndCustomIsBlank() {
        val draft = NetworkPickerModel.draft(PickerRow.Preset(libera))
        assertEquals("Libera.Chat", draft.name)
        assertEquals("irc.libera.chat", draft.host)
        assertEquals("", draft.nick)
        // Nothing known about its channel: the guess is offered, as a guess.
        assertEquals("#chat", draft.defaultChannel)
        assertEquals(NetworkDraft(), NetworkPickerModel.draft(PickerRow.Custom))
        assertEquals("Other Server…", NetworkPickerModel.CUSTOM_TITLE)
    }
}
