// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.list

import kotlinx.coroutines.flow.conflate
import net.amiantos.lurker.ui.networks.NetworkSheets
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.LocalAppEvents
import net.amiantos.lurker.ui.feeds.AppView
import net.amiantos.lurker.ui.feeds.AppViewMenuItem
import net.amiantos.lurker.ui.feeds.ViewsLayout
import net.amiantos.lurker.ui.shell.ConnectionBanner
import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.shell.StatusTitle
import net.amiantos.lurker.ui.shell.StatusTitleText
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferListPlaceholder
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The app's home screen: every buffer you have, and the way into all of them. lurker-ios's
 * `BufferListViewController`.
 *
 * The list pane of `MainScaffold`'s list–detail scaffold: on a phone it is the screen you go into a
 * conversation from and come back to, with predictive back; side by side it is the sidebar beside
 * the conversation. It reports a pick through [onOpen] and doesn't know what happens next.
 *
 * ⚠ The store is read in two stages, never collected raw — see [BufferListInputs]. And there is no
 * "is this screen on screen" gate, as iOS needs (its list outlives every conversation pushed over
 * it, so it rebuilt for a screen nobody could see): a pane that isn't shown isn't composed, so
 * nothing here runs while a conversation covers it on a phone.
 *
 * @param hasRenderedList the burst latch, hoisted to `MainScaffold` so it survives this pane leaving
 *   composition and a configuration change — see [BufferListModel.drawsList].
 * @param openKey the buffer in the conversation pane, or null.
 * @param sideBySide whether the list is beside the conversation rather than instead of it — iOS's
 *   `marksOpenBuffer`. It gates the open mark, and the banner: side by side the conversation pane
 *   carries it, so the two never draw over each other or are read out twice.
 * @param onClose leaves a channel or closes a buffer — the swipe and the menu both come here. False
 *   when it couldn't go out: the row stays, and the swipe puts it back.
 * @param onOpenSettings opens Settings, which `MainScaffold` hosts (with Sign Out inside it).
 * @param sheets the networks dialogs, hosted by `MainScaffold`: "+" opens Join Channel and Add
 *   Network, and Settings → Networks opens the networks list over Settings.
 * @param onOpenView opens Search, Activity or Bookmarks — the feeds `MainScaffold` hosts. Offered here
 *   only on its own screen (`ViewsLayout`): side by side the conversation column carries them.
 */
