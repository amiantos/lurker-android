// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.store

import kotlinx.serialization.json.JsonObject
import net.amiantos.lurkerkit.model.SettingValue

/**
 * Where `SettingsCache` keeps its one value: the three `UserDefaults` calls LurkerKit makes, and
 * no more. The kit holds the rule; `:app` implements this over its own preferences store, and the
 * tests over a map.
 *
 * Port-only: LurkerKit takes a `UserDefaults` (PORTING.md, the module boundary).
 *
 * The value is a JSON object because that is what a `SettingValue` already is on the wire
 * (`jsonValue`, `SettingValue.from`): an implementation stores it however it likes and hands
 * the same object back.
 */
interface SettingsCacheStorage {
    /**
     * `UserDefaults.dictionary(forKey:)`: the object last stored under `key`, or null when
     * there is none (or what is there is not an object).
     */
    fun dictionary(key: String): JsonObject?

    /** `UserDefaults.set(_:forKey:)`: replace whatever is stored under `key`. */
    fun set(value: JsonObject, key: String)

    /** `UserDefaults.removeObject(forKey:)`. */
    fun removeObject(key: String)
}

/**
 * Last-known setting *values*, persisted across launches.
 *
 * This exists for one reason, and it's a correctness one rather than a speed one:
 * `chat.send_typing_notifications` is a privacy switch, and without a cache the only answer
 * available before `/api/settings/bootstrap` returns is the registry default — `true`. So a
 * user who turned typing notifications **off** on the web would have their phone broadcast
 * `+typing` for the whole session any time that fetch failed: a flaky launch, or a
 * self-hosted server predating the endpoint. The setting used to be mirrored in
 * `UserPreferences` on iOS and survived relaunches; this is what replaces that guarantee.
 *
 * It generalizes for free — every cached value is in force from the first frame, so the app
 * no longer starts under default rules and reflow into the user's real ones a moment later.
 *
 * **Values only, not the registry.** A read consults `values` before the registry
 * (`Settings.effective`), so cached values alone are enough to make behavior correct with no
 * registry at all. The registry is only needed to *render controls*, and a settings screen
 * that needs the network once is a fair trade against silently broadcasting something the
 * user switched off.
 *
 * Only ever touched from the main thread, alongside the store it seeds.
 *
 * Port note: LurkerKit defaults `defaults` to `UserDefaults.standard`. There is no platform
 * store in this module, so the app always passes one.
 */
data class SettingsCache(
    /** Injectable so tests get their own store rather than scribbling on the app's. */
    private val defaults: SettingsCacheStorage,
) {
    /** The values from the last session, or empty when there's nothing cached. */
    fun load(): Map<String, SettingValue> {
        val raw = defaults.dictionary(valuesKey) ?: return emptyMap()
        val out = LinkedHashMap<String, SettingValue>()
        for ((key, value) in raw) {
            SettingValue.from(value)?.let { out[key] = it }
        }
        return out
    }

    /**
     * Replace the cache with the current values.
     *
     * A full replace rather than a merge, deliberately: this mirrors the server's stored set,
     * and a setting reset to its default disappears from that set. Merging would keep the old
     * value alive locally forever, which is exactly the kind of stale-forever state the cache
     * is otherwise designed to avoid.
     */
    fun save(values: Map<String, SettingValue>) {
        // `jsonValue` yields only a string, a number, a boolean or a list of strings.
        defaults.set(JsonObject(values.mapValues { it.value.jsonValue }), key = valuesKey)
    }

    /**
     * Drop the cache. Called on sign-out: the next account's preferences are not this one's,
     * and a privacy switch in particular must not carry across users.
     */
    fun clear() {
        defaults.removeObject(valuesKey)
    }

    private companion object {
        const val valuesKey = "lurker.settings.values"
    }
}
