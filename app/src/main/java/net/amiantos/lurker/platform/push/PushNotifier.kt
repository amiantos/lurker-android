// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.amiantos.lurker.MainActivity
import net.amiantos.lurker.R

/**
 * Draws a [PushMessage] as a system notification (lurker-android#16).
 *
 * One notification per buffer: posted under the server's `tag`, so the next push for the same buffer
 * replaces this one rather than stacking — the same role as APNs' thread-id and the Notification
 * API's tag. The server gives a kick and a came-online their own tags, so neither replaces a message.
 */
object PushNotifier {
    /** Every notification shares this id; the tag is what tells them apart. */
    private const val NOTIFICATION_ID = 1

    /**
     * The action on a tap's intent, suffixed with the tag. `MainActivity` reads the extras, not this:
     * it's what keeps two buffers' taps apart. PendingIntents whose intents differ only in extras are
     * the SAME PendingIntent to the system, so without a per-buffer action every notification's tap
     * would point at the latest buffer. (Not a request code from the tag's hash: two tags can share
     * one. Not a data URI: `MainActivity` reads intent data as the sign-in redirect.)
     */
    private const val ACTION_OPEN = "net.amiantos.lurker.OPEN_NOTIFICATION"

    /**
     * Create (or update the names of) one channel per kind. Idempotent, and cheap enough for every
     * launch, which is how a renamed channel reaches existing installs. The user's own settings on
     * a channel are theirs: creating an existing one never resets its importance.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            PushMessage.Kind.entries.map { kind ->
                val importance = if (kind.urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
                NotificationChannel(kind.channelId, kind.channelName, importance)
            },
        )
    }

    fun show(context: Context, message: PushMessage) {
        // Unanswered or refused (Android 13+): nothing can be posted. PushRegistrar doesn't register
        // a device without it, so this is a grant revoked after registering.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tap = Intent(context, MainActivity::class.java).setAction("$ACTION_OPEN/${message.tag}")
        message.tap.forEach { (key, value) -> tap.putExtra(key, value) }
        val open = PendingIntent.getActivity(
            context,
            0,
            tap,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, message.kind.channelId)
            .setSmallIcon(R.drawable.ic_stat_lurker)
            .setContentTitle(message.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(
                if (message.kind == PushMessage.Kind.FRIEND_ONLINE) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_MESSAGE,
            )
        if (message.body.isNotEmpty()) {
            builder.setContentText(message.body).setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
        }
        message.sentAt?.let { builder.setWhen(it).setShowWhen(true) }
        // No `setNumber`: a launcher that shows a count sums its notifications' numbers, so the
        // server's account-wide total on each one would count it once per buffer. Left at the default,
        // the icon counts buffers with something new, and goes down as they're read (`clearRead`).
        NotificationManagerCompat.from(context).notify(message.tag, NOTIFICATION_ID, builder.build())
    }

    /**
     * The buffers [bufferIds] (`BufferKey.id`) were read, here or on another device: take their
     * notifications down, which is what brings a launcher's badge down with them (see
     * [ReadTransitions]). A message notification's tag is the server's `"<networkId>::<target>"` —
     * the id's shape before the fold — so it's matched folded. A kick's and a came-online's tags carry
     * a suffix and stay: reading the channel doesn't answer either.
     */
    fun clearRead(context: Context, bufferIds: Set<String>) {
        if (bufferIds.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (posted in manager.activeNotifications) {
            val tag = posted.tag ?: continue
            if (posted.id == NOTIFICATION_ID && tag.lowercase() in bufferIds) manager.cancel(tag, NOTIFICATION_ID)
        }
    }

    /** Sign-out: the previous account's messages must not stay on the lock screen for the next one. */
    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
