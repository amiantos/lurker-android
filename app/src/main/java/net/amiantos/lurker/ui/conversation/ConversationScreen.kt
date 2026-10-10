// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.LocalAppEvents
import net.amiantos.lurker.platform.LocalToastCenter
import net.amiantos.lurker.prefs.UiPreferences
import net.amiantos.lurker.ui.actions.MessageActionsHost
import net.amiantos.lurker.ui.actions.rememberMessageActionsState
import net.amiantos.lurker.ui.composer.ComposerBar
import net.amiantos.lurker.ui.composer.ComposerModel
import net.amiantos.lurker.ui.composer.SendScroll
import net.amiantos.lurker.ui.feeds.AppView
import net.amiantos.lurker.ui.feeds.ConversationViewsActions
import net.amiantos.lurker.ui.composer.rememberComposerState
import net.amiantos.lurker.ui.media.MediaSource
import net.amiantos.lurker.ui.media.PreviewContext
import net.amiantos.lurker.ui.media.PreviewToggles
import net.amiantos.lurker.ui.shell.NoticeHost
import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.openNotification
import net.amiantos.lurker.ui.shell.rememberToastSurface
import net.amiantos.lurker.ui.uploads.ComposerInsertTarget
import net.amiantos.lurker.ui.uploads.LocalUploadServices
import net.amiantos.lurker.ui.uploads.UploadTargets
import net.amiantos.lurker.ui.uploads.rememberAttachments
import net.amiantos.lurker.ui.message.MessageListContext
import net.amiantos.lurker.ui.message.MessageListLayout
import net.amiantos.lurker.ui.message.MessageListRow
import net.amiantos.lurker.ui.message.ReactionContext
import net.amiantos.lurker.ui.message.RowPress
import net.amiantos.lurker.ui.message.previewMessageRows
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.shell.ConnectionBanner
import net.amiantos.lurker.ui.shell.JumpLedger
import net.amiantos.lurker.ui.shell.JumpRequest
import net.amiantos.lurker.ui.shell.SafeUriHandler
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.shell.rememberConnectionBannerState
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferPlaceholder
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.StatusToast
import net.amiantos.lurkerkit.rendering.NickHighlighter
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A buffer's messages, live. lurker-ios's `ChatViewController` (lurker-android#10, #7): the rows the
 * kit builds for this buffer, drawn compact, with the title, the connection banner, the placeholder
 * behind an empty list — and everything about *where* the list is: paging older and newer, the
 * unread divider and its banner, jumps to a message (in or out of the loaded slice), jump to latest,
 * and mark-read.
 *
 * Messages arrive two ways and are treated identically: the slice the server sends in reply to the
 * hydrate below, and live frames after that — including the echo of our own sends, which is why
 * there's no optimistic-row bookkeeping here.
 *
 * The decisions are `ConversationScroll`'s, pure and tested; this composable feeds it frames, builds
 * and measured layouts, and performs the scrolls it asks for. Three streams, in a fixed order:
 *
 *  1. **Frames** (every `ChatState`), on the main thread: the buffer leaving, the hydrate, the jump's
 *     `around` fetch, the read-boundary latch, mark-read. Each processed frame is stamped and handed
 *     on, so every later build is of a frame these effects have already seen.
 *  2. **Builds**, off the main thread (`flowOn(Dispatchers.Default)`): filtering, consolidation and
 *     the row keys, from immutable inputs — see `ConversationModel.built`. The main thread takes each
 *     build, decides whether to follow the tail or hold the reader's line, publishes it, and lands
 *     whatever landing is pending. Composition only draws.
 *  3. **Layouts** (`snapshotFlow` over `layoutInfo`): the floating controls, the `dividerSeen` latch,
 *     and paging — from the measured layout, never a scroll callback that can describe a stale frame.
 *
 * The composer rides the bottom edge (`ComposerBar`, lurker-android#11): it is the scaffold's bottom
 * bar, so the list's reservation includes it, and it pads itself by the keyboard — the reverse layout
 * keeps the newest row anchored as either grows. Its suggestions float over the list, above it.
 *
 * A long press on a row opens its actions sheet (lurker-android#37): the line's, a link's, or — on
 * the chips — who reacted. Reply goes through `ComposerState.startReply`. See `MessageActionsHost`.
 *
 * Link previews and inline media draw under the rows that carry them (lurker-android#15): the kit
 * resolves them at ingest, and each row re-plans when one of its own URLs moves (`PreviewUpdates`). A
 * tap on a picture opens [onOpenMedia]'s viewer.
 *
 * @param jump the message to land on rather than the bottom (`BufferRoute.jump`) — a new request on
 *   the same buffer arrives here without rebuilding the screen.
 * @param jumps the scaffold's record of the requests already acted on: each acts once for the
 *   session's navigator, however often this screen is rebuilt from the route that carries it.
 * @param resting this is the system buffer the detail pane shows side by side when nothing is picked,
 *   rather than a buffer anyone opened — iOS's `isResting`. It isn't recorded as the buffer you were
 *   reading, which would make a buffer nobody opened the next launch's destination.
 * @param showsBack whether the bar carries a back arrow: on a phone, where the list is behind this
 *   screen; never side by side, where it's beside it.
 * @param onVisit this is now the buffer you're reading — record it for the next launch. On
 *   appearance rather than on the pick (iOS's `recordVisit`), so a launch restore, a join and a DCC
 *   chat count too.
 * @param onGone the buffer isn't open any more — see [BufferWatch].
 * @param onMoved the buffer was renamed under us — follow it to its new key.
 * @param uiPreferences the device's own settings — the composer reads its capitalization.
 * @param onOpenBuffer `/msg` or `/query` to a channel asks to switch to it. To a nick they ask nothing of
 *   the screen: the kit lands on the DM once its row is in (`ChatViewModel.openAndShow`).
 * @param onShowProfile `/whois` asks for this person's profile — `BufferSheets.showProfile`.
 * @param onShowMembers the bar's members button (U5) — offered on channels only.
 * @param onShowInfo the bar's info button (U5) — this buffer's info and settings, on every buffer.
 * @param sideBySide whether the list is beside this screen — the bar's views come out as buttons (U7).
 * @param onOpenView the bar's views — Search, Activity, Bookmarks (U7) and Uploads (U8), which
 *   `MainScaffold` hosts.
 * @param onOpenMedia the media viewer `MainScaffold` hosts, over a message's pictures and positioned on
 *   one; null and a tap on a picture opens its address.
 * @param media where preview pictures come from — `MainScaffold`'s, shared with the viewer.
 * @param covered a dialog is over the window — Settings, a sheet, the uploads browser, the media
 *   viewer, from either pane. The status row can't be seen, so no toast goes into it (lurker#1098).
 * @param takesToasts this screen is the destination, not one sliding out behind the list on a phone.
 */
@Composable
fun ConversationScreen(
    model: ChatViewModel,
    key: BufferKey,
    jump: JumpRequest? = null,
    jumps: JumpLedger = remember { JumpLedger() },
    resting: Boolean,
    showsBack: Boolean,
    onBack: () -> Unit,
    onVisit: () -> Unit,
    onGone: () -> Unit,
    onMoved: (BufferKey) -> Unit,
    uiPreferences: UiPreferences,
    onOpenBuffer: (BufferKey) -> Unit,
    onShowProfile: (networkId: Int, nick: String) -> Unit = { _, _ -> },
    onShowMembers: () -> Unit = {},
    onShowInfo: () -> Unit = {},
    sideBySide: Boolean = false,
    onOpenView: ((AppView) -> Unit)? = null,
    onOpenMedia: ((List<LinkPreview>, Int) -> Unit)? = null,
    media: MediaSource,
    covered: Boolean = false,
    takesToasts: Boolean = true,
) {
    val kind = remember(key) { BufferKind.of(networkId = key.networkId, target = key.target) }

    val currentOnVisit by rememberUpdatedState(onVisit)
    LaunchedEffect(key.id, resting) {
        if (!resting) currentOnVisit()
    }

    // Where the list is and where it's going. Saved, so a rotation keeps the latched read boundary
    // (by then the buffer has been marked read, and re-latching would latch our own mark). A route's
    // jump is taken here, at birth, so the very first frame already knows a jump is pending and
    // hydrates through its `around` slice instead — once per session: see `JumpLedger`.
    val scroll = rememberSaveable(key.id, saver = scrollSaver(kind)) {
        ConversationScroll(kind).also { machine ->
            jump?.takeIf(jumps::claim)?.let { machine.jumpTo(it.messageId) }
        }
    }
    val options = remember(scroll) { MutableStateFlow(scroll.options) }
    fun publishOptions() {
        options.value = scroll.options
    }
    // Bumped whenever the machine changes in a way the floating controls read, so the layout stream
    // re-decides them with nothing having scrolled.
    var revision by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val followSlop = with(density) { FOLLOW_SLOP.roundToPx() }
    val pagingDistance = with(density) { PAGING_DISTANCE.roundToPx() }

    // Frames, stamped with the order this screen processed them in — see `ConversationScroll.onRows`.
    val frames = remember(key) { MutableStateFlow<Frame?>(null) }
    val frameSeq = remember(key) { FrameCounter() }
    // Seeded from where the list state starts, not "at the bottom": a rotation restores a reader
    // mid-history, and a first build that lands before the list has measured would otherwise read
    // the fallback, follow the tail, and throw the restored position away.
    val jobs = remember(key) {
        Jobs(nearBottom = ConversationModel.followsTail(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, followSlop))
    }

    var instanceHasPreviews by remember(model) { mutableStateOf(model.features.linkPreviews) }

    // The two things the screen must DO about every frame, rather than draw, in iOS's order: notice
    // the buffer disappearing (or moving), ask for its history, ask for a jump's slice, latch the
    // read boundary, and mark read. Side effects, never Compose state — straight off the publisher.
    val currentOnGone by rememberUpdatedState(onGone)
    val currentOnMoved by rememberUpdatedState(onMoved)
    LaunchedEffect(model, key) {
        val watch = BufferWatch(key, kind)
        val hydrate = HydrateGate(kind)
        // Once: every later frame would say the same, and each would ask the navigator to leave again.
        var left = false
        // Every frame, not conflated: these are transition-driven — `HydrateGate` must see the
        // `hydrated = true` frame before a rename merge resets the row, or its earlier request stays
        // latched and the buffer sits on "Loading messages…". The drawn streams below conflate.
        model.statePublisher.collect { state ->
            if (left) return@collect
            when (val verdict = watch.check(state)) {
                BufferWatch.Verdict.Gone -> {
                    left = true
                    // Sign-out empties `buffers` too. Leave that entirely to `AppRoot`, which swaps
                    // the app out — leaving here as well would run two transitions at once.
                    if (model.session == ChatViewModel.SessionState.LoggedIn) currentOnGone()
                    return@collect
                }
                is BufferWatch.Verdict.Moved -> {
                    left = true
                    currentOnMoved(verdict.key)
                    return@collect
                }
                BufferWatch.Verdict.Present, BufferWatch.Verdict.Waiting -> Unit
            }
            // The instance's preview flag isn't state: it's re-read from `/api/config` on every
            // reconnect, so it's sampled here, on every frame — a reconnect's own frames carry the new
            // answer to the rows. (A flag turning off between frames fetches nothing meanwhile: the
            // media source checks it live, see `MediaSource.of`.)
            instanceHasPreviews = model.features.linkPreviews
            val seq = frameSeq.next()
            // A pending jump hydrates through its `around` slice instead: asking for both would
            // double-fetch, and the latest slice would fight the jump.
            hydrate.check(state.connection, state.buffers[key.id], state.burstGeneration, jumpPending = scroll.jumpPending)
                ?.let(model::hydrate)
            val step = scroll.onFrame(state, key, seq)
            jobs.lastFrame = state
            step.loadAround?.let { anchor -> model.loadAround(key, anchorId = anchor) }
            if (step.loadLatest) model.loadLatest(key)
            if (step.optionsChanged) publishOptions()
            if (step.optionsChanged || step.loadAround != null || step.loadLatest) revision++
            // New traffic while we're on screen: keep it marked read — never before the boundary is
            // latched (`ConversationScroll.marksRead`), and only while the screen can be seen (iOS's
            // `view.window != nil`). ⚠ A WRITE: the server's pointer moves for every device. Asked
            // when this buffer's messages changed (or it's the first mark), as iOS's deduped `apply`
            // asks — not for every frame of every other buffer, each of which would scan this one's.
            // ⚠ And only online (`mayWrite`), checked before anything is recorded. Going offline, or a
            // new socket without its snapshot yet, forgets this screen's own mark, so the first frame
            // back asks again even when no new line has landed: a mark written into a socket that died
            // without saying so would otherwise never be re-sent (sweep L23). The kit forgets its own
            // on a drop and on a new socket.
            if (!ConversationScroll.mayWrite(state) || !state.snapshotSinceOpen) jobs.markedFor = null
            val held = state.messages[key.id]
            if (scroll.marksRead && ConversationScroll.mayWrite(state) &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && held !== jobs.markedFor
            ) {
                jobs.markedFor = held
                model.markRead(key)
            }
            frames.value = Frame(seq, state)
        }
    }
    // Coming into view marks read too (iOS's `viewDidAppear`) — the same gates.
    LifecycleStartEffect(key, scroll) {
        if (scroll.marksRead && ConversationScroll.mayWrite(model.state)) model.markRead(key)
        onStopOrDispose {}
    }

    val banner = rememberConnectionBannerState(model)
    var connectionShown by remember { mutableStateOf(false) }

    val clock = rememberDayClock()
    val clockFlow = remember { snapshotFlow { clock.value } }

    // The builds. Re-projected on every processed frame, distinct by `same`; then rebuilt for the
    // screen's own options (the divider, a jump's exemption, a `/clear` reveal) and the zone (a
    // time-zone change moves every day boundary). Who's typing no longer passes through here: it's
    // the composer's status row's own stream (lurker-ios#61), with its own once-a-second re-read.
    val projector = remember(key) { ConversationProjector(key, kind) }
    // The first build, on the main thread, once: so a screen that opens on loaded history draws it
    // on its first frame — and a restored scroll position has rows to restore onto — rather than
    // flashing "Loading messages…" for the frame the first background build takes.
    val initialBuilt = remember(model, key) {
        ConversationModel.built(projector.project(model.state), scroll.options, seq = 0, zone = clock.value.zone)
    }
    val builtFlow = remember(model, key) {
        val inputs = frames.filterNotNull().map { frame -> Stamped(frame.seq, projector.project(frame.state)) }
            .distinctUntilChanged { old, new -> ConversationInputs.same(old.inputs, new.inputs) }
        combine(inputs, options, clockFlow) { stamped, opts, day ->
            ConversationModel.built(stamped.inputs, opts, seq = stamped.seq, zone = day.zone)
        }
            .conflate()
            .flowOn(Dispatchers.Default)
            .conflate()
    }
    var current by remember(model, key) { mutableStateOf(initialBuilt) }
    var forceLoading by remember(key) { mutableStateOf(false) }
    var pills by remember(key) { mutableStateOf(ConversationScroll.Pills()) }
    var flash by remember(key) { mutableStateOf<RowFlash?>(null) }

    // MARK: - Landing

    fun finish(token: Any, interrupted: Boolean) {
        val rows = current
        when (val end = scroll.finishJump(token, rows, interrupted)) {
            is ConversationScroll.Finish.Flash -> {
                // The warm pulse behind the row it landed on — the "here it is" after a jump.
                val pulse = RowFlash(rows.keys[end.row], ++jobs.flashes)
                flash = pulse
                scope.launch {
                    delay(FLASH_HOLD_MS + FLASH_FADE_MS + 100)
                    if (flash === pulse) flash = null
                }
            }
            ConversationScroll.Finish.AtTail -> listState.requestScrollToItem(0)
            ConversationScroll.Finish.Release -> Unit
        }
        revision++
    }

    // A jump's convergence: centre the target, re-resolved by message id on every pass against the
    // build on screen — never a cached index, which a rebuild between passes would slide onto the
    // wrong message — for a few frames, while the jump stays pending so nothing else (a follow, the
    // banner) steals the scroll in between. Bounded, and released by the reader's own drag.
    suspend fun converge(token: Any) {
        var interrupted = false
        try {
            repeat(JUMP_PASSES) {
                if (!scroll.isCurrent(token)) return
                awaitLayoutOf(listState, current)
                // Whatever is on screen now — a build may have landed while we waited.
                val rows = current
                val row = scroll.jumpTargetRow(rows) ?: return
                centre(listState, rows, row)
                withFrameNanos {}
            }
        } catch (e: CancellationException) {
            // The reader took hold of the list (their drag outranks our scroll), or the screen went.
            interrupted = true
            throw e
        } finally {
            if (scroll.isCurrent(token)) finish(token, interrupted)
        }
    }

    fun land() {
        when (val landing = scroll.landing(current)) {
            ConversationScroll.Landing.Idle, ConversationScroll.Landing.Wait -> Unit
            // Exact in one pass: the reverse layout puts item 0 at the bottom edge by construction.
            ConversationScroll.Landing.AtTail -> listState.requestScrollToItem(0)
            is ConversationScroll.Landing.Converge -> {
                // A superseded chain would stop on its own at its next pass; it needn't scroll first.
                jobs.converging?.cancel()
                jobs.converging = scope.launch { converge(landing.token) }
            }
        }
        revision++
    }

    // A jump the machine just took: its fetch (if the message isn't held) and its landing (if it is).
    // Asked of the last frame the screen processed, never a fresh read of the store: the reply is
    // recognised against the frame after it (`ReplyWatch`). Before the first frame there's nothing
    // to ask against, and that frame asks for itself.
    fun startJump() {
        publishOptions()
        jobs.lastFrame?.let { frame -> scroll.requestAround(frame, key) }?.let { anchor -> model.loadAround(key, anchorId = anchor) }
        land()
    }

    // The pill's path, and a send's from a detached slice (iOS's `send`): the line just sent isn't in
    // the slice and no scroll will reach it.
    fun jumpToLatest() {
        val detached = model.state.buffers[key.id]?.hasMoreNewer == true
        val choice = scroll.jumpToLatest(hasRows = current.rows.isNotEmpty(), detached = detached)
        // Both branches can put a `/clear` back.
        publishOptions()
        when (choice) {
            ConversationScroll.ToLatest.Reattach -> {
                // A converging chain belongs to the jump this cancelled.
                jobs.converging?.cancel()
                if (jobs.lastFrame?.let { frame -> scroll.requestLatest(frame, key) } == true) model.loadLatest(key)
            }
            ConversationScroll.ToLatest.ScrollDown -> {
                jobs.converging?.cancel()
                scope.launch { listState.animateScrollToItem(0) }
            }
            ConversationScroll.ToLatest.Nothing -> Unit
        }
        revision++
    }

    // A route's request that arrived after birth: a jump into the buffer already open.
    LaunchedEffect(jump?.nonce) {
        val request = jump ?: return@LaunchedEffect
        if (jumps.claim(request)) {
            scroll.jumpTo(request.messageId)
            startJump()
        }
    }

    // MARK: - Builds

    // A build, on its way onto the screen. Read where the reader is BEFORE it lands: following and
    // holding are both about the rows they were reading.
    fun accept(built: BuiltRows) {
        val drawn = current
        val visible = visibleItems(listState)
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        val wasNearBottom = ConversationScroll.nearBottomBefore(
            visible, drawn, firstIndex, firstOffset, followSlop, lastKnown = jobs.nearBottom,
        )
        val step = scroll.onRows(built, wasNearBottom)
        if (step.optionsChanged) publishOptions()
        val hold = if (step.follow || scroll.landingPending) {
            null
        } else {
            ConversationScroll.hold(
                firstVisibleIndex = firstIndex,
                firstVisibleOffset = firstOffset,
                visible = visible,
                drawn = drawn,
                next = built,
            )
        }
        current = built
        // ⚠ A reverse layout does NOT follow the tail by itself: a lazy list keeps its first visible
        // item by KEY, and the newest row is inserted in front of it, so a reader at the bottom
        // would watch new lines arrive below the viewport. So the list is asked to stay at item 0
        // for its next measure — the measure that lays these rows out.
        if (step.follow) {
            listState.requestScrollToItem(0)
        } else if (hold != null) {
            listState.requestScrollToItem(hold.index, hold.scrollOffset)
            if (hold.thenScrollBy != 0) {
                scope.launch {
                    awaitLayoutOf(listState, built)
                    listState.scrollBy(hold.thenScrollBy.toFloat())
                }
            }
        }
        // A window the filters thinned to nothing asks for more itself — no layout will — and says
        // "Loading messages…" while the page is in the air rather than claiming the buffer is empty.
        forceLoading = scroll.wantsTopUp(built, online = ConversationScroll.mayWrite(model.state)) &&
            model.loadOlder(key, showingClearedHistory = scroll.options.showsClearedHistory)
        land()
    }

    LaunchedEffect(model, key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            builtFlow.collect { built -> accept(built) }
        }
    }
    // MARK: - Layouts

    // The reader taking hold of the list releases a converging jump — see `onUserDrag`.
    LaunchedEffect(listState, scroll) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && scroll.onUserDrag()) jobs.converging?.cancel()
        }
    }
    LaunchedEffect(listState, scroll) {
        snapshotFlow { LayoutSample(listState.layoutInfo, current, connectionShown, revision) }
            .collect { sample ->
                val rows = sample.rows
                if (rows.rows.isEmpty()) {
                    pills = ConversationScroll.Pills()
                    // Nothing measured: where the list state says it is (the bottom, unless a
                    // restore put it elsewhere — it keeps its position while there's no list).
                    jobs.nearBottom = ConversationModel.followsTail(
                        listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, followSlop,
                    )
                    return@collect
                }
                val info = sample.layout
                val facts = LayoutFacts.of(
                    items = info.visibleItemsInfo.map { VisibleItem(it.key, it.index, it.offset, it.size) },
                    built = rows,
                    contentEnd = info.viewportEndOffset - info.afterContentPadding,
                    nearBottomPx = followSlop,
                    pagingPx = pagingDistance,
                ) ?: return@collect
                jobs.nearBottom = facts.nearBottom
                // Read live, as iOS's `isDetached` is: whether a page is worth asking for is a question
                // about the server's latest word, not the frame that happened to trigger this pass.
                val detached = model.state.buffers[key.id]?.hasMoreNewer == true
                val step = scroll.onLayout(
                    facts, rows,
                    detached = detached,
                    connectionBannerShown = sample.connectionShown,
                    online = ConversationScroll.mayWrite(model.state),
                )
                pills = step.pills
                if (step.loadOlder) model.loadOlder(key, showingClearedHistory = scroll.options.showsClearedHistory)
                if (step.loadNewer) model.loadNewer(key)
            }
    }

    // MARK: - Drawing

    val rows = current.rows
    val inputs = current.inputs
    val highlighter = remember(inputs.highlighterNicks) { NickHighlighter(inputs.highlighterNicks) }

    // Which spoilers the reader has opened, by message id, then by the spoiler's ordinal within it.
    // Here rather than on a row, because a row is recycled and a reveal stored on one would reappear
    // on whatever scrolled into its place; keyed by id rather than position, because history loading
    // above shifts every position. For the screen's life and never pruned — forgetting one because
    // the reader scrolled away would re-hide something they had deliberately opened.
    val revealed = remember(key) { mutableStateMapOf<Long, Set<Int>>() }
    // Toggle, not just reveal: a second tap puts the box back. The height can't change — hidden and
    // revealed are the same glyphs, only recoloured — so nothing under the reader's thumb moves.
    fun toggleSpoiler(message: Message, ordinal: Int) {
        val opened = revealed[message.id].orEmpty()
        val next = if (ordinal in opened) opened - ordinal else opened + ordinal
        if (next.isEmpty()) revealed.remove(message.id) else revealed[message.id] = next
    }

    // MARK: - Link previews

    val currentOnOpenMedia by rememberUpdatedState(onOpenMedia)
    val openMedia: ((List<LinkPreview>, Int) -> Unit)? = if (onOpenMedia == null) {
        null
    } else {
        remember { { previews: List<LinkPreview>, index: Int -> currentOnOpenMedia?.invoke(previews, index) } }
    }

    val day = clock.value
    val style = rememberMessageTextStyle()
    val haptics = LocalHapticFeedback.current

    // The message sheets — actions, reactions, Ignore (lurker-ios#60, #183).
    val actions = rememberMessageActionsState(model, key)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Every way a sheet opens comes through here first: the keyboard would otherwise stay up over it,
    // and the sheet is sized to a few rows — shorter than the keyboard — so it would land entirely
    // behind it: the common case, mid-draft, long-pressing a line to reply to it.
    fun beforeSheet() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    fun onLongPress(press: RowPress) {
        if (!actions.press(press)) return
        beforeSheet()
        // The press has no other visible effect at the moment it fires, so the tap is what confirms it
        // registered, before the sheet animates in.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Keyed on everything the resolvers capture, the sheets' state and the keyboard's included.
    // Which previews draw: both settings and the instance's flag (`PreviewToggles`). A preview landing
    // doesn't come through here — each row reads its own URLs' versions (`PreviewUpdates`).
    val previewToggles = PreviewToggles.resolve(instanceHasPreviews, inputs.settings)
    val context = remember(rows, inputs, highlighter, style, day, actions, keyboard, focusManager, haptics, previewToggles, media, openMedia) {
        MessageListContext(
            style = style,
            networkName = { message -> ConversationModel.networkName(message, key, inputs.networks) },
            highlighter = highlighter,
            modePrefixes = inputs.modePrefixes,
            settings = inputs.settings,
            isStatusRow = { index -> rows.getOrNull(index)?.isStatus == true },
            row = { index -> rows.getOrNull(index) },
            revealedSpoilers = { message -> revealed[message.id].orEmpty() },
            onToggleSpoiler = ::toggleSpoiler,
            // The quoted line, through the same jump a search hit takes (lurker-ios#184) — it may be
            // outside the loaded slice, and then it's fetched.
            onJumpToReply = { quote ->
                scroll.jumpTo(quote.id)
                startJump()
            },
            reactions = ReactionContext(
                groups = { message ->
                    if (Reactions.canCarry(message, networkId = key.networkId)) inputs.reactionGroups(message.id) else emptyList()
                },
                canToggle = { message -> ConversationModel.chipToggles(message, key.target, inputs.support) },
                showsAdd = { message -> Reactions.lineTakes(message, target = key.target) },
                onToggle = { message, value ->
                    // The chip doesn't move until the network echoes it, so the tap is acknowledged
                    // here — and a send that went nowhere says so the same way, rather than nothing.
                    val sent = model.toggleReaction(value, message = message, key = key)
                    haptics.performHapticFeedback(if (sent) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
                },
                // The add chip, or a chip that can't toggle: the reaction sheet, keyboard down first.
                onOpen = { message -> if (actions.showReactions(message)) beforeSheet() },
            ),
            onLongPress = ::onLongPress,
            zone = day.zone,
            today = day.today,
            previews = previewToggles?.let { PreviewContext(model.linkPreviews, media, it) },
            onOpenMedia = openMedia,
        )
    }

    // Server errors are `MainScaffold`'s (`ServerErrorDialog`): always composed, so one never waits
    // unseen behind the buffer list on a phone and surfaces later over an unrelated conversation.

    // MARK: - Composer

    // Carry the reader to where the composer's line will be: a send, or the keyboard coming up to write
    // one. Detached, re-attach (the line won't be in the slice); else down to the tail, unless they
    // asked to keep their place up in history (`chat.keep_position_on_send`). "At the tail" is the
    // layout's own answer (`jobs.nearBottom`, which counts the bar's reservation), not one re-derived
    // here from the first visible item.
    fun carryToComposer() {
        val detached = model.state.buffers[key.id]?.hasMoreNewer == true
        val keeps = ComposerModel.keepsPositionWhileReading(model.state.settings, nearBottom = jobs.nearBottom)
        when (ComposerModel.sendScroll(detached = detached, keepsPosition = keeps)) {
            SendScroll.Reattach -> jumpToLatest()
            // The echo lands a moment later, over the socket; being at the tail when it does is what
            // makes the build follow it rather than count it on the pill.
            SendScroll.ToBottom -> if (current.rows.isNotEmpty() && !scroll.landingPending) listState.requestScrollToItem(0)
            SendScroll.Stay -> Unit
        }
    }

    val composer = rememberComposerState(
        model = model,
        key = key,
        kind = kind,
        onWillSend = ::carryToComposer,
        onOpenBuffer = onOpenBuffer,
        onShowProfile = onShowProfile,
    )

    // In-app notifications in the status row (lurker#1098). Whether the row can be seen: this screen
    // is the destination, started, with nothing over it — neither a dialog from either pane, nor a
    // message's sheet, nor the composer's own colour editor. iOS's `isUncovered`.
    val events = LocalAppEvents.current
    val toastCenter = LocalToastCenter.current
    val uncovered = takesToasts && !covered && !composer.editorOpen && actions.sheet == null
    val currentUncovered by rememberUpdatedState(uncovered)
    val started = rememberToastSurface(
        visible = { composer.canShowToasts() },
        // By `id`, which folds case: `#Lurker` and `#lurker` are one conversation.
        showsBuffer = { it.id == composer.key.id && composer.canShowToasts() },
        show = { composer.showToast(StatusToast.Notification(it)) },
    )
    val currentStarted by rememberUpdatedState(started)
    SideEffect {
        composer.canShowToasts = { currentUncovered && currentStarted }
        // A notice too long for the one-line row floats above the composer, where it can wrap.
        composer.onNoticeOverflow = { message -> events?.send(AppEvent.Notice(message, floats = true)) }
        // The sound comes with the toast going up, not with its arrival.
        composer.onNotificationShown = { notification -> toastCenter?.shown(notification) }
    }
    // Toasts that waited while the row was covered go up once it's clear.
    LaunchedEffect(uncovered, started) { if (uncovered && started) composer.toastSurfaceChanged() }

    // Uploads (lurker-android#15): this composer is where outside text lands while it's on screen — an
    // upload's link, Add to Message, a share's text — and its paperclip and paste start a run.
    val uploads = LocalUploadServices.current
    // Only a conversation takes uploads (`UploadTargets`): a server log or the Lurker console gets no
    // paperclip, and isn't where a finished link lands — with none on screen it goes to the clipboard.
    val takesUploads = UploadTargets.takes(kind)
    ComposerInsertTarget(if (takesUploads) uploads else null, key, composer::insert)
    val attachments = rememberAttachments(uploads, attaches = takesUploads)

    // The keyboard arriving FOR THE COMPOSER carries the reader to it — `keep_position_on_send` is
    // written as a rule about sending, but on a phone raising the keyboard to reply is what takes a
    // reader out of the history they were reading, well before they've typed anything (iOS's
    // `keyboardWillChange`). A reader already at the tail needs nothing more: the reverse layout keeps
    // item 0 at the bottom edge as the keyboard pushes it up. On the arrival only — the keyboard
    // leaving moves nobody — and only the composer's: `isImeVisible` is true for any field, and a
    // dialog's (Settings, Join, a network form, side by side over this pane) is no reason to move the
    // conversation. A hardware keyboard raises no IME, and moves nothing, as on iOS.
    val composing = composer.isFocused && imeVisible()
    val composingWas = remember { booleanArrayOf(composing) }
    LaunchedEffect(composing) {
        val arrived = composing && !composingWas[0]
        composingWas[0] = composing
        if (!arrived || current.rows.isEmpty() || scroll.landingPending) return@LaunchedEffect
        carryToComposer()
    }

    val placeholder = ConversationModel.placeholder(hasRows = rows.isNotEmpty(), inputs = inputs, forceLoading = forceLoading)
    ConversationContent(
        showsBack = showsBack,
        onBack = onBack,
        // A channel's nick list. iOS reaches it by a swipe in from the right edge as well as the info
        // sheet's Members row; on Android that edge is the system's back gesture, so it's a button here.
        onShowMembers = if (kind == BufferKind.Channel) onShowMembers else null,
        onShowInfo = onShowInfo,
        sideBySide = sideBySide,
        onOpenView = onOpenView,
        banner = banner,
        onConnectionBannerShown = { connectionShown = it },
        rows = rows,
        keys = current.keys,
        context = context,
        placeholder = placeholder,
        empty = ConversationModel.emptyState(kind, inputs.buffer?.target ?: key.target),
        listState = listState,
        pills = pills,
        flash = flash,
        onJumpToUnread = { if (scroll.jumpToFirstUnread()) startJump() },
        onJumpToLatest = ::jumpToLatest,
        bottomBar = {
            ComposerBar(
                composer, uiPreferences.composerAutocapitalizes, uiPreferences.composerEnterSends, clockKey = day,
                attachments = attachments,
                sideBySide = sideBySide,
                // The highlight count goes where the highlights are.
                onHighlightCountTap = onBack,
                onToastTap = { notification -> openNotification(model, events, notification) },
            )
        },
        // Inside the screen's link-opener provider, so Open Link uses the same `SafeUriHandler` as a tap.
        sheets = {
            MessageActionsHost(
                state = actions,
                model = model,
                key = key,
                // The line as the list shows it — a relayed line as the person inside it — which is
                // whom the Reply addresses.
                onReply = composer::startReply,
                onShowProfile = onShowProfile,
            )
        },
        overlay = { bottom ->
            // The app's notices, while a conversation is up: into the status row when one fits and
            // has no button (iOS's `showNotice`), else a snackbar floated above the composer, where it
            // can wrap. An invitation's Join stays a snackbar: the row has no room for a button. Not
            // while this screen is leaving, or its session is over: the scaffold's host has them then.
            if (events != null && takesToasts) {
                NoticeHost(
                    events,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom),
                    priority = NoticeHost.PRIORITY_CONVERSATION,
                    intercept = { notice -> notice.action == null && !notice.floats && uncovered && composer.showNotice(notice.message) },
                )
            }
        },
    )
}

/** A frame, stamped with the order the screen processed it in. Identity is the comparison. */
private class Frame(val seq: Long, val state: ChatState)

/** Projected inputs, with the frame they came from. */
private class Stamped(val seq: Long, val inputs: ConversationInputs)

/** The frame stamp: a counter, not state — reading it must not recompose anything. */
private class FrameCounter {
    var value = 0L
        private set

    fun next(): Long = ++value
}

/** The screen's running coroutines and bookkeeping — not state. */
private class Jobs(
    /** Whether the reader was at the bottom, as of the last layout that measured what's drawn. */
    var nearBottom: Boolean,
) {
    var converging: Job? = null
    var flashes = 0L

    /** This buffer's message list as of the last mark-read. */
    var markedFor: List<Message>? = null

    /** The last frame the frame stream processed — what a tap's request is judged against. */
    var lastFrame: ChatState? = null
}

/** What the layout stream reads, in one value, so a change to any of it re-decides. */
private data class LayoutSample(
    val layout: LazyListLayoutInfo,
    val rows: BuiltRows,
    val connectionShown: Boolean,
    val revision: Int,
)

/** The row a finished jump pulses, by key, and which pulse — so the same row can pulse twice. */
internal class RowFlash(val key: String, val nonce: Long)

/** Saves the machine's [ConversationScroll.Memory] across recreation. */
private fun scrollSaver(kind: BufferKind): Saver<ConversationScroll, ConversationScroll.Memory> =
    Saver(save = { it.memory() }, restore = { ConversationScroll(kind, it) })

/** The laid-out items, in plain numbers. */
private fun visibleItems(listState: LazyListState): List<VisibleItem> =
    listState.layoutInfo.visibleItemsInfo.map { VisibleItem(it.key, it.index, it.offset, it.size) }

/**
 * Wait until the lazy list has measured [rows] — every laid-out item's key is the one these rows put
 * at that index. A scroll asked of the list before then would be measured against the previous
 * rows, and an index past their end is clamped and kept. Bounded: a list that never lays out (an
 * empty build draws the placeholder instead) mustn't hang the caller.
 */
private suspend fun awaitLayoutOf(listState: LazyListState, rows: BuiltRows) {
    withTimeoutOrNull(LAYOUT_WAIT_MS) {
        snapshotFlow { listState.layoutInfo }.first { info ->
            info.totalItemsCount == rows.rows.size &&
                info.visibleItemsInfo.isNotEmpty() &&
                info.visibleItemsInfo.all { rows.keys.getOrNull(rows.itemIndex(it.index)) == it.key }
        }
    }
}

/**
 * Put [row] in the middle of the viewport — clamped near an edge, as centred as it can be (a recent
 * row with little below it lands lower). Brought on screen first if it isn't, then nudged by the
 * difference: the list's scroll-to-item puts an item at its START, which in a reverse layout is
 * the bottom edge, and takes no negative offset to lift it from there.
 */
private suspend fun centre(listState: LazyListState, rows: BuiltRows, row: Int) {
    val key = rows.keys[row]
    var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    if (item == null) {
        listState.scrollToItem(rows.itemIndex(row))
        item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
    }
    val info = listState.layoutInfo
    val contentEnd = info.viewportEndOffset - info.afterContentPadding
    val wanted = (contentEnd - item.size) / 2
    // Forward is toward older rows, which lowers every offset — so forward by how far above the
    // middle it sits (a negative amount, backward, when it's below).
    listState.scrollBy((item.offset - wanted).toFloat())
}

/** iOS's `isNearBottom`: within 80pt of the newest row still counts as following the conversation. */
private val FOLLOW_SLOP = 80.dp

/** iOS's paging distance: within 300pt of either end of what's loaded, ask for the next page. */
private val PAGING_DISTANCE = 300.dp

/**
 * How many frames a jump re-centres before settling (iOS's `jumpConvergePasses`). The first pass
 * lands — a lazy list measures the rows it shows rather than estimating them — so the rest are the
 * correction for rows still composing under it.
 */
private const val JUMP_PASSES = 3

/** How long a converging pass waits for the list to measure a new build. */
private const val LAYOUT_WAIT_MS = 1_000L

/** iOS's pulse: the wash holds for half a second, then fades over 1.2s. */
private const val FLASH_HOLD_MS = 500L
private const val FLASH_FADE_MS = 1_200

/**
 * The day the labels read, and the zone that decides it. A row carries only an instant, and the day
 * dividers say "Today" and "Yesterday" — so a buffer left open past midnight would go on calling
 * yesterday "Today" until some unrelated change redrew it, and a quiet buffer is exactly the one that
 * gets none. iOS's "date-label invalidation" (`NSCalendarDayChanged`), which also covers what a
 * midnight timer can't: a time-zone change moves the boundary itself (and every row's day with it —
 * the builds take the zone), and a clock set by hand moves "now". A locale change recreates the
 * activity, which is the other half of iOS's rule.
 */
internal data class DayClock(val today: LocalDate, val zone: ZoneId) {
    companion object {
        fun now(): DayClock {
            val zone = ZoneId.systemDefault()
            return DayClock(LocalDate.now(zone), zone)
        }
    }
}

@Composable
internal fun rememberDayClock() = run {
    val context = LocalContext.current.applicationContext
    produceState(initialValue = DayClock.now(), context) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changed.trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        // System broadcasts reach an unexported receiver; nothing else should be able to.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            while (true) {
                // Measured between instants, in the zone, so a day that's 23 or 25 hours long (a DST
                // change) still wakes at its midnight rather than an hour either side of it.
                val zone = ZoneId.systemDefault()
                val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
                withTimeoutOrNull(Duration.between(Instant.now(), midnight).toMillis() + 1_000) { changed.receive() }
                value = DayClock.now()
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}

/**
 * The row-flash wash's strength: 1 for half a second, then faded to 0 over 1.2s (iOS's `flashRow`),
 * or null for a row that isn't flashing. A state, read at draw time, so the fade redraws the row
 * without recomposing it every frame.
 */
@Composable
private fun rememberFlashStrength(nonce: Long?): State<Float>? {
    if (nonce == null) return null
    val strength = remember { Animatable(1f) }
    LaunchedEffect(nonce) {
        strength.snapTo(1f)
        delay(FLASH_HOLD_MS)
        strength.animateTo(0f, tween(FLASH_FADE_MS, easing = LinearOutSlowInEasing))
    }
    return strength.asState()
}

/**
 * The screen itself, stateless — for previews, and so what it draws is visibly only what it's given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationContent(
    showsBack: Boolean,
    onBack: () -> Unit,
    banner: ConnectionBannerState,
    rows: List<MessageRow>,
    keys: List<String>,
    context: MessageListContext,
    placeholder: BufferPlaceholder,
    empty: StateModel,
    listState: LazyListState,
    pills: ConversationScroll.Pills = ConversationScroll.Pills(),
    flash: RowFlash? = null,
    onConnectionBannerShown: (Boolean) -> Unit = {},
    onJumpToUnread: () -> Unit = {},
    onJumpToLatest: () -> Unit = {},
    /** The composer — the scaffold's bottom bar, so the list's reservation includes it. */
    bottomBar: @Composable () -> Unit = {},
    /** What floats over the list's bottom edge, given the reservation's height: the screen's notices. */
    overlay: @Composable BoxScope.(bottom: Dp) -> Unit = {},
    /** The message sheets (`MessageActionsHost`), drawn inside the screen's `LocalUriHandler` provider. */
    sheets: @Composable () -> Unit = {},
    onShowMembers: (() -> Unit)? = null,
    onShowInfo: (() -> Unit)? = null,
    sideBySide: Boolean = false,
    onOpenView: ((AppView) -> Unit)? = null,
) {
    val colors = LurkerTheme.colors
    Scaffold(
        // ⚠⚠ A focus target of the screen's own, so opening a buffer never focuses the composer. The
        // list–detail scaffold requests focus into the detail pane on every navigation
        // (`ThreePaneScaffold`'s `LaunchedEffect(currentDestination)`, on the pane's focus GROUP), and a
        // group hands that to its first child that can take focus. In touch mode the buttons can't
        // (theirs is "system defined"), but a text field always can — so the composer took it, and a
        // text field gaining focus raises the keyboard: every buffer opened with the IME up. This
        // target is that first child, takes the pane's focus itself, and shows nothing for it; the
        // field is focused only by a tap on it (or a Reply), which goes to the field directly.
        modifier = Modifier.focusTarget(),
        // What the log sits on — the web's `look.color.bg`, not the system's ground.
        containerColor = colors.bg,
        topBar = {
            // No title: where you are and how the connection is doing sit in the composer's status
            // row instead, and the top of the screen goes back to the conversation (iOS's `updateTitle`).
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (showsBack) {
                        IconButton(onClick = onBack) { Icon(LurkerIcons.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    // iOS's phone bar, trailing-most first: the views menu ("…"), then Info — the one
                    // item about THIS buffer rather than a view over all. Members leads them here, being
                    // the one iOS reaches by an edge swipe Android can't have (see `ConversationScreen`).
                    if (onShowMembers != null) {
                        IconButton(onClick = onShowMembers) { Icon(LurkerIcons.Group, contentDescription = "Members") }
                    }
                    if (onShowInfo != null) {
                        IconButton(onClick = onShowInfo) { Icon(LurkerIcons.Info, contentDescription = "Info") }
                    }
                    // The views — Search, Activity, Bookmarks — trailing-most, per layout (iOS's
                    // `applyBarLayout`): behind one ⋮ on top of the list, Search a button beside it.
                    // Uploads among them, which from here can Add to Message (`MainScaffold`).
                    if (onOpenView != null) ConversationViewsActions(sideBySide = sideBySide, onOpenView = onOpenView)
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        // Links open in the browser through the platform's handler — which throws when nothing on the
        // device takes the address (a `mailto:` with no mail app, an `ftp://`). A tap that does nothing
        // is better than a crash.
        val platform = LocalUriHandler.current
        val uriHandler = remember(platform) { SafeUriHandler(platform) }
        val bottom = padding.calculateBottomPadding()
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = padding.calculateTopPadding(),
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                    ),
            ) {
                // Never with no rows (`None`); drawn as the empty list it is. One `StateView` for both
                // states, so "Loading messages…" settling to "No messages yet" is a change TalkBack
                // reads out rather than one node swapped for another.
                if (rows.isEmpty() && placeholder != BufferPlaceholder.None) {
                    StateView(if (placeholder == BufferPlaceholder.Loading) ConversationModel.LOADING else empty)
                } else if (rows.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        // Newest at the bottom, and the list starts there: item 0 is the last row.
                        reverseLayout = true,
                        // The composer's height (and the keyboard's, under it): the newest row sits just
                        // above the bar, and the rows scroll on under it.
                        contentPadding = PaddingValues(bottom = bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            count = rows.size,
                            key = { item -> keys[rows.size - 1 - item] },
                            contentType = { item -> MessageListLayout.contentType(rows[rows.size - 1 - item]) },
                        ) { item ->
                            val index = rows.size - 1 - item
                            // The warm pulse behind a row a jump just landed on, in the highlight
                            // colour — behind the content, so a matched line's own wash reads over it.
                            val pulse = rememberFlashStrength(flash?.takeIf { it.key == keys[index] }?.nonce)
                            val wash = colors.highlightBubble
                            val modifier = if (pulse == null) {
                                Modifier
                            } else {
                                Modifier.drawBehind {
                                    val strength = pulse.value
                                    if (strength > 0f) drawRect(wash.copy(alpha = wash.alpha * strength))
                                }
                            }
                            // A long press is the row's own (`MessageListContext.onLongPress`).
                            MessageListRow(rows[index], index, context, modifier = modifier)
                        }
                    }
                }
                // Over the rows, not above them: they float, and the list scrolls under them. Each sits
                // on the edge it takes you to — the unread banner up top (in the connection banner's
                // slot, which wins it), the jump pill in the bottom-trailing corner.
                ConnectionBanner(
                    state = banner,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 16.dp, end = 16.dp),
                    onShownChange = onConnectionBannerShown,
                )
                UnreadBanner(
                    visible = pills.showsUnread,
                    onClick = onJumpToUnread,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
                JumpToLatestButton(
                    visible = pills.showsLatest,
                    newCount = pills.newCount,
                    onClick = onJumpToLatest,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottom + 12.dp),
                )
                overlay(bottom)
                sheets()
            }
        }
    }
}

