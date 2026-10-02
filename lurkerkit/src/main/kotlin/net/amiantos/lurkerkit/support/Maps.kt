// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

// Swift dictionary idioms over Kotlin's read-only `Map`, for the immutable ports of structs
// that LurkerKit mutates in place (PORTING.md, structs that mutate). Nothing here has a Swift
// counterpart: Swift spells all of these as subscript assignments.

/** A dictionary assignment in LurkerKit's sense: a value sets the key, null removes it. */
internal fun <K, V : Any> Map<K, V>.setting(key: K, value: V?): Map<K, V> =
    if (value == null) this - key else this + (key to value)

/**
 * `map[to] = map[from]; map[from] = nil` — LurkerKit's rekey of one side table. A missing
 * `from` removes `to`, as assigning nil does.
 */
internal fun <V : Any> Map<String, V>.moving(from: String, to: String): Map<String, V> =
    (this - from).setting(to, this[from])

/** `revisions[key, default: 0] &+= 1`. */
internal fun Map<String, Int>.bumped(key: String): Map<String, Int> = this + (key to (this[key] ?: 0) + 1)
