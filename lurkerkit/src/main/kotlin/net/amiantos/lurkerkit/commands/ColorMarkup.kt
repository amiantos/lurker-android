// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.rendering.IRCColor
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.support.graphemeBoundaries
import net.amiantos.lurkerkit.support.isSwiftWhitespace

/**
 * One stretch of composer text in one colour pair — what the colour editor works in. `fg` and
 * `bg` are mIRC slots 0–15, null for "no colour" (the theme's text, no fill).
 */
data class ColorSpan(
    val text: String,
    val fg: Int? = null,
    val bg: Int? = null,
)

/**
 * The composer's coloured text ↔ the `\x03` line it holds, syncs and sends.
 *
 * The composer never shows a control code: colour lives on the text as an attribute and is
 * written out only here. That keeps the codes out of reach of the caret — a backspace can't eat
 * one digit of `\x0304` and silently turn red into white.
 *
 * **One line format, for the draft and the send alike.** It's what was typed — `||` stay `||` —
 * with the colour written in. Spoilers are made later, on the chat body, by `chatBody`, as they
 * always were. So a refused send, a synced draft and a restored draft are all the same string,
 * and `decode` gives back exactly what `encode` was handed.
 *
 * Only the sixteen palette slots, and only colour. A line holding anything else (bold, `\x04`
 * truecolour, slot 42) is not something this model can represent, so `decode` declines it and
 * the composer keeps it as raw text, exactly as it did before colours existed — a draft written
 * on the web loses nothing by passing through iOS.
 *
 * Port note: `encode` and `decode` work in grapheme clusters, as LurkerKit works in `Character`s
 * — a cell is one cluster (`support.graphemeBoundaries`), and the bare head is counted in them,
 * so a code is never written inside a cluster. That is a different unit from `CommandParser`,
 * which cuts by UTF-16 unit (its Port note): the two disagree only where a combining mark, a
 * joiner or a variation selector fuses onto the `/` or the whitespace the head is read from —
 * a `/` wearing a mark is a command to the parser here and plain text to `bareHead`, which
 * colours the whole line (as LurkerKit does) and so sends it as a message. `chatBody` works in
 * UTF-16 units instead; its note says why.
 */
object ColorMarkup {

    /**
     * The line for `spans`.
     *
     * ⚠⚠ Every slot is written as TWO digits, and a foreground-only code grows a `,99` when the
     * text after it opens with a comma and a digit. Both are the digit trap `SpoilerMarkup`
     * documents: `\x03` `4` then `2 cats` is colour 42, and `\x0304` then `,5 cats` is red on
     * blue — the text's own characters read as the code, and deleted from the line.
     *
     * ⚠⚠ Colour goes on a CHAT BODY only — the line, a `//` line past its escape, and the text of
     * `/me`, `/shrug`, `/msg`, `/notice`, `/topic`, `/part`, `/kick` and `/away` (`bareHead`).
     * A verb, a target, a channel, a flag, a password to `/ns`: a code in any of those is a
     * different word, so the head stays bare and every other command goes out with no colour.
     *
     * ⚠ A coloured line ends in a reset before each line break, and its colour is written again
     * after it. A server without multiline sends each line as its own message, which a code
     * doesn't carry into; and a reader of the joined text (`decode`, the list) carries the state
     * across, so a plain line after a red one has to say so.
     */
    fun encode(spans: List<ColorSpan>): String {
        val text = spans.joinToString("") { it.text }
        val bare = bareHead(text) ?: return text
        val cells = cells(spans).toMutableList()
        for (index in 0 until minOf(bare, cells.size)) {
            cells[index] = cells[index].copy(fg = null, bg = null)
        }
        return write(cells)
    }

