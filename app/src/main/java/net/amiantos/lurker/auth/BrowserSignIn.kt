// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import net.amiantos.lurkerkit.client.OAuth
import okhttp3.HttpUrl

/**
 * The server's sign-in and approval pages, in a Custom Tab. The kit runs the OAuth flow and asks
 * this for one thing (`ChatViewModel.signIn`'s `authorize`): show a page, and say where it sent
 * the browser back — or null when the user cancelled.
 *
 * A normal tab, sharing the browser's cookies: someone already signed in to Lurker in their
 * browser only has to approve, and a browser holding a client certificate offers it here too.
 *
 * The page's answer comes back as an intent: the approval page redirects to `chat.lurker:/oauth…`,
 * the manifest routes that scheme to `MainActivity`, and `singleTask` brings the existing task
 * forward — clearing the tab off the top of it — and hands the intent to `onNewIntent`. The
 * activity passes it to [onRedirect]; the parked `authorize` resumes with it.
 *
 * Nothing guesses that the tab was closed. A Custom Tab has no "closed" callback, and reading the
 * app's own activity resuming as one lost sign-ins: a browser that took its tab down before
 * dispatching the redirect resumed the activity first, the attempt ended as closed, and the
 * redirect then found nothing waiting. The sign-in screen says the browser is waiting and offers
 * Cancel ([cancel]) instead, as Mastodon's and Spooky's apps do.
 *
 * App-scoped (one in `LurkerApp`), not the activity's: the activity under the tab can be destroyed
 * and recreated while the tab is up (a configuration change, or the system reclaiming it), and the
 * attempt — with its PKCE verifier, inside the kit's suspended `signIn` — has to survive that. A
 * process death it can't survive: the redirect then finds nothing waiting and goes to
 * [onOrphanRedirect], which finishes from what the kit saved (`ChatViewModel.resumeSignIn`).
 *
 * Main thread only, like everything that feeds the kit.
 */
class BrowserSignIn {
    private val waiter = RedirectWaiter()

    /**
     * What this side has to say when a sign-in ends for a reason the kit cannot see — it reads a
     * null answer as "cancelled" and says nothing. One case: no browser could show the page. The
     * sign-in screen shows it where it shows the kit's status; a new attempt clears it.
     */
    private val noticeSubject = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = noticeSubject.asStateFlow()

    /** Whether the approval page is up and the attempt is waiting on it; the screen offers Cancel. */
    val waiting: StateFlow<Boolean> get() = waiter.waiting

    /**
     * Handed a redirect that found no attempt waiting. Set once by `LurkerApp`, before any
     * activity can deliver one.
     */
    var onOrphanRedirect: (callback: String) -> Unit = {}

    /** The started activity a tab can be launched from, if any. Held only between start and stop. */
    private var host: Activity? = null

    fun attach(activity: Activity) {
        host = activity
    }

    fun detach(activity: Activity) {
        if (host === activity) host = null
    }

    /** The address the page redirected to, or null when the tab closed without one. */
    suspend fun authorize(page: HttpUrl): String? {
        noticeSubject.value = null
        return waiter.await { open(page) }
    }

    /** An intent's data reached the activity; taken if it is this sign-in's redirect. */
    fun onRedirect(data: String?) {
        val callback = callbackFrom(data) ?: return
        if (!waiter.redirected(callback)) {
            // Nothing waiting: the process died while the tab was up (or the user cancelled
            // first). The kit kept the attempt and decides which.
            Log.i(TAG, "sign-in redirect arrived with no sign-in waiting; resuming from what was saved")
            onOrphanRedirect(callback)
        }
    }

    /** The user gave up on the browser: the attempt ends as a close, with nothing to say. */
    fun cancel() {
        waiter.cancel()
    }

    private fun open(page: HttpUrl): Boolean {
        val activity = host
        if (activity == null) {
            // Asked while nothing is on screen — the app went to the background during the
            // registration request. A tab can't be put up from there; the attempt ends, and the
            // notice is waiting when the user comes back.
            Log.w(TAG, "no activity to open the sign-in page from")
            noticeSubject.value = COULD_NOT_OPEN
            return false
        }
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            // The page carries this attempt's state and challenge; there's nothing to share.
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        return try {
            tab.launchUrl(activity, page.toString().toUri())
            true
        } catch (e: ActivityNotFoundException) {
            // No browser at all. The kit reads a null answer as "cancelled" and says nothing, so
            // the notice does.
            Log.w(TAG, "no browser to open the sign-in page", e)
            noticeSubject.value = COULD_NOT_OPEN
            false
        } catch (e: SecurityException) {
            // A browser that refuses the launch. Thrown out of here it would reach the kit's
            // `signIn` and crash the app scope; as "not shown" it ends the attempt like a cancel.
            Log.w(TAG, "the browser refused the sign-in page", e)
            noticeSubject.value = COULD_NOT_OPEN
            false
        }
    }

    internal companion object {
        private const val TAG = "BrowserSignIn"

        private const val COULD_NOT_OPEN = "Couldn\u2019t open a browser to sign in."

        /** `OAuth.redirectURI`, split where the scheme ends: the scheme compares case-insensitively, the path exactly. */
        private val redirectScheme = OAuth.redirectURI.substringBefore(':')
        private val redirectPath = OAuth.redirectURI.substringAfter(':')

        /**
         * The callback text the kit's `OAuth.callback` reads, when [data] is the approval page's
         * redirect: scheme `chat.lurker` (any case, as RFC 3986 has it) and path exactly `/oauth`,
         * with whatever query it carries. Null for anything else.
         * The manifest's filter can't check a path on a host-less URI (Android ignores `path`
         * without a `host`), so this is where a stray `chat.lurker:` link is told apart from the
         * redirect.
         */
        fun callbackFrom(data: String?): String? {
            if (data == null) return null
            val colon = data.indexOf(':')
            if (colon <= 0 || !data.substring(0, colon).equals(redirectScheme, ignoreCase = true)) return null
            val rest = data.substring(colon + 1)
            val path = rest.substringBefore('?').substringBefore('#')
            return if (path == redirectPath) data else null
        }
    }
}

/**
 * The one sign-in attempt in flight, and how it ends: with the redirect, or with null when the
 * user cancels. Android-free, so the ordering below is tested on the JVM.
 *
 * One attempt at a time: a second [await] ends the first with null before it starts.
 */
internal class RedirectWaiter {
    private var pending: CompletableDeferred<String?>? = null

    private val waitingSubject = MutableStateFlow(false)

    /** Whether the current attempt's page went up and it's waiting on the redirect. */
    val waiting: StateFlow<Boolean> = waitingSubject.asStateFlow()

    /** Run [show] (true when the page went up) and wait for the attempt's answer. */
    suspend fun await(show: () -> Boolean): String? {
        finish(null)
        val attempt = CompletableDeferred<String?>()
        pending = attempt
        try {
            if (!show()) finish(null) else if (pending === attempt) waitingSubject.value = true
            return attempt.await()
        } finally {
            // A cancelled caller leaves nothing behind for a later redirect to find.
            if (pending === attempt) {
                pending = null
                waitingSubject.value = false
            }
        }
    }

    /** The redirect arrived. False when no attempt was waiting for it. */
    fun redirected(callback: String): Boolean {
        if (pending == null) return false
        finish(callback)
        return true
    }

    /** The user cancelled: the attempt, if any, ends with null. */
    fun cancel() {
        finish(null)
    }

    private fun finish(answer: String?) {
        val attempt = pending ?: return
        pending = null
        waitingSubject.value = false
        attempt.complete(answer)
    }
}