@Composable
fun BufferListScreen(
    model: ChatViewModel,
    hasRenderedList: Boolean,
    onListRendered: () -> Unit,
    openKey: BufferKey?,
    sideBySide: Boolean,
    onOpen: (Buffer) -> Unit,
    onClose: (Buffer) -> Boolean,
    onOpenSettings: () -> Unit,
    sheets: NetworkSheets,
    onOpenView: (AppView) -> Unit = {},
) {
    // Stage one: map every frame to what the list draws, and drop the frames that change none of
    // it. Stage two (below) builds the sections from what's left.
    val inputsFlow = remember(model) {
        model.statePublisher
            // Conflated: a burst's frames for other buffers needn't be mapped one by one when only
            // the latest is ever drawn.
            .conflate()
            .map(BufferListInputs::of)
            .distinctUntilChanged { old, new -> BufferListInputs.same(old, new) }
    }
    val initialInputs = remember(model) { BufferListInputs.of(model.state) }
    val inputs by inputsFlow.collectAsStateWithLifecycle(initialValue = initialInputs)

    var optimistic by remember { mutableStateOf<OptimisticFavorites?>(null) }
    var drag by remember { mutableStateOf<DragSession?>(null) }
    val events = LocalAppEvents.current

    // ⚠⚠ The burst gate. `hasRenderedList` is NEVER reset here. On iOS it used to be cleared
    // whenever `backlogComplete` was false — meant as "a fresh session waits again" — which
    // silently disarmed the fallback below: the timer set the flag, the next rebuild cleared it
    // again, so the list blanked and re-armed on a 4-second loop forever. On a server that never
    // sends the terminator that is a permanent flashing spinner over a full store: the exact
    // failure the fallback exists to prevent, caused by the fallback. A new session gets a new
    // latch anyway — sign-out swaps `MainScaffold` out entirely (`AppRoot`), and the latch with it
    // — so nothing has to notice a session change to do it.
    val draws = BufferListModel.drawsList(rosterSettled = inputs.rosterSettled, hasRenderedList = hasRenderedList)
    // A settled list has been drawn: latch it. The fallback that latches it when the burst never
    // settles is `MainScaffold`'s, which runs for the session rather than for this pane.
    if (draws && !hasRenderedList) {
        SideEffect { onListRendered() }
    }

    // ANY favorites change (the echo, or another device's edit) is authoritative, and drops the
    // shadow order — see `orderedFavorites`.
    LaunchedEffect(inputs.favorites) {
        if (optimistic?.isCurrent(inputs.favorites) == false) optimistic = null
    }
    // …and so does the socket ending. The shadow waits for an echo the dropped socket took with it,
    // and the reconnect's burst re-sends the list unchanged, which releases nothing (sweep L29) — the
    // case of a drop written into a socket that had died without saying so.
    // And with a socket that hasn't had its snapshot: a foreground reconnect can replace one that
    // died without saying so while the state never left Connected.
    LaunchedEffect(inputs.connection, inputs.snapshotSinceOpen) {
        if (inputs.connection != SocketStatus.Connected || !inputs.snapshotSinceOpen) optimistic = null
    }

    // Stage two. `inputs` compares by identity, so this rebuilds exactly when stage one let a frame
    // through, or the shadow order moved.
    val built = remember(inputs, optimistic, draws) {
        if (draws) BufferListModel.buildSections(inputs, optimistic) else emptyList()
    }
    val latestBuilt by rememberUpdatedState(built)
    val sections = drag?.rendered() ?: built
    val placeholder = BufferListModel.placeholder(inputs, built, draws)

    val actions = BufferListActions(
        onOpen = { row ->
            // A friend's DM often isn't a materialized buffer — a DM that's closed server-side has
            // no row in `state.buffers`, and the conversation's hydrate only fires for a buffer that
            // already has one. Send open-buffer explicitly here (as /query does) so the server ships
            // that DM's backlog and it opens, instead of hanging on the loading spinner.
            //
            // Gated on the row being ABSENT, which is the only case that describes. On iOS it used
            // to fire on every friend tap, on the reasoning that a redundant one just re-hydrates —
            // no longer true. `open-buffer` is a WRITE: it announces to every other device the user
            // owns, it's refused outright for a paused account, and the conversation's own hydrate
            // would fetch the same backlog a second time. Gated on the explicit Friends-row flag,
            // not a presence proxy: presence is styling every DM row carries, not a fact about where
            // the buffer came from.
            //
            // ⚠ And it goes there once that row is in, not at once (lurker-ios#201). `open-buffer` only
            // queues the write; a conversation opened before the row lands finds a settled roster
            // without it and backs straight out to this list. The kit's landing
            // (`AppEvent.OpenBuffer`) is the navigation.
            if (row.isFriend && model.state.buffers[row.buffer.key.id] == null) {
                model.openAndShow(row.buffer.key)
            } else {
                onOpen(row.buffer)
            }
        },
        onClose = onClose,
        menuFor = { buffer -> BufferListModel.rowMenu(model.state, buffer) },
        onJoin = { buffer ->
            // No navigation — the row lighting up is the answer — and a refusal says why
            // (lurker-ios#57: `AppEvent.Notice`, the scaffold's snackbar).
            val networkId = buffer.networkId
            if (networkId != null) model.requestJoin(networkId = networkId, channel = buffer.target, opens = false)
        },
        onToggleFavorite = { buffer, isFavorite ->
            val networkId = buffer.networkId
            if (networkId != null) {
                if (isFavorite) {
                    model.unfavoriteBuffer(networkId = networkId, target = buffer.target)
                } else {
                    model.favoriteBuffer(networkId = networkId, target = buffer.target)
                }
            }
        },
        onDragStarted = { sectionId, key ->
            drag = DragSession.begin(latestBuilt, sectionId, key, inputs.favorites)
        },
        onMove = move@{ fromLazyKey, toLazyKey ->
            val session = drag ?: return@move
            val token = session.sectionId.token
            // Confined to the row's OWN group — the two are kind-filtered views of one list, and a
            // row dropped anywhere foreign would snap back on the next rebuild.
            if (ItemId.sectionTokenOf(fromLazyKey) != token || ItemId.sectionTokenOf(toLazyKey) != token) return@move
            drag = session.moved(ItemId.rowKeyOf(fromLazyKey), ItemId.rowKeyOf(toLazyKey))
        },
        onDragStopped = stop@{ cancelled ->
            val session = drag ?: return@stop
            // Released FIRST, and on a cancelled drag as surely as on a drop — a drag abandoned
            // would otherwise leave the list frozen on whatever it held when the row was lifted.
            drag = null
            if (cancelled) return@stop
            val current = inputs.favorites
            val order = session.dropOrder(current, optimistic) ?: return@stop
            // ⚠ Only a drop that went out is kept. The shadow order below lasts until favorites
            // change, and a reorder that went nowhere has no echo coming to change them — the
            // reconnect re-sends the same list — so this device kept an order nobody else had
            // (sweep L29). The released list draws the store's order instead, which is the truth.
            if (!model.reorderFavorites(bufferIds = order)) {
                events?.send(AppEvent.Notice(BufferListModel.NOT_CONNECTED))
                return@stop
            }
            // Shadow the new order until the echo folds — the list released above would otherwise
            // redraw the store's pre-drop order (a visible snap home, and a corrupt base for a
            // quick second drag).
            optimistic = OptimisticFavorites(order = order, favoritesAtDrop = current)
        },
        // The system buffer is app-scoped and always exists, so fall back to the synthetic one if
        // its row hasn't arrived from the server yet.
        onOpenSystem = { onOpen(model.state.buffers[Buffer.system.key.id] ?: Buffer.system) },
        onMarkAllRead = model::markAllRead,
        onOpenSettings = onOpenSettings,
        // Read as the "+" menu opens, not when the bar was drawn — see `AddMenu`.
        hasNetworks = { model.state.networks.isNotEmpty() },
        onJoinChannel = sheets::showJoinChannel,
        onAddNetwork = sheets::showAddNetwork,
        onOpenView = onOpenView,
    )

    BufferListContent(
        title = BufferListModel.statusTitle(inputs),
        sections = sections,
        placeholder = placeholder,
        // The banner is about the connection, not the roster, so it follows every frame regardless
        // of whether the list below is drawn yet. It yields to the conversation pane side by side.
        banner = if (sideBySide) {
            ConnectionBannerState.Hidden
        } else {
            ConnectionBannerState.of(reachable = inputs.reachable, connection = inputs.connection)
        },
        openKey = openKey,
        marksOpenBuffer = sideBySide,
        draggingSection = drag?.sectionId,
        actions = actions,
    )
}

