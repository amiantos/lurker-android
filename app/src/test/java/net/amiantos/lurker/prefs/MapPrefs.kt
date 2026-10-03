// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

/** [StringPrefs] over a map, standing in for a `SharedPreferences` file. */
class MapPrefs(val values: MutableMap<String, String> = mutableMapOf()) : StringPrefs {
    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
