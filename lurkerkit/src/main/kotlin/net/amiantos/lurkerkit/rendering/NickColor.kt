// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.rendering

/**
 * The color tables the web client uses, so native rendering matches it exactly.
 *
 * Source of truth is the web client's two built-in themes — `look.color.mirc_colors` and
 * `look.nick.colors`, as the Monokai Plus / Monokai Plus Light presets define them in
 * `shared/themePresets.ts`. Both tables here are byte-identical to those. When one moves,
 * this moves.
 */
object IRCPalette {
    /**
     * mIRC colors 0–15. Indices 16+ are intentionally not rendered.
     *
     * ⚠ **Every slot is a literal, and no slot may become a theme reference.** A colour code
     * names a colour — `^C00` means white, not "whatever this theme calls text" — and a run can
     * carry its own background, so a slot that defers to the theme is being resolved against a
     * surface it doesn't know about. Both clients had this: on iOS slot 1 mapped to
     * `.systemBackground` and drew white-on-white, then slots 0/14/15 tracked the foreground
     * and broke every `^CFG,BG` pair that touched them — including `^C01,00`, where slot 0 is
     * the *background* and a foreground reference painted the box.
     *
     * The cost is the sender's and is accepted: white is invisible on the light canvas, as
     * black already was on the dark one.
     * `List<String>`, not `List<String?>`. The optional existed only to mark a theme slot, and
     * the type is now the enforcement: there is no way to spell "resolve this one against the
     * theme".
     */
    val mirc: List<String> = listOf(
        "#ffffff", "#000000", "#6799f3", "#a9dc76", "#ff6188", "#ed6c89", "#ab9df2", "#fc9867",
        "#ffd866", "#b3db82", "#78dce8", "#a0f1ff", "#7ba4ff", "#ff7494", "#7f7f7f", "#d2d2d2",
    )

    /**
     * Light-mode variants of `mirc`, same indices. Each chromatic slot is the light variant
     * of the same hex it maps to in `mirc` (all of which are drawn from `nick`), so a color
     * code and a nick that resolve to the same hue stay consistent.
     *
     * The six slots whose dark value is an official Monokai Pro accent take the official Pro
     * Light accent (3, 4, 6, 7, 8, 10); the rest keep the OKLCH derivation described on
     * `nickLight`. The four mono slots are the SAME in both tables — see the ⚠ above; those are
     * the ones a sender pairs with a background, and re-tinting them per scheme is exactly what
     * broke the pairs.
     */
    val mircLight: List<String> = listOf(
        "#ffffff", "#000000", "#3163c0", "#269d69", "#e14775", "#b52d55", "#7058be", "#e16032",
        "#cc7a0a", "#688f2d", "#1c8ca8", "#409ba9", "#4268c5", "#c12d5b", "#7f7f7f", "#d2d2d2",
    )

    /**
     * Per-nick colors (19), indexed by the weechat djb2 hash. All fixed hex. These are the
     * dark-mode variants — the web client's Monokai palette, matched exactly.
     */
    val nick: List<String> = listOf(
        "#ff6188", "#fc9867", "#ffd866", "#a9dc76", "#78dce8", "#ab9df2", "#ed6c89",
        "#d4996e", "#f9d978", "#b3db82", "#91dae6", "#a99dec", "#ff7494", "#ffaf75",
        "#c4e29a", "#a0f1ff", "#b6aaff", "#7ba4ff", "#6799f3",
    )

    /**
     * Light-mode variants of `nick`, same order.
     *
     * The first six entries are the **official Monokai Pro Light** accents — the filter defines
     * a red, orange, yellow, green, cyan and purple, and the dark palette opens with exactly
     * those six hues, so the light theme uses the real thing rather than a derivation of it.
     *
     * Pro Light defines nothing for the thirteen extended hues that follow, so those keep the
     * OKLCH transform of their dark value: hue kept exactly (so a nick's identity is unchanged),
     * lightness compressed toward a legible band (`L → 0.575 + (L−mean)·0.55`) rather than
     * pinned — pinning would collapse the three purples and two blues, which differ mostly in
     * lightness, into near-duplicates. Chroma held.
     *
     * Every entry clears WCAG's 3:1 large-text bar on the light canvas, which is the right bar
     * since nicks always render bold. Yellows unavoidably read as gold: a pure yellow can't be
     * both yellow and dark enough for a light background.
     */
    val nickLight: List<String> = listOf(
        "#e14775", "#e16032", "#cc7a0a", "#269d69", "#1c8ca8", "#7058be", "#b52d55",
        "#9a5f30", "#a68500", "#688f2d", "#3d8f9b", "#7061b1", "#c12d5b", "#b66621",
        "#759247", "#409ba9", "#7767bd", "#4268c5", "#3163c0",
    )
}

/**
 * Deterministic per-nick coloring, reproducing the web client's algorithm so the same
 * nick gets the same color on every client.
 */
object NickColor {

    /**
     * The index into `IRCPalette.nick` for `nick`. Trims trailing stop chars, lowercases,
     * then hashes with weechat's djb2 variant.
     */
    fun index(nick: String, paletteCount: Int = IRCPalette.nick.size): Int {
        val key = lowercasedLikeTheWeb(trimForColor(nick))
        return (djb2(key) % maxOf(paletteCount, 1).toUInt()).toInt()
    }

