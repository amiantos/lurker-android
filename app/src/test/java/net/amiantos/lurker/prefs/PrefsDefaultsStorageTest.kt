// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.store.SettingsCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsDefaultsStorageTest {

    @Test
    fun anObjectRoundTrips() {
        val prefs = MapPrefs()
        val storage = PrefsDefaultsStorage(prefs)
        val value = buildJsonObject {
            put("text", "héllo \"quoted\" ✓")
            put("number", 42)
            put("fraction", 1.5)
            put("flag", false)
            putJsonArray("list") {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        }
        storage.set(value, key = "k")
        assertEquals(value, PrefsDefaultsStorage(prefs).dictionary("k"))
    }

    @Test
    fun nothingStoredIsNull() {
        assertNull(PrefsDefaultsStorage(MapPrefs()).dictionary("k"))
    }

    @Test
    fun whatIsNotAnObjectIsNull() {
        for (text in listOf("not json", "[1,2]", "\"text\"", "42", "{\"open\":")) {
            assertNull(text, PrefsDefaultsStorage(MapPrefs(mutableMapOf("k" to text))).dictionary("k"))
        }
    }

    @Test
    fun removeObjectRemovesOnlyItsKey() {
        val prefs = MapPrefs()
        val storage = PrefsDefaultsStorage(prefs)
        storage.set(buildJsonObject { put("a", 1) }, key = "one")
        storage.set(buildJsonObject { put("b", 2) }, key = "two")
        storage.removeObject("one")
        assertNull(storage.dictionary("one"))
        assertEquals(buildJsonObject { put("b", 2) }, storage.dictionary("two"))
    }

    /** The two kit stores share one file, and sign-out clears only the settings. */
    @Test
    fun theKitsStoresShareItAndClientIdsOutliveTheSettings() {
        val prefs = MapPrefs()
        val storage = PrefsDefaultsStorage(prefs)
        val settings = SettingsCache(storage)
        settings.save(
            mapOf(
                "chat.send_typing_notifications" to SettingValue.from(JsonPrimitive(false))!!,
                "look.list" to SettingValue.from(JsonArray(listOf(JsonPrimitive("x"))))!!,
            ),
        )
        // What `OAuthClients.save` writes (its accessors are internal to the kit).
        prefs.putString("lurker.oauth.clientIds", buildJsonObject { put("https://a.example", "client-1") }.toString())

        val reread = SettingsCache(PrefsDefaultsStorage(prefs)).load()
        assertEquals(2, reread.size)

        settings.clear()
        assertTrue(SettingsCache(storage).load().isEmpty())
        assertEquals(
            buildJsonObject { put("https://a.example", "client-1") },
            storage.dictionary("lurker.oauth.clientIds"),
        )
    }
}
