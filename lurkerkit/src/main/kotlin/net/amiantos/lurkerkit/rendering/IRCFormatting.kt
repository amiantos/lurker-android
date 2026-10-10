// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.rendering

/**
 * A colour a formatting code named: a mIRC palette slot from `\x03`, or a 24-bit value from
 * `\x04` truecolour. Both are literal — neither is mapped through the theme.
 */
sealed interface IRCColor {
    /** A raw mIRC index (0–99). The UI paints 0–15 and leaves the rest uncoloured. */
    data class Slot(val index: Int) : IRCColor

    /** `0xRRGGBB`. */
    data class Rgb(val value: UInt) : IRCColor
}

/** One run of message text sharing the same mIRC formatting. */
data class FormattingRun(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    /**
     * `\x16`. Kept as a flag rather than applied here, because the renderer swaps `fg` and `bg`
     * and a side the run leaves unset swaps with the theme's own colour, which only it knows.
     */
    val reverse: Boolean,
    val fg: IRCColor?,
    val bg: IRCColor?,
) {
    /**
     * Whether the run's text is invisible: foreground and background the same colour, which is
     * the IRC spoiler convention (and how ASCII art fills a block). The renderer draws it as a
     * tap-to-reveal box and link previews skip its URLs, so both ask here — the web's
     * `hidesText`. Reverse changes nothing about an equal pair.
     *
     * ⚠ The colour has to be one the renderer can paint. Slots above 15 draw nothing, so such a
     * run is not hidden and its links are ordinary links — and since `SpoilerMarkup` closes a
     * spoiler with `\u000399,99` when a digit follows, the tail of those messages IS a 99,99 run.
     * Without the bound, the rest of the line after a spoiler would become one: a box that
     * reveals nothing, and a URL anywhere in it silently losing its preview.
     */
    val hidesText: Boolean
        get() {
            val fg = fg
            if (fg == null || fg != bg) return false
            if (fg is IRCColor.Slot) return fg.index in 0..15
            return true
        }

    /**
     * What [paint] answers: the run's text colour and its fill.
     *
     * Port note: the Swift returns a named tuple, `(ink: Color, fill: Color?)`.
     */
    data class Paint<Color>(val ink: Color, val fill: Color?)

    /**
     * What the run is drawn in: its text colour and its fill, given `fg`/`bg` already resolved
     * by the caller (null for a slot it can't paint) and the colours plain text gets — generic
     * so the rule is decided, and tested, here rather than in the renderer.
     *
     * Reverse (`\x16`) swaps the pair. A side the run leaves unset, or names with a slot that
     * can't be painted, is the theme's own — so reversed plain text reads as the theme inverted
     * rather than as nothing. `text` is whatever the run would otherwise be drawn in, which is
     * how a reversed `/me` comes out as a block of the nick's colour. An equal pair is a spoiler,
     * and reverse changes nothing about it.
     */
    fun <Color> paint(fg: Color?, bg: Color?, text: Color, canvas: Color): Paint<Color> {
        if (!reverse || hidesText) return Paint(fg ?: text, bg)
        return Paint(bg ?: canvas, fg ?: text)
    }
}

/**
 * Byte-level mIRC control-code parser, mirroring the web client's `parseIrcFormatting`.
 * The server stores raw IRC text with the control bytes intact; this turns it into runs.
 */
object IRCFormatting {

    /**
     * `text` with its mIRC control codes removed — what the line *reads* as, rather than what
     * came over the wire.
     *
     * For showing a message somewhere that can't render its formatting: `\u000304ALERT\u0003` is
     * red "ALERT" in the list, but pasted into a plain label it reads `04ALERT`, because the 0x03
     * is invisible and the color digits are not.
     */
    fun strip(text: String): String = parse(text).joinToString("") { it.text }

