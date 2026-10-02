// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.model.BuiltinNetworks
import net.amiantos.lurkerkit.model.NetworkPreset
import net.amiantos.lurkerkit.model.NetworkPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The add-network picker's data (lurker-ios#11): the bundled catalogue, the instance's own
 * presets, and what a pick fills into the form.
 */
class NetworkPresetTests {

    // MARK: - The bundled catalogue

    @Test
    fun testTheCatalogueLoadsFromTheBundle() {
        // A resource that doesn't reach the bundle fails silently — an empty list and a
        // picker offering nothing but "Other Server…", which looks like a design decision
        // rather than a build problem.
        assertTrue(BuiltinNetworks.all.size > 50)
        assertTrue(BuiltinNetworks.all.any { it.host == "irc.libera.chat" })
    }

    @Test
    fun testEveryEntryIsUsableAsAPreset() {
        // A row missing a host or a port is a row that fills the form with something that
        // can't connect — worse than not offering it, because the user tries it.
        for (preset in BuiltinNetworks.all) {
            assertFalse(preset.name.isEmpty())
            assertFalse(preset.host.isEmpty(), preset.name)
            assertTrue(preset.port in 1..65535, preset.name)
        }
    }

    @Test
    fun testLurkerFriendlyNetworksComeFirst() {
        // The picker's default order has to be meaningful before anyone types, and the
        // networks where a new user can get help with this client come first.
        val firstNonLurker = BuiltinNetworks.all
            .indexOfFirst { !it.tags.contains(BuiltinNetworks.lurkerTag) }.takeIf { it >= 0 }
        val lastLurker = BuiltinNetworks.all
            .indexOfLast { it.tags.contains(BuiltinNetworks.lurkerTag) }.takeIf { it >= 0 }
        assertNotNull(firstNonLurker)
        if (lastLurker != null) assertTrue(lastLurker < firstNonLurker)
    }

    // MARK: - Suggested channels

    @Test
    fun testALurkerNetworkLeadsWithLurker() {
        val preset = NetworkPreset(
            name = "Libera.Chat", host = "irc.libera.chat", port = 6697, tls = true,
            defaultChannel = "#libera", tags = listOf(BuiltinNetworks.lurkerTag),
        )
        assertEquals(listOf("#lurker", "#libera"), preset.suggestedChannels)
    }

    @Test
    fun testANetworkWeKnowNothingAboutSuggestsNothing() {
        // Absent is the honest answer: a wrong channel name lands a brand-new user somewhere
        // that doesn't exist, which is worse than landing them nowhere.
        val preset = NetworkPreset(name = "Somewhere", host = "irc.example", port = 6697, tls = true)
        assertTrue(preset.suggestedChannels.isEmpty())
    }

    @Test
    fun testAnInstancePresetsChannelsAreTheAdminsAlone() {
        // They run the place, so their word is the last word — we don't add #lurker to it.
        val preset = NetworkPreset(
            name = "Corp", host = "irc.corp.example", port = 6697, tls = true,
            recommendedChannels = listOf("#general", "#random"),
            tags = listOf(BuiltinNetworks.lurkerTag), isInstance = true,
        )
        assertEquals(listOf("#general", "#random"), preset.suggestedChannels)
    }

    @Test
    fun testTheNetworksOwnChannelIsNotRepeated() {
        val preset = NetworkPreset(
            name = "N", host = "h", port = 6697, tls = true,
            defaultChannel = "#LURKER", tags = listOf(BuiltinNetworks.lurkerTag),
        )
        assertEquals(listOf("#lurker"), preset.suggestedChannels)
    }

    // MARK: - What a pick fills in

