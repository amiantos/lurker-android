// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.SecureStorage
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.DefaultsStorage
import net.amiantos.lurkerkit.store.SettingsCache
import okio.ByteString

// Port-only: the in-memory stand-ins for the platform storage the kit's stores take. Where a
// LurkerKit test gives each suite its own Keychain service and its own `UserDefaults` suite so
// nothing touches the app's, a test here gives each its own fresh map.

/** `SecureStorage` over a map — the in-memory stand-in for the Keychain. */
class InMemorySecureStorage : SecureStorage {
    val stored = mutableMapOf<String, ByteString>()

    override fun read(account: String): ByteString? = stored[account]

    override fun write(account: String, data: ByteString) {
        stored[account] = data
    }

    override fun delete(account: String) {
        stored.remove(account)
    }
}

/** `DefaultsStorage` over a map — the in-memory stand-in for `UserDefaults`. */
class InMemoryDefaultsStorage : DefaultsStorage {
    val stored = mutableMapOf<String, JsonObject>()

    override fun dictionary(key: String): JsonObject? = stored[key]

    override fun set(value: JsonObject, key: String) {
        stored[key] = value
    }

    override fun removeObject(key: String) {
        stored.remove(key)
    }
}

/**
 * A view model over empty storage and the default HTTP client: no session to restore, so it
 * fetches nothing. Its tasks are launched in [scope]; a `TestScope` nobody advances runs none of
 * them, as a LurkerKit test's `Task`s run only after its synchronous body returns.
 */
fun testViewModel(scope: TestScope = TestScope()): ChatViewModel =
    ChatViewModel(
        scope = scope,
        sessions = SessionStore(InMemorySecureStorage()),
        settingsCache = SettingsCache(InMemoryDefaultsStorage()),
        oauthClients = OAuthClients(InMemoryDefaultsStorage()),
        formatExpiry = { it.toString() },
    )
