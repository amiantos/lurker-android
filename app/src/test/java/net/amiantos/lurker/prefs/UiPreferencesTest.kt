// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiPreferencesTest {

    @Test
    fun theServerDefaultsToLurkerChat() {
        assertEquals(ServerAddress.lurkerChat, UiPreferences(MapPrefs()).lastServerURL)
    }

    @Test
    fun theServerIsRemembered() {
        val prefs = MapPrefs()
        UiPreferences(prefs).lastServerURL = "http://xerxes:8010"
        assertEquals("http://xerxes:8010", UiPreferences(prefs).lastServerURL)
    }

    @Test
    fun theLastBufferKeepsItsCase() {
        val prefs = MapPrefs()
        UiPreferences(prefs).recordLastOpenBuffer(BufferKey(networkId = 3, target = "#Lurker"))
        assertEquals(BufferKey(networkId = 3, target = "#Lurker"), UiPreferences(prefs).lastOpenBufferKey)
    }

    @Test
    fun theSystemBufferIsStoredByAbsence() {
        val prefs = MapPrefs()
        val ui = UiPreferences(prefs)
        ui.recordLastOpenBuffer(BufferKey(networkId = 3, target = "#lurker"))
        ui.recordLastOpenBuffer(BufferKey(networkId = null, target = "Lurker"))
        assertEquals(BufferKey(networkId = null, target = "Lurker"), ui.lastOpenBufferKey)
        assertEquals(setOf("lastBufferTarget"), prefs.values.keys)
    }

    @Test
    fun nothingRecordedIsNull() {
        assertNull(UiPreferences(MapPrefs()).lastOpenBufferKey)
        assertNull(UiPreferences(MapPrefs(mutableMapOf("lastBufferTarget" to ""))).lastOpenBufferKey)
    }

    @Test
    fun forgettingDropsBothParts() {
        val prefs = MapPrefs()
        val ui = UiPreferences(prefs)
        ui.lastServerURL = "https://chat.example"
        ui.recordLastOpenBuffer(BufferKey(networkId = 1, target = "#a"))
        ui.forgetLastOpenBuffer()
        assertNull(ui.lastOpenBufferKey)
        // The server stays: it's the prefill after sign-out.
        assertEquals(mapOf("lastServerURL" to "https://chat.example"), prefs.values)
    }

    @Test
    fun forgettingAClosedBufferMatchesById() {
        val ui = UiPreferences(MapPrefs())
        ui.recordLastOpenBuffer(BufferKey(networkId = 1, target = "#Lurker"))
        ui.forgetLastOpenBuffer(ifMatching = BufferKey(networkId = 2, target = "#lurker"))
        assertEquals(BufferKey(networkId = 1, target = "#Lurker"), ui.lastOpenBufferKey)
        ui.forgetLastOpenBuffer(ifMatching = BufferKey(networkId = 1, target = "#lurker"))
        assertNull(ui.lastOpenBufferKey)
    }

    @Test
    fun aRenameIsFollowed() {
        val ui = UiPreferences(MapPrefs())
        ui.recordLastOpenBuffer(BufferKey(networkId = 1, target = "bob"))
        ui.rewriteBuffer(from = BufferKey(networkId = 1, target = "Bob"), to = BufferKey(networkId = 1, target = "robert"))
        assertEquals(BufferKey(networkId = 1, target = "robert"), ui.lastOpenBufferKey)
    }

    @Test
    fun aCasingOnlyRenameRefreshesTheCase() {
        val ui = UiPreferences(MapPrefs())
        ui.recordLastOpenBuffer(BufferKey(networkId = 1, target = "bob"))
        ui.rewriteBuffer(from = BufferKey(networkId = 1, target = "bob"), to = BufferKey(networkId = 1, target = "Bob"))
        assertEquals(BufferKey(networkId = 1, target = "Bob"), ui.lastOpenBufferKey)
    }

    @Test
    fun anotherBuffersRenameLeavesItAlone() {
        val ui = UiPreferences(MapPrefs())
        ui.recordLastOpenBuffer(BufferKey(networkId = 1, target = "bob"))
        ui.rewriteBuffer(from = BufferKey(networkId = 2, target = "bob"), to = BufferKey(networkId = 2, target = "robert"))
        assertEquals(BufferKey(networkId = 1, target = "bob"), ui.lastOpenBufferKey)
    }
}
