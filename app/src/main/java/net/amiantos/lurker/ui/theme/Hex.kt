// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * `#rrggbb` as an opaque ARGB int, or null when it isn't exactly that. Every hex in the palette
 * is written that way — the web's presets and the kit's `IRCPalette` both are — so anything else
 * is a typo.
 */
internal fun parseHex(hex: String): Int? {
    if (hex.length != 7 || hex[0] != '#') return null
    var rgb = 0
    for (index in 1 until hex.length) {
        val digit = Character.digit(hex[index], 16)
        if (digit < 0) return null
        rgb = (rgb shl 4) or digit
    }
    return (0xFF shl 24) or rgb
}

/**
 * A palette literal as a [Color].
 *
 * ⚠ A malformed hex throws rather than falling back. iOS asserts in debug and ships a muted
 * fallback, because its app target has no test bundle and nothing but the compiler ever sees
 * `Palette`'s literals. Here `LurkerColorsTest` builds both palettes on every
 * `testDebugUnitTest`, so a typo fails the build before it can ship — and a throw is the only way
 * that test can see it.
 */
internal fun hexColor(hex: String): Color =
    Color(requireNotNull(parseHex(hex)) { "Palette: malformed hex \"$hex\"" })
