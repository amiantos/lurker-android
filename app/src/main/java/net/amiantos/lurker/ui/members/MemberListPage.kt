// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.members

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormInset
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * The member list's page state: the filter, which outlives a page pushed over it (a profile) — iOS's
 * search bar keeps its text while the profile is up, and Back returns to the list you were scanning.
 */
class MembersPageState(val key: BufferKey) {
    var query by mutableStateOf("")

    /**
     * The rows last built, and the inputs they were built from. Kept with the page, so coming Back to the
     * list from a profile draws it at once instead of sorting it again — and the publisher's replay of a
     * state these inputs already answer is skipped, not re-sorted. Written off the main thread.
     */
    @Volatile
    internal var built: BuiltMembers? = null
}

/** A ranked member list and the inputs it was built from. */
internal class BuiltMembers(val inputs: MemberListInputs, val rows: List<MemberRow>)

/**
 * The nick list (lurker-android#13) — lurker-ios's `MemberListViewController`. Who's here, ranked,
 * with away state; a row opens that person's profile, pushed inside this dialog so Back returns to the
 * list. A filter field appears once the channel is big enough to need one.
 *
 * The list is live: the store folds join/part/quit/kick/nick into `ChatState.members` and applies the
 * server's `names`/`member-update` broadcasts, so what renders here tracks the channel. This page just
 * observes — through [MemberListInputs], compared by identity, since a member list is large and
 * changes constantly.
 *
 * @param onOpenProfile a row was tapped — whose profile to push. Null where there is no network to
 *   look anyone up on (never for a channel), and the rows then go nowhere, as iOS's guard has it.
 */
@Composable
internal fun MemberListPage(
    model: ChatViewModel,
    state: MembersPageState,
    exit: PageExit,
    onExit: () -> Unit,
    onOpenProfile: ((String) -> Unit)?,
) {
    val key = state.key
    // Filtered and ranked off the main thread, and only there: a big channel is thousands of members,
    // re-sorted on every join and part. One build per change — the publisher replays its latest state
    // to every new collector, and inputs the page already built from are skipped. The very first open
    // draws an empty page for the frame that build takes, rather than sorting the list on the main
    // thread to have it a frame sooner; every later appearance draws the last build at once.
    val rowsFlow = remember(model, state) {
        model.statePublisher
            .conflate()
            .map { MemberListInputs.of(it, key) }
            .filter { inputs -> state.built?.let { MemberListInputs.same(it.inputs, inputs) } != true }
            .map { inputs -> BuiltMembers(inputs, MemberListModel.rows(inputs.visible)).also { state.built = it }.rows }
            .flowOn(Dispatchers.Default)
            .conflate()
    }
    val built by rowsFlow.collectAsStateWithLifecycle(initialValue = state.built?.rows)
    val rows = built ?: emptyList()
    // ⚠⚠ Cleared, not just hidden, when the field goes — see `MemberListModel.effectiveQuery`.
    val wantsSearch = MemberListModel.wantsSearch(rows.size)
    LaunchedEffect(wantsSearch) { if (!wantsSearch) state.query = "" }
    val query = MemberListModel.effectiveQuery(state.query, rows.size)
    val kind = remember(key) { BufferKind.of(networkId = key.networkId, target = key.target) }

    MemberListContent(
        loaded = built != null,
        title = MemberListModel.title(rows.size),
        rows = MemberListModel.filter(rows, query),
        showsSearch = wantsSearch,
        query = query,
        onQueryChange = { state.query = it },
        emptyText = MemberListModel.emptyText(kind, searching = query.isNotEmpty()),
        exit = exit,
        onExit = onExit,
        onOpen = onOpenProfile,
        // Read as the menu opens, not when the row was drawn: a friend added on another device since.
        friendTitle = { nick ->
            val networkId = key.networkId ?: return@MemberListContent null
            MemberListModel.friendActionTitle(model.state.isFavorite(BufferKey(networkId = networkId, target = nick)))
        },
        onFriend = { nick -> toggleFriend(model, key, nick) },
    )
}

/**
 * Add a member to Friends, or take them off — straight off a nick you're looking at, as the web's
 * member menu does. A friend is a favorited DM: open-buffer first mints or reopens the DM row (the
 * server refuses favoriting a buffer that doesn't exist or is closed), and the same socket delivers it
 * before the favorite, so the pair can't race. No navigation — the DM appears under Friends.
 *
 * ⚠ WRITES: both verbs change the account on every device.
 */
private fun toggleFriend(model: ChatViewModel, key: BufferKey, nick: String) {
    val networkId = key.networkId ?: return
    val dm = BufferKey(networkId = networkId, target = nick)
    if (model.state.isFavorite(dm)) {
        model.unfavoriteBuffer(networkId = networkId, target = nick)
    } else {
        model.openBuffer(dm)
        model.favoriteBuffer(networkId = networkId, target = nick)
    }
}

