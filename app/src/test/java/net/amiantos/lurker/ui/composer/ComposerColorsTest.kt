// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurker.ui.composer.ComposerColors.Companion.NONE
import net.amiantos.lurker.ui.composer.ComposerColors.Companion.pack
import net.amiantos.lurker.ui.composer.ComposerColors.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour beside the composer's text (lurker#1117) — how it follows an edit, and its line. */
class ComposerColorsTest {
    private val red = pack(4, null)
    private val blue = pack(2, null)

    /** [text] with [pair] on units [start] until [end]. */
    private fun colored(text: String, start: Int, end: Int, pair: Int) =
        ComposerColors.plain(text.length).painted(ComposerColors.fg(pair), Layer.Text, start, end)
            .painted(ComposerColors.bg(pair), Layer.Highlight, start, end)

    private fun pairs(colors: ComposerColors) = (0 until colors.length).map(colors::at)

    @Test
    fun typingCarriesTheColourOfTheUnitBefore() {
        val colors = colored("hi", 0, 2, red).followed("hi", "hi there", pen = null)
        assertEquals(List(8) { red }, pairs(colors))
    }

    @Test
    fun aPenColoursWhatItIsTypedInto() {
        val colors = ComposerColors.plain(1).followed("a", "abc", pen = ComposerColors.Pen(blue, at = 1))
        assertEquals(listOf(NONE, blue, blue), pairs(colors))
    }

    /** An edit somewhere else isn't typing where the colour was picked, and doesn't take it. */
    @Test
    fun aPenColoursOnlyAnEditAtItsCaret() {
        val pen = ComposerColors.Pen(blue, at = 2)
        val appended = ComposerColors.plain(4).followed("ab c", "ab c link", pen)
        assertEquals(List(9) { NONE }, pairs(appended))
        val prepended = colored("hello", 0, 5, red).followed("hello", "bob: hello", ComposerColors.Pen(blue, at = 3))
        assertEquals(List(5) { NONE } + List(5) { red }, pairs(prepended))
    }

    @Test
    fun aPenIsSpentByItsEditAndCarriedByOthers() {
        val pen = ComposerColors.Pen(blue, at = 2)
        assertEquals(null, pen.after("ab", "abc"))
        // Three units in front of its caret carry it three along.
        assertEquals(pen.copy(at = 5), pen.after("ab", "xyzab"))
        assertEquals(pen, pen.after("abcd", "abcdlink"))
        assertEquals(null, pen.after("abcd", "ad"))
    }

    /** Autocorrect's replace: the word takes the colour of what it replaces. */
    @Test
    fun aReplacementTakesTheColourOfWhatItReplaces() {
        val colors = colored("teh cat", 0, 3, red).followed("teh cat", "the cat", pen = null)
        assertEquals(List(3) { red } + List(4) { NONE }, pairs(colors))
    }

    /** A Reply's `bob: ` doesn't take the colour of the words it's put in front of. */
    @Test
    fun anInsertAtTheStartIsPlain() {
        val colors = colored("hello", 0, 5, red).followed("hello", "bob: hello", pen = null)
        assertEquals(List(5) { NONE } + List(5) { red }, pairs(colors))
    }

    @Test
    fun aDeletionTakesItsColourWithIt() {
        val colors = colored("abcd", 1, 3, red).followed("abcd", "ad", pen = null)
        assertEquals(listOf(NONE, NONE), pairs(colors))
    }

    @Test
    fun paintingSetsOneLayerAndKeepsTheOther() {
        val colors = colored("abc", 0, 3, pack(4, 1)).painted(9, Layer.Text, 1, 2)
        assertEquals(listOf(pack(4, 1), pack(9, 1), pack(4, 1)), pairs(colors))
        assertEquals(listOf(pack(4, 1), pack(4, null), pack(4, 1)), pairs(colors.painted(9, Layer.Text, 1, 2).painted(null, Layer.Highlight, 1, 2).painted(4, Layer.Text, 1, 2)))
    }

    @Test
    fun theLineIsColorMarkupsEncoding() {
        val colors = colored("red plain", 0, 3, red)
        assertEquals("\u0003" + "04red\u0003 plain", ComposerColors.line("red plain", colors))
        assertEquals("plain", ComposerColors.line("plain", ComposerColors.plain(5)))
    }

    /** The send trims as characters, then writes the line of what's left. */
    @Test
    fun aTrimmedPrefixWritesNoTrailingCode() {
        val colors = colored("hi ", 2, 3, red)
        assertEquals("hi", ComposerColors.line("hi", colors))
    }

    @Test
    fun aLineReadsBackAsItsTextAndColour() {
        val (text, colors) = ComposerColors.read("\u0003" + "04red\u0003 plain")
        assertEquals("red plain", text)
        assertEquals(List(3) { red } + List(6) { NONE }, pairs(colors))
        assertEquals("\u0003" + "04red\u0003 plain", ComposerColors.line(text, colors))
    }

    /** What the field can't hold as colour stays the raw line, codes and all. */
    @Test
    fun aLineItCannotHoldStaysRaw() {
        val (text, colors) = ComposerColors.read("\u0002bold\u0002")
        assertEquals("\u0002bold\u0002", text)
        assertFalse(colors.isColored)
    }

    @Test
    fun runsAreTheColouredStretches() {
        val colors = colored("a red b", 2, 5, red)
        assertEquals(listOf(ComposerColors.Run(2, 5, 4, null)), colors.runs())
        assertTrue(colors.isColored)
    }
}