/** What the list's touches do. One object so the content composable stays stateless (previews). */
internal class BufferListActions(
    val onOpen: (Row) -> Unit,
    val onClose: (Buffer) -> Boolean,
    val menuFor: (Buffer) -> RowMenu?,
    val onJoin: (Buffer) -> Unit,
    val onToggleFavorite: (Buffer, isFavorite: Boolean) -> Unit,
    val onDragStarted: (SectionId, key: String) -> Unit,
    val onMove: (fromLazyKey: String, toLazyKey: String) -> Unit,
    val onDragStopped: (cancelled: Boolean) -> Unit,
    val onOpenSystem: () -> Unit,
    val onMarkAllRead: () -> Unit,
    val onOpenSettings: () -> Unit,
    /** Whether the account has any network — what the "+" menu offers depends on it. */
    val hasNetworks: () -> Boolean,
    val onJoinChannel: () -> Unit,
    val onAddNetwork: () -> Unit,
    /** Search, Activity, Bookmarks (U7). */
    val onOpenView: (AppView) -> Unit = {},
) {
    companion object {
        /** Touches that do nothing — for previews. */
        val None = BufferListActions(
            onOpen = {},
            onClose = { true },
            menuFor = { null },
            onJoin = {},
            onToggleFavorite = { _, _ -> },
            onDragStarted = { _, _ -> },
            onMove = { _, _ -> },
            onDragStopped = {},
            onOpenSystem = {},
            onMarkAllRead = {},
            onOpenSettings = {},
            hasNetworks = { true },
            onJoinChannel = {},
            onAddNetwork = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BufferListContent(
    title: StatusTitle,
    sections: List<Section>,
    placeholder: BufferListPlaceholder,
    banner: ConnectionBannerState,
    openKey: BufferKey?,
    marksOpenBuffer: Boolean,
    draggingSection: SectionId?,
    actions: BufferListActions,
) {
    Scaffold(
        containerColor = LurkerTheme.colors.rosterGround,
        topBar = {
            TopAppBar(
                // Inline: the bar's own row is enough to say what the screen is.
                title = { StatusTitleText(title) },
                actions = {
                    // Android's search action, where iOS puts a field in the bottom toolbar — on its own
                    // screen only: side by side the conversation column carries search (`ViewsLayout`).
                    if (ViewsLayout.listSearch(sideBySide = marksOpenBuffer)) {
                        IconButton(onClick = { actions.onOpenView(AppView.Search) }) {
                            Icon(AppView.Search.icon, contentDescription = AppView.Search.title)
                        }
                    }
                    AddMenu(actions = actions)
                    OverflowMenu(actions = actions, sideBySide = marksOpenBuffer)
                },
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(direction),
                    end = padding.calculateEndPadding(direction),
                ),
        ) {
            if (placeholder == BufferListPlaceholder.None) {
                RosterList(
                    sections = sections,
                    openKey = openKey,
                    marksOpenBuffer = marksOpenBuffer,
                    draggingSection = draggingSection,
                    actions = actions,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + RosterMetrics.groupGap),
                )
            } else {
                // One `StateView` for every state, so a change between them (loading settling to "No
                // buffers yet") is read out by TalkBack — see `StateView`.
                val state = when (placeholder) {
                    BufferListPlaceholder.Loading -> StateModel("Loading buffers…", isLoading = true)
                    // The button is the whole point of this state: it used to say "add a network" to a
                    // person with nowhere to do it, which is the dead end lurker-ios#11 exists to close.
                    BufferListPlaceholder.NoNetworks -> StateModel(
                        title = "No networks yet",
                        symbol = StateSymbol.Buffers,
                        subtitle = "Add a network to start a conversation.",
                        actionTitle = "Add Network",
                    )
                    // They've done the adding already — the next step is joining something, and saying
                    // "add a network" here would read as the app not knowing its own state.
                    else -> StateModel(
                        title = "No buffers yet",
                        symbol = StateSymbol.Buffers,
                        subtitle = "Join a channel or start a DM to see it here.",
                    )
                }
                StateView(state, onAction = actions.onAddNetwork)
            }
            // Over the rows, not above them: it floats, and the list scrolls under it.
            ConnectionBanner(
                state = banner,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

/**
 * "+" — "one more of these": join a channel on a network you have, or add a network to have channels
 * on. lurker-ios's `joinItem`, item for item: one "Join Channel…" whatever the account looks like
 * (the network is picked inside the dialog, where it has a default and can be ignored), or a disabled
 * "No networks" on an account with none; then, under a divider because it's the rarer of the two by a
 * wide margin, "Add Network…".
 *
 * Read as it opens ([BufferListActions.hasNetworks]), as iOS defers its menu: built earlier, it
 * could offer "No networks" to an account whose first network has since arrived. Join Channel stays
 * enabled when nothing is connected — the dialog names each network's state and disables Join, which
 * says why; a greyed-out menu row says nothing at all.
 */
@Composable
private fun AddMenu(actions: BufferListActions) {
    var expanded by remember { mutableStateOf(false) }
    // Kept apart from `expanded`, so the items don't change under the menu's closing fade.
    var hasNetworks by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                hasNetworks = actions.hasNetworks()
                expanded = true
            },
        ) {
            Icon(LurkerIcons.Add, contentDescription = "Add")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (hasNetworks) {
                DropdownMenuItem(
                    text = { Text("Join Channel…") },
                    onClick = {
                        expanded = false
                        actions.onJoinChannel()
                    },
                )
            } else {
                DropdownMenuItem(text = { Text("No networks") }, enabled = false, onClick = {})
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add Network…") },
                onClick = {
                    expanded = false
                    actions.onAddNetwork()
                },
            )
        }
    }
}

/**
 * The app-wide menu: the things that outlast whichever conversation you're reading. One "⋮" —
 * Android's idiom for what iOS splits between a cog and its own "…" (and folds into the "…" side by
 * side, where it holds the Lurker buffer and Settings — this menu, item for item).
 *
 * Networks and Sign Out live in Settings, as on iOS: Networks is Settings' first row, and sign-out
 * sits behind a confirmation there rather than one slipped thumb away in a menu.
 *
 * On its own screen it also carries the views — Activity, Bookmarks and Uploads, app-scoped, so
 * reaching them only from inside some conversation would be an artifact (iOS's `viewsMenuElements`).
 * Side by side they're the conversation column's, and a copy here would be the same thing twice on
 * one screen. Uploads opened from here offers no Add to Message: there's no composer behind the list.
 */
@Composable
private fun OverflowMenu(actions: BufferListActions, sideBySide: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(LurkerIcons.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Set apart at the top because it's a buffer you open, not a view over all of them. The
            // Lurker buffer has no row in the list; this is its door.
            DropdownMenuItem(
                text = { Text("Lurker") },
                onClick = {
                    expanded = false
                    actions.onOpenSystem()
                },
            )
            // Not on iOS — issue #9 asks for it, and it's the web's.
            DropdownMenuItem(
                text = { Text("Mark All as Read") },
                onClick = {
                    expanded = false
                    actions.onMarkAllRead()
                },
            )
            val views = ViewsLayout.listMenu(sideBySide)
            if (views.isNotEmpty()) HorizontalDivider()
            for (view in views) {
                AppViewMenuItem(view) {
                    expanded = false
                    actions.onOpenView(view)
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    expanded = false
                    actions.onOpenSettings()
                },
            )
        }
    }
}

/**
 * The tree itself: a section per group, each opening with its header item, no separators.
 *
 * Every item is keyed by its [ItemId] — section-qualified, so unique across the whole list — and the
 * list is never animated outside a drag: it changes on every frame that changes the roster, and a
 * list that slides every time someone speaks is a list you can't read.
 */
@Composable
private fun RosterList(
    sections: List<Section>,
    openKey: BufferKey?,
    marksOpenBuffer: Boolean,
    draggingSection: SectionId?,
    actions: BufferListActions,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        actions.onMove(from.key as String, to.key as String)
    }
    // Drawn only side by side — under a conversation rather than beside one, a row left marked
    // after you navigate away is stale emphasis.
    fun isOpen(buffer: Buffer): Boolean = marksOpenBuffer && buffer.key.id == openKey?.id
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        for (section in sections) {
            for ((id, entry) in section.entries) {
                when (entry) {
                    is Entry.HeaderEntry -> item(key = id.lazyKey, contentType = "header") {
                        val log = entry.header.log
                        RosterHeader(
                            header = entry.header,
                            isOpen = log != null && isOpen(log.buffer),
                            // A network's header opens its server log, as the web's does.
                            gestures = if (log == null) {
                                Modifier
                            } else {
                                Modifier.clickable(onClickLabel = "open the server log", role = Role.Button) {
                                    actions.onOpen(log)
                                }
                            },
                        )
                    }
                    Entry.PinBreak -> item(key = id.lazyKey, contentType = "pins") { PinBreak() }
                    is Entry.BufferEntry -> item(key = id.lazyKey, contentType = "row") {
                        if (section.id.reorderable) {
                            ReorderableRow(
                                reorderState = reorderState,
                                sectionId = section.id,
                                id = id,
                                row = entry.row,
                                isOpen = isOpen(entry.row.buffer),
                                draggingSection = draggingSection,
                                actions = actions,
                            )
                        } else {
                            NetworkRow(
                                sectionId = section.id,
                                row = entry.row,
                                isOpen = isOpen(entry.row.buffer),
                                actions = actions,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A row in a network's group: tap opens, long-press opens the menu, a full swipe from the end
 * leaves or closes. Never reorders — those groups are the same sorted list every time.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkRow(sectionId: SectionId, row: Row, isOpen: Boolean, actions: BufferListActions) {
    RowWithMenu(row = row, actions = actions) { openMenu, _ ->
        val gestures = Modifier.combinedClickable(
            role = Role.Button,
            onLongClickLabel = MENU_LABEL,
            onLongClick = openMenu,
            onClick = { actions.onOpen(row) },
        )
        val swipe = BufferListModel.swipeTitle(sectionId, row)
        if (swipe == null) {
            BufferRow(row = row, isOpen = isOpen, gestures = gestures)
        } else {
            SwipeToClose(title = swipe, onClose = { actions.onClose(row.buffer) }) {
                BufferRow(row = row, isOpen = isOpen, gestures = gestures)
            }
        }
    }
}

/**
 * A Friends or Favorites row: tap opens; long-press opens the menu, and if the finger then moves
 * before lifting, the menu closes and the row drags — the launcher's idiom, and iOS's (a drag
 * session there reconciles the two: a lift that moves reorders, a lift that stays put opens the
 * menu). See [MenuThenDrag].
 *
 * Reorderable only within its own group: while a drag is live, only that group's rows are targets
 * ([draggingSection]), so a Friends row can't land among Favorites and the other group's rows stay
 * put. Rows slide aside only during a drag; outside one the list is never animated.
 */
@Composable
private fun LazyItemScope.ReorderableRow(
    reorderState: ReorderableLazyListState,
    sectionId: SectionId,
    id: ItemId,
    row: Row,
    isOpen: Boolean,
    draggingSection: SectionId?,
    actions: BufferListActions,
) {
    ReorderableItem(
        state = reorderState,
        key = id.lazyKey,
        enabled = draggingSection == null || draggingSection == sectionId,
        animateItemModifier = if (draggingSection != null) Modifier.animateItem() else Modifier,
    ) { isDragging ->
        RowWithMenu(row = row, actions = actions) { openMenu, closeMenu ->
            val haptics = LocalHapticFeedback.current
            // ⚠ Everything the gesture calls is read through updated state: the library's
            // pointer handler is keyed on the list state alone, so it keeps the lambdas it started
            // with across recompositions, and a captured `row` or `actions` would go stale.
            val longPress by rememberUpdatedState {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                openMenu()
            }
            val detector = remember { MenuThenDrag(onLongPress = { longPress() }) }
            val started by rememberUpdatedState {
                closeMenu()
                actions.onDragStarted(sectionId, row.buffer.key.id)
            }
            val stopped by rememberUpdatedState { actions.onDragStopped(detector.lastDragCancelled) }
            BufferRow(
                row = row,
                isOpen = isOpen,
                lifted = isDragging,
                gestures = Modifier
                    .clickable(role = Role.Button) { actions.onOpen(row) }
                    // The menu for TalkBack, which has no long press to share with a drag.
                    .semantics {
                        onLongClick(label = MENU_LABEL) {
                            openMenu()
                            true
                        }
                    }
                    // Inside the click, so this handler sees each event first and can claim the
                    // lift that ends a long press before the click reads it as a tap.
                    .draggableHandle(
                        onDragStarted = { started() },
                        onDragStopped = { stopped() },
                        dragGestureDetector = detector,
                    ),
            )
        }
    }
}

/**
 * Long-press opens the menu; moving past touch slop after that closes it and drags. lurker-ios got
 * this from a drag session, which is how UIKit reconciles a context menu with drag-to-reorder:
 * a lift that *moves* reorders, a lift that stays put opens the menu, which is what every
 * reorderable list on the system does. The library's own long-press detector starts the drag at
 * the long press, with nothing in between, so this is its detector with the menu in the gap.
 *
 * Nothing is claimed before the long press, so a tap is the row's click and a fling is the list's
 * scroll. After it, the lift is claimed whether or not the finger moved, so a long press never also
 * reads as a tap.
 */
internal class MenuThenDrag(private val onLongPress: () -> Unit) : DragGestureDetector {
    /**
     * Whether the last drag ended in a cancel (the system took the pointer) rather than a lift —
     * read by the stop callback, which the library calls for both. A cancelled drag isn't a drop.
     */
    var lastDragCancelled: Boolean = false
        private set

    override suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            onLongPress()
            val moved = awaitTouchSlopOrCancellation(press.id) { change, _ -> change.consume() }
            if (moved == null) {
                // Lifted where it was pressed: the menu stays, and the lift is ours.
                currentEvent.changes.forEach { it.consume() }
                return@awaitEachGesture
            }
            lastDragCancelled = false
            onDragStart(moved.position)
            val lifted = drag(moved.id) { change ->
                onDrag(change, change.positionChange())
                change.consume()
            }
            if (lifted) {
                currentEvent.changes.forEach { it.consume() }
                onDragEnd()
            } else {
                lastDragCancelled = true
                onDragCancel()
            }
        }
    }
}

/**
 * A row with its long-press menu anchored to it — lurker-ios's context menu, item for item and in
 * its order. Read from the store as it opens ([BufferListActions.menuFor]), as iOS's is: a menu
 * built earlier could offer Join on a network that has since dropped. None on the system buffer or
 * a server log, which never reach a row anyway.
 */
@Composable
private fun RowWithMenu(
    row: Row,
    actions: BufferListActions,
    content: @Composable (openMenu: () -> Unit, closeMenu: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf<RowMenu?>(null) }
    val buffer = row.buffer
    Box {
        content({ menu = actions.menuFor(buffer) }, { menu = null })
        val shown = menu
        DropdownMenu(
            expanded = shown != null,
            onDismissRequest = { menu = null },
            offset = DpOffset(RosterMetrics.inset + RosterMetrics.name, 0.dp),
        ) {
            if (shown != null) {
                val destructive = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                // A parted channel keeps its row and its history, and getting back in is the usual
                // reason to long-press one, so Join leads. Disabled while the network is down: a
                // JOIN needs a live connection, and the section header already says why.
                val join = shown.joinEnabled
                if (join != null) {
                    DropdownMenuItem(
                        text = { Text("Join Channel") },
                        enabled = join,
                        onClick = {
                            menu = null
                            actions.onJoin(buffer)
                        },
                    )
                }
                val favoriteTitle = shown.favoriteTitle
                if (favoriteTitle != null) {
                    if (join != null) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(favoriteTitle) },
                        colors = if (shown.favoriteDestructive) destructive else MenuDefaults.itemColors(),
                        onClick = {
                            menu = null
                            actions.onToggleFavorite(buffer, shown.isFavorite)
                        },
                    )
                }
                // Under its own divider, so a destructive action is set apart from the toggle above
                // rather than a thumb-slip from it.
                if (join != null || favoriteTitle != null) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(shown.closeTitle) },
                    colors = destructive,
                    onClick = {
                        menu = null
                        actions.onClose(buffer)
                    },
                )
            }
        }
    }
}

/**
 * A full swipe from the end leaves or closes — iOS's trailing swipe action, destructive, firing on
 * a full swipe. Half the row's width is the line: far enough that a scroll that drifts sideways
 * doesn't leave a channel, near enough to be one motion.
 *
 * The state is `remember`ed, not saved: a row that comes back (a channel rejoined) must not come
 * back already swiped away.
 */
@Composable
private fun SwipeToClose(title: String, onClose: () -> Boolean, content: @Composable () -> Unit) {
    val state = remember {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { distance -> distance * 0.5f })
    }
    val currentOnClose by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    // Once: the box re-runs its dismiss callback whenever it recomposes still dismissed, and the
    // row stays composed until the store's removal lands.
    var fired by remember { mutableStateOf(false) }
    val onDismiss = remember {
        { _: SwipeToDismissBoxValue ->
            if (!fired) {
                fired = true
                // A close that couldn't go out leaves the row, so it slides back rather than
                // sitting swiped off with nothing coming to remove it (sweep L16).
                // `fired` stays set until the row is home: the box re-runs this while it's still
                // dismissed, and a second close mid-slide could go out once the connection is back.
                if (!currentOnClose()) {
                    scope.launch {
                        state.reset()
                        fired = false
                    }
                }
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = onDismiss,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(end = RosterMetrics.inset),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(title, style = rosterTextStyle(), color = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        content()
    }
}

/** What TalkBack calls a row's long press. */
private const val MENU_LABEL = "show actions"

// MARK: - Previews

/** A populated account: two networks (one down), Friends with a name collision, pins, a draft. */
private fun previewState(): ChatState {
    fun channel(networkId: Int, target: String, unread: Int = 0, highlights: Int = 0, joined: Boolean = true) =
        Buffer(networkId, target, BufferKind.Channel, unread = unread, highlights = highlights, joined = joined)
    fun dm(networkId: Int, target: String, unread: Int = 0) = Buffer(networkId, target, BufferKind.Dm, unread = unread)
    val buffers = listOf(
        channel(1, "#lurker", unread = 3),
        channel(1, "#swift", unread = 2, highlights = 1),
        channel(1, "#kotlin", joined = false),
        channel(1, "#android"),
        Buffer(1, Buffer.serverTarget(1), BufferKind.Server, unread = 1),
        dm(1, "alice", unread = 1),
        dm(1, "bob"),
        channel(2, "#debian"),
        dm(2, "alice"),
    )
    return ChatState(
        connection = SocketStatus.Connected,
        snapshotSinceOpen = true,
        backlogComplete = true,
        networks = mapOf(
            1 to Network(id = 1, name = "Libera", position = 0, state = ConnectionState.Connected),
            2 to Network(id = 2, name = "OFTC", position = 1, state = ConnectionState.Disconnected),
        ),
        buffers = buffers.associateBy { it.key.id },
        favorites = listOf(
            FavoriteEntry(networkId = 1, target = "alice", bufferId = 10),
            FavoriteEntry(networkId = 2, target = "alice", bufferId = 11),
            FavoriteEntry(networkId = 1, target = "#lurker", bufferId = 12),
        ),
        peerPresence = mapOf(1 to mapOf("alice" to PresenceState.Online, "bob" to PresenceState.Away)),
        pinned = mapOf(1 to listOf("#swift")),
        drafts = mapOf(BufferKey(1, "#android").id to ComposerDraft(body = "half a thought")),
    )
}

@Composable
private fun PopulatedPreview(dark: Boolean) {
    val inputs = BufferListInputs.of(previewState())
    val sections = BufferListModel.buildSections(inputs)
    LurkerTheme(darkTheme = dark) {
        BufferListContent(
            title = BufferListModel.statusTitle(inputs),
            sections = sections,
            placeholder = BufferListModel.placeholder(inputs, sections, draws = true),
            banner = ConnectionBannerState.Hidden,
            openKey = BufferKey(1, "#lurker"),
            marksOpenBuffer = true,
            draggingSection = null,
            actions = BufferListActions.None,
        )
    }
}

@Composable
private fun PlaceholderPreview(dark: Boolean, placeholder: BufferListPlaceholder) {
    LurkerTheme(darkTheme = dark) {
        BufferListContent(
            title = StatusTitle(title = "Lurker", status = net.amiantos.lurkerkit.model.StatusLight.Warn),
            sections = emptyList(),
            placeholder = placeholder,
            banner = ConnectionBannerState.Hidden,
            openKey = null,
            marksOpenBuffer = false,
            draggingSection = null,
            actions = BufferListActions.None,
        )
    }
}

@Preview(name = "List — light", heightDp = 640)
@Composable
private fun BufferListPreviewLight() = PopulatedPreview(dark = false)

@Preview(name = "List — dark", heightDp = 640)
@Composable
private fun BufferListPreviewDark() = PopulatedPreview(dark = true)

@Preview(name = "Loading — light")
@Composable
private fun LoadingPreviewLight() = PlaceholderPreview(dark = false, placeholder = BufferListPlaceholder.Loading)

@Preview(name = "Loading — dark")
@Composable
private fun LoadingPreviewDark() = PlaceholderPreview(dark = true, placeholder = BufferListPlaceholder.Loading)

@Preview(name = "No networks — light")
@Composable
private fun NoNetworksPreviewLight() = PlaceholderPreview(dark = false, placeholder = BufferListPlaceholder.NoNetworks)

@Preview(name = "No networks — dark")
@Composable
private fun NoNetworksPreviewDark() = PlaceholderPreview(dark = true, placeholder = BufferListPlaceholder.NoNetworks)

@Preview(name = "No buffers — light")
@Composable
private fun NoBuffersPreviewLight() = PlaceholderPreview(dark = false, placeholder = BufferListPlaceholder.NoBuffers)

@Preview(name = "No buffers — dark")
@Composable
private fun NoBuffersPreviewDark() = PlaceholderPreview(dark = true, placeholder = BufferListPlaceholder.NoBuffers)