@Composable
private fun MemberListContent(
    loaded: Boolean,
    title: String,
    rows: List<MemberRow>,
    showsSearch: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    emptyText: String,
    exit: PageExit,
    onExit: () -> Unit,
    onOpen: ((String) -> Unit)?,
    friendTitle: (String) -> String?,
    onFriend: (String) -> Unit,
) {
    DialogPage(title = title, exit = exit, onExit = onExit) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showsSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 8.dp),
                    placeholder = { Text(MemberListModel.FILTER_PLACEHOLDER) },
                    leadingIcon = { Icon(LurkerIcons.Search, contentDescription = null) },
                    singleLine = true,
                    // A nick is not prose: no capitals, no corrections.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
            if (!loaded) {
                // The first build's frame: nothing yet, and not "No members yet." either.
            } else if (rows.isEmpty()) {
                // The list filters in place — there's no second results screen — so the empty sentence
                // stands where the rows were.
                Box(Modifier.fillMaxSize()) { StateView(title = emptyText) }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { row ->
                        MemberListRow(row = row, onOpen = onOpen, friendTitle = friendTitle, onFriend = onFriend)
                    }
                }
            }
        }
    }
}

/**
 * One member: the rank glyph in its rank's colour and bold, the nick in the text colour — the split
 * the web makes, and why ranks are scannable without reading: five fixed colours in a fixed order down
 * the leading edge. Bold, not only for emphasis: these hues are specified against the message list's
 * ground, and bold at this size is WCAG large text, whose 3:1 all five clear on either scheme's page.
 *
 * Away members stay in place rather than sorting to the bottom — you look for a nick where you last saw
 * it — and the whole row goes flat, glyph included, so it reads as inert: a bright `@` beside a greyed
 * nick says the wrong thing about who is around to use it (the web's `li.away` rule).
 *
 * Long-press for Friends; TalkBack reaches it as the row's long-click action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberListRow(
    row: MemberRow,
    onOpen: ((String) -> Unit)?,
    friendTitle: (String) -> String?,
    onFriend: (String) -> Unit,
) {
    val colors = LurkerTheme.colors
    var menu by remember { mutableStateOf<String?>(null) }
    val base = if (row.away) colors.fgFaint else MaterialTheme.colorScheme.onSurface
    val rank = if (row.away) null else colors.memberPrefix(row.prefix)
    val text = buildAnnotatedString {
        if (row.prefix.isNotEmpty()) {
            withStyle(SpanStyle(color = rank ?: base, fontWeight = if (rank != null) FontWeight.Bold else null)) { append(row.prefix) }
        }
        append(row.nick)
    }
    val label = MemberListModel.accessibilityLabel(row)
    Box {
        ListItem(
            modifier = Modifier
                .combinedClickable(
                    role = Role.Button,
                    enabled = true,
                    onClickLabel = if (onOpen != null) "show profile" else null,
                    onLongClickLabel = "show actions",
                    onLongClick = { menu = friendTitle(row.nick) },
                    onClick = { onOpen?.invoke(row.nick) },
                )
                .clearAndSetSemantics { contentDescription = label },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(text, color = base) },
        )
        val title = menu
        DropdownMenu(expanded = title != null, onDismissRequest = { menu = null }) {
            if (title != null) {
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { Icon(if (title == MemberListModel.friendActionTitle(true)) LurkerIcons.Remove else LurkerIcons.Add, contentDescription = null) },
                    colors = if (title == MemberListModel.friendActionTitle(true)) {
                        MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error)
                    } else {
                        MenuDefaults.itemColors()
                    },
                    onClick = {
                        menu = null
                        onFriend(row.nick)
                    },
                )
            }
        }
    }
}

// MARK: - Previews

private val previewRows = listOf(
    MemberRow("ChanServ", "@", away = false),
    MemberRow("alice", "@", away = false),
    MemberRow("bob", "%", away = true),
    MemberRow("carol", "+", away = false),
    MemberRow("dave", "", away = false),
    MemberRow("erin", "", away = true),
)

@Composable
private fun MembersPreview(dark: Boolean, rows: List<MemberRow>, search: Boolean = false) {
    LurkerTheme(darkTheme = dark) {
        MemberListContent(
            loaded = true,
            title = MemberListModel.title(rows.size),
            rows = rows,
            showsSearch = search,
            query = "",
            onQueryChange = {},
            emptyText = MemberListModel.emptyText(BufferKind.Channel, searching = false),
            exit = PageExit.Close,
            onExit = {},
            onOpen = {},
            friendTitle = { null },
            onFriend = {},
        )
    }
}

@Preview(name = "Members — light")
@Composable
private fun MembersPreviewLight() = MembersPreview(dark = false, rows = previewRows)

@Preview(name = "Members — dark")
@Composable
private fun MembersPreviewDark() = MembersPreview(dark = true, rows = previewRows)

@Preview(name = "Members, filter — light")
@Composable
private fun MembersSearchPreviewLight() = MembersPreview(dark = false, rows = previewRows, search = true)

@Preview(name = "Members, filter — dark")
@Composable
private fun MembersSearchPreviewDark() = MembersPreview(dark = true, rows = previewRows, search = true)

@Preview(name = "No members — light")
@Composable
private fun NoMembersPreviewLight() = MembersPreview(dark = false, rows = emptyList())

@Preview(name = "No members — dark")
@Composable
private fun NoMembersPreviewDark() = MembersPreview(dark = true, rows = emptyList())
