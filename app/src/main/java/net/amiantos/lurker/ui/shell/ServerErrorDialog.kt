// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * A server error, said once in a dialog until it's acknowledged — iOS's `surface(_:)`.
 *
 * Hosted by `MainScaffold`, which is composed whatever pane is showing: iOS surfaces it over
 * whichever screen is up, and an error held by the conversation alone sat unseen behind the buffer
 * list on a phone, then popped up later over a conversation it had nothing to do with.
 *
 * Its own stream: it moves nothing else on screen. Acknowledging clears it in the store, which is
 * what lets the same error come back as news rather than be dropped as a duplicate.
 */
@Composable
fun ServerErrorDialog(model: ChatViewModel) {
    val errorFlow = remember(model) { model.statePublisher.conflate().map { it.error }.distinctUntilChanged() }
    val error by errorFlow.collectAsStateWithLifecycle(initialValue = model.state.error)
    error?.let { message ->
        AlertDialog(
            // Back and a tap outside acknowledge it too — Android's dialog convention, where iOS's
            // alert has only its button.
            onDismissRequest = model::clearError,
            confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } },
            text = { Text(message) },
        )
    }
}
