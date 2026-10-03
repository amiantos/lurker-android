// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.ui.platform.UriHandler

/**
 * The platform's link opener, minus the crash when nothing on the device takes the link — a `mailto:`
 * with no mail app, an `ftp://`. A tap that does nothing is better than a crash.
 *
 * ⚠ Both exceptions: Compose's `AndroidUriHandler` catches the platform's `ActivityNotFoundException`
 * and rethrows it as an `IllegalArgumentException`. Shared by every surface that opens links — the
 * conversation, and the media viewer, whose dialog sits outside the conversation's provider.
 */
internal class SafeUriHandler(private val platform: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        try {
            platform.openUri(uri)
        } catch (e: ActivityNotFoundException) {
            Log.w("Lurker", "no app opens $uri", e)
        } catch (e: IllegalArgumentException) {
            Log.w("Lurker", "can't open $uri", e)
        }
    }
}
