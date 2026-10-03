// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.amiantos.lurkerkit.store.DefaultsStorage

/**
 * The kit's [DefaultsStorage] — its one `UserDefaults` seam — over a plain preferences file, each
 * key holding its `JsonObject` as JSON text.
 *
 * ONE instance, shared by `SettingsCache` and `OAuthClients`, as both share `UserDefaults` on iOS.
 * Their keys never collide (`lurker.settings.values`, `lurker.oauth.clientIds`), and sign-out
 * clears only the settings one: a server's client id outlives the session, or every sign-in after
 * a sign-out would spend another of the server's few registrations.
 *
 * Kept out of backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`). The
 * settings values are an account's — the kit clears them at sign-out precisely so they can't carry
 * to the next person — and a restored device starts with no session, so whoever signs in there
 * next would inherit them. The client ids are this install's registrations and are cheap to redo.
 */
class PrefsDefaultsStorage(private val prefs: StringPrefs) : DefaultsStorage {

    /** Null when nothing is stored, or what is stored isn't a JSON object — as `dictionary(forKey:)`. */
    override fun dictionary(key: String): JsonObject? {
        val text = prefs.getString(key) ?: return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }

    override fun set(value: JsonObject, key: String) {
        prefs.putString(key, value.toString())
    }

    override fun removeObject(key: String) {
        prefs.remove(key)
    }

    companion object {
        /** The preferences file. Named in the backup exclusions; rename both together. */
        const val FILE = "lurker_defaults"
    }
}
