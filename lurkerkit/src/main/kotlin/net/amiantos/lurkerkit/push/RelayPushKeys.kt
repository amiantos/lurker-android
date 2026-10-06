// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.amiantos.lurkerkit.session.SecureStorage
import okio.ByteString.Companion.encodeUtf8

/**
 * This install's Web Push keys (lurker-dev/RELAY_PLAN.md §6.2): created on first use, then kept for
 * the life of the install — every server the phone registers with gets the same p256dh and auth,
 * and the messaging service reads them back to open each relayed push.
 *
 * In [storage] — secure storage of its own on Android (a Keystore key that isn't the session's),
 * which is excluded from backup: keys restored onto new hardware would sit beside a registration
 * that names the old phone's token.
 */
class RelayPushKeys(private val storage: SecureStorage) {

    /** The stored keys, or null when there are none yet (or they can't be read). */
    fun load(): DeviceKeys? {
        val blob = storage.read(ACCOUNT) ?: return null
        val obj = runCatching { Json.parseToJsonElement(blob.utf8()) as? JsonObject }.getOrNull() ?: return null
        val form = runCatching {
            DeviceKeys.PersistedForm(
                privateKey = obj.getValue("privateKey").jsonPrimitive.content,
                publicKey = obj.getValue("publicKey").jsonPrimitive.content,
                authSecret = obj.getValue("authSecret").jsonPrimitive.content,
            )
        }.getOrNull() ?: return null
        return DeviceKeys.fromPersisted(form)
    }

    /**
     * The stored keys, making and storing them first if there are none. Unreadable keys are
     * replaced: a registration filed with the old ones gets re-filed with these on the next
     * enable, and a push encrypted for the old ones in the meantime is dropped.
     */
    fun loadOrCreate(): DeviceKeys {
        load()?.let { return it }
        val keys = DeviceKeys.generate()
        val form = keys.persisted()
        val json = buildJsonObject {
            put("privateKey", form.privateKey)
            put("publicKey", form.publicKey)
            put("authSecret", form.authSecret)
        }
        storage.write(ACCOUNT, json.toString().encodeUtf8())
        return keys
    }

    private companion object {
        const val ACCOUNT = "relay-push-keys"
    }
}