    /**
     * The spans `line` reads as, or null when the composer can't hold it as colour — the caller
     * keeps such a line as raw text rather than drop or change what it can't show.
     *
     * Declined, so that `encode` always writes back what was decoded:
     * - any formatting but palette colour, including a slot outside 0–15 that no text follows;
     * - a code inside the bare head (`encode`): `\x0304/me waves` is red TEXT, and `/j\x0304oin`
     *   is an unknown command — shown without its code, either would send as a different line;
     * - any code at all on a command that has no chat body: `encode` would drop its colour, and
     *   a reset in front of the slash (`\x03/quit bye`) is what makes that line text.
     *
     * `\x0F` is accepted: with nothing but colour in play it is a colour reset. Slot 99 (IRC's
     * "default") reads as no colour.
     */
    fun decode(line: String): List<ColorSpan>? {
        val spans = colorSpans(line) ?: return null
        val text = spans.joinToString("") { it.text }
        if (text == line) return spans
        val bare = bareHead(text) ?: return null
        // The line has to open with the head exactly — no code before it or inside it.
        if (!line.startsWith(prefix(text, bare))) return null
        return spans
    }

    /** Whether `spans` carry any colour at all. */
    fun isColored(spans: List<ColorSpan>): Boolean =
        spans.any { it.text.isNotEmpty() && (it.fg != null || it.bg != null) }

    /**
     * A chat body with its `||spoilers||` made — `CommandParser.chatBody`.
     *
     * Plain text goes to `SpoilerMarkup.apply`, byte for byte as before. A coloured body is
     * rebuilt span by span instead: that rewrite closes a spoiler with a bare reset, which would
     * drop the colour after the box, and a colour inside the box would show the hidden text.
     * Here the box is grey on grey whatever it was, and the colour around it resumes after it.
     * Where the pairs are is `SpoilerMarkup.layout`'s answer, so both agree on every line — and
     * it's read from the characters alone, so a colour change between two `|` can't hide a pair.
     *
     * Port note: the cells here are UTF-16 units (`unitCells`), not grapheme clusters, because
     * `SpoilerMarkup.apply` walks units here and `layout` has to be read in the same units by
     * both of its users. So this agrees with `apply` — and with the web — where LurkerKit
     * doesn't: a combining mark straight after a `|` or a `\`. No cut can split a surrogate
     * pair: the cells change colour only at a span edge, which `parse` makes between scalars,
     * and beside a `|` or a `\`.
     */
    internal fun chatBody(text: String): String {
        if (!text.contains("|") || !text.contains('\u0003')) return SpoilerMarkup.apply(text)
        val spans = colorSpans(text)
        if (spans == null || !isColored(spans)) return SpoilerMarkup.apply(text)
        val body = unitCells(spans)
        val layout = SpoilerMarkup.layout(body.joinToString("") { it.char })
        if (layout.spoilers.isEmpty() && layout.escapes.isEmpty()) return text
        val dropped = HashSet<Int>()
        val hidden = HashSet<Int>()
        for (pair in layout.spoilers) {
            dropped.addAll(listOf(pair.open, pair.open + 1, pair.close, pair.close + 1))
            hidden.addAll((pair.open + 2) until pair.close)
        }
        // `\||` reads as a literal `||`: the backslash goes.
        dropped.addAll(layout.escapes)
        // Each box opens on its own, so `||a||||b||` stays two boxes as `apply` makes it — two
        // touching runs in one colour would otherwise be written as one.
        val opens = layout.spoilers.map { it.open + 2 }.toSet()
        val out = mutableListOf<Cell>()
        for ((index, cell) in body.withIndex()) {
            if (index in dropped) continue
            out.add(
                if (index in hidden) {
                    Cell(cell.char, fg = SpoilerMarkup.slot, bg = SpoilerMarkup.slot, opensBox = index in opens)
                } else {
                    cell
                }
            )
        }
        return write(out)
    }

    // MARK: - Private

    /**
     * One character and its colour — the working form, so a head or a spoiler can be cut at
     * any character rather than only at span edges.
     *
     * Port note: `char` is a `String` — one grapheme cluster from `cells`, one UTF-16 unit from
     * `unitCells` — so `write` serves both.
     */
    private data class Cell(
        val char: String,
        val fg: Int?,
        val bg: Int?,
        /**
         * The first character of a spoiler box, which is closed and reopened even when the box
         * before it touches it.
         */
        val opensBox: Boolean = false,
    )

    /** A colour pair. Port note: the Swift's named tuple `(fg: Int?, bg: Int?)`. */
    private data class Colors(val fg: Int?, val bg: Int?)

