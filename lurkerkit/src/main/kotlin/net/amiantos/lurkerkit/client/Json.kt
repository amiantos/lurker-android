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

// Typed reads over a parsed `JsonObject` — the Kotlin stand-in for LurkerKit's reads over a
// `JSONSerialization` dictionary. `stringOrNull` collapses missing, JSON null, and "" to
// null — restoring the wire's distinction between an absent/empty string and a present one
// (e.g. a null topic vs. a real one).
//
// ⚠ Every read casts the way a Swift `as?` does on a `JSONSerialization` value, no more and no
// less. A string is never a number and a number never a string: kotlinx's own
// `JsonPrimitive.content` and `.int` would read `"3"` as 3 and `3` as "3", so nothing below
// goes through them unguarded. But a JSON number or boolean is an `NSNumber` over there, and
// those DO bridge into each other: `true` reads as the integer 1, `1` and `0` read as booleans,
// and `3.0` reads as the integer 3. A field the server sends as a SQLite 0/1 is a boolean on
// iOS for that reason, and has to be one here. (Answers taken from Foundation, pinned in
// `JsonTests`.)

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
internal fun JsonObject.intOrNull(key: String): Int? =
    longOrNull(key)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

internal fun JsonObject.int(key: String, fallback: Int = 0): Int = intOrNull(key) ?: fallback

/**
 * The 64-bit read, for the fields that are `Long` on this side: message and event ids, byte
 * counts, epoch milliseconds. Swift's `Int` is 64-bit everywhere, so LurkerKit has one read
 * where this port has two.
 */
internal fun JsonObject.longOrNull(key: String): Long? = literal(key)?.let(::integer)

internal fun JsonObject.long(key: String, fallback: Long = 0): Long = longOrNull(key) ?: fallback

internal fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val literal = literal(key) ?: return fallback
    return when (literal.content) {
        "true" -> true
        "false" -> false
        // Only 0 and 1 are booleans; `2` is not.
        else -> when (integer(literal)) {
            0L -> false
            1L -> true
            else -> fallback
        }
    }
}

/**
 * An unquoted literal as an integer, the way an `NSNumber` casts to `Int`: a boolean is 1 or 0,
 * and a number is itself when it is whole (`3`, `3.0`, `1e3`) and in range. `1.5` is nothing.
 */
private fun integer(literal: JsonPrimitive): Long? {
    when (literal.content) {
        "true" -> return 1
        "false" -> return 0
    }
    literal.content.toLongOrNull()?.let { return it }
    val number = literal.content.toDoubleOrNull() ?: return null
    // NaN fails the first test; 2^63 itself rounds into range as a double, hence `<`.
    if (number != Math.rint(number)) return null
    if (number < -9.223372036854775808E18 || number >= 9.223372036854775808E18) return null
    return number.toLong()
}

/**
 * The array of objects under [key], or empty.
 *
 * All-or-nothing, as the Swift `as? [[String: Any]]` cast is: one element that isn't an
 * object empties the whole read rather than quietly shortening it.
 */
internal fun JsonObject.objects(key: String): List<JsonObject> = this[key]?.asObjects() ?: emptyList()

/**
 * This element as an array of objects, or null — `as? [[String: Any]]`, all-or-nothing in the
 * same way, for a body whose top level is the array.
 */
internal fun JsonElement.asObjects(): List<JsonObject>? {
    val array = this as? JsonArray ?: return null
    return array.map { it as? JsonObject ?: return null }
}

/**
 * The array of strings under [key], or null — `as? [String]`. All-or-nothing again: one
 * element that isn't a string and there is no list, rather than a shorter one.
 */
internal fun JsonObject.strings(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    return array.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    }
}

/** `as? String` on a value rather than under a key: the string, with no fallback and "" kept. */
internal fun JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `as? Int` on a value rather than under a key, bridging a boolean the way the keyed read does. */
internal fun JsonElement?.asLong(): Long? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.let(::integer)

/**
 * The array of integers under [key], or null — `as? [Int]`. All-or-nothing, as `strings` is: one
 * element that isn't a whole number and there is no list, rather than a shorter one.
 */
internal fun JsonObject.longs(key: String): List<Long>? {
    val array = this[key] as? JsonArray ?: return null
    return array.map { element -> element.asLong() ?: return null }
}

/** The object of strings under [key], or null — `as? [String: String]`, all-or-nothing again. */
internal fun JsonObject.stringMap(key: String): Map<String, String>? {
    val obj = this[key] as? JsonObject ?: return null
    return obj.mapValues { (_, value) -> value.asString() ?: return null }
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
