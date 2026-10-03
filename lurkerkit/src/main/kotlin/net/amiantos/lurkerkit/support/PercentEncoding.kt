// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import okio.Buffer

/**
 * `removingPercentEncoding`: the text with every `%XX` escape decoded, or null for a malformed
 * escape (`%`, `%4`, `%zz`) or for bytes that are not UTF-8. Text with no `%` is itself.
 */
internal fun removingPercentEncoding(text: String): String? {
    if (!text.contains('%')) return text
    val bytes = Buffer()
    var index = 0
    while (index < text.length) {
        val percent = text.indexOf('%', index)
        if (percent < 0) {
            bytes.writeUtf8(text, index, text.length)
            break
        }
        bytes.writeUtf8(text, index, percent)
        if (percent + 2 >= text.length) return null
        val high = hexValue(text[percent + 1])
        val low = hexValue(text[percent + 2])
        if (high < 0 || low < 0) return null
        bytes.writeByte(high * 16 + low)
        index = percent + 3
    }
    return bytes.readByteString().utf8OrNull()
}

/** The value of one hex digit, or -1. */
internal fun hexValue(unit: Char): Int = when (unit) {
    in '0'..'9' -> unit - '0'
    in 'a'..'f' -> unit - 'a' + 10
    in 'A'..'F' -> unit - 'A' + 10
    else -> -1
}
