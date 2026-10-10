// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.TextEdit
import net.amiantos.lurkerkit.support.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class TextEditTests {

    private fun apply(old: String, new: String): String {
        val edit = TextEdit.difference(old, new)
        return old.replaceRange(edit.range.start, edit.range.end, edit.replacement)
    }

    @Test
    fun testRewritesOnlyWhatChanged() {
        val edit = TextEdit.difference("hi al", "hi alice: ")
        assertEquals(TextRange.of(5, 0), edit.range)
        assertEquals("ice: ", edit.replacement)
    }

    @Test
    fun testAPrependSharingAPrefixStillLandsRight() {
        assertEquals("alice: alice is cool", apply("alice is cool", "alice: alice is cool"))
        assertEquals("hi", apply("bob: hi", "hi"))
        assertEquals("same", apply("same", "same"))
        assertEquals("new", apply("", "new"))
        assertEquals("", apply("old", ""))
    }

    /** 😀 and 😃 share their high surrogate: an edit cut in UTF-16 would replace only the low half. */
    @Test
    fun testNeverSplitsASurrogatePair() {
        val edit = TextEdit.difference("x😀", "x😃")
        assertEquals(TextRange.of(1, 2), edit.range)
        assertEquals("😃", edit.replacement)
        val tail = TextEdit.difference("😀x", "😃x")
        assertEquals(TextRange.of(0, 2), tail.range)
        assertEquals("😃", tail.replacement)
    }

    /** A flag is two regional indicators; changing one must replace the whole flag. */
    @Test
    fun testNeverSplitsAGraphemeCluster() {
        val edit = TextEdit.difference("a🇫🇷", "a🇫🇮")
        assertEquals("🇫🇮", edit.replacement)
        assertEquals("a🇫🇮", apply("a🇫🇷", "a🇫🇮"))
    }
}
