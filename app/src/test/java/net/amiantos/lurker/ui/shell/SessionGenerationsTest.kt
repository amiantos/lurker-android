// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Each sign-in gets a scaffold of its own — `AppRoot`'s key moves on every arrival at signed in, and only then. */
class SessionGenerationsTest {
    @Test
    fun aRestoredSessionKeepsItsGenerationAndRecompositionsCountNothing() {
        val state = SessionGenerations.start(signedIn = true)
        assertEquals(0, SessionGenerations.advance(state, signedIn = true))
        assertEquals(0, SessionGenerations.advance(state, signedIn = true))
    }

    @Test
    fun everySignInIsANewGeneration() {
        val state = SessionGenerations.start(signedIn = true)
        val first = SessionGenerations.advance(state, signedIn = true)
        SessionGenerations.advance(state, signedIn = false)
        // Signed back in while the old scaffold may still be fading out: a different key, so a fresh scaffold.
        val second = SessionGenerations.advance(state, signedIn = true)
        assertNotEquals(first, second)
        assertEquals(second, SessionGenerations.advance(state, signedIn = true))
    }

    @Test
    fun aColdSignInStartsAtOne() {
        val state = SessionGenerations.start(signedIn = false)
        assertEquals(0, SessionGenerations.advance(state, signedIn = false))
        assertEquals(1, SessionGenerations.advance(state, signedIn = true))
    }
}
