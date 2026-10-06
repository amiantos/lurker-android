// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.amiantos.lurker.LurkerApp
import net.amiantos.lurkerkit.push.RelayPush
import net.amiantos.lurkerkit.push.WebPushDecrypt
import net.amiantos.lurkerkit.session.ChatViewModel
import java.util.Base64

/**
 * Where FCM delivers (lurker-android#16). The server sends data-only messages, so this runs for every
 * push whether the app is in the foreground or not, and the notification is ours to draw.
 *
 * Both callbacks run on a worker thread.
 */
class LurkerMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as LurkerApp
        // Anything at all thrown reading a push drops that push: the body is the network's input,
        // and nothing from it may escape FCM's callback and take the process down.
        val push = try {
            val data = message.data["p"]?.let { opened(app, it) ?: return } ?: message.data
            PushMessage.parse(data) ?: return
        } catch (t: Throwable) {
            Log.w(TAG, "dropped a push that couldn't be read: ${t.javaClass.simpleName}")
            return
        }
        // On the main thread, where sign-out clears notifications (`LurkerApp.observeSession`): checked
        // and posted there, a sign-out can't land between the check and the post and leave the
        // departing account's message on the lock screen. Blocking this worker briefly is fine; it's
        // FCM's thread for exactly this.
        runBlocking(Dispatchers.Main) {
            // Signed out, nothing is shown: sign-out deletes the token (`PushRegistrar.signedOut`), but a
            // push already in flight, or sent before the delete reached FCM, still lands here.
            if (app.model.session != ChatViewModel.SessionState.LoggedIn) return@runBlocking
            // Someone looking at the app sees the message there. The server already holds pushes while
            // a client reports itself visible; this covers one in flight as the app came forward —
            // iOS's `willPresent`, which shows nothing but the badge.
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@runBlocking
            // Already read: FCM can hold a push (Doze) past the read on another device, and nothing would
            // take a notification posted now back down.
            if (push.readIn(app.model.state)) return@runBlocking
            PushNotifier.show(app, push)
        }
    }

    /**
     * A push relayed through push.lurker.chat (lurker-dev/RELAY_PLAN.md §6.2): the server's Web Push
     * body, still encrypted for this install, in `p`. Opened with this install's keys and flattened
     * to the map a direct push carries, so everything after reads both the same. Null — the push is
     * dropped — when it can't be opened: no keys (they were lost; the next foreground files new
     * ones), or a body that isn't ours.
     */
    private fun opened(app: LurkerApp, p: String): Map<String, String>? {
        val keys = app.pushKeys.load() ?: return null.also { Log.w(TAG, "relayed push, but no keys to open it") }
        val body = runCatching { Base64.getUrlDecoder().decode(p) }.getOrNull()
        val plain = body?.let { WebPushDecrypt.decrypt(it, keys) }
            ?: return null.also { Log.w(TAG, "couldn't open a relayed push") }
        return RelayPush.flatten(plain.decodeToString())
    }

    /**
     * FCM rotated this install's token. Register the new one if we're signed in and allowed to post —
     * without prompting: no screen is up to ask on.
     */
    override fun onNewToken(token: String) {
        val app = application as LurkerApp
        app.scope.launch { app.push.enableIfSignedIn(mayPrompt = false) }
    }

    private companion object {
        const val TAG = "Lurker.push"
    }
}
