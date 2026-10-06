// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.auth

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSignInTest {

    // MARK: - The redirect

    @Test
    fun theRedirectIsHandedOnWhole() {
        val url = "chat.lurker:/oauth?code=abc&state=xyz"
        assertEquals(url, BrowserSignIn.callbackFrom(url))
    }

    @Test
    fun aDenialIsStillTheRedirect() {
        // The kit reads `error=access_denied` itself; this only recognises the address.
        val url = "chat.lurker:/oauth?error=access_denied&state=xyz"
        assertEquals(url, BrowserSignIn.callbackFrom(url))
    }

    @Test
    fun theSchemeIsCaseInsensitive() {
        val url = "CHAT.Lurker:/oauth?code=abc&state=xyz"
        assertEquals(url, BrowserSignIn.callbackFrom(url))
    }

    @Test
    fun aBarePathOrFragmentIsTheRedirect() {
        assertEquals("chat.lurker:/oauth", BrowserSignIn.callbackFrom("chat.lurker:/oauth"))
        assertEquals("chat.lurker:/oauth#x", BrowserSignIn.callbackFrom("chat.lurker:/oauth#x"))
    }

    @Test
    fun anythingElseIsNot() {
        for (data in listOf(
            null,
            "",
            "chat.lurker:",
            "chat.lurker:/other?code=abc",
            "chat.lurker:/oauthx?code=abc",
            "chat.lurker:/oauth/?code=abc",
            "chat.lurker://oauth?code=abc",
            "https://app.lurker.chat/oauth?code=abc",
            "lurker:/oauth?code=abc",
            ":/oauth",
        )) {
            assertNull(data, BrowserSignIn.callbackFrom(data))
        }
    }

    // MARK: - The attempt

    @Test
    fun theRedirectAnswersTheAttempt() = runTest {
        val waiter = RedirectWaiter()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { waiter.await { true } }
        assertTrue(waiter.waiting.value)
        assertTrue(waiter.redirected("chat.lurker:/oauth?code=abc"))
        assertEquals("chat.lurker:/oauth?code=abc", answer.await())
        assertFalse(waiter.waiting.value)
    }

    @Test
    fun aCancelEndsTheAttempt() = runTest {
        val waiter = RedirectWaiter()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { waiter.await { true } }
        waiter.cancel()
        assertNull(answer.await())
        assertFalse(waiter.waiting.value)
        // And a late redirect finds nothing to answer.
        assertFalse(waiter.redirected("chat.lurker:/oauth?code=abc"))
    }

    @Test
    fun aCancelBeforeAnyAttemptDoesNothing() = runTest {
        val waiter = RedirectWaiter()
        waiter.cancel()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { waiter.await { true } }
        assertTrue(waiter.redirected("chat.lurker:/oauth?code=abc"))
        assertEquals("chat.lurker:/oauth?code=abc", answer.await())
    }

    @Test
    fun aPageThatNeverWentUpEndsAtOnce() = runTest {
        val waiter = RedirectWaiter()
        assertNull(waiter.await { false })
        assertFalse(waiter.waiting.value)
        assertFalse(waiter.redirected("chat.lurker:/oauth?code=abc"))
    }

    @Test
    fun aSecondAttemptEndsTheFirst() = runTest {
        val waiter = RedirectWaiter()
        val first = async(start = CoroutineStart.UNDISPATCHED) { waiter.await { true } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { waiter.await { true } }
        assertNull(first.await())
        // The first one ending leaves the second's page up.
        assertTrue(waiter.waiting.value)
        assertTrue(waiter.redirected("chat.lurker:/oauth?code=second"))
        assertEquals("chat.lurker:/oauth?code=second", second.await())
    }

    @Test
    fun aCancelledAttemptLeavesNothingWaiting() = runTest {
        val waiter = RedirectWaiter()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { waiter.await { true } }
        answer.cancelAndJoin()
        assertFalse(waiter.waiting.value)
        assertFalse(waiter.redirected("chat.lurker:/oauth?code=abc"))
    }
}
