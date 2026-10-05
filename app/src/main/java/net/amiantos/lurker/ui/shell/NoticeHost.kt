// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurker.platform.AppEvents

/**
 * Shows the app's notices (`AppEvent.Notice`) as snackbars — iOS's `ToastView`, which goes on the
 * sheet on top if there is one. Every surface that can be on top hosts one (`MainScaffold`, each
 * full-screen dialog); only the one that claimed last shows, so a notice lands where the user is
 * looking rather than in a window under a dialog.
 *
 * A notice leaves the queue only after it has been shown out: a rotation, or a dialog opening over
 * it, cancels the showing and the next host shows it again.
 */
@Composable
fun NoticeHost(events: AppEvents, modifier: Modifier = Modifier) {
    val token = remember { Any() }
    DisposableEffect(events) {
        events.claimNotices(token)
        onDispose { events.releaseNotices(token) }
    }
    val hosts by events.noticeHosts.collectAsStateWithLifecycle()
    val notices by events.notices.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val head = notices.firstOrNull()
    val showing = hosts.lastOrNull() === token
    LaunchedEffect(head, showing) {
        if (head != null && showing) {
            val action = head.action
            val result = snackbar.showSnackbar(
                head.message,
                actionLabel = action?.label,
                // Long enough to reach for the button. Not Indefinite, the default with an action:
                // an invitation nobody answers is still in the system buffer.
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            events.consume(head)
            if (result == SnackbarResult.ActionPerformed) action?.run()
        }
    }
    SnackbarHost(snackbar, modifier)
}
