// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import net.amiantos.lurkerkit.push.PushRoute
import net.amiantos.lurkerkit.push.RelayPushKeys
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Owns this install's relationship with FCM: asking the server, asking the user, fetching the token
 * and handing it to the server (lurker-android#16, server side lurker#490). lurker-ios's
 * `PushRegistrar`, and the order below is its point, as it is there:
 *
 *   1. ask the SERVER whether it can deliver FCM at all,
 *   2. only then ask the USER for permission,
 *   3. only then fetch the token and register it.
 *
 * Backwards, and a self-hoster's user gets a permission prompt for notifications their server can
 * never send — a grant spent on nothing.
 *
 * Two ways to hand the token over (lurker-dev/RELAY_PLAN.md §6.2): directly, to a server that holds
 * our FCM key (the hosted service); or, on a self-hosted server whose admin has turned on
 * push.lurker.chat, inside a relay endpoint filed as a Web Push subscription with this install's
 * keys ([pushKeys]). The relay forwards each push still encrypted; the messaging service opens it.
 * The app never contacts the relay itself.
 *
 * App-scoped (built by `LurkerApp`) because a token rotation arrives in the messaging service with no
 * activity up. The permission prompt is the one step that needs an activity, so `MainActivity` lends
 * one ([permissionPrompt]) while it's started, and hands the answer back ([onPermissionResult]) —
 * from whichever instance is up when it comes, so a rotation mid-prompt doesn't lose it.
 */
class PushRegistrar(
    private val context: Context,
    private val model: ChatViewModel,
    private val scope: CoroutineScope,
    private val pushKeys: RelayPushKeys,
) {
    sealed interface Outcome {
        /** The server has this install's token. */
        data object Registered : Outcome

        /** The server answered, and won't take the token. */
        data object Rejected : Outcome

        /**
         * The server answered, and can't push to the app: it holds no FCM key (self-hosted, or older
         * than lurker#490) and its admin hasn't turned on push.lurker.chat. Settings says so.
         */
        data object UnsupportedByServer : Outcome

        /** We couldn't ask the server. Transient, and says nothing about its configuration. */
        data object ServerUnreachable : Outcome

        /** Notifications are off for this app, by the user's answer or in system settings. */
        data object Denied : Outcome

        /** Permission is unanswered and there was no screen to ask on; the next foreground asks. */
        data object Deferred : Outcome

        /** Built without google-services.json — CI, or someone's own build. Push is off, by design. */
        data object NoFirebase : Outcome

        /**
         * No Google Play services: Fire OS tablets (a stated minSdk target), de-Googled phones. FCM
         * rides Play services, so there is no token to have. Checked up front rather than left to the
         * token request, whose failure on such a device is Firebase's to define.
         */
        data class NoPlayServices(val code: Int) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Set by `MainActivity` while started: shows the system's notification-permission dialog. */
    var permissionPrompt: (() -> Unit)? = null

    private var permissionAnswer: CompletableDeferred<Boolean>? = null

    /** The dialog's answer, from whichever `MainActivity` is up to hear it. */
    fun onPermissionResult(granted: Boolean) {
        permissionAnswer?.complete(granted)
        permissionAnswer = null
    }

    /** In flight, if any: one run at a time. */
    private var inFlight: Job? = null

    /**
     * Calls that arrived while a run was in flight, owed one more run when it ends. A token rotation
     * mid-registration would otherwise leave the server with the old token (the run in flight fetched
     * it before the rotation); a foreground behind a run that couldn't prompt (a push woke the
     * process, then the user opened the app) would never ask.
     */
    private var rerunOwed = false

    /** Whether any call owed a rerun could prompt. */
    private var rerunMayPrompt = false

    /**
     * The sign-out's token deletion. A run waits for it before fetching a token: until it lands,
     * Firebase still hands out the old cached token, the next session would register it, and the
     * deletion landing afterwards would invalidate the token that session just registered.
     */
    private var deletion: Job? = null

    /**
     * The token the server has from this session, so a foreground or a rotation of the SCREEN
     * doesn't post it again. Cleared at sign-out ([signedOut]).
     */
    private var registeredToken: String? = null

    /** The relay endpoint the server has from this session, likewise. */
    private var registeredEndpoint: String? = null

    /**
     * A debug build accepts any https relay, for developing the relay against a local server
     * (`LURKER_PUSH_RELAY_URL`); a release build only push.lurker.chat — see `RelayPush.route`.
     */
    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Run the sequence, once signed in and never before: a prompt on the sign-in screen asks the user
     * to authorize notifications for an account they haven't named yet. Safe to call on every
     * foreground — FCM re-issues the same token, the server upserts, and a granted permission doesn't
     * re-prompt. [mayPrompt] is false where there is no screen to ask on (a token rotation).
     */
    fun enableIfSignedIn(mayPrompt: Boolean) {
        if (model.session != ChatViewModel.SessionState.LoggedIn) return
        if (inFlight?.isActive == true) {
            rerunOwed = true
            rerunMayPrompt = rerunMayPrompt || mayPrompt
            return
        }
        inFlight = scope.launch {
            log(enable(mayPrompt))
            inFlight = null
            if (rerunOwed) {
                val prompt = rerunMayPrompt
                rerunOwed = false
                rerunMayPrompt = false
                enableIfSignedIn(prompt)
            }
        }
    }

    /**
     * Sign-out, deliberate or a revoked session: invalidate this install's token with FCM. The kit's
     * `logout` takes the token off the server when this process registered it, but not when it never
     * got that far (the server was unreachable at launch), nor after a 401, when there's no session to
     * ask with — and the server files tokens by account, so the next account's phone would keep
     * getting this one's DMs. A deleted token makes the server's next push fail UNREGISTERED, and the
     * server drops it. The next sign-in fetches a new one.
     */
    fun signedOut() {
        registeredToken = null
        registeredEndpoint = null
        rerunOwed = false
        rerunMayPrompt = false
        inFlight?.cancel()
        inFlight = null
        if (FirebaseApp.getApps(context).isEmpty()) return
        deletion = scope.launch {
            try {
                FirebaseMessaging.getInstance().deleteToken().await()
            } catch (e: Exception) {
                Log.w(TAG, "couldn't delete the push token: ${e.message}")
            }
        }
    }

    private suspend fun enable(mayPrompt: Boolean): Outcome {
        if (FirebaseApp.getApps(context).isEmpty()) return Outcome.NoFirebase
        val playServices = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) return Outcome.NoPlayServices(playServices)
        val route = model.pushRoute(allowAnyHttpsRelay = debuggable) ?: return Outcome.ServerUnreachable
        if (route == PushRoute.None) {
            // Turning the relay off deletes our registration on the server; if it comes back on,
            // the endpoint has to be filed again, not taken as already there.
            registeredEndpoint = null
            return Outcome.UnsupportedByServer
        }
        permission(mayPrompt)?.let { return it }
        deletion?.join()
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        return when (route) {
            PushRoute.Native -> registerDirect(token)
            is PushRoute.Relay -> registerRelay(route.endpoint(token))
            PushRoute.None -> Outcome.UnsupportedByServer
        }
    }

    private suspend fun registerDirect(token: String): Outcome {
        if (token == registeredToken) return Outcome.Registered
        if (!model.registerPushDevice(token)) return Outcome.Rejected
        registeredToken = token
        return Outcome.Registered
    }

    private suspend fun registerRelay(endpoint: String): Outcome {
        if (endpoint == registeredEndpoint) return Outcome.Registered
        val keys = pushKeys.loadOrCreate()
        return when (model.registerRelaySubscription(endpoint, keys)) {
            ChatViewModel.RelayRegistration.Registered -> {
                registeredEndpoint = endpoint
                Outcome.Registered
            }
            ChatViewModel.RelayRegistration.RelayOff -> {
                registeredEndpoint = null
                Outcome.UnsupportedByServer
            }
            ChatViewModel.RelayRegistration.Failed -> Outcome.Rejected
        }
    }

    /** Null when notifications may be posted; otherwise why not. */
    private suspend fun permission(mayPrompt: Boolean): Outcome? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime grant before Android 13; the user can still switch the app's notifications off.
            return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) null else Outcome.Denied
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return null
        }
        // Asked once, as iOS asks once. Android would allow a second ask after a refusal, but a prompt
        // on the next launch after "no" is nagging; system settings is the way back, and the next
        // foreground notices the grant.
        if (prefs.getBoolean(KEY_ASKED, false)) return Outcome.Denied
        val prompt = permissionPrompt?.takeIf { mayPrompt } ?: return Outcome.Deferred
        prefs.edit { putBoolean(KEY_ASKED, true) }
        val answer = CompletableDeferred<Boolean>()
        permissionAnswer = answer
        prompt()
        return if (answer.await()) null else Outcome.Denied
    }

    private fun log(outcome: Outcome) {
        when (outcome) {
            Outcome.Registered, Outcome.Deferred -> Unit
            Outcome.Rejected -> Log.w(TAG, "the server rejected this device's push registration")
            // Expected on a self-hosted server until its admin turns on push.lurker.chat.
            Outcome.UnsupportedByServer -> Log.i(TAG, "this server can't push to the app (no FCM, no relay); not registering")
            // Worded so nobody reads it and goes auditing LURKER_FCM_* on a healthy server.
            Outcome.ServerUnreachable -> Log.i(TAG, "couldn't reach the server to ask about push; retrying later")
            Outcome.Denied -> Log.i(TAG, "notifications are off for this app")
            Outcome.NoFirebase -> Log.i(TAG, "built without google-services.json; push is off")
            is Outcome.NoPlayServices -> Log.i(TAG, "no Google Play services (code ${outcome.code}); push is off")
            is Outcome.Failed -> Log.w(TAG, "could not enable push: ${outcome.reason}")
        }
    }

    private companion object {
        const val TAG = "Lurker.push"
        const val PREFS_FILE = "push"
        const val KEY_ASKED = "askedPermission"
    }
}

