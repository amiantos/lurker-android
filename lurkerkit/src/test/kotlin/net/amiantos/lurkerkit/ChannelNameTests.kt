// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ChannelName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `namesAChannel` — the guard in front of every join. */
class ChannelNameTests {

    @Test
    fun testASigilOnlyTargetIsNotAChannelName() {
        // ⚠⚠ The whole reason this isn't built on `fold`. `fold` drops exactly ONE leading
        // sigil, so every one of these folds to something non-empty, the guard passes, and
        // `ensurePrefix` hands them along untouched — a JOIN for a channel with no name.
        assertFalse(ChannelName.namesAChannel("#"))
        assertFalse(ChannelName.namesAChannel("##"))
        assertFalse(ChannelName.namesAChannel("#&"))
        assertFalse(ChannelName.namesAChannel("&&"))
        assertFalse(ChannelName.namesAChannel("+"))
        assertFalse(ChannelName.namesAChannel("!"))
        assertFalse(ChannelName.namesAChannel(""))
    }

    @Test
    fun testARealNameIsAChannelNameWhateverItsSigils() {
        assertTrue(ChannelName.namesAChannel("lurker"))
        assertTrue(ChannelName.namesAChannel("#lurker"))
        // `##anime` is a real convention, and has to survive a guard aimed at `##`.
        assertTrue(ChannelName.namesAChannel("##anime"))
        // All four sigils — this repo's most-repeated bug is treating `#` as the only one.
        assertTrue(ChannelName.namesAChannel("&local"))
        assertTrue(ChannelName.namesAChannel("+modeless"))
        assertTrue(ChannelName.namesAChannel("!ABCDEfoo"))
    }

    @Test
    fun testWhitespaceIsNotAChannelName() {
        // Trimmed inside, so two call sites can't drift to different trims — which is exactly
        // what the two that existed had done, under a comment claiming they agreed.
        assertFalse(ChannelName.namesAChannel("   "))
        assertFalse(ChannelName.namesAChannel(" # "))
        assertTrue(ChannelName.namesAChannel("  #lurker\n"))
    }

    @Test
    fun testEnsurePrefixLeavesEveryRealSigilAlone() {
        // What the join sheet's footer promises: a `#` is added only when there is no sigil.
        assertEquals("#lurker", ChannelName.ensurePrefix("lurker"))
        assertEquals("#lurker", ChannelName.ensurePrefix("#lurker"))
        assertEquals("&local", ChannelName.ensurePrefix("&local"))
        assertEquals("+modeless", ChannelName.ensurePrefix("+modeless"))
        assertEquals("!ABCDEfoo", ChannelName.ensurePrefix("!ABCDEfoo"))
    }
}
