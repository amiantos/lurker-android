// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.client

import org.json.JSONArray
import org.json.JSONObject

// org.json's opt* helpers coerce missing/null to "" or 0, which loses the
// distinction the wire relies on (e.g. a null topic vs. an empty one, an absent
// networkId vs. 0). These restore null-awareness.

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray.strings(): List<String> =
    (0 until length()).map { optString(it) }
