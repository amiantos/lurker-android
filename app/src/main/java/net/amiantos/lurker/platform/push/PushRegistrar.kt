// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.amiantos.lurkerkit.session.ChatViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 * App-scoped (built by `LurkerApp`) because a token rotation arrives in the messaging service with no
 * activity up. The permission prompt is the one step that needs an activity, so `MainActivity` lends
 * one ([permissionPrompt]) while it's started, and hands the answer back ([onPermissionResult]) —
 * from whichever instance is up when it comes, so a rotation mid-prompt doesn't lose it.
 */
class PushRegistrar(
    private val context: Context,
    private val model: ChatViewModel,
    private val scope: CoroutineScope,
) {
    sealed interface Outcome {
        /** The server has this install's token. */
        data object Registered : Outcome

        /** The server answered, and won't take the token. */
        data object Rejected : Outcome

        /** The server answered, and can't deliver FCM (self-hosted, or older than lurker#490). */
        data object UnsupportedByServer : Outcome

        /** We couldn't ask the server. Transient, and says nothing about its configuration. */
        data object ServerUnreachable : Outcome

        /** Notifications are off for this app, by the user's answer or in system settings. */
        data object Denied : Outcome

        /** Permission is unanswered and there was no screen to ask on; the next foreground asks. */
        data object Deferred : Outcome

        /** Built without google-services.json — CI, or someone's own build. Push is off, by design. */
        data object NoFirebase : Outcome

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

    /**
     * In flight, if any. Two paths want to enable push at once on a restored launch — the session
     * publisher replaying `LoggedIn` and the activity starting — and each would otherwise do the full
     * round trip.
     */
    private var inFlight: Job? = null

    /**
     * Run the sequence, once signed in and never before: a prompt on the sign-in screen asks the user
     * to authorize notifications for an account they haven't named yet. Safe to call on every
     * foreground — FCM re-issues the same token, the server upserts, and a granted permission doesn't
     * re-prompt. [mayPrompt] is false where there is no screen to ask on (a token rotation).
     */
    fun enableIfSignedIn(mayPrompt: Boolean) {
        if (model.session != ChatViewModel.SessionState.LoggedIn || inFlight?.isActive == true) return
        inFlight = scope.launch { log(enable(mayPrompt)) }
    }

    private suspend fun enable(mayPrompt: Boolean): Outcome {
        if (FirebaseApp.getApps(context).isEmpty()) return Outcome.NoFirebase
        val supported = model.serverSupportsAPNs() ?: return Outcome.ServerUnreachable
        if (!supported) return Outcome.UnsupportedByServer
        permission(mayPrompt)?.let { return it }
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        return if (model.registerPushDevice(token)) Outcome.Registered else Outcome.Rejected
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
            Outcome.Rejected -> Log.w(TAG, "the server rejected this device token")
            // Expected on a self-hosted server: only the app's publisher can push to it.
            Outcome.UnsupportedByServer -> Log.i(TAG, "this server delivers Web Push only, not FCM; not registering")
            // Worded so nobody reads it and goes auditing LURKER_FCM_* on a healthy server.
            Outcome.ServerUnreachable -> Log.i(TAG, "couldn't reach the server to ask about push; retrying later")
            Outcome.Denied -> Log.i(TAG, "notifications are off for this app")
            Outcome.NoFirebase -> Log.i(TAG, "built without google-services.json; push is off")
            is Outcome.Failed -> Log.w(TAG, "could not enable push: ${outcome.reason}")
        }
    }

    private companion object {
        const val TAG = "Lurker.push"
        const val PREFS_FILE = "push"
        const val KEY_ASKED = "askedPermission"
    }
}

/** A Play services [Task] as a suspend call — the one place this app awaits one. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