    /**
     * `lowercased()` plus the one rule it leaves out and JavaScript's `toLowerCase()` applies:
     * a capital sigma that ends a word lowers to final `ς`, not `σ` (Unicode's Final_Sigma).
     * The palette is keyed on the lowered nick, so `ΑΛΕΞΗΣ` hashed to a different colour on iOS
     * than on the web (lurker-ios#199).
     *
     * Port note: written out rather than left to `lowercase()`, which applies a Final_Sigma
     * rule of its own: the JDK's disagreed with node's `toLowerCase()` on 204 of 6,026 random
     * strings of Greek, marks, apostrophes and punctuation (this function and the Swift agreed
     * with node on all but the eight below), and Android's runtime has its own implementation,
     * not measured. Spelled out, it is the Swift's rule on every runtime. The lowercase mapping
     * of each other code point is `lowercase()` of that code point alone, which is its full
     * (unconditional) mapping, as `lowercaseMapping` is.
     */
    internal fun lowercasedLikeTheWeb(string: String): String {
        if (string.indexOf(CAPITAL_SIGMA.toChar()) < 0) return string.lowercase()
        val scalars = string.codePoints().toArray()
        val out = StringBuilder()
        for ((index, scalar) in scalars.withIndex()) {
            if (scalar == CAPITAL_SIGMA && isFinalSigma(index, scalars)) {
                out.append(0x03C2.toChar())
            } else {
                out.append(String(Character.toChars(scalar)).lowercase())
            }
        }
        return out.toString()
    }

    private const val CAPITAL_SIGMA = 0x03A3

    /**
     * Final_Sigma: after a cased letter and not before one, skipping case-ignorables
     * (apostrophes, combining marks) on both sides.
     */
    private fun isFinalSigma(index: Int, scalars: IntArray): Boolean {
        val before = (index - 1 downTo 0).map { scalars[it] }.firstOrNull { !isCaseIgnorable(it) }
        val after = (index + 1 until scalars.size).map { scalars[it] }.firstOrNull { !isCaseIgnorable(it) }
        return before != null && isCased(before) && !(after != null && isCased(after))
    }

    /**
     * Unicode's `Cased`: `Lowercase`, `Uppercase` or a titlecase letter. Port-only — Swift asks
     * `Unicode.Scalar.Properties.isCased`. `Character.isLowerCase`/`isUpperCase` include
     * `Other_Lowercase`/`Other_Uppercase`, as `Lowercase`/`Uppercase` do.
     */
    private fun isCased(scalar: Int): Boolean =
        Character.isLowerCase(scalar) || Character.isUpperCase(scalar) || Character.isTitleCase(scalar)

    /**
     * Unicode's `Case_Ignorable`: a mark (Mn, Me), a format character (Cf), a modifier (Lm,
     * Sk), or a `Word_Break` of `MidLetter`, `MidNumLet` or `Single_Quote`. Port-only — Swift
     * asks `Unicode.Scalar.Properties.isCaseIgnorable`; Java has no such property, so it is
     * built from the general category and the `Word_Break` code points listed in
     * `WordBreakProperty.txt`. Port note: each runtime's general categories follow its own
     * Unicode version, so a code point assigned since can answer differently: JDK 21 has
     * Unicode 15, where U+10D4E (Garay, new in 16) is unassigned and so not case-ignorable,
     * which was the whole of the eight differences above.
     */
    private fun isCaseIgnorable(scalar: Int): Boolean =
        when (Character.getType(scalar).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT,
            Character.MODIFIER_LETTER, Character.MODIFIER_SYMBOL,
            -> true
            else -> scalar in wordBreakCaseIgnorables
        }

    /** `Word_Break` = `MidLetter`, `MidNumLet` or `Single_Quote`. Port-only. */
    private val wordBreakCaseIgnorables: Set<Int> = setOf(
        // MidLetter
        0x003A, 0x00B7, 0x0387, 0x055F, 0x05F4, 0x2027, 0xFE13, 0xFE55, 0xFF1A,
        // MidNumLet
        0x002E, 0x2018, 0x2019, 0x2024, 0xFE52, 0xFF07, 0xFF0E,
        // Single_Quote
        0x0027,
    )

    /**
     * weechat `gui_color_get_custom`: `h = h ^ ((h << 5) + (h >> 2) + cp)` per code point,
     * seeded at 5381, all unsigned-32-bit. NOT classic djb2.
     *
     * Port note: `UInt` is the Swift's `UInt32` — `+` wraps and `shl` discards the high bits,
     * which is what the Swift spells out as `&+` and `&<<`.
     */
    internal fun djb2(string: String): UInt {
        var hash = 5381u
        var i = 0
        while (i < string.length) {
            val scalar = string.codePointAt(i)
            val term = (hash shl 5) + (hash shr 2) + scalar.toUInt()
            hash = hash xor term
            i += Character.charCount(scalar)
        }
        return hash
    }

    /**
     * Trim trailing "away/alt" stop chars: keep leading stop chars, but once a real char
     * has been seen, stop at the next stop char (`amiantos__` / `amiantos|` → `amiantos`).
     * Walks code points, as the web's `for…of` does, not grapheme clusters: `bob_` plus a
     * combining mark is one cluster whose `_` would otherwise never read as a stop.
     */
    internal fun trimForColor(nick: String, stopChars: Set<Int> = setOf('_'.code, '|'.code)): String {
        val result = StringBuilder()
        var seenNonStop = false
        var i = 0
        while (i < nick.length) {
            val scalar = nick.codePointAt(i)
            if (scalar in stopChars) {
                if (seenNonStop) break
            } else {
                seenNonStop = true
            }
            result.appendCodePoint(scalar)
            i += Character.charCount(scalar)
        }
        return result.toString()
    }
}