    private val noColor = Colors(null, null)

    private fun cells(spans: List<ColorSpan>): List<Cell> =
        spans.flatMap { span -> characters(span.text).map { Cell(it, span.fg, span.bg) } }

    /** [cells] by UTF-16 unit, for `chatBody`. Port-only; see the note there. */
    private fun unitCells(spans: List<ColorSpan>): List<Cell> =
        spans.flatMap { span -> span.text.map { Cell(it.toString(), span.fg, span.bg) } }

    /** `text`'s grapheme clusters — what LurkerKit iterates as `Character`s. Port-only. */
    private fun characters(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (end in graphemeBoundaries(text)) {
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    /** `text.prefix(count)`: its first `count` grapheme clusters. Port-only. */
    private fun prefix(text: String, count: Int): String {
        if (count <= 0) return ""
        val boundaries = graphemeBoundaries(text)
        if (count > boundaries.size) return text
        return text.substring(0, boundaries[count - 1])
    }

    /**
     * Swift's `Character.isWhitespace`, which asks the cluster's first scalar. Every whitespace
     * scalar is a single BMP unit, so the first unit answers for it. Port-only.
     */
    private fun isWhitespace(char: String): Boolean = char.firstOrNull()?.isSwiftWhitespace() ?: false

    /**
     * `line` as palette-colour spans, or null when it holds anything else. No command rules.
     *
     * Every `\x03` code is checked where it stands, not through the runs it produces: a code no
     * text follows (`…\x0342`) makes no run, and checking runs alone would accept the line and
     * silently drop the colour it leaves in effect.
     */
    private fun colorSpans(line: String): List<ColorSpan>? {
        for (scalar in line.codePoints()) {
            when (scalar) {
                0x02, 0x04, 0x11, 0x16, 0x1D, 0x1E, 0x1F -> return null
                else -> continue
            }
        }
        if (!IRCFormatting.colorSlots(line).all { it in 0..15 || it == 99 }) return null
        val spans = mutableListOf<ColorSpan>()
        for (run in IRCFormatting.parse(line)) {
            val fg = slot(run.fg)
            val bg = slot(run.bg)
            val last = spans.lastOrNull()
            if (last != null && last.fg == fg && last.bg == bg) {
                spans[spans.size - 1] = last.copy(text = last.text + run.text)
            } else {
                spans.add(ColorSpan(run.text, fg = fg, bg = bg))
            }
        }
        return spans
    }

    /** A slot `colorSpans` already vetted: 0–15 as itself, 99 as no colour. */
    private fun slot(color: IRCColor?): Int? {
        if (color is IRCColor.Slot && color.index in 0..15) return color.index
        return null
    }

    /**
     * Port note: [text] reaches `SpoilerMarkup.close` as its first UTF-16 unit, where LurkerKit
     * hands over its first `Character`; `close` asks only whether it opens with an ASCII digit,
     * which is one unit, so the answers are the same.
     */
    private fun code(current: Colors, next: Colors, text: String): String {
        val fg = next.fg
        val bg = next.bg
        return when {
            fg == null && bg == null ->
                // A bare `\x03` resets both — unless a digit follows, which it would swallow.
                SpoilerMarkup.close(text.firstOrNull())
            fg != null && bg == null -> {
                // A bare foreground keeps whatever background is in effect, so dropping one has to
                // say so; and a leading `,digit` in the text would otherwise be read as one.
                val clearsBackground = current.bg != null || opensWithCommaDigit(text)
                "\u0003" + two(fg) + (if (clearsBackground) ",99" else "")
            }
            else -> "\u0003" + two(fg ?: 99) + "," + two(bg!!)
        }
    }

    private fun two(slot: Int): String = if (slot < 10) "0$slot" else "$slot"

    private fun opensWithCommaDigit(text: String): Boolean {
        val scalars = text.codePoints().limit(2).toArray()
        return scalars.size == 2 && scalars[0] == ','.code && scalars[1] in 0x30..0x39
    }

    /**
     * How many leading characters stay bare, or null when the line is a command with no chat
     * body, which takes no colour at all. See `encode`.
     *
     * Each command names how many of its leading words are not its text. Channel-shaped words
     * count as bare wherever the command takes an optional channel — the parser asks open
     * buffers to decide (`leadsWithChannel`), and a reason's first word left uncoloured costs
     * nothing, where a coloured channel name is a different channel.
     *
     * Port note: characters are grapheme clusters, as in LurkerKit; see the note on `ColorMarkup`.
     */
    private fun bareHead(text: String): Int? {
        val chars = characters(text)
        if (chars.firstOrNull() != "/") return 0
        // `//` is the escape: a message with one slash taken off. Both stay bare — a code between
        // them would make the line a command named after the code.
        if (chars.size >= 2 && chars[1] == "/") return 2
        val verb = chars.drop(1).takeWhile { !isWhitespace(it) }
        val words = splitOnWhitespace(chars.drop(1 + verb.size))
        val channelFirst = words.firstOrNull()?.let { ChannelName.isChannelTarget(it) } ?: false
        val bare = when (verb.joinToString("").lowercase()) {
            "me", "shrug" -> 0
            "msg", "query", "notice" -> 1
            "topic", "part", "leave", "p" -> if (channelFirst) 1 else 0
            "kick" -> if (channelFirst) 2 else 1
            // `CommandParser.awayFlag`: one leading `-all` or `-one`, and nothing else is a flag.
            "away" -> if (words.firstOrNull()?.let { it.lowercase() in listOf("-all", "-one") } == true) 1 else 0
            else -> return null
        }
        // The verb, then each bare word with the whitespace after it: a code welded onto the end
        // of a word is part of the word (`/msg bob\x0304 hi` messages "bob\x0304").
        var rest = chars.drop(1 + verb.size)
        var length = 1 + verb.size
        for (word in 0..bare) {
            if (word > 0) {
                val bareWord = rest.takeWhile { !isWhitespace(it) }
                length += bareWord.size
                rest = rest.drop(bareWord.size)
            }
            val gap = rest.takeWhile { isWhitespace(it) }
            length += gap.size
            rest = rest.drop(gap.size)
        }
        return length
    }

    /** `split(whereSeparator: \.isWhitespace)` over clusters: the runs between, none empty. */
    private fun splitOnWhitespace(chars: List<String>): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        for (char in chars) {
            if (isWhitespace(char)) {
                if (word.isNotEmpty()) words.add(word.toString())
                word.setLength(0)
            } else {
                word.append(char)
            }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words
    }

    /**
     * Line breaks as the server splits on them (`splitSay`: `\r\n`, `\r`, `\n`). Swift reads
     * `\r\n` as ONE character, which a test for `"\n"` alone would miss — and so does
     * `graphemeBoundaries`, while `unitCells` hands over the two halves one at a time.
     */
    private fun isLineBreak(char: String): Boolean =
        char == "\n" || char == "\r" || char == "\r\n"

    private fun write(cells: List<Cell>): String {
        val out = StringBuilder()
        var current = noColor
        var index = 0
        while (index < cells.size) {
            val cell = cells[index]
            if (isLineBreak(cell.char)) {
                // A reset the joined text can read, then plain until the next line colours itself.
                if (current != noColor) out.append(SpoilerMarkup.close)
                out.append(cell.char)
                current = noColor
                index += 1
                continue
            }
            val state = Colors(cell.fg, cell.bg)
            if (cell.opensBox && state == current) {
                out.append(SpoilerMarkup.close)
                current = noColor
            }
            if (state != current) {
                // What follows the code — what the digit and comma traps look at.
                // Port note: two cells, where LurkerKit takes two `Character`s; see `code`.
                val run = StringBuilder()
                var taken = 0
                var end = index
                while (end < cells.size && taken < 2 &&
                    cells[end].fg == cell.fg && cells[end].bg == cell.bg && !isLineBreak(cells[end].char)
                ) {
                    run.append(cells[end].char)
                    taken += 1
                    end += 1
                }
                out.append(code(current, state, run.toString()))
                current = state
            }
            out.append(cell.char)
            index += 1
        }
        return out.toString()
    }
}