    fun parse(text: String): List<FormattingRun> {
        val runs = mutableListOf<FormattingRun>()
        val current = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var reverse = false
        var fg: IRCColor? = null
        var bg: IRCColor? = null

        fun flush() {
            if (current.isEmpty()) return
            runs.add(
                FormattingRun(
                    text = current.toString(), bold = bold, italic = italic, underline = underline,
                    strike = strike, reverse = reverse, fg = fg, bg = bg,
                )
            )
            current.setLength(0)
        }

        val scalars = text.codePoints().toArray()
        var i = 0
        while (i < scalars.size) {
            val value = scalars[i]
            when (value) {
                0x02 -> { flush(); bold = !bold; i += 1 }
                0x1D -> { flush(); italic = !italic; i += 1 }
                0x1F -> { flush(); underline = !underline; i += 1 }
                0x1E -> { flush(); strike = !strike; i += 1 }
                0x16 -> { flush(); reverse = !reverse; i += 1 }
                0x11 -> { flush(); i += 1 } // monospace: consumed, the list is already monospaced
                0x0F -> { // reset
                    flush()
                    bold = false; italic = false; underline = false; strike = false; reverse = false
                    fg = null; bg = null
                    i += 1
                }
                0x03 -> { // color: \x03[FG[,BG]]
                    flush()
                    val code = readColorCode(scalars, i)
                    i = code.end
                    val foreground = code.fg
                    if (foreground != null) {
                        fg = IRCColor.Slot(foreground)
                        // A bare FG (no ,BG) leaves the existing bg untouched.
                        val background = code.bg
                        if (background != null) bg = IRCColor.Slot(background)
                        continue
                    } else {
                        // Bare \x03 resets both foreground and background.
                        fg = null
                        bg = null
                    }
                }
                0x04 -> { // truecolour: \x04[RRGGBB[,RRGGBB]]
                    flush()
                    i += 1
                    // Exactly six hex for each colour, as the web and the server's `FORMAT_RE` take
                    // it. Anything shorter is no colour at all: a bare \x04 that resets both, like
                    // a bare \x03, with whatever followed it left as text.
                    val foreground = readHex(scalars, i)
                    if (foreground != null) {
                        fg = IRCColor.Rgb(foreground)
                        i += 6
                        // A foreground alone keeps the background in effect, as with \x03 — and a
                        // comma without a full colour after it is text.
                        if (i < scalars.size && scalars[i] == 0x2C) {
                            val background = readHex(scalars, i + 1)
                            if (background != null) {
                                bg = IRCColor.Rgb(background)
                                i += 7
                            }
                        }
                    } else {
                        fg = null
                        bg = null
                    }
                }
                else -> {
                    current.appendCodePoint(scalars[i])
                    i += 1
                }
            }
        }
        flush()
        return runs
    }

    /**
     * Where the `visibleOffset`-th *visible* character of `text` begins inside `text` itself —
     * the inverse of `strip`, for when you matched against the stripped form and now have to
     * slice the original.
     *
     * The web's `rawIndexForVisibleOffset` (`shared/textMatch.ts`), ported for its one caller:
     * relay re-attribution (lurker#277) matches a bot's envelope against stripped text and
     * then has to hand back the relayed message with its OWN colours and bold intact.
     *
     * Offsets in and out are UTF-16 units — `TextRange`'s currency, and JavaScript's, so a
     * capture range from a regex match can be handed straight in and the answer handed straight
     * to `String.substring`. An offset past the end answers the end.
     *
     * ⚠ This is a SECOND scanner over the control codes `parse` consumes. Should the two ever
     * disagree about what counts as formatting, a slice lands mid-code and the recovered text
     * opens with stray colour digits. `RelayEnvelopeTests.testRawIndexAgreesWithStrip` pins them
     * to each other over a corpus rather than leaving it to inspection.
     */
    fun rawIndex(text: String, visibleOffset: Int): Int {
        if (visibleOffset <= 0) return 0
        val units = text.toCharArray()
        var visible = 0
        var i = 0
        while (i < units.size) {
            if (visible >= visibleOffset) return i
            val length = controlLength(units, i)
            if (length != null) {
                i += length
            } else {
                visible += 1
                i += 1
            }
        }
        return units.size
    }

    /**
     * The length, in UTF-16 units, of the mIRC control sequence starting at `i` — or null when
     * `units[i]` doesn't start one.
     *
     * Every code is ASCII, so walking UTF-16 units lands on exactly the same positions `parse`'s
     * scalar walk does; only the *content* between codes is counted differently, and that's the
     * caller's business rather than this function's.
     */
    private fun controlLength(units: CharArray, i: Int): Int? {
        when (units[i].code) {
            // The toggles, the reset, and monospace, which `parse` consumes without rendering.
            0x02, 0x0F, 0x11, 0x16, 0x1D, 0x1E, 0x1F -> return 1
            0x03 -> {
                // \x03[FG[,BG]]. A bare \x03 is a reset and consumes nothing more; the background half
                // needs a digit after the comma, or the comma is text (`\x0304,not-a-bg`).
                var j = skip(units, i + 1, limit = 2, member = ::isDigit)
                if (j <= i + 1) return 1
                if (j + 1 < units.size && units[j].code == 0x2C && isDigit(units[j + 1])) {
                    j = skip(units, j + 1, limit = 2, member = ::isDigit)
                }
                return j - i
            }
            0x04 -> {
                // \x04[RRGGBB[,RRGGBB]] — exactly six hex per colour, or the code is a bare \x04 and
                // consumes nothing more. Likewise the comma is text unless a full colour follows it.
                if (!isHex6(units, i + 1)) return 1
                var j = i + 7
                if (j < units.size && units[j].code == 0x2C && isHex6(units, j + 1)) j += 7
                return j - i
            }
            else -> return null
        }
    }

