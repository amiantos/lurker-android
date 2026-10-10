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
     * Fitted to the user's own edit, from the field's report of exactly what changed ([changes], in
     * order and apart, as Compose's `ChangeList` gives them): each replaced stretch goes, and what
     * replaces it takes [pen] when it's typed at the pen's caret — a colour picked there and not yet
     * typed with — else [inherited].
     *
     * ⚠ From the ranges, never a diff of the two texts: typing `a` in front of `ab` is, to a diff, an
     * `a` inserted AFTER the first one, and the colour would land on the wrong letter.
     */
    fun edited(changes: List<Change>, newLength: Int, pen: Pen?): ComposerColors {
        val next = IntArray(newLength)
        var from = 0
        for (change in changes) {
            val to = change.start - (change.originalStart - from)
            cells.copyInto(next, to, from, change.originalStart)
            val fill = pen?.takeIf { it.at == change.originalStart }?.pair ?: inherited(change.originalStart, change.originalEnd)
            next.fill(fill, change.start, change.end)
            from = change.originalEnd
        }
        val tail = length - from
        cells.copyInto(next, newLength - tail, from, length)
        return ComposerColors(next)
    }

    /**
     * Fitted to an edit the composer made itself — a completion, a Reply's address, an upload's link,
     * a send's clear — which Compose reports to nobody: the smallest edit that explains [old] becoming
     * [new] ([TextEdit]), its text taking [inherited]. No pen: none of these is the user typing where
     * they picked a colour.
     */
    fun followed(old: String, new: String): ComposerColors {
        if (old == new) return this
        val edit = TextEdit.difference(old, new)
        val start = edit.range.start
        val end = edit.range.end
        val next = IntArray(length - (end - start) + edit.replacement.length)
        cells.copyInto(next, 0, 0, start)
        next.fill(inherited(start, end), start, start + edit.replacement.length)
        cells.copyInto(next, start + edit.replacement.length, end, length)
        return ComposerColors(next)
    }

    /**
     * What text put over units [start] until [end] is written in: the colour of what it replaces,
     * else of the unit before it — and plain at the very start, so a Reply's `bob: ` doesn't take the
     * colour of the words it's put in front of.
     */
    private fun inherited(start: Int, end: Int): Int = when {
        end > start -> at(start)
        start > 0 -> at(start - 1)
        else -> NONE
    }

    /** One stretch of a user edit: units [originalStart] until [originalEnd] became [start] until [end]. */
    data class Change(val originalStart: Int, val originalEnd: Int, val start: Int, val end: Int)

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
         * What's left of it after the user's edit [changes]: spent by typing at its caret, carried along
         * by an edit before it, kept by one after it, and gone if one swallowed its caret.
         */
        fun afterTyping(changes: List<Change>): Pen? {
            var shift = 0
            for (change in changes) {
                when {
                    change.originalStart == at -> return null
                    change.originalStart > at -> break
                    change.originalEnd <= at -> shift += (change.end - change.start) - (change.originalEnd - change.originalStart)
                    else -> return null
                }
            }
            return copy(at = at + shift)
        }

        /**
         * What's left of it after the composer's own edit turned [old] into [new]: carried along by an
         * edit before its caret — an insert AT its caret too, which isn't typing (an upload's link, a
         * Reply's address), so the pick waits after it — kept by one after it, gone if one swallowed it.
         */
        fun after(old: String, new: String): Pen? {
            if (old == new) return this
            val edit = TextEdit.difference(old, new)
            val start = edit.range.start
            val end = edit.range.end
            return when {
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
