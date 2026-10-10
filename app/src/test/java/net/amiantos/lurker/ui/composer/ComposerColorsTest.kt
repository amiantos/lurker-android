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

    /** One insertion of [length] units at [at], as Compose reports it. */
    private fun typedAt(at: Int, length: Int) = listOf(ComposerColors.Change(at, at, at, at + length))

    @Test
    fun typingCarriesTheColourOfTheUnitBefore() {
        val colors = colored("hi", 0, 2, red).edited(typedAt(2, 6), 8, pen = null)
        assertEquals(List(8) { red }, pairs(colors))
    }

    @Test
    fun aPenColoursWhatItIsTypedInto() {
        val colors = ComposerColors.plain(1).edited(typedAt(1, 2), 3, pen = ComposerColors.Pen(blue, at = 1))
        assertEquals(listOf(NONE, blue, blue), pairs(colors))
    }

    /**
     * `a` typed in front of `ab`: a diff of the texts would say the second `a` is the new one, and put
     * the pen's colour on the wrong letter. The field's own report says where it went.
     */
    @Test
    fun typingIsPlacedWhereTheFieldSaysNotWhereADiffGuesses() {
        val colors = colored("ab", 0, 1, red).edited(typedAt(0, 1), 3, pen = ComposerColors.Pen(blue, at = 0))
        assertEquals(listOf(blue, red, NONE), pairs(colors))
    }

    /** Several changes in one edit (an IME's batch): each lands where it was made. */
    @Test
    fun aBatchOfChangesLandsInPlace() {
        val changes = listOf(ComposerColors.Change(0, 1, 0, 2), ComposerColors.Change(3, 4, 4, 4))
        val colors = colored("abcd", 1, 3, red).edited(changes, 4, pen = null)
        assertEquals(listOf(NONE, NONE, red, red), pairs(colors))
    }

    /** An edit somewhere else isn't typing where the colour was picked, and doesn't take it. */
    @Test
    fun aPenColoursOnlyTypingAtItsCaret() {
        val colors = ComposerColors.plain(4).edited(typedAt(4, 5), 9, pen = ComposerColors.Pen(blue, at = 2))
        assertEquals(List(9) { NONE }, pairs(colors))
    }

    @Test
    fun aPenIsSpentByTypingAndCarriedByOtherEdits() {
        val pen = ComposerColors.Pen(blue, at = 2)
        assertEquals(null, pen.afterTyping(typedAt(2, 1)))
        assertEquals(pen.copy(at = 5), pen.afterTyping(typedAt(0, 3)))
        assertEquals(pen, pen.afterTyping(typedAt(4, 4)))
        assertEquals(null, pen.afterTyping(listOf(ComposerColors.Change(1, 3, 1, 1))))
    }

    /** The composer's own insert at the pen's caret — an upload's link — isn't typing: the pick waits after it. */
    @Test
    fun theComposersOwnEditCarriesThePenPast() {
        val pen = ComposerColors.Pen(blue, at = 2)
        assertEquals(pen.copy(at = 6), pen.after("ab", "ab url"))
        assertEquals(pen.copy(at = 5), pen.after("ab", "xyzab"))
        assertEquals(pen, pen.after("abcd", "abcdlink"))
        assertEquals(null, pen.after("abcd", "ad"))
    }

    /** Autocorrect's replace: the word takes the colour of what it replaces. */
    @Test
    fun aReplacementTakesTheColourOfWhatItReplaces() {
        val colors = colored("teh cat", 0, 3, red).followed("teh cat", "the cat")
        assertEquals(List(3) { red } + List(4) { NONE }, pairs(colors))
    }

    /** A Reply's `bob: ` doesn't take the colour of the words it's put in front of. */
    @Test
    fun anInsertAtTheStartIsPlain() {
        val colors = colored("hello", 0, 5, red).followed("hello", "bob: hello")
        assertEquals(List(5) { NONE } + List(5) { red }, pairs(colors))
    }

    @Test
    fun aDeletionTakesItsColourWithIt() {
        val colors = colored("abcd", 1, 3, red).followed("abcd", "ad")
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
