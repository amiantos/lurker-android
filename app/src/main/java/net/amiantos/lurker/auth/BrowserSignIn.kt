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
 * the browser back — or null when the user closed it first.
 *
 * Ephemeral where the browser supports it, so the tab shares no cookies with the browser: a
 * sign-in never lands on whichever account the browser last used, and signing out of the app
 * leaves no web session behind. That is iOS's `prefersEphemeralWebBrowserSession`; a browser
 * without ephemeral tabs ignores the request and shares its cookies. The cost is typing a password
 * (or using a passkey) each time, which is rare, since the token never expires.
 *
 * The page's answer comes back as an intent: the approval page redirects to `chat.lurker:/oauth…`,
 * the manifest routes that scheme to `MainActivity`, and `singleTask` brings the existing task
 * forward — clearing the tab off the top of it — and hands the intent to `onNewIntent`. The
 * activity passes it to [onRedirect]; the parked `authorize` resumes with it.
 *
 * App-scoped (one in `LurkerApp`), not the activity's: the activity under the tab can be destroyed
 * and recreated while the tab is up (a configuration change, or the system reclaiming it), and the
 * attempt — with its PKCE verifier, inside the kit's suspended `signIn` — has to survive that.
 *
 * Main thread only, like everything that feeds the kit.
 */
class BrowserSignIn {
    private val waiter = RedirectWaiter()

    /**
     * What this side has to say when a sign-in ends for a reason the kit cannot see — it reads a
     * null answer as "closed" and says nothing. Two cases: no browser could show the page, and a
     * redirect that found no attempt waiting (the process died while the tab was up, taking the
     * PKCE verifier with it, or the tab resumed the app before dispatching). The sign-in screen
     * shows it where it shows the kit's status; a new attempt clears it.
     */
    private val noticeSubject = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = noticeSubject.asStateFlow()

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
            // A redirect nothing is waiting for: the process died while the tab was up, taking the
            // attempt's PKCE verifier with it, so the code can't be exchanged. The user approved
            // and came back to the form; say why nothing happened. The kit's own sentence.
            Log.w(TAG, "sign-in redirect arrived with no sign-in waiting; dropped")
            noticeSubject.value = "Sign-in didn\u2019t finish. Try again."
        }
    }

    /** The activity resumed. See [RedirectWaiter.resumed] for why that can mean "closed". */
    fun onHostResumed() {
        waiter.resumed()
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
            .setEphemeralBrowsingEnabled(true)
            // The page carries this attempt's state and challenge; there's nothing to share.
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        return try {
            tab.launchUrl(activity, page.toString().toUri())
            true
        } catch (e: ActivityNotFoundException) {
            // No browser at all. The kit reads a null answer as "closed" and says nothing, so
            // the notice does.
            Log.w(TAG, "no browser to open the sign-in page", e)
            noticeSubject.value = COULD_NOT_OPEN
            false
        } catch (e: SecurityException) {
            // A browser that refuses the launch. Thrown out of here it would reach the kit's
            // `signIn` and crash the app scope; as "not shown" it ends the attempt like a close.
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
 * user closes the tab. Android-free, so the ordering below is tested on the JVM.
 *
 * **Cancel detection.** A Custom Tab has no "closed" callback. What the app sees is its own
 * activity resuming: the tab covered it, and something brought it back. That something is either
 * the redirect or the user (back, the tab's ✕, recents). Android delivers a new intent to
 * `onNewIntent` *before* `onResume` whenever that intent is what resumes the activity — and a
 * recreated activity gets it in `onCreate`, also before `onResume` — so by the time [resumed] runs,
 * a redirect has already been taken by [redirected]. A resume with the attempt still waiting is
 * therefore one without a redirect, and it ends the attempt with null. Only resumes *after* the
 * tab went up count ([shown]): the activity was already resumed when it launched the tab, so the
 * next `onResume` is necessarily the one that follows the tab.
 *
 * The residue: a browser that tears the tab down *before* dispatching the redirect would resume the
 * activity first, and the redirect then finds nothing waiting. Chrome dispatches from the tab, so
 * `singleTask` clears the tab as part of delivering the intent; the residue is a browser that
 * doesn't, where the user signs in again.
 *
 * One attempt at a time: a second [await] ends the first with null before it starts.
 */
internal class RedirectWaiter {
    private var pending: CompletableDeferred<String?>? = null

    /** Whether the current attempt's tab went up, so a resume can mean it closed. */
    private var shown = false

    /** Run [show] (true when the page went up) and wait for the attempt's answer. */
    suspend fun await(show: () -> Boolean): String? {
        finish(null)
        val attempt = CompletableDeferred<String?>()
        pending = attempt
        shown = false
        try {
            if (show()) shown = true else finish(null)
            return attempt.await()
        } finally {
            // A cancelled caller leaves nothing behind for a later redirect or resume to find.
            if (pending === attempt) {
                pending = null
                shown = false
            }
        }
    }

    /** The redirect arrived. False when no attempt was waiting for it. */
    fun redirected(callback: String): Boolean {
        if (pending == null) return false
        finish(callback)
        return true
    }

    /** The activity resumed: with the tab up and no redirect taken, the user closed it. */
    fun resumed() {
        if (shown) finish(null)
    }

    private fun finish(answer: String?) {
        val attempt = pending ?: return
        pending = null
        shown = false
        attempt.complete(answer)
    }
}
