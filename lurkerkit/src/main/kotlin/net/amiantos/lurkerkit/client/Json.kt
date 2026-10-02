// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Typed reads over a parsed `JsonObject` — the Kotlin stand-in for LurkerKit's reads over a
// `JSONSerialization` dictionary. `stringOrNull` collapses missing, JSON null, and "" to
// null — restoring the wire's distinction between an absent/empty string and a present one
// (e.g. a null topic vs. a real one).
//
// ⚠ Every read is type-strict, the way a Swift `as?` cast is: a number is never read as a
// string, nor `"3"` as a number, nor `1` as `true`. kotlinx's own `JsonPrimitive.content`
// and `.int` would happily coerce all three, so nothing below goes through them unguarded.

/** The value under [key] if it is a JSON string (quoted on the wire), else null. */
private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The value under [key] if it is an unquoted primitive — a number or a boolean — else null. */
private fun JsonObject.literal(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }

internal fun JsonObject.stringOrNull(key: String): String? =
    stringValue(key)?.takeIf { it.isNotEmpty() }

internal fun JsonObject.string(key: String, fallback: String = ""): String =
    stringValue(key) ?: fallback

/**
 * null for missing/null; used where absent (null networkId → system buffer) must be
 * distinguished from 0.
 */
internal fun JsonObject.intOrNull(key: String): Int? = literal(key)?.intOrNull

internal fun JsonObject.int(key: String, fallback: Int = 0): Int = intOrNull(key) ?: fallback

/**
 * The 64-bit read, for the fields that are `Long` on this side: message and event ids, byte
 * counts, epoch milliseconds. Swift's `Int` is 64-bit everywhere, so LurkerKit has one read
 * where this port has two.
 */
internal fun JsonObject.longOrNull(key: String): Long? = literal(key)?.longOrNull

internal fun JsonObject.long(key: String, fallback: Long = 0): Long = longOrNull(key) ?: fallback

internal fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
    literal(key)?.booleanOrNull ?: fallback

/**
 * The array of objects under [key], or empty.
 *
 * All-or-nothing, as the Swift `as? [[String: Any]]` cast is: one element that isn't an
 * object empties the whole read rather than quietly shortening it.
 */
internal fun JsonObject.objects(key: String): List<JsonObject> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.map { it as? JsonObject ?: return emptyList() }
}

/** A key present with a non-null value (`reset:false` still counts as present). */
internal fun JsonObject.has(key: String): Boolean {
    val value = this[key] ?: return false
    return value !is JsonNull
}

/**
 * Each element of a heterogeneous array, decoded independently of its neighbours — LurkerKit's
 * `FailableDecodable`.
 *
 * ⚠ For wire arrays where a single unrecognised element must not discard the rest. Decoding a
 * `List<T>` is all-or-nothing: one element that throws takes the whole array with it, and one
 * layer up that is indistinguishable from the request having failed — which, for anything with
 * a retry path, turns a decode mismatch into a permanent loop over data that will never decode.
 *
 * The failure is deliberately silent. The caller is deciding what to DRAW; an element it cannot
 * understand is one it cannot draw, and there is nothing useful to say about it.
 */
internal fun <T> Json.decodeEach(strategy: DeserializationStrategy<T>, elements: List<JsonElement>): List<T> =
    elements.mapNotNull { element ->
        try {
            decodeFromJsonElement(strategy, element)
        } catch (_: IllegalArgumentException) {
            // SerializationException is one; so is what a malformed enum or number throws.
            null
        }
    }
