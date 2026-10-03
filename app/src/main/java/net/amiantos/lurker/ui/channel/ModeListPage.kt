// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.channel

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormInset
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ChannelRefusals
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.channelAccess
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.time.Instant

/**
 * One of a channel's list modes, as `ModeListViewController` keeps it: the fetch and its rows since,
 * the refusal of the last change, and the two subscriptions — in the page's own scope, so a live
 * `MODE +b` that lands during a rotation still patches the list.
 */
class ModeListState(
    private val model: ChatViewModel,
    val key: BufferKey,
    val letter: String,
    val name: String,
    private val scope: CoroutineScope,
) {
    var status by mutableStateOf<ModeListStatus>(ModeListStatus.Loading)
        private set

    /** Mode rows seen since the latest fetch went out; they patch what it brought back. */
    var rowsSinceFetch by mutableStateOf<List<Message>>(emptyList())
        private set

    /** The refusal of the last add or remove: the verb's own, or the channel's error rows soon after it. */
    var actionError by mutableStateOf<String?>(null)
        private set
    var refusals by mutableStateOf(ChannelRefusals())
        private set

    var slice by mutableStateOf(ModeListSlice.of(model.state, key, letter))
        private set

    /** One change at a time: a double tap must not send the ban twice. */
    var busy by mutableStateOf(false)
        private set

    /** The fetch the user asked for by pulling — the pull's own spinner shows, not the page's. */
    var refreshing by mutableStateOf(false)
        private set

    /** What a pull to refresh is holding on screen until its answer — see `ModeListModel.shown`. */
    private var held by mutableStateOf<List<ModeListEntry>?>(null)

    /** The Add dialog is up, with what's been typed. */
    var adding by mutableStateOf<String?>(null)

    private val fetches = ModeListFetches()

    init {
        // Only what this page reads: the state moves on every line in every buffer.
        scope.launch {
            model.statePublisher.conflate().map { ModeListSlice.of(it, key, letter) }.distinctUntilChanged().collect {
                slice = it
                if (fetches.linkMoved(it)) load()
            }
        }
        scope.launch {
            model.channelEvents.collect { event ->
                when (event) {
                    ChatViewModel.ChannelEvent.Resynced -> if (fetches.resynced()) load()
                    is ChatViewModel.ChannelEvent.Line -> {
                        if (event.key.id != key.id) return@collect
                        when (event.message.type) {
                            EventType.Mode -> rowsSinceFetch = rowsSinceFetch + event.message
                            EventType.Error -> refusals = refusals.note(event.message.text ?: "")
                            else -> Unit
                        }
                    }
                }
            }
        }
        fetches.linkMoved(slice)
        load()
    }

    /** Fetch the list. ⚠ Asks the IRC server for it (a MODE query) — a read, but it goes out on the wire. */
    fun load(byPull: Boolean = false) {
        val mine = fetches.start()
        // Taken before the rows reset: the list as the reader sees it.
        held = if (byPull) shown else null
        status = ModeListStatus.Loading
        rowsSinceFetch = emptyList()
        refreshing = byPull
        scope.launch {
            val result = model.fetchModeList(key, letter = letter)
            val next = fetches.answered(mine, result) ?: return@launch
            refreshing = false
            held = null
            status = next
        }
    }

    /** What the list shows: the fetch, patched by every live row since. */
    val shown: List<ModeListEntry> get() = ModeListModel.shown(status, rowsSinceFetch, letter, held)

    val footer: String?
        get() = (listOfNotNull(actionError) + refusals.current).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /**
     * Send one `±letter mask`. The entry appears or goes when the channel's MODE line comes back —
     * nothing is applied here. ⚠ A WRITE: a ban, exception or invite exception, for the whole channel.
     */
    fun change(sign: Char, mask: String) {
        if (busy) return
        val state = model.state
        val refusal = ModeListModel.refusal(
            listedNow = key.networkId?.let { state.networks[it] }?.modeSpec?.list?.contains(letter) == true,
            canEditNow = state.channelAccess(key).canEditModes,
            mask = mask,
        )
        if (refusal != null) {
            actionError = refusal
            return
        }
        busy = true
        actionError = null
        refusals = refusals.arm()
        scope.launch {
            val failure = withContext(NonCancellable) {
                model.setChannelModes(key, changes = listOf(OutgoingModeChange(sign = sign, letter = letter, param = mask)))
            }
            busy = false
            actionError = failure?.message
        }
    }
}