    /** Advance past up to `limit` units satisfying `member`, returning the index just past them. */
    private fun skip(units: CharArray, start: Int, limit: Int, member: (Char) -> Boolean): Int {
        var i = start
        var count = 0
        while (i < units.size && count < limit && member(units[i])) {
            i += 1
            count += 1
        }
        return i
    }

    /**
     * Every slot a `\x03` code in `text` names, in order — including codes no text follows,
     * which `parse` makes no run for. Read by the same scanner `parse` uses, so the two agree on
     * what a code is (`ColorMarkup.decode` vets slots with this).
     */
    internal fun colorSlots(text: String): List<Int> {
        val scalars = text.codePoints().toArray()
        val slots = mutableListOf<Int>()
        var i = 0
        while (i < scalars.size) {
            if (scalars[i] != 0x03) { i += 1; continue }
            val code = readColorCode(scalars, i)
            slots += listOfNotNull(code.fg, code.bg)
            i = code.end
        }
        return slots
    }

    /**
     * What [readColorCode] reads.
     *
     * Port note: the Swift returns a named tuple, `(fg: Int?, bg: Int?, end: Int)`.
     */
    private data class ColorCode(val fg: Int?, val bg: Int?, val end: Int)

    /**
     * The `\x03[FG[,BG]]` at `start` (which is the `\x03`): its slots, null for a part it
     * doesn't have, and where the text after it begins. A comma is part of the code only with a
     * digit after it.
     */
    private fun readColorCode(scalars: IntArray, start: Int): ColorCode {
        val (foreground, afterFg) = readDigits(scalars, start + 1)
        if (foreground == null) return ColorCode(null, null, start + 1)
        if (!(afterFg + 1 < scalars.size && scalars[afterFg] == 0x2C && isDigit(scalars[afterFg + 1]))) {
            return ColorCode(foreground, null, afterFg)
        }
        val (background, afterBg) = readDigits(scalars, afterFg + 1)
        return ColorCode(foreground, background, afterBg)
    }

    /**
     * Read up to two ASCII digits from `start`; returns the value (null if none) and the
     * index just past them.
     */
    private fun readDigits(scalars: IntArray, start: Int): Pair<Int?, Int> {
        val digits = StringBuilder()
        var i = start
        while (i < scalars.size && digits.length < 2 && isDigit(scalars[i])) {
            digits.appendCodePoint(scalars[i])
            i += 1
        }
        return Pair(if (digits.isEmpty()) null else digits.toString().toInt(), i)
    }

    /**
     * The `0xRRGGBB` spelled by exactly six ASCII hex digits at `start`, or null when fewer are
     * there.
     */
    private fun readHex(scalars: IntArray, start: Int): UInt? {
        if (start + 6 > scalars.size) return null
        var value = 0u
        for (index in start until start + 6) {
            val scalar = scalars[index]
            if (!isHex(scalar)) return null
            val digit = Character.digit(scalar, 16)
            if (digit < 0) return null
            value = (value shl 4) or digit.toUInt()
        }
        return value
    }

    private fun isDigit(s: Int): Boolean = s >= 0x30 && s <= 0x39

    private fun isHex(s: Int): Boolean =
        isDigit(s) || (s >= 0x41 && s <= 0x46) || (s >= 0x61 && s <= 0x66)

    // The UTF-16 halves of the same two tests, for `controlLength`'s walk.
    private fun isDigit(u: Char): Boolean = u.code >= 0x30 && u.code <= 0x39

    private fun isHex(u: Char): Boolean =
        isDigit(u) || (u.code >= 0x41 && u.code <= 0x46) || (u.code >= 0x61 && u.code <= 0x66)

    private fun isHex6(units: CharArray, start: Int): Boolean =
        skip(units, start, limit = 6, member = ::isHex) == start + 6
}
