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
 *
 * Loaded keys are cached: a push wakes the process cold, and the Keystore unwrap plus key parsing
 * would otherwise run for every one. One instance is shared by the registrar and the messaging
 * service, so the cache is theirs alike; a write replaces it.
 *
 * Every touch of [storage] holds one lock. A read can delete (Android's secure storage drops a blob
 * it can't decrypt), so an FCM worker loading stale keys while the registrar writes replacements
 * would otherwise delete the replacements it raced past.
 */
class RelayPushKeys(private val storage: SecureStorage) {

    private val lock = Any()

    @Volatile private var cached: DeviceKeys? = null

    /** The stored keys, or null when there are none yet (or they can't be read). */
    fun load(): DeviceKeys? {
        cached?.let { return it }
        return synchronized(lock) { cached ?: readStored()?.also { cached = it } }
    }

    /**
     * The stored keys, making and storing them first if there are none. Null when fresh keys can't
     * be stored — read back and checked, since keys filed with a server but missing from storage
     * would make every push to this phone unreadable. Unreadable stored keys are replaced; the
     * caller compares the p256dh it last filed and files these.
     */
    fun loadOrCreate(): DeviceKeys? = synchronized(lock) { create() }

    private fun create(): DeviceKeys? {
        cached?.let { return it }
        readStored()?.let { cached = it; return it }
        val keys = DeviceKeys.generate()
        val form = keys.persisted()
        val json = buildJsonObject {
            put("privateKey", form.privateKey)
            put("publicKey", form.publicKey)
            put("authSecret", form.authSecret)
        }
        cached = null
        storage.write(ACCOUNT, json.toString().encodeUtf8())
        val back = readStored() ?: return null
        if (back.p256dh != keys.p256dh || back.auth != keys.auth) return null
        cached = back
        return back
    }

    private fun readStored(): DeviceKeys? {
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

    private companion object {
        const val ACCOUNT = "relay-push-keys"
    }
}
