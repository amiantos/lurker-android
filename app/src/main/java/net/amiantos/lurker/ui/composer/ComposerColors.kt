// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.commands.ColorMarkup
import net.amiantos.lurkerkit.commands.ColorSpan
import net.amiantos.lurkerkit.commands.TextEdit

/**
 * The colour on the composer's text (lurker#1117): one mIRC pair per UTF-16 unit of the field.
 *
 * iOS keeps colour as attributes on its text view's own string. A Compose `TextFieldState` holds
 * plain text only, so here the colour lives beside it and is fitted to every edit ([followed]), then
 * painted by the field's `OutputTransformation`. The composer never shows a control code either way:
 * colour becomes `\x03` codes only in [line], which is what the draft holds and the send sends.
 *
 * A pair is packed into an `Int` — the foreground slot + 1 in the low byte, the background slot + 1 in
 * the next, 0 for "none" — so a cell is one array slot and [NONE] is plain text.
 */
internal class ComposerColors private constructor(private val cells: IntArray) {

    val length: Int get() = cells.size

    /** The pair on unit [index], [NONE] past either end. */
    fun at(index: Int): Int = cells.getOrElse(index) { NONE }

    val isColored: Boolean get() = cells.any { it != NONE }

    /**
     * Fitted to an edit that turned [old] into [new]: the replaced stretch goes, and what replaces it
     * takes [pen] when the edit is at the pen's caret — a colour picked there and not yet typed with —
     * else the colour of what it replaces, else of the unit before it. At the very start it's plain, so
     * a Reply's `bob: ` doesn't take the colour of the words it's put in front of.
     *
     * ⚠ Only at the pen's caret. An upload's link appended at the end, a Reply's address, an
     * autocorrect of the word before — none is the user typing where they picked, and none takes it.
     *
     * The edit is the smallest one that explains the two texts ([TextEdit]), which is exactly the
     * keystroke, the completion or the link when one thing changed — and, when the field's observer
     * folds two edits into one, the same span the two together made.
     */
    fun followed(old: String, new: String, pen: Pen?): ComposerColors {
        if (old == new) return this
        val edit = TextEdit.difference(old, new)
        val start = edit.range.start
        val end = edit.range.end
        val fill = pen?.takeIf { it.at == start }?.pair ?: when {
            end > start -> at(start)
            start > 0 -> at(start - 1)
            else -> NONE
        }
        val next = IntArray(length - (end - start) + edit.replacement.length)
        cells.copyInto(next, 0, 0, start)
        next.fill(fill, start, start + edit.replacement.length)
        cells.copyInto(next, start + edit.replacement.length, end, length)
        return ComposerColors(next)
    }

    /** [layer] of units [start] until [end] set to [slot] — null takes it off. */
    fun painted(slot: Int?, layer: Layer, start: Int, end: Int): ComposerColors {
        val next = cells.copyOf()
        for (index in start until minOf(end, length)) next[index] = with(next[index], slot, layer)
        return ComposerColors(next)
    }

    /** The spans `ColorMarkup` writes from, over [text] — which these colours were fitted to. */
    fun spans(text: String): List<ColorSpan> {
        val spans = mutableListOf<ColorSpan>()
        var start = 0
        while (start < text.length) {
            val pair = at(start)
            var end = start + 1
            while (end < text.length && at(end) == pair) end++
            spans += ColorSpan(text.substring(start, end), fg = fg(pair), bg = bg(pair))
            start = end
        }
        return spans
    }

    /** Each coloured stretch, for painting: start, end, and its pair. */
    fun runs(): List<Run> {
        val runs = mutableListOf<Run>()
        var start = 0
        while (start < length) {
            val pair = cells[start]
            var end = start + 1
            while (end < length && cells[end] == pair) end++
            if (pair != NONE) runs += Run(start, end, fg(pair), bg(pair))
            start = end
        }
        return runs
    }

    data class Run(val start: Int, val end: Int, val fg: Int?, val bg: Int?)

    /**
     * A colour picked at a bare caret and not yet typed with — what typing at [at] writes in, as UIKit's
     * typing attributes are on iOS.
     */
    data class Pen(val pair: Int, val at: Int) {
        /**
         * What's left of it after an edit turned [old] into [new]: spent by an edit at its caret, carried
         * along by one before it, kept by one after it, and gone if one swallowed its caret.
         */
        fun after(old: String, new: String): Pen? {
            if (old == new) return this
            val edit = TextEdit.difference(old, new)
            val start = edit.range.start
            val end = edit.range.end
            return when {
                start == at -> null
                start > at -> this
                end <= at -> copy(at = at + edit.replacement.length - (end - start))
                else -> null
            }
        }
    }

    /** Which half of a pair a pick sets. */
    enum class Layer { Text, Highlight }

    override fun equals(other: Any?): Boolean = other is ComposerColors && cells.contentEquals(other.cells)

    override fun hashCode(): Int = cells.contentHashCode()

    companion object {
        const val NONE = 0

        /** [length] units of plain text. */
        fun plain(length: Int) = ComposerColors(IntArray(length))

        fun pack(fg: Int?, bg: Int?): Int = (fg?.plus(1) ?: 0) or ((bg?.plus(1) ?: 0) shl 8)

        fun fg(pair: Int): Int? = (pair and 0xFF).takeIf { it != 0 }?.minus(1)

        fun bg(pair: Int): Int? = ((pair shr 8) and 0xFF).takeIf { it != 0 }?.minus(1)

        /** The slot [pair] gives [layer], null for none. */
        fun slot(pair: Int, layer: Layer): Int? = if (layer == Layer.Text) fg(pair) else bg(pair)

        /** [pair] with [layer] set to [slot]. */
        fun with(pair: Int, slot: Int?, layer: Layer): Int =
            if (layer == Layer.Text) pack(slot, bg(pair)) else pack(fg(pair), slot)

        /**
         * The text and colours for a line that came from outside the field — a draft, a refused send.
         * What `ColorMarkup` can hold becomes colour (a lone reset reads as the plain text it shows);
         * anything else stays the raw line it always was, codes and all, so nothing written elsewhere
         * is lost or changed on the way through.
         */
        fun read(line: String): Pair<String, ComposerColors> {
            val spans = ColorMarkup.decode(line) ?: return line to plain(line.length)
            val text = StringBuilder()
            val cells = IntArray(spans.sumOf { it.text.length })
            for (span in spans) {
                cells.fill(pack(span.fg, span.bg), text.length, text.length + span.text.length)
                text.append(span.text)
            }
            return text.toString() to ComposerColors(cells)
        }

        /** The line [text] in [colors] holds, syncs and sends — one format for all three (`ColorMarkup.encode`). */
        fun line(text: String, colors: ComposerColors): String =
            if (colors.isColored) ColorMarkup.encode(colors.spans(text)) else text
    }
}
