// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.amiantos.lurker.prefs.UiPreferences
import net.amiantos.lurker.ui.list.BufferListScreen
import net.amiantos.lurkerkit.model.Buffer
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
 *
 * Built fresh for every session: `AppRoot` swaps it out on sign-out, which is what resets the
 * burst latch and the launch restore below without either having to notice a session change.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScaffold(model: ChatViewModel, uiPreferences: UiPreferences, onSignOut: () -> Unit) {
    // The content key is the buffer the detail pane shows, in parts a Bundle can hold — the
    // navigator saves its history, so the conversation survives rotation and process death.
    val navigator = rememberListDetailPaneScaffoldNavigator<BufferRoute>(scaffoldDirective = lurkerPaneDirective())
    val scope = rememberCoroutineScope()

    // Whether a settled list has been drawn this session — the buffer list's burst gate
    // (`BufferListModel.drawsList`). Here rather than in the list because on a phone the list pane
    // leaves composition while a conversation is shown, and a configuration change rebuilds it; a
    // latch that reset with either would blank a populated list back to "Loading buffers…" on the
    // way back to it.
    //
    // ⚠ Saved as the process it was set in, not as a Boolean. A saved `true` would also come back
    // after process death — a fresh connect, whose burst is exactly what the gate exists for — so
    // the list would assemble itself in front of the reader on every cold restore. The token
    // survives a configuration change (same process) and lapses with the process.
    var latchedIn by rememberSaveable { mutableStateOf<String?>(null) }
    val hasRenderedList = latchedIn == processToken

    // Launch restore (lurker-ios#49): signing in lands on the list, with the buffer you were last
    // reading opened over it when there is one — so a returning user is back in their conversation
    // immediately, and back is right there when they aren't where they wanted to be, which is what
    // makes going straight in safe.
    //
    // Once per scaffold, not per composition: `restored` is saved, so neither a rotation nor a
    // return to the list pushes anyone back in, and a user who backed out to the list and then
    // rotated stays on the list. A navigator that already has a destination (restored from saved
    // state) is left alone.
    //
    // The buffer is synthesized from the stored key: the store is empty at launch, and the
    // conversation hydrates it once its frames land. Probing for it first would be wrong — the
    // server reads an `open-buffer` for a `#channel` with no row as a request to JOIN it, so a
    // probe would silently re-join a channel you left on purpose. U2: a buffer that gets no frame by
    // the end of the burst (`ChatState.rosterSettled`) sends the reader back to the list instead of
    // spinning — iOS's `handleBufferDisappeared`.
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (restored) return@LaunchedEffect
        restored = true
        if (navigator.currentDestination?.contentKey != null) return@LaunchedEffect
        val key = uiPreferences.lastOpenBufferKey ?: return@LaunchedEffect
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, BufferRoute.of(key))
    }

    // Side by side — the list beside the conversation rather than instead of it. iOS's
    // `marksOpenBuffer`: it gates the list's open mark and decides which pane carries the banner.
    val value = navigator.scaffoldValue
    val sideBySide = value[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
        value[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val openRoute = navigator.currentDestination?.contentKey

    // Opening is navigation plus the record of where a relaunch should land (lurker-ios#49).
    // Recorded on the pick here; U2 moves this to the conversation's appearance (iOS's
    // `recordVisit`), so a launch restore, a notification tap or a join counts too, and owns the
    // other writer iOS has — forgetting it when you back out to the list (`viewDidDisappear`), so
    // the list is where a relaunch lands after you left a conversation on purpose. Sign-out's
    // forget is `LurkerApp`'s, a rename's is `LurkerApp`'s, and a close's is below.
    fun open(buffer: Buffer) {
        uiPreferences.recordLastOpenBuffer(buffer.key)
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, BufferRoute.of(buffer.key)) }
    }

    // Leave a channel / close a DM. Shared by the swipe and the menu rather than written twice:
    // the forget half is easy to leave out of a second copy and impossible to notice missing until
    // a relaunch strands someone on a spinner. Closing here is the one moment the client *knows* a
    // buffer is gone; restoring into one that isn't there lands on a spinner, and the launch path
    // can't detect it — so tell it.
    //
    // If it's the buffer open in the detail pane, it's left there: U2 owns "the buffer
    // disappeared" (iOS's `handleBufferDisappeared`), which is a decision about the conversation,
    // not the list.
    fun close(buffer: Buffer) {
        model.closeBuffer(buffer.key)
        uiPreferences.forgetLastOpenBuffer(ifMatching = buffer.key)
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                BufferListScreen(
                    model = model,
                    hasRenderedList = hasRenderedList,
                    onListRendered = { latchedIn = processToken },
                    openKey = openRoute?.key,
                    sideBySide = sideBySide,
                    onOpen = ::open,
                    onClose = ::close,
                    onSignOut = onSignOut,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                // U2: the conversation replaces this. iOS never shows an empty column — with
                // nothing picked it rests on the system buffer, the app's own log — and U2 should
                // do the same rather than keep a placeholder whose whole job is dead space.
                DetailPanePlaceholder(model = model, route = openRoute)
            }
        },
    )
}

/** Unique to this process — what tells saved state written before a process death from our own. */
private val processToken: String = java.util.UUID.randomUUID().toString()

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

/**
 * U2: replaced by the conversation. The open buffer's name, or "Pick a buffer." with nothing open —
 * and the connection banner, which this pane carries whenever it's shown: alone on a phone it is
 * the screen in front of you, and side by side the list yields its banner to it.
 */
@Composable
private fun DetailPanePlaceholder(model: ChatViewModel, route: BufferRoute?) {
    // Mapped to the one string before it becomes state — never a raw `ChatState` (see
    // `BufferListInputs`).
    fun name(state: ChatState): String? {
        val key = route?.key ?: return null
        val networkName = key.networkId?.let { state.networks[it]?.displayName }
        return state.buffer(key).displayName(networkName)
    }
    val nameFlow = remember(model, route) { model.statePublisher.map(::name).distinctUntilChanged() }
    val initialName = remember(model, route) { name(model.state) }
    val shownName by nameFlow.collectAsStateWithLifecycle(initialValue = initialName)
    val banner = rememberConnectionBannerState(model)
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Text(
                shownName ?: "Pick a buffer.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectionBanner(
                state = banner,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}
