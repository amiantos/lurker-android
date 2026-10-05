// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import net.amiantos.lurker.LurkerApp
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Where FCM delivers (lurker-android#16). The server sends data-only messages, so this runs for every
 * push whether the app is in the foreground or not, and the notification is ours to draw.
 *
 * Both callbacks run on a worker thread.
 */
class LurkerMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // Signed out, nothing is shown: sign-out deletes this install's token (`PushRegistrar.signedOut`),
        // but a push already in flight, or one sent before the delete reached FCM, still lands here,
        // and it's the previous account's. (`session` is safe to read off the main thread.)
        val app = application as LurkerApp
        if (app.model.session != ChatViewModel.SessionState.LoggedIn) return
        // Someone looking at the app sees the message there. The server already holds pushes while a
        // client reports itself visible; this covers one that was in flight as the app came forward —
        // iOS's `willPresent`, which shows nothing but the badge.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val push = PushMessage.parse(message.data) ?: return
        PushNotifier.show(this, push)
    }

    /**
     * FCM rotated this install's token. Register the new one if we're signed in and allowed to post —
     * without prompting: no screen is up to ask on.
     */
    override fun onNewToken(token: String) {
        val app = application as LurkerApp
        app.scope.launch { app.push.enableIfSignedIn(mayPrompt = false) }
    }
}
