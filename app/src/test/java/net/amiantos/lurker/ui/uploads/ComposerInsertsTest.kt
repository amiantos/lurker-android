// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.model.BufferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where outside text lands: the composer on screen, or the one a buffer is about to show. */
class ComposerInsertsTest {
    private val lurker = BufferKey(1, "#lurker")
    private val android = BufferKey(1, "##android")

    private class Field {
        val got = mutableListOf<Pair<String, Boolean>>()
        val insert = ComposerInserts.Insert { text, atCaret -> got += text to atCaret }
    }

    @Test
    fun withNothingOnScreenThereIsNowhereToInsert() {
        val inserts = ComposerInserts()
        assertNull(inserts.activeKey)
        assertFalse(inserts.insertIntoActive("https://u/1", atCaret = true))
    }

    @Test
    fun theNewestMountedComposerIsTheOneOnScreen() {
        val inserts = ComposerInserts()
        val old = Field()
        val new = Field()
        val oldHandle = inserts.mount(lurker, old.insert)
        // The next pane animates in over the leaving one: both are mounted for a moment.
        inserts.mount(android, new.insert)
        inserts.unmount(oldHandle)
        assertEquals(android, inserts.activeKey)
        assertTrue(inserts.insertIntoActive("https://u/1", atCaret = false))
        assertEquals(listOf("https://u/1" to false), new.got)
        assertTrue(old.got.isEmpty())
    }

    @Test
    fun textForAnotherBufferWaitsForItsComposer() {
        val inserts = ComposerInserts()
        val here = Field()
        inserts.mount(lurker, here.insert)
        inserts.insert(android, "shared text")
        assertTrue(here.got.isEmpty())
        val there = Field()
        inserts.mount(android, there.insert)
        assertEquals(listOf("shared text" to true), there.got)
        // Delivered once.
        val again = Field()
        inserts.mount(android, again.insert)
        assertTrue(again.got.isEmpty())
    }

    @Test
    fun textForTheBufferOnScreenGoesInAtOnce() {
        val inserts = ComposerInserts()
        val here = Field()
        inserts.mount(BufferKey(1, "#Lurker"), here.insert)
        // Target case folds, as `BufferKey.id` does.
        inserts.insert(lurker, "https://u/x")
        assertEquals(listOf("https://u/x" to true), here.got)
    }

    @Test
    fun aSignOutDropsWhatWasWaiting() {
        val inserts = ComposerInserts()
        inserts.insert(android, "for the old account")
        inserts.clear()
        val there = Field()
        inserts.mount(android, there.insert)
        assertTrue(there.got.isEmpty())
    }
}
