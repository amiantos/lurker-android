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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.AppEvents

/**
 * Shows the app's notices (`AppEvent.Notice`) as snackbars — iOS's `ToastView`, which goes on the
 * sheet on top if there is one. Every surface that can be on top hosts one (`MainScaffold`, the
 * conversation, each full-screen dialog); only the one that claimed highest shows, so a notice lands
 * where the user is looking rather than in a window under a dialog.
 *
 * A notice leaves the queue only after it has been shown out: a rotation, or a dialog opening over
 * it, cancels the showing and the next host shows it again.
 *
 * @param priority where this host stands among the others — see `AppEvents.noticeHosts`.
 * @param intercept offered each notice first; true means it was shown some other way (the
 *   conversation's status row, lurker#1098) and is consumed without a snackbar.
 */
@Composable
fun NoticeHost(
    events: AppEvents,
    modifier: Modifier = Modifier,
    priority: Int = NoticeHost.PRIORITY_SCAFFOLD,
    intercept: ((AppEvent.Notice) -> Boolean)? = null,
) {
    val token = remember { Any() }
    DisposableEffect(events, priority) {
        events.claimNotices(token, priority)
        onDispose { events.releaseNotices(token) }
    }
    val hosts by events.noticeHosts.collectAsStateWithLifecycle()
    val notices by events.notices.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val currentIntercept by rememberUpdatedState(intercept)
    val head = notices.firstOrNull()
    val showing = hosts.lastOrNull()?.host === token
    LaunchedEffect(head, showing) {
        if (head != null && showing) {
            if (currentIntercept?.invoke(head) == true) {
                events.consume(head)
                return@LaunchedEffect
            }
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

/** The hosts' standing, lowest first: the scaffold's, under a conversation's, under a dialog's. */
object NoticeHost {
    const val PRIORITY_SCAFFOLD = 0
    const val PRIORITY_CONVERSATION = 1
    const val PRIORITY_DIALOG = 2
}