/**
 * One of a channel's list modes — bans, exceptions, invite exceptions or quiets (lurker-ios#187) —
 * fetched from the IRC server when the page opens, with who set each entry and when. An op adds an
 * entry with + and removes one from its menu (iOS's swipe and context menu are one long-press menu
 * here, which TalkBack reaches as the row's long-click action). Pull to refresh asks again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeListPage(state: ModeListState, dateTime: (Instant) -> String, onBack: () -> Unit) {
    val canEdit = state.slice.canEdit
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModeListContent(
        title = state.name,
        status = state.status,
        entries = state.shown,
        footer = state.footer,
        canEdit = canEdit,
        busy = state.busy,
        refreshing = state.refreshing,
        meta = { ModeListModel.meta(it, dateTime) },
        onBack = onBack,
        onRefresh = { state.load(byPull = true) },
        onAdd = { state.adding = "" },
        onCopy = { mask -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(state.name, mask))) } },
        onRemove = { mask -> state.change('-', mask) },
    )
    val adding = state.adding
    if (adding != null) {
        AddEntryDialog(
            title = ModeListModel.addTitle(state.name),
            text = adding,
            busy = state.busy,
            onTextChange = { state.adding = it },
            onDismiss = { state.adding = null },
            onAdd = { mask ->
                state.adding = null
                if (mask.isNotEmpty()) state.change('+', mask)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeListContent(
    title: String,
    status: ModeListStatus,
    entries: List<ModeListEntry>,
    footer: String?,
    canEdit: Boolean,
    busy: Boolean,
    refreshing: Boolean,
    meta: (ModeListEntry) -> String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onCopy: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    DialogPage(
        title = title,
        exit = PageExit.Back,
        onExit = onBack,
        actions = {
            if (canEdit) {
                IconButton(onClick = onAdd, enabled = !busy) { Icon(LurkerIcons.Add, contentDescription = "Add") }
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                // The refusal leads, where it's seen whether the list is long or empty.
                if (footer != null) {
                    item(key = "footer") {
                        Text(
                            footer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LurkerTheme.colors.badText,
                        )
                    }
                }
                // By mask, unique per list (`ModeListModel.shown`), so a row keeps its state — an open
                // menu — while live edits add and remove the rows around it.
                items(entries, key = { it.mask }) { entry ->
                    EntryRow(entry, meta(entry), canEdit, busy, onCopy, onRemove)
                }
            }
            // Loading, the fetch's refusal, or an empty list — said in place of rows. A pull shows its
            // own spinner, so the page's stays away while one is out.
            when (status) {
                ModeListStatus.Loading -> if (!refreshing) StateView(title = "Loading…", isLoading = true)
                is ModeListStatus.Failed -> StateView(title = status.message)
                is ModeListStatus.Ready -> if (entries.isEmpty()) StateView(title = ModeListModel.EMPTY)
            }
        }
    }
}

/** An entry: the mask in monospace (it's a pattern, read character by character), who set it and when under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: ModeListEntry, meta: String?, canEdit: Boolean, busy: Boolean, onCopy: (String) -> Unit, onRemove: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClickLabel = "show actions",
                onLongClickLabel = "show actions",
                onLongClick = { menu = true },
                onClick = { menu = true },
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(entry.mask, fontFamily = FontFamily.Monospace) },
            supportingContent = meta?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Copy Mask") },
                leadingIcon = { Icon(LurkerIcons.ContentCopy, contentDescription = null) },
                onClick = {
                    menu = false
                    onCopy(entry.mask)
                },
            )
            if (canEdit) {
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(LurkerIcons.Remove, contentDescription = null) },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error),
                    // One change at a time: disabled, not dropped, while the last one is unanswered.
                    enabled = !busy,
                    onClick = {
                        menu = false
                        // Not deleted from the list here: the entry goes when the channel's -letter comes back.
                        onRemove(entry.mask)
                    },
                )
            }
        }
    }
}

/** iOS's Add alert: one field, a mask. Trimmed on Add; a mask with a space inside is refused by the page. */
@Composable
private fun AddEntryDialog(title: String, text: String, busy: Boolean, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.focusRequester(focus),
                placeholder = { Text(ModeListModel.MASK_PLACEHOLDER) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            )
        },
        // Held off while a change is unanswered — the page sends one at a time — rather than tapped and dropped.
        confirmButton = { TextButton(onClick = { onAdd(text.trimmingWhitespacesAndNewlines()) }, enabled = !busy) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// MARK: - Previews

private val previewEntries = listOf(
    ModeListEntry("*!*@spam.example", setBy = "alice!a@host", setAt = Instant.ofEpochSecond(1_790_000_000)),
    ModeListEntry("troll!*@*", setBy = "ChanServ", setAt = null),
    ModeListEntry("\$a:badaccount", setBy = null, setAt = null),
)

@Composable
private fun ListPreview(dark: Boolean, status: ModeListStatus, footer: String? = null) {
    LurkerTheme(darkTheme = dark) {
        ModeListContent(
            title = "Bans",
            status = status,
            entries = (status as? ModeListStatus.Ready)?.entries.orEmpty(),
            footer = footer,
            canEdit = true,
            busy = false,
            refreshing = false,
            meta = { ModeListModel.meta(it) { "Sep 21, 2026, 10:00" } },
            onBack = {},
            onRefresh = {},
            onAdd = {},
            onCopy = {},
            onRemove = {},
        )
    }
}

@Preview(name = "Bans — light")
@Composable
private fun BansPreviewLight() = ListPreview(dark = false, status = ModeListStatus.Ready(previewEntries))

@Preview(name = "Bans — dark")
@Composable
private fun BansPreviewDark() = ListPreview(dark = true, status = ModeListStatus.Ready(previewEntries))

@Preview(name = "Bans, refused — light")
@Composable
private fun BansRefusedPreviewLight() =
    ListPreview(dark = false, status = ModeListStatus.Ready(previewEntries), footer = "You're not a channel operator")

@Preview(name = "Bans, refused — dark")
@Composable
private fun BansRefusedPreviewDark() =
    ListPreview(dark = true, status = ModeListStatus.Ready(previewEntries), footer = "You're not a channel operator")

@Preview(name = "Bans, failed — light")
@Composable
private fun BansFailedPreviewLight() = ListPreview(dark = false, status = ModeListStatus.Failed("Only channel operators can see this list."))

@Preview(name = "Bans, failed — dark")
@Composable
private fun BansFailedPreviewDark() = ListPreview(dark = true, status = ModeListStatus.Failed("Only channel operators can see this list."))

@Preview(name = "Bans, empty — light")
@Composable
private fun BansEmptyPreviewLight() = ListPreview(dark = false, status = ModeListStatus.Ready(emptyList()))

@Preview(name = "Bans, empty — dark")
@Composable
private fun BansEmptyPreviewDark() = ListPreview(dark = true, status = ModeListStatus.Ready(emptyList()))
