// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurker.platform.findActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import net.amiantos.lurkerkit.session.ChatViewModel
import java.util.UUID

/**
 * Which of this slice's full-screen dialogs is up, if any: the networks list, Add Network, or Join
 * Channel. The buffer list opens them; [NetworkSheetsHost] draws them.
 *
 * One at a time — the dialog covers everything that could open another.
 */
@Stable
class NetworkSheets internal constructor(private val open: MutableState<String?>) {
    /** The networks list — iOS's Settings → Networks. */
    fun showNetworks() {
        open.value = "${NetworksStart.List.name}:${UUID.randomUUID()}"
    }

    /** The preset picker, then the form — iOS's `showAddNetwork`. */
    fun showAddNetwork() {
        open.value = "${NetworksStart.AddNetwork.name}:${UUID.randomUUID()}"
    }

    fun showJoinChannel() {
        open.value = JOIN
    }

    /**
     * Close whatever is open, discarding its state — iOS's `dismissPresented`, which `land(on:)` runs
     * before opening a buffer a join or a DCC chat asked for.
     */
    fun dismiss() {
        current = null
    }

    internal var current: String?
        get() = open.value
        set(value) {
            open.value = value
        }

    internal companion object {
        const val JOIN = "Join"
    }
}

/**
 * The dialogs' open/closed state, saved so a rotation keeps the dialog up. What's saved is only
 * which dialog and a token for its flow — never its contents (see [NetworksFlow]).
 */
@Composable
fun rememberNetworkSheets(): NetworkSheets {
    val open = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(open) { NetworkSheets(open) }
}

/**
 * Draws whichever dialog [sheets] has open.
 *
 * @param onJoin a channel name from Join Channel, already checked and sigil-prefixed, and the
 *   network to join it on — see [JoinChannelModel.channelToSend]. The dialog has closed by the time
 *   this runs, as iOS dismisses before handing over: the caller navigates to the new buffer, and a
 *   screen arriving under a dialog still on its way out is an animation fighting itself.
 */
@Composable
fun NetworkSheetsHost(sheets: NetworkSheets, model: ChatViewModel, onJoin: (networkId: Int, channel: String) -> Unit) {
    val current = sheets.current ?: return
    if (current == NetworkSheets.JOIN) {
        JoinChannelDialog(
            model = model,
            onDismiss = { sheets.current = null },
            onJoin = { networkId, channel ->
                sheets.current = null
                onJoin(networkId, channel)
            },
        )
        return
    }
    val start = NetworksStart.entries.firstOrNull { current.startsWith("${it.name}:") }
    if (start == null) {
        // A token this build doesn't know (restored from an older one): nothing to draw.
        LaunchedEffect(current) { sheets.current = null }
        return
    }
    val store: NetworksFlowStore = viewModel()
    val flow = store.flow(current) { NetworksFlow(model, start) }
    val activity = LocalContext.current.findActivity()
    // ⚠ Dropped when the dialog closes, and when this host leaves composition for good (sign-out
    // swaps the whole scaffold out) — but NOT across a configuration change, which is the one
    // disposal the flow exists to survive.
    DisposableEffect(current) {
        onDispose {
            if (sheets.current != current || activity?.isChangingConfigurations != true) store.discard(current)
        }
    }
    NetworksDialog(
        model = model,
        flow = flow,
        onDismiss = {
            sheets.current = null
            store.discard(current)
        },
    )
}

/**
 * A networks dialog: its page stack, drawn top page only. Back pops a pushed page (predictive back
 * included), then dismisses. Inner navigation is the flow's own, not the app's navigator: it's a
 * detour inside one dialog, with nothing in it a deep link or the system's back stack should know.
 */
@Composable
private fun NetworksDialog(model: ChatViewModel, flow: NetworksFlow, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(flow.finished) { if (flow.finished) dismiss() }
    FullScreenDialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = flow.pages.size > 1) { flow.back() }
        val depth = flow.pages.size
        AnimatedContent(
            targetState = depth to flow.pages.last(),
            transitionSpec = {
                // A push slides in from the end, a pop back from the start — the platform's own
                // forward and back.
                val forward = targetState.first >= initialState.first
                (slideInHorizontally { width -> if (forward) width / 4 else -width / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally { width -> if (forward) -width / 4 else width / 4 } + fadeOut())
            },
            label = "networks page",
        ) { (pageDepth, page) ->
            val isRoot = pageDepth == 1
            when (page) {
                is NetworksPage.List -> NetworksListPage(
                    model = model,
                    state = page.state,
                    onClose = onDismiss,
                    onAdd = flow::pushPicker,
                    onEdit = flow::pushEdit,
                )
                is NetworksPage.Picker -> NetworkPickerPage(
                    state = page.state,
                    exit = if (isRoot) PageExit.Close else PageExit.Back,
                    onExit = { if (!flow.back()) onDismiss() },
                    onPicked = flow::pushAdd,
                )
                is NetworksPage.Form -> NetworkFormPage(state = page.state, onBack = { flow.back() })
            }
        }
    }
}