// MARK: - Previews

@Composable
private fun ConversationPreview(dark: Boolean, empty: Boolean, pills: ConversationScroll.Pills = ConversationScroll.Pills()) {
    LurkerTheme(darkTheme = dark) {
        val style = rememberMessageTextStyle()
        val rows = if (empty) emptyList() else previewMessageRows()
        val context = MessageListContext.over(
            rows,
            style = style,
            highlighter = NickHighlighter(listOf("alice", "bob")),
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 7, 25),
        )
        ConversationContent(
            showsBack = true,
            onBack = {},
            banner = ConnectionBannerState.Hidden,
            rows = rows,
            keys = MessageListLayout.rowKeys(rows),
            context = context,
            placeholder = if (empty) BufferPlaceholder.Empty else BufferPlaceholder.None,
            empty = ConversationModel.emptyState(BufferKind.Channel, "#lurker"),
            listState = rememberLazyListState(),
            pills = pills,
            onShowMembers = {},
            onShowInfo = {},
        )
    }
}

@Preview(name = "Conversation — light", widthDp = 360, heightDp = 640)
@Composable
private fun ConversationPreviewLight() = ConversationPreview(dark = false, empty = false)

@Preview(name = "Conversation — dark", widthDp = 360, heightDp = 640)
@Composable
private fun ConversationPreviewDark() = ConversationPreview(dark = true, empty = false)

private val previewPills = ConversationScroll.Pills(showsLatest = true, newCount = 4, showsUnread = true)

@Preview(name = "Unread above, new below — light", widthDp = 360, heightDp = 640)
@Composable
private fun PillsPreviewLight() = ConversationPreview(dark = false, empty = false, pills = previewPills)

@Preview(name = "Unread above, new below — dark", widthDp = 360, heightDp = 640)
@Composable
private fun PillsPreviewDark() = ConversationPreview(dark = true, empty = false, pills = previewPills)

@Preview(name = "Empty — light", widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreviewLight() = ConversationPreview(dark = false, empty = true)

@Preview(name = "Empty — dark", widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreviewDark() = ConversationPreview(dark = true, empty = true)

/** Whether the soft keyboard is up — `WindowInsets.isImeVisible`, behind its opt-in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun imeVisible(): Boolean = WindowInsets.isImeVisible
