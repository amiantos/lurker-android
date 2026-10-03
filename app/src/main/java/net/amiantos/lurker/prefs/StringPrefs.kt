// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The three `SharedPreferences` calls the app's plain preferences make, and no more — so the
 * stores built on them ([PrefsDefaultsStorage], [UiPreferences]) are tested on the JVM over a
 * map, without Robolectric.
 */
interface StringPrefs {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)
}

/**
 * [StringPrefs] over a `SharedPreferences` file. `apply`, not `commit`: every caller is on the
 * main thread, the in-memory value is current the moment `apply` returns, and the disk write
 * happens off it.
 */
class SharedStringPrefs(private val prefs: SharedPreferences) : StringPrefs {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }
}
