// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Test

/** A link dropped into the field — lurker-ios's `ComposerBar.insert(_:atCaret:)`. */
class ComposerInsertTest {
    @Test
    fun intoAnEmptyFieldWithASpaceAfterForTheCaption() {
        assertEquals(
            ComposerInsert.Result("https://u/1 ", 12, 12, focuses = true),
            ComposerInsert.insert("", 0, 0, "https://u/1", atCaret = true),
        )
    }

    @Test
    fun aSpaceBeforeOnlyWhenItWouldOtherwiseWeld() {
        assertEquals("look https://u/1 ", ComposerInsert.insert("look", 4, 4, "https://u/1", atCaret = true).text)
        assertEquals("look https://u/1 ", ComposerInsert.insert("look ", 5, 5, "https://u/1", atCaret = true).text)
        assertEquals("look\nhttps://u/1 ", ComposerInsert.insert("look\n", 5, 5, "https://u/1", atCaret = true).text)
    }

    @Test
    fun atTheCaretItSplicesAndReplacesASelection() {
        val result = ComposerInsert.insert("see here ok", 4, 8, "https://u/1", atCaret = true)
        assertEquals("see https://u/1  ok", result.text)
        assertEquals(16, result.selectionStart)
    }

    @Test
    fun aLaterLinkAppendsWithoutTheKeyboardAndCarriesATrailingCaret() {
        val result = ComposerInsert.insert("https://u/1 ", 12, 12, "https://u/2", atCaret = false)
        assertEquals(ComposerInsert.Result("https://u/1 https://u/2 ", 24, 24, focuses = false), result)
    }

    @Test
    fun aLaterLinkLeavesACaretTheUserMovedIntoTheText() {
        // Mid-caption: the link goes at the end, and the caret stays where they're typing.
        val result = ComposerInsert.insert("nice shot", 4, 4, "https://u/2", atCaret = false)
        assertEquals(ComposerInsert.Result("nice shot https://u/2 ", 4, 4, focuses = false), result)
    }
}
