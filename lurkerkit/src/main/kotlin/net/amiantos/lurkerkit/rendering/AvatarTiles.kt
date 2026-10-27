// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.rendering

import java.util.Locale

/**
 * Generated avatar tiles (lurker#607, Mobile lurker#1203): which image of a set a nick gets.
 *
 * A port of `shared/avatarTiles.ts`, byte-for-byte in what it hashes, so the web, the apps and
 * the server pick the same image for the same nick. The nick is trimmed and lowercased the way
 * the nick colour is (`NickColor`), then hashed with the same djb2 — but salted: the palette
 * has 19 entries and a set may have 140, which share no factor today but did on the web's 16
 * and 100, and the salt is what keeps colour and tile independent either way.
 *
 * Port note: a stop character is a code point (`Int`) here, where LurkerKit's is a
 * `Unicode.Scalar` — the same unit, and the type `NickColor.trimForColor` already takes.
 */
object AvatarTiles {
    private const val salt = "avatar-tile:"

    /** The stop characters the nick colour trims at by default (`look.nick.color_stop_chars`). */
    val defaultStopChars: Set<Int> = setOf('_'.code, '|'.code)

    /**
     * The index in a set of `count` tiles for `nick`, or null when there is nothing to pick: no
     * nick, or no tiles.
     */
    fun index(nick: String?, count: Int, stopChars: Set<Int> = defaultStopChars): Int? {
        if (nick == null || nick.isEmpty() || count <= 0) return null
        val normalized = NickColor.lowercasedLikeTheWeb(NickColor.trimForColor(nick, stopChars = stopChars))
        if (normalized.isEmpty()) return null
        return (NickColor.djb2(salt + normalized) % count.toUInt()).toInt()
    }

    /**
     * `<base>/<nnn>.<ext>`: tiles are numbered from 000 in file-name order.
     *
     * Port note: the padding is `String.format(Locale.ROOT, "%03d")`, which is LurkerKit's
     * "zeros up to three digits" for every index a set can produce (`index` is never negative).
     */
    fun url(base: String, index: Int, ext: String): String {
        var trimmed = base
        while (trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
        val padded = String.format(Locale.ROOT, "%03d", index)
        return "$trimmed/$padded.$ext"
    }

    /**
     * The stop-character set a `look.nick.color_stop_chars` value names. Empty means "trim
     * nothing", as the web's `trimForHash` with no stop chars does.
     */
    fun stopChars(setting: String): Set<Int> = setting.codePoints().toArray().toSet()
}
