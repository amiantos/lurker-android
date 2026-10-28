// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.ui.platform.UriHandler
import net.amiantos.lurkerkit.model.ExternalLink

/**
 * The one door a link leaves the app through: the platform's opener behind `ExternalLink`'s
 * allowlist, minus the crash when nothing on the device takes the link.
 *
 * Every tap, menu action and "open in browser" comes here rather than to the platform directly, so
 * the allowlist is a fact about the app and not about whichever call sites happened to remember it
 * (the audit, lurker-ios#245; iOS's `UIApplication.openExternal`). A refused scheme does nothing:
 * the tap on an `ftp://`, `tel:`, `intent:` or `javascript:` link is a tap on text. Nothing the app
 * renders as a link legitimately needs another scheme than the web's or mail's.
 *
 * ⚠ Both exceptions: Compose's `AndroidUriHandler` catches the platform's `ActivityNotFoundException`
 * and rethrows it as an `IllegalArgumentException`. Shared by every surface that opens links — the
 * conversation, the uploads page, and the media viewer, whose dialog sits outside the conversation's
 * provider.
 */
internal class SafeUriHandler(private val platform: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        if (!ExternalLink.isOpenable(uri)) return
        try {
            platform.openUri(uri)
        } catch (e: ActivityNotFoundException) {
            Log.w("Lurker", "no app opens $uri", e)
        } catch (e: IllegalArgumentException) {
            Log.w("Lurker", "can't open $uri", e)
        }
    }
}
