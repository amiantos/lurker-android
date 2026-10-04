// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import net.amiantos.lurker.ui.settings.SettingsDialog
import net.amiantos.lurker.ui.bufferinfo.BufferSheetsHost
import net.amiantos.lurker.ui.feeds.FeedSheetsHost
import net.amiantos.lurker.ui.feeds.AppView
import net.amiantos.lurker.ui.uploads.AttachmentSource
import net.amiantos.lurker.ui.uploads.LocalUploadServices
import net.amiantos.lurker.ui.uploads.SharePickerDialog
import net.amiantos.lurker.ui.uploads.UploadReportDialog
import net.amiantos.lurker.ui.uploads.UploadServices
import net.amiantos.lurker.ui.uploads.UploadTargets
import net.amiantos.lurker.ui.uploads.UploadsSheetsHost
import net.amiantos.lurker.ui.uploads.rememberUploadsSheets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurker.ui.feeds.rememberFeedSheets
import net.amiantos.lurker.ui.bufferinfo.rememberBufferSheets
import net.amiantos.lurker.ui.networks.rememberNetworkSheets
import net.amiantos.lurker.ui.networks.NetworkSheetsHost
import net.amiantos.lurker.platform.findActivity
import net.amiantos.lurker.platform.LocalAppEvents
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.saveable.Saver
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
import net.amiantos.lurker.ui.conversation.ConversationScroll
import net.amiantos.lurker.ui.dcc.DccOfferDialog
import net.amiantos.lurker.ui.dcc.DccOffers
import net.amiantos.lurker.ui.list.BufferListModel
import net.amiantos.lurker.ui.list.BufferListScreen
import net.amiantos.lurker.ui.media.MediaSource
import net.amiantos.lurker.ui.media.MediaViewerHost
import net.amiantos.lurker.ui.media.rememberMediaViewer
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
 *
 * [sessionLive] false is the session already over while `AppRoot` fades this out: every dialog goes
 * at once rather than with the fade. A dialog is a window of its own and takes none of the fade's
 * alpha, so it would otherwise sit fully drawn — and still taking taps, for an account that's gone —
 * over the incoming sign-in screen, the way iOS's sheets would have sat over its login had it not
 * dismissed them before swapping the root.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScaffold(
    model: ChatViewModel,
    uiPreferences: UiPreferences,
    events: AppEvents,
    dccOffers: DccOffers,
    uploads: UploadServices,
    onSignOut: () -> Unit,
    sessionLive: Boolean = true,
) {
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

    // The conversation's members, info and profile dialogs (U5) — here for the networks dialogs' reason:
    // the conversation is rebuilt under a new key when its buffer is renamed, and a dialog hosted inside
    // it would close mid-edit. See `BufferSheets`.
    val bufferSheets = rememberBufferSheets()

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

    // A jump into the buffer that's ALREADY open (a search hit or a bookmark for the conversation
    // beside the list, a notification for the one you're reading): the route the pane shows, with
    // the new request on it. Not a navigation, for the rename's reason — the navigator has no
    // replace, and a pop and push would rebuild the conversation (and on a phone slide it out and
    // back in) to deliver one message id. The conversation is keyed by buffer, so a new `jump` on
    // the same buffer reaches the screen in place, and each request acts once (`jumps`).
    // Saved with the history it amends; any navigation of ours clears it, as with the rename.
    var jumpedInPlace by rememberSaveable { mutableStateOf<BufferRoute?>(null) }

    // Every jump request the conversation has acted on, so each acts once for the life of the
    // history that holds it — back to a buffer you were jumped into rebuilds its screen from the
    // same route, request and all (`JumpLedger`).
    val jumps = rememberSaveable(saver = JumpLedgerSaver) { JumpLedger() }

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
        val route = if (destination == renamedFrom) renamedTo else destination
        val inPlace = jumpedInPlace
        return if (inPlace != null && route != null && inPlace.key.id == route.key.id) inPlace else route
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
    //
    // THE way into a conversation, for every caller: the list's pick, a join landing, a DCC chat,
    // and — with [jumpTo] — U7's search hits, bookmarks and highlights and U9's notification taps,
    // which land on that message rather than the bottom (lurker-ios#42): scrolled to and flashed when
    // it's loaded, fetched in an `around` slice when it isn't (`ConversationScroll`). A jump into the
    // buffer that's already open reaches the conversation in place (`jumpedInPlace`), without
    // rebuilding it. The message id is the server's stored id (`Message.id`), never a row index.
    //
    // Whatever was still waiting to land — a DM, a DCC chat, a join — would pull the reader off this
    // one (lurker-ios#201), so every way a buffer goes on screen stands it down
    // (`ChatViewModel.supersedeLandings`) — before the early-out, as iOS's `showBuffer` does. A
    // landing coming through here cancels nothing: its own wait has already ended.
    fun open(key: BufferKey, jumpTo: Long? = null) {
        model.supersedeLandings()
        val current = currentRoute()
        if (current?.key?.id == key.id) {
            // "Open this buffer" while you're already in it is nothing to do — unless it's a jump.
            if (jumpTo != null) jumpedInPlace = current.copy(jump = JumpRequest.to(jumpTo))
            return
        }
        val route = BufferRoute.of(key, jump = jumpTo?.let(JumpRequest::to))
        clearRename()
        jumpedInPlace = null
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

    fun openBuffer(buffer: Buffer) = open(buffer.key)

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
        // A members or info dialog about it has nothing left to describe (U5).
        bufferSheets.dismissIfAbout(key)
        navigate {
            // Already on the way out — a pop in flight, or the reader moved on — so don't stack a
            // second one.
            if (currentRoute()?.key?.id != key.id) return@navigate
            uiPreferences.forgetLastOpenBuffer(ifMatching = key)
            clearRename()
            jumpedInPlace = null
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
        // An open members/info/profile dialog follows too, whichever buffer it's about (U5).
        bufferSheets.follow(from, to)
        if (currentRoute()?.key?.id != from.id) return
        val destination = navigator.currentDestination?.contentKey ?: return
        val target = BufferRoute.of(to)
        // The conversation is rebuilt under its new key (it's keyed by buffer), so a jump it was
        // opened with, or given in place, has already been consumed and isn't carried across.
        jumpedInPlace = null
        if (destination.copy(jump = null) == target) {
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
        // An in-place jump amends the destination it was given on; any other destination — the
        // system back included, which bypasses `open` and `leave` — retires it.
        if (previous != null && previous != destinationRoute) jumpedInPlace = null
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
    //
    // Offline it says so and leaves the row (sweep L16). Removing it anyway sent no PART, so the
    // reconnect's snapshot put the row back and the channel had never been left. Asked of both
    // connection signals before the send's own answer, for the dropped-but-unnoticed socket that
    // takes a write and loses it.
    fun close(buffer: Buffer): Boolean {
        if (!ConversationScroll.mayWrite(model.state) || !model.closeBuffer(buffer.key)) {
            events.send(AppEvent.Notice(BufferListModel.NOT_CONNECTED))
            return false
        }
        uiPreferences.forgetLastOpenBuffer(ifMatching = buffer.key)
        return true
    }

    // Join Channel, Add Network and the networks list — full-screen dialogs. Here, not in the list
    // pane: on a phone the list leaves composition whenever a conversation is shown, and a dialog
    // hosted there would close (and drop a half-typed form) the moment a join navigated.
    val sheets = rememberNetworkSheets()

    // Settings (U10) — here for the same reason, and saved so a rotation keeps it up. Its own flag
    // rather than one of `sheets`: the networks list opens OVER it (Back comes back here, as from iOS's
    // pushed networks screen), so the two can be up at once.
    var showingSettings by rememberSaveable { mutableStateOf(false) }

    // Search, Activity and Bookmarks (U7) — here for the networks dialogs' reason. A row's tap closes
    // the dialog and opens the buffer at that line, through `open` like every other way in.
    val feedSheets = rememberFeedSheets()

    // The media viewer (lurker-android#15) — here for the same reason: a phone's navigation must not
    // drop it, and a conversation rebuilt under a rename must not close it.
    val mediaViewer = rememberMediaViewer()
    val media = remember(model) { MediaSource.of(model) }

    // The uploads browser (U8) — here for the feeds' reason. Opened from a conversation it can Add to
    // Message into that conversation's composer; from the list it can't, there being no composer there.
    val uploadsSheets = rememberUploadsSheets()

    // A view from a menu: the uploads browser, or one of the feeds. [from] is the buffer whose bar asked,
    // if one did — Add to Message's destination, offered only when that buffer takes uploads at all
    // (`UploadTargets`): never from the list, a server log or the Lurker console.
    fun openView(view: AppView, from: BufferKey? = null) {
        if (view == AppView.Uploads) {
            uploadsSheets.show(insertInto = from?.takeIf(UploadTargets::takes))
        } else {
            feedSheets.show(view)
        }
    }

    // A share from another app (lurker-android#15), once the reader has said which conversation: open it
    // — the same move as a pick — and put the text in its composer; the files go through the same run as
    // the paperclip, their links landing in that composer as it comes on screen. A run already under
    // way keeps the files out, and says so: two runs at once would interleave their links.
    fun sendShare(key: BufferKey) {
        val share = uploads.shares.take() ?: return
        sheets.dismiss()
        bufferSheets.dismiss()
        feedSheets.dismiss()
        uploadsSheets.dismiss()
        mediaViewer.dismiss()
        showingSettings = false
        open(key)
        share.text?.let { text -> uploads.inserts.insert(key, text) }
        if (share.streams.isNotEmpty() && !uploads.runner.start(share.streams.map { AttachmentSource.Content(it) })) {
            events.send(AppEvent.Notice("An upload is already in progress — share again once it's done"))
        }
    }

    // The kit's asks of the screen (`AppEvents`), taken for as long as this scaffold is composed AND
    // its session is the live one ([sessionLive]): a scaffold fading out after a sign-out — or still
    // fading while a quick sign-in's new scaffold comes up — stops taking them at once, so it can't
    // consume the new session's navigation. Attached across a configuration change — that gap is what
    // the queue bridges — and detached when the screen goes for good (or its session does), so
    // nothing waits for a launch hours later. The detach is by token, so a late one from an old
    // scaffold can't switch off the new one's attachment.
    val activity = LocalContext.current.findActivity()
    if (sessionLive) {
        DisposableEffect(events) {
            val attachment = events.attach()
            onDispose { if (activity?.isChangingConfigurations != true) events.detach(attachment) }
        }
        LaunchedEffect(events) {
            events.events.collect { event ->
                when (event) {
                    // iOS's `land(on:)`: anything presented comes down, then the buffer opens — the same
                    // move as a pick; the buffer is synthesized when its row hasn't landed yet, and the
                    // conversation hydrates it.
                    is AppEvent.OpenBuffer -> {
                        sheets.dismiss()
                        bufferSheets.dismiss()
                        feedSheets.dismiss()
                        mediaViewer.dismiss()
                        uploadsSheets.dismiss()
                        showingSettings = false
                        open(event.key, jumpTo = event.jumpTo)
                    }
                    is AppEvent.BufferRenamed -> follow(event.from, event.to)
                    // Shown by `NoticeHost`, never sent down this channel.
                    is AppEvent.Notice -> Unit
                }
            }
        }
    }

    CompositionLocalProvider(LocalAppEvents provides events, LocalUploadServices provides uploads) {
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
                        onOpen = ::openBuffer,
                        onClose = ::close,
                        onOpenSettings = { showingSettings = true },
                        sheets = sheets,
                        onOpenView = { view -> openView(view) },
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
                            // A new request on the same buffer arrives here without rebuilding the
                            // screen (`jumpedInPlace`); each acts once for the session (`jumps`).
                            jump = route?.jump,
                            jumps = jumps,
                            resting = route == null,
                            showsBack = !sideBySide,
                            onBack = ::back,
                            onVisit = { uiPreferences.recordLastOpenBuffer(bufferKey) },
                            onGone = { leave(bufferKey) },
                            onMoved = { to -> follow(bufferKey, to) },
                            uiPreferences = uiPreferences,
                            // `/msg` and `/query` to a channel — a pick, at once. To a nick they ask
                            // nothing of the screen: the kit lands on the DM once its row is in
                            // (`ChatViewModel.openAndShow` → `AppEvent.OpenBuffer`).
                            onOpenBuffer = { to -> open(to) },
                            onShowMembers = { bufferSheets.showMembers(bufferKey) },
                            onShowInfo = { bufferSheets.showInfo(bufferKey) },
                            onShowProfile = { networkId, nick -> bufferSheets.showProfile(networkId, nick) },
                            sideBySide = sideBySide,
                            onOpenView = { view -> openView(view, from = bufferKey) },
                            onOpenMedia = mediaViewer::show,
                            media = media,
                        )
                    }
                }
            },
        )
        // Every window below goes the moment the session ends — see [sessionLive]. The notice host
        // too, though it isn't a window: a fading scaffold's host would otherwise claim and show the
        // new session's notices.
        if (sessionLive) {
            NoticeHost(events, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
            ServerErrorDialog(model)
            // A DCC chat offer, asked about over whatever is on screen — here for the error dialog's
            // reason, so it neither waits behind the list on a phone nor closes when a conversation opens.
            DccOfferDialog(dccOffers)
            // Joining is also switching: you asked for a channel, so land in it — once the server says
            // you're in (lurker-ios#57). Nothing navigates before then: a join can be refused, and a
            // screen for a channel you never got into has nothing to show. `requestJoin` opens the
            // channel when `channel-joined` lands (`AppEvent.OpenBuffer`), and says why when it doesn't
            // (`AppEvent.Notice`).
            if (showingSettings) {
                SettingsDialog(
                    model = model,
                    uiPreferences = uiPreferences,
                    onDismiss = { showingSettings = false },
                    // Over Settings, not instead of it: closing the networks list comes back here.
                    onOpenNetworks = sheets::showNetworks,
                    onSignOut = {
                        showingSettings = false
                        onSignOut()
                    },
                )
            }
            // Over the conversation it's about: Send Message leaves it up until the DM's row is in, then
            // the landing (`AppEvent.OpenBuffer`) takes it down and opens the DM, as iOS's `land(on:)`.
            BufferSheetsHost(sheets = bufferSheets, model = model, onSearch = feedSheets::showSearch)
            FeedSheetsHost(sheets = feedSheets, model = model, onJump = { key, messageId -> open(key, jumpTo = messageId) })
            // Add to Message: the file's address into the composer of the conversation that opened the browser
            // — on screen behind it now, or as soon as it is again.
            UploadsSheetsHost(sheets = uploadsSheets, model = model) { key, url -> uploads.inserts.insert(key, url) }
            // A finished run's one dialog, over whatever is up (the run outlives the buffer it started in).
            UploadReportDialog(uploads.runner)
            // A share waiting for its conversation — asked as soon as the signed-in app is up.
            val share by uploads.shares.share.collectAsStateWithLifecycle()
            if (share != null) SharePickerDialog(model = model, onPick = ::sendShare, onDismiss = { uploads.shares.take() })
            // After Settings, so a networks list opened from it is the window on top.
            NetworkSheetsHost(sheets = sheets, model = model) { networkId, channel ->
                model.requestJoin(networkId = networkId, channel = channel, opens = true)
            }
            // Last, so a picture opened from anywhere is the window on top.
            MediaViewerHost(mediaViewer, media)
        }
    }
    }
}

/** Saves the consumed jump requests with the navigator's history. */
private val JumpLedgerSaver = Saver<JumpLedger, LongArray>(save = { it.saved() }, restore = { JumpLedger(it.toList()) })

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
