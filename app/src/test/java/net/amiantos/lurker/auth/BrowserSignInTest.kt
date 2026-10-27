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

    // MARK: - Whose redirect it is

    @Test
    fun theStateIsReadOffTheRedirect() {
        assertEquals("xyz", BrowserSignIn.stateOf("chat.lurker:/oauth?code=abc&state=xyz"))
        assertEquals("xyz", BrowserSignIn.stateOf("chat.lurker:/oauth?state=xyz&code=abc#frag"))
        assertEquals("", BrowserSignIn.stateOf("chat.lurker:/oauth?state=&code=abc"))
        assertNull(BrowserSignIn.stateOf("chat.lurker:/oauth?code=abc"))
        assertNull(BrowserSignIn.stateOf("chat.lurker:/oauth?state"))
        assertNull(BrowserSignIn.stateOf("chat.lurker:/oauth"))
        // A name that merely starts with `state` is not it.
        assertNull(BrowserSignIn.stateOf("chat.lurker:/oauth?stateful=1"))
    }

    /**
     * ⚠⚠ Any app can fire `chat.lurker:/oauth?state=x&code=y` at the exported activity while the
     * tab is up. Taken, it ended the attempt with a redirect the kit then refused, and the genuine
     * one arrived to nothing waiting. A redirect without this attempt's `state` is not taken, and
     * the attempt keeps waiting for the one that carries it.
     */
    @Test
    fun aRedirectWithAnotherStateIsNotTaken() = runTest {
        val waiter = RedirectWaiter()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { waiter.await(state = "xyz") { true } }
        assertFalse(waiter.redirected("chat.lurker:/oauth?code=bogus&state=other"))
        assertFalse(waiter.redirected("chat.lurker:/oauth?code=bogus"))
        assertTrue(waiter.waiting.value)
        assertFalse(answer.isCompleted)
        assertTrue(waiter.redirected("chat.lurker:/oauth?code=abc&state=xyz"))
        assertEquals("chat.lurker:/oauth?code=abc&state=xyz", answer.await())
        assertFalse(waiter.waiting.value)
    }

    /** A page that went out without a `state` (none ever does) takes the first redirect, as before. */
    @Test
    fun aPageWithoutAStateTakesAnyRedirect() = runTest {
        val waiter = RedirectWaiter()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { waiter.await(state = null) { true } }
        assertTrue(waiter.redirected("chat.lurker:/oauth?code=abc&state=whatever"))
        assertEquals("chat.lurker:/oauth?code=abc&state=whatever", answer.await())
    }

    /** The expected `state` belongs to the attempt: a cancel forgets it, and the next attempt brings its own. */
    @Test
    fun theExpectedStateDoesNotOutliveItsAttempt() = runTest {
        val waiter = RedirectWaiter()
        val first = async(start = CoroutineStart.UNDISPATCHED) { waiter.await(state = "one") { true } }
        waiter.cancel()
        assertNull(first.await())
        val second = async(start = CoroutineStart.UNDISPATCHED) { waiter.await(state = "two") { true } }
        assertFalse(waiter.redirected("chat.lurker:/oauth?code=abc&state=one"))
        assertTrue(waiter.redirected("chat.lurker:/oauth?code=abc&state=two"))
        assertEquals("chat.lurker:/oauth?code=abc&state=two", second.await())
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
