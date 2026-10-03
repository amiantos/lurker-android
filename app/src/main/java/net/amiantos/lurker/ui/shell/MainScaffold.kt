// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.AppEvents
import net.amiantos.lurker.prefs.UiPreferences
import net.amiantos.lurker.ui.conversation.ConversationScreen
import net.amiantos.lurker.ui.list.BufferListModel
import net.amiantos.lurker.ui.list.BufferListScreen
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.session.ChatViewModel

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
fun MainScaffold(model: ChatViewModel, uiPreferences: UiPreferences, events: AppEvents, onSignOut: () -> Unit) {
    // The content key is the buffer the detail pane shows, in parts a Bundle can hold — the
    // navigator saves its history, so the conversation survives rotation and process death.
    val navigator = rememberListDetailPaneScaffoldNavigator<BufferRoute>(scaffoldDirective = lurkerPaneDirective())
    val scope = rememberCoroutineScope()

    // One navigation at a time. Every move is a suspend call that animates, and two interleaved
    // ones corrupt the history: a pop that finds nothing left to pop CLEARS it (the navigator's
    // `navigateBack` with no previous destination), so a replace racing a leave could strand the
    // scaffold with no destination at all.
    val navigation = remember { Mutex() }
    fun navigate(move: suspend () -> Unit) {
        scope.launch { navigation.withLock { move() } }
    }

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

    // Gives up waiting for `backlog-complete` and draws whatever has arrived.
    //
    // ⚠⚠ Not belt-and-braces — the terminator genuinely may not come. It was added as an ADDITIVE
    // frame with no protocol-version bump and no capability signal (lurker#640), so a self-hosted
    // server older than it simply never sends one, and this is a product whose operators upgrade on
    // their own schedule. A current server withholds it too when a burst throws part-way, which is
    // deliberate: it is emitted from inside `sendSnapshotInner` precisely so a failed burst isn't
    // declared complete.
    //
    // Without this, waiting for it would trade a flicker for a permanent spinner over a fully
    // populated store — a far worse trade. The wait is the optimization; drawing is the correct
    // behaviour, so the fallback is the one that has to be unconditional.
    //
    // Here, for the session, rather than in the list pane: the pane leaves composition whenever a
    // conversation covers it on a phone — from the first moment, after a launch restore — and a
    // timer that restarted with it would never fire for a list that's never looked at, then make
    // each return to it wait the full four seconds again.
    //
    // The deadline is saved beside the latch, under the same process token: a rotation recreates
    // this effect, and a fresh four seconds each time would let repeated rotations hold an old
    // server's list off indefinitely. Recreated, it waits only what's left; after process death the
    // token doesn't match and a fresh connect gets a fresh wait.
    var fallbackAt by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (hasRenderedList) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        val saved = fallbackAt?.takeIf { it.startsWith("$processToken@") }?.substringAfter('@')?.toLongOrNull()
        val deadline = saved ?: (now + BufferListModel.BURST_WAIT_MS).also { fallbackAt = "$processToken@$it" }
        delay((deadline - now).coerceAtLeast(0))
        latchedIn = processToken
    }

    // A rename the open conversation followed (lurker-ios's `followRename`): the history still holds
    // the route it was opened under, and this says what that route is called now. Not a navigation —
    // a replace on a phone would animate the conversation out and back in under a reader who did
    // nothing, for a peer's nick change. Saved, so it survives rotation with the history it amends.
    //
    // Scoped to that one history entry: any navigation of ours clears it, because an alias left
    // behind would redirect a later open of a NEW buffer that happens to reuse the old name.
    var renamedFrom by rememberSaveable { mutableStateOf<BufferRoute?>(null) }
    var renamedTo by rememberSaveable { mutableStateOf<BufferRoute?>(null) }
    fun clearRename() {
        renamedFrom = null
        renamedTo = null
    }

    // A side-by-side pick in flight. The replace below passes through the list destination, and the
    // detail pane would otherwise show the system buffer for the frame in between.
    var replacing by remember { mutableStateOf<BufferRoute?>(null) }

    // Read through functions rather than captured as values: the event collector below is started
    // once and calls these for the life of the scaffold, and a value captured on its first
    // composition would be the layout and the route of the first frame forever.

    // Side by side — the list beside the conversation rather than instead of it. iOS's
    // `marksOpenBuffer`: it gates the list's open mark and decides which pane carries the banner.
    fun isSideBySide(): Boolean {
        val value = navigator.scaffoldValue
        return value[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
            value[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    }

    // The buffer the conversation pane shows — the destination's, as a rename has since named it.
    fun currentRoute(): BufferRoute? {
        replacing?.let { return it }
        val destination = navigator.currentDestination?.contentKey ?: return null
        return if (destination == renamedFrom) renamedTo else destination
    }

    val sideBySide = isSideBySide()
    val openRoute = currentRoute()

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
    // probe would silently re-join a channel you left on purpose. A buffer that gets no frame by
    // the end of the burst (`ChatState.rosterSettled`) sends the reader back to the list instead of
    // spinning — the conversation's `BufferWatch`.
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (restored) return@LaunchedEffect
        restored = true
        if (navigator.currentDestination?.contentKey != null) return@LaunchedEffect
        val key = uiPreferences.lastOpenBufferKey ?: return@LaunchedEffect
        navigation.withLock { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, BufferRoute.of(key)) }
    }

    // Open a buffer in the conversation pane. Recording it as the relaunch target is the
    // conversation's, on appearance (`ConversationScreen.onVisit`), so a launch restore, a join and a
    // DCC chat count as surely as a pick.
    //
    // ⚠ Side by side, a pick REPLACES the conversation rather than stacking a history entry — iOS
    // replaces the secondary column. The navigator has no replace, so it's a pop then a push in one
    // locked move: side by side both panes are expanded at either destination, so the scaffold value
    // never changes and nothing animates — the pane stays put and its content changes (`replacing`
    // covers the frame between). Without it every pick would grow the saved history by one entry for
    // the life of the session. On a phone the list is behind the conversation, so a pick there (a
    // join landing, a DCC chat) pushes: a pop first WOULD change the scaffold value, sliding the
    // conversation out and back in, and back skips the stacked entry anyway (the scaffold's
    // `PopUntilScaffoldValueChange` pops to the list).
    fun openKey(key: BufferKey) {
        // "Open this buffer" while you're already in it is nothing to do.
        if (currentRoute()?.key?.id == key.id) return
        val route = BufferRoute.of(key)
        clearRename()
        if (isSideBySide()) replacing = route
        navigate {
            try {
                if (isSideBySide() && navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail) {
                    navigator.navigateBack(BackNavigationBehavior.PopLatest)
                }
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, route)
            } finally {
                if (replacing == route) replacing = null
            }
        }
    }

    fun open(buffer: Buffer) = openKey(buffer.key)

    // The bar's back arrow — the same move as the system back.
    fun back() {
        navigate { if (navigator.canNavigateBack()) navigator.navigateBack() }
    }

    // The buffer the conversation shows isn't open any more (iOS's `handleBufferDisappeared` →
    // `showBufferList`): back to the list on a phone; side by side, the conversation pane drops to the
    // system buffer and the row that just vanished stops being marked. Popped one entry at a time to
    // the list destination, never by a behaviour that might find nothing to pop — see `navigation`.
    //
    // Forgotten as the relaunch target too, for the same reason a close is: restoring into a buffer
    // that isn't there lands on a spinner.
    fun leave(key: BufferKey) {
        navigate {
            // Already on the way out — a pop in flight, or the reader moved on — so don't stack a
            // second one.
            if (currentRoute()?.key?.id != key.id) return@navigate
            uiPreferences.forgetLastOpenBuffer(ifMatching = key)
            clearRename()
            while (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail &&
                navigator.canNavigateBack(BackNavigationBehavior.PopLatest)
            ) {
                navigator.navigateBack(BackNavigationBehavior.PopLatest)
            }
        }
    }

    // A rename (`AppEvent.BufferRenamed`, or the conversation finding its row under a new key): if
    // it's the buffer open in the conversation, the route follows — the conversation and the list's
    // open mark both read it. The relaunch record follows in `LurkerApp` (`rewriteBuffer`).
    fun follow(from: BufferKey, to: BufferKey) {
        if (currentRoute()?.key?.id != from.id) return
        val destination = navigator.currentDestination?.contentKey ?: return
        val target = BufferRoute.of(to)
        if (destination == target) {
            clearRename()
        } else {
            renamedFrom = destination
            renamedTo = target
        }
    }

    // Backing out to the list on a phone means the *list* is where you were, not that buffer
    // (lurker-ios's `viewDidDisappear`): without this the restore target could only ever be a
    // conversation — leave one on purpose, relaunch, and you're shoved straight back into it, the one
    // move a home screen is supposed to make unnecessary.
    //
    // Only a back-out: the destination going from a buffer to none while one pane is showing. Side by
    // side, nothing is forgotten by navigation — a pick replaces through the list destination, and the
    // column rests on the system buffer rather than closing. Not a configuration change either: the
    // last destination is remembered, not saved, so a recreated scaffold starts from where it is.
    val destinationRoute = navigator.currentDestination?.contentKey
    val lastDestination = remember { LastDestination(destinationRoute) }
    LaunchedEffect(destinationRoute) {
        val previous = lastDestination.route
        lastDestination.route = destinationRoute
        if (previous != null && destinationRoute == null && !isSideBySide() && replacing == null) {
            uiPreferences.forgetLastOpenBuffer()
        }
    }

    // Leave a channel / close a DM. Shared by the swipe and the menu rather than written twice:
    // the forget half is easy to leave out of a second copy and impossible to notice missing until
    // a relaunch strands someone on a spinner. Closing here is the one moment the client *knows* a
    // buffer is gone; restoring into one that isn't there lands on a spinner, and the launch path
    // can't detect it — so tell it.
    //
    // If it's the buffer open in the detail pane, the conversation notices its row go
    // (`BufferWatch`) and leaves, as it does for a close on another device.
    fun close(buffer: Buffer) {
        model.closeBuffer(buffer.key)
        uiPreferences.forgetLastOpenBuffer(ifMatching = buffer.key)
    }

    // The kit's asks of the screen (`AppEvents`), drained for as long as this scaffold is composed.
    // Queued in between, so a rotation loses none of them.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) {
        events.events.collect { event ->
            when (event) {
                // The same move as a pick: the buffer is synthesized when its row hasn't landed yet,
                // and the conversation hydrates it.
                is AppEvent.OpenBuffer -> openKey(event.key)
                // Launched, so a notice waiting out its duration doesn't hold up the queue behind it.
                is AppEvent.Notice -> launch { snackbar.showSnackbar(event.message) }
                is AppEvent.BufferRenamed -> follow(event.from, event.to)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    // On a phone the pane slides away AFTER the destination has gone back to the
                    // list, so the route it was showing is held for the exit — rather than the
                    // conversation turning into the system buffer as it leaves.
                    val held = remember { LastDestination(null) }
                    if (openRoute != null) held.route = openRoute
                    val route = openRoute ?: if (sideBySide) null else held.route
                    // iOS never shows an empty column: side by side with nothing picked, the pane rests
                    // on the system buffer, the app's own log — and doesn't record it as where you were.
                    val bufferKey = route?.key ?: Buffer.system.key
                    // A fresh screen per buffer — its scroll position, revealed spoilers and hydrate
                    // bookkeeping belong to the buffer, as iOS builds a fresh screen per open.
                    key(bufferKey.id) {
                        ConversationScreen(
                            model = model,
                            key = bufferKey,
                            resting = route == null,
                            showsBack = !sideBySide,
                            onBack = ::back,
                            onVisit = { uiPreferences.recordLastOpenBuffer(bufferKey) },
                            onGone = { leave(bufferKey) },
                            onMoved = { to -> follow(bufferKey, to) },
                        )
                    }
                }
            },
        )
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        ServerErrorDialog(model)
    }
}

/** A route remembered across compositions without being state — reading it must not recompose. */
private class LastDestination(var route: BufferRoute?)

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
