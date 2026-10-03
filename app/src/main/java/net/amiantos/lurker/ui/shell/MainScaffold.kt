// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState

/**
 * The app proper: the buffer list and the conversation, side by side wherever there's room for
 * both and one at a time wherever there isn't — one code path, so a foldable opening or a window
 * resizing rearranges the panes without the app swapping its root, and the conversation rides
 * across. lurker-ios's `BufferSplitViewController`.
 *
 * Back from the conversation returns to the list through the navigator, with predictive back:
 * `NavigableListDetailPaneScaffold` installs the handler, enabled only while there is a pane to
 * go back from, so back on the list itself leaves the app as the platform expects.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScaffold(model: ChatViewModel, onSignOut: () -> Unit) {
    // U1: the content key is what the list hands the detail pane — a buffer's key, in parts that
    // survive a Bundle (the navigator saves its history). A placeholder type until then.
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = lurkerPaneDirective())
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                // U1: the buffer list replaces this.
                ListPanePlaceholder(model = model, onSignOut = onSignOut)
            }
        },
        detailPane = {
            AnimatedPane {
                // U2: the conversation replaces this. iOS never shows an empty column — with
                // nothing picked it rests on the system buffer, the app's own log — and U2 should
                // do the same rather than keep a placeholder whose whole job is dead space.
                DetailPanePlaceholder()
            }
        },
    )
}

/**
 * One pane or two. Two at regular × regular — width at least Medium (600dp) *and* height at least
 * Medium (480dp) — which is iOS's rule for expanding its split view (an iPad, an unfolded iPhone
 * Duo), mapped onto Android's size classes. So an unfolded foldable or a tablet in either
 * orientation gets two; a phone gets one, including a large phone in landscape, whose width alone
 * would pass but whose height is compact — iOS holds a Pro Max in landscape collapsed for the same
 * reason. Material's own default waits for Expanded width (840dp), which would leave an unfolded
 * foldable in portrait with one.
 *
 * Everything else — the gutter, the hinge avoidance, the preferred pane width — is Material's.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun lurkerPaneDirective(): PaneScaffoldDirective {
    val info = currentWindowAdaptiveInfo()
    val material = calculatePaneScaffoldDirective(info)
    val sizeClass = info.windowSizeClass
    val regularRegular =
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
            sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    return if (regularRegular) {
        material.copy(
            maxHorizontalPartitions = maxOf(material.maxHorizontalPartitions, 2),
            horizontalPartitionSpacerSize = 24.dp,
        )
    } else {
        material.copy(maxHorizontalPartitions = 1, horizontalPartitionSpacerSize = 0.dp)
    }
}

/** U1: replaced by the buffer list. For now: Lurker's connection in words, and a way out. */
@Composable
private fun ListPanePlaceholder(model: ChatViewModel, onSignOut: () -> Unit) {
    // ⚠ Mapped to the one value this reads before it becomes Compose state. `statePublisher`
    // publishes the whole `ChatState` on every frame, and a `State<ChatState>` would compare each
    // new one to the last with a data class's deep `equals` — every buffer's messages, every frame.
    // U1 and U2 should read the store the same way: map to what the screen draws, then distinct.
    fun banner(state: ChatState) = ConnectionBannerState.of(reachable = state.reachable, connection = state.connection)
    val connectionFlow = remember(model) { model.statePublisher.map(::banner).distinctUntilChanged() }
    val connection by connectionFlow.collectAsStateWithLifecycle(initialValue = banner(model.state))
    ListPaneContent(connection = connection, onSignOut = onSignOut)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPaneContent(connection: ConnectionBannerState, onSignOut: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lurker") },
                actions = { TextButton(onClick = { confirming = true }) { Text("Sign Out") } },
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier.padding(padding).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Working states spin, in the warn colour, as iOS's banner does; the words say the
            // same thing on their own.
            if (connection.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = LurkerTheme.colors.warn,
                    strokeWidth = 2.dp,
                )
            }
            Text(connectionWords(connection), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (confirming) {
        // Sign-out asks first: it ends the session on the server, and the way back in is a password
        // the user may not have to hand. iOS's copy.
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out of Lurker?") },
            text = { Text("You'll need your password to sign back in.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onSignOut()
                    },
                ) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** U2: replaced by the conversation. */
@Composable
private fun DetailPanePlaceholder() {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(
                "Pick a buffer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "List pane — light, reconnecting")
@Composable
private fun ListPanePreviewLight() {
    LurkerTheme(darkTheme = false) { ListPaneContent(ConnectionBannerState.Reconnecting, onSignOut = {}) }
}

@Preview(name = "List pane — dark, offline")
@Composable
private fun ListPanePreviewDark() {
    LurkerTheme(darkTheme = true) { ListPaneContent(ConnectionBannerState.Offline, onSignOut = {}) }
}
