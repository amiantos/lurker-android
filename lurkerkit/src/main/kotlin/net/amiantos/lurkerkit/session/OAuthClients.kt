// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.asString
import net.amiantos.lurkerkit.store.DefaultsStorage

/**
 * The `client_id` this install registered with each server, so a sign-in reuses it rather
 * than registering again. Not a secret (the app is a public client), so it lives in the app's
 * plain preferences ([DefaultsStorage], `UserDefaults` on iOS), and it outlives sign-out.
 *
 * Saved as soon as the app registers. A server allows only a few registrations per address
 * (5 in 10 minutes), and otherwise every sign-in that didn't finish (a closed sheet, a Deny)
 * would spend another one on the next try.
 *
 * A saved id can still be gone: a registration nobody approves is deleted after an hour, and a
 * reinstalled instance loses approved ones too. The approval page would report that inside the
 * browser sheet, where closing it is all the app hears, so `ChatViewModel.signIn` asks the
 * server before using a saved id, and it's forgotten only when the server says it's unknown.
 *
 * Port note: LurkerKit defaults `defaults` to `UserDefaults.standard`. There is no platform
 * store in this module, so the app always passes one — the same [DefaultsStorage] it hands
 * `SettingsCache`. The ids travel as a `JsonObject` of strings.
 */
data class OAuthClients(
    /** Injectable so tests get their own store rather than scribbling on the app's. */
    private val defaults: DefaultsStorage,
) {
    internal fun clientId(server: String): String? = ids[server]

    internal fun save(clientId: String, server: String) {
        val ids = LinkedHashMap(this.ids)
        ids[server] = clientId
        defaults.set(encoded(ids), key = key)
    }

    internal fun forget(server: String) {
        val ids = LinkedHashMap(this.ids)
        ids.remove(server)
        defaults.set(encoded(ids), key = key)
    }

    /**
     * Port note: `as? [String: String]` on the stored dictionary — all or nothing, as that cast
     * is: one value that isn't a string and there are no ids at all, rather than fewer.
     */
    private val ids: Map<String, String>
        get() {
            val stored = defaults.dictionary(key) ?: return emptyMap()
            return stored.mapValues { (_, value) -> value.asString() ?: return emptyMap() }
        }

    private companion object {
        const val key = "lurker.oauth.clientIds"

        fun encoded(ids: Map<String, String>): JsonObject = JsonObject(ids.mapValues { JsonPrimitive(it.value) })
    }
}
