// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.flow.conflate
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.message.MessageListContext
import net.amiantos.lurker.ui.message.MessageListLayout
import net.amiantos.lurker.ui.message.MessageListRow
import net.amiantos.lurker.ui.message.ReactionContext
import net.amiantos.lurker.ui.message.previewMessageRows
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.shell.ConnectionBanner
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.shell.StatusTitle
import net.amiantos.lurker.ui.shell.StatusTitleText
import net.amiantos.lurker.ui.shell.rememberConnectionBannerState
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferPlaceholder
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.rendering.NickHighlighter
import net.amiantos.lurkerkit.session.ChatViewModel
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A buffer's messages, live. lurker-ios's `ChatViewController`, first cut (lurker-android#10): the
 * rows the kit builds for this buffer, drawn compact, with the title, the connection banner, and the
 * placeholder behind an empty list.
 *
 * Messages arrive two ways and are treated identically: the slice the server sends in reply to the
 * hydrate below, and live frames after that — including the echo of our own sends, which is why
 * there's no optimistic-row bookkeeping here.
 *
 * U2b owns everything about *where* the list is: paging older and newer with scroll anchoring,
 * landing on the first unread with the divider and banner, jumps (search, bookmark, reply quote,
 * notification) including outside the loaded slice, jump to latest, and mark-read. The list's
 * [LazyListState] is owned here, hoisted, so U2b can drive it. U3: the composer. U6: long-press
 * message actions and the reaction picker. U8: link previews.
 *
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
 */
@Composable
fun ConversationScreen(
    model: ChatViewModel,
    key: BufferKey,
    resting: Boolean,
    showsBack: Boolean,
    onBack: () -> Unit,
    onVisit: () -> Unit,
    onGone: () -> Unit,
    onMoved: (BufferKey) -> Unit,
) {
    val kind = remember(key) { BufferKind.of(networkId = key.networkId, target = key.target) }

    val currentOnVisit by rememberUpdatedState(onVisit)
    LaunchedEffect(key.id, resting) {
        if (!resting) currentOnVisit()
    }

    // The two things the screen must DO about the store, rather than draw: notice the buffer
    // disappearing (or moving), and ask for its history. Run against every frame straight off the
    // publisher — side effects, never Compose state — in iOS's order: a buffer that's gone or moved
    // asks for nothing.
    val currentOnGone by rememberUpdatedState(onGone)
    val currentOnMoved by rememberUpdatedState(onMoved)
    LaunchedEffect(model, key) {
        val watch = BufferWatch(key, kind)
        val hydrate = HydrateGate(kind)
        // Once: every later frame would say the same, and each would ask the navigator to leave again.
        var left = false
        model.statePublisher.conflate().collect { state ->
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
            // U2b: `jumpPending` — a pending jump hydrates through its `around` slice instead.
            hydrate.check(state.connection, state.buffers[key.id], state.burstGeneration)?.let(model::hydrate)
            // U2b: mark-read, once the read boundary is latched (never before — marking read is what
            // destroys the record of where the reader left off).
        }
    }

    val banner = rememberConnectionBannerState(model)

    // The title moves on its own — a DM peer's presence turns over with nothing else changing — and it
    // moves nothing else, so it's its own stream rather than a reason to rebuild every row.
    val titleFlow = remember(model, key) {
        model.statePublisher.conflate().map { ConversationModel.title(it, key, kind) }.distinctUntilChanged()
    }
    val initialTitle = remember(model, key) { ConversationModel.title(model.state, key, kind) }
    val title by titleFlow.collectAsStateWithLifecycle(initialValue = initialTitle)

    // The rows' inputs, re-projected on every frame AND on a one-second tick while anybody is typing:
    // a typing entry's lease expires by the clock, not by a frame — the last thing a peer sends is
    // `active`, and what happens next is nothing — so without the tick the line would sit there until
    // some unrelated frame redrew it. Distinct by `same`, so most ticks change nothing.
    val ticks = remember(key) { MutableStateFlow(0) }
    val projector = remember(key) { ConversationProjector(key, kind) }
    val inputsFlow = remember(model, key) {
        combine(model.statePublisher.conflate(), ticks) { state, _ -> projector.project(state) }
            .distinctUntilChanged(ConversationInputs::same)
    }
    val initialInputs = remember(model, key) { projector.project(model.state) }
    val inputs by inputsFlow.collectAsStateWithLifecycle(initialValue = initialInputs)
    val someoneTyping = inputs.typists.isNotEmpty()
    LaunchedEffect(someoneTyping) {
        // One second is well inside the shortest lease (6s), and coarse enough to be free.
        while (someoneTyping) {
            delay(1_000)
            ticks.value += 1
        }
    }

    val rows = remember(inputs) { ConversationModel.buildRows(inputs) }
    val keys = remember(rows) { MessageListLayout.rowKeys(rows) }
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

    val today by rememberToday()
    val style = rememberMessageTextStyle()
    val haptics = LocalHapticFeedback.current
    val context = remember(rows, inputs, highlighter, style, today) {
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
            // U2b: jump to the quoted line (`jumpToMessage`). Until then the quote is drawn and not
            // tappable — a quote that lights up and goes nowhere reads as broken.
            onJumpToReply = null,
            reactions = ReactionContext(
                groups = { message ->
                    if (Reactions.canCarry(message, networkId = key.networkId)) inputs.reactionGroups(message.id) else emptyList()
                },
                canToggle = { message ->
                    Reactions.canSend(message, target = key.target, networkCanReact = inputs.canReact)
                },
                showsAdd = { message -> Reactions.lineTakes(message, target = key.target) },
                onToggle = { message, value ->
                    // The chip doesn't move until the network echoes it, so the tap is acknowledged
                    // here — and a send that went nowhere says so the same way, rather than nothing.
                    val sent = model.toggleReaction(messageId = message.id, value = value)
                    haptics.performHapticFeedback(if (sent) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
                },
                // U6: the reaction sheet (`ReactionSheetViewController`).
                onOpen = {},
            ),
            today = today,
        )
    }

    // New rows arriving at the bottom stay pinned to the bottom when the reader is there; anywhere
    // else the viewport holds. ⚠ A reverse layout does NOT do the first half by itself: a lazy list
    // keeps its first visible item by KEY, and the newest row is inserted in front of that item, so a
    // reader at the bottom would watch new lines arrive below the viewport. So when the rows change and
    // the reader was at the bottom (read before this frame lays out), the list is asked to stay at item
    // 0 for its next measure. Holding the viewport elsewhere is the lazy list's own key anchoring.
    // U2b: everything finer — prepends, jumps, the detached-slice rule (`followsTail`'s `wasDetached`).
    val listState = rememberLazyListState()
    val followSlop = with(LocalDensity.current) { FOLLOW_SLOP.roundToPx() }
    val atBottom by remember(listState, followSlop) {
        derivedStateOf {
            ConversationModel.followsTail(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, followSlop)
        }
    }
    val drawn = remember(key) { DrawnRows() }
    val follow = drawn.rows != null && drawn.rows !== rows && atBottom
    SideEffect {
        if (follow) listState.requestScrollToItem(0)
        drawn.rows = rows
    }

    // Server errors are `MainScaffold`'s (`ServerErrorDialog`): always composed, so one never waits
    // unseen behind the buffer list on a phone and surfaces later over an unrelated conversation.

    val placeholder = ConversationModel.placeholder(hasRows = rows.isNotEmpty(), inputs = inputs)
    ConversationContent(
        title = title,
        showsBack = showsBack,
        onBack = onBack,
        banner = banner,
        rows = rows,
        keys = keys,
        context = context,
        placeholder = placeholder,
        empty = ConversationModel.emptyState(kind, inputs.buffer?.target ?: key.target),
        listState = listState,
    )
}

/** What the list last drew — not state: comparing it must not itself recompose anything. */
private class DrawnRows {
    var rows: List<MessageRow>? = null
}

/** iOS's `isNearBottom`: within 80pt of the newest row still counts as following the conversation. */
private val FOLLOW_SLOP = 80.dp

/**
 * Today's date, advanced at local midnight. The day dividers say "Today" and "Yesterday", and a row
 * carries only its day — so a buffer left open past midnight would go on calling yesterday "Today"
 * until some unrelated change redrew it, and a quiet buffer is exactly the one that gets none. iOS
 * reloads on `NSCalendarDayChanged` for the same reason. (A locale change recreates the activity.)
 */
@Composable
private fun rememberToday() = produceState(initialValue = LocalDate.now()) {
    while (true) {
        // Measured between instants, in the zone, so a day that's 23 or 25 hours long (a DST
        // change) still wakes at its midnight rather than an hour either side of it.
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        delay(Duration.between(now, midnight).toMillis() + 1_000)
        value = LocalDate.now()
    }
}

/**
 * The screen itself, stateless — for previews, and so what it draws is visibly only what it's given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationContent(
    title: StatusTitle,
    showsBack: Boolean,
    onBack: () -> Unit,
    banner: ConnectionBannerState,
    rows: List<MessageRow>,
    keys: List<String>,
    context: MessageListContext,
    placeholder: BufferPlaceholder,
    empty: EmptyState,
    listState: LazyListState,
) {
    val colors = LurkerTheme.colors
    Scaffold(
        // What the log sits on — the web's `look.color.bg`, not the system's ground.
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { StatusTitleText(title) },
                navigationIcon = {
                    if (showsBack) {
                        IconButton(onClick = onBack) { Icon(LurkerIcons.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    // U5: the member list and buffer info. U7: search and the views menu. iOS sets
                    // these per layout (`applyBarLayout`), beside the list or on top of it.
                },
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        // Links open in the browser through the platform's handler — which throws when nothing on the
        // device takes the address (a `mailto:` with no mail app, an `ftp://`). A tap that does nothing
        // is better than a crash.
        val platform = LocalUriHandler.current
        val uriHandler = remember(platform) { SafeUriHandler(platform) }
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
                if (rows.isEmpty()) {
                    when (placeholder) {
                        // Never with no rows; drawn as the empty list it is.
                        BufferPlaceholder.None -> Unit
                        BufferPlaceholder.Loading -> StateView(title = "Loading messages…", isLoading = true)
                        BufferPlaceholder.Empty -> StateView(title = empty.title, subtitle = empty.subtitle)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        // Newest at the bottom, and the list starts there: item 0 is the last row.
                        reverseLayout = true,
                        // U3: the composer's height joins this reservation.
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            count = rows.size,
                            key = { item -> keys[rows.size - 1 - item] },
                            contentType = { item -> MessageListLayout.contentType(rows[rows.size - 1 - item]) },
                        ) { item ->
                            val index = rows.size - 1 - item
                            // U6: long-press for the line's actions (lurker-ios#60).
                            MessageListRow(rows[index], index, context)
                        }
                    }
                }
                // Over the rows, not above them: it floats, and the list scrolls under it.
                ConnectionBanner(
                    state = banner,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

/** The platform's link opener, minus the crash when nothing on the device takes the link. */
private class SafeUriHandler(private val platform: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        try {
            platform.openUri(uri)
        } catch (e: ActivityNotFoundException) {
            Log.w("Lurker", "no app opens $uri", e)
        } catch (e: IllegalArgumentException) {
            Log.w("Lurker", "can't open $uri", e)
        }
    }
}

// MARK: - Previews

@Composable
private fun ConversationPreview(dark: Boolean, empty: Boolean) {
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
            title = StatusTitle(title = "#lurker", status = StatusLight.Good, detail = "Libera"),
            showsBack = true,
            onBack = {},
            banner = ConnectionBannerState.Hidden,
            rows = rows,
            keys = MessageListLayout.rowKeys(rows),
            context = context,
            placeholder = if (empty) BufferPlaceholder.Empty else BufferPlaceholder.None,
            empty = ConversationModel.emptyState(BufferKind.Channel, "#lurker"),
            listState = rememberLazyListState(),
        )
    }
}

@Preview(name = "Conversation — light", widthDp = 360, heightDp = 640)
@Composable
private fun ConversationPreviewLight() = ConversationPreview(dark = false, empty = false)

@Preview(name = "Conversation — dark", widthDp = 360, heightDp = 640)
@Composable
private fun ConversationPreviewDark() = ConversationPreview(dark = true, empty = false)

@Preview(name = "Empty — light", widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreviewLight() = ConversationPreview(dark = false, empty = true)

@Preview(name = "Empty — dark", widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreviewDark() = ConversationPreview(dark = true, empty = true)