    @Test
    fun testAPickFillsEverythingButTheNick() {
        val preset = NetworkPreset(
            name = "Libera.Chat", host = "irc.libera.chat", port = 6697, tls = true,
            defaultChannel = "#libera", tags = listOf(BuiltinNetworks.lurkerTag),
        )
        val draft = preset.draft()
        assertEquals("Libera.Chat", draft.name)
        assertEquals("irc.libera.chat", draft.host)
        assertEquals(6697, draft.port)
        assertTrue(draft.tls)
        assertEquals("#lurker, #libera", draft.defaultChannel)
        // The one thing the picker can't know, and the reason the form still opens.
        assertTrue(draft.nick.isEmpty())
        // ⚠ And it still verifies certificates — a prefill must not quietly relax the
        // security default. See `NetworkConfig.trustedCertificates`.
        assertTrue(draft.trustedCertificates)
    }

    @Test
    fun testAnUnknownNetworkStillOffersAChannelToJoin() {
        // Landing in an empty server buffer is what makes a new user think the app is broken.
        // `#chat` is a guess, and it's offered as an editable field, never joined silently.
        val draft = NetworkPreset(name = "N", host = "h", port = 6697, tls = true).draft()
        assertEquals(BuiltinNetworks.fallbackChannel, draft.defaultChannel)
    }

    // MARK: - Instance presets and the lockdown

    @Test
    fun testPresetsParseWithTheirChannelsAndPolicy() {
        val presets = FrameParser.parseNetworkPresets(
            """
            {"presets":[{"id":3,"name":"Corp","host":"irc.corp.example","port":6667,"tls":false,
            "saslLikelyRequired":true,"channels":["#general"]}],"allowUserDefined":false}
            """.trimIndent(),
        )
        assertEquals(1, presets?.instance?.size)
        assertEquals(3, presets?.instance?.firstOrNull()?.instanceID)
        assertEquals(6667, presets?.instance?.firstOrNull()?.port)
        assertEquals(false, presets?.instance?.firstOrNull()?.tls)
        assertEquals(listOf("#general"), presets?.instance?.firstOrNull()?.recommendedChannels)
        assertEquals(true, presets?.instance?.firstOrNull()?.isInstance)
        assertEquals(false, presets?.allowUserDefined)
    }

    @Test
    fun testAnOlderServerIsNotTreatedAsLockedDown() {
        // ⚠⚠ A server predating lurker#298 sends no policy, and reading its silence as "locked
        // down" would hide the custom-server path — leaving an app that can't add a network
        // at all, which is the failure lurker-ios#11 exists to fix.
        assertEquals(true, FrameParser.parseNetworkPresets("""{"presets":[]}""")?.allowUserDefined)
        assertNull(FrameParser.parseNetworkPresets("not json"))
    }

    @Test
    fun testALockedDownInstanceOffersOnlyItsOwnNetworks() {
        // ⚠⚠ The policy is an allowlist of hosts: with `allowUserDefined` off, the enabled
        // presets are the entire allowed set. Every builtin would be a row whose only outcome
        // is a 403.
        val corp = NetworkPreset(
            name = "Corp", host = "irc.corp.example", port = 6697, tls = true, isInstance = true,
        )
        val presets = NetworkPresets(instance = listOf(corp), allowUserDefined = false)
        assertEquals(listOf(corp), presets.offered)
    }

    @Test
    fun testAnOpenInstancePinsItsOwnNetworksAboveTheCatalogue() {
        val corp = NetworkPreset(
            name = "Corp", host = "irc.corp.example", port = 6697, tls = true, isInstance = true,
        )
        val offered = NetworkPresets(instance = listOf(corp), allowUserDefined = true).offered
        assertEquals(corp, offered.first())
        assertTrue(offered.size > BuiltinNetworks.all.size)
    }

    @Test
    fun testAnAdminsOwnListingWinsOverTheBuiltinForTheSameHost() {
        // Otherwise the same server appears twice under two names, and one of the two carries
        // the admin's recommended channels while the other doesn't.
        val libera = NetworkPreset(
            name = "Our Libera", host = "irc.libera.chat", port = 6697, tls = true, isInstance = true,
        )
        val offered = NetworkPresets(instance = listOf(libera), allowUserDefined = true).offered
        assertEquals(listOf(libera), offered.filter { it.host == "irc.libera.chat" })
    }
}
