// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

/**
 * Swift's `Int(_ text: String)`: an optional sign and ASCII digits, nothing else, in range.
 *
 * Port note: `toLongOrNull` alone also reads the digits of other scripts (`"٣"` is 3 to it),
 * which Swift does not.
 */
internal fun swiftInt(text: String): Long? {
    val digits = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return text.toLongOrNull()
}
