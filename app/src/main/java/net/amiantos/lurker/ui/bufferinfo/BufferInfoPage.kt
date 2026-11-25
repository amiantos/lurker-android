// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.platform.MomentText
import net.amiantos.lurker.ui.list.BufferListModel
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormInset
import net.amiantos.lurker.ui.networks.FormRefusalFooter
import net.amiantos.lurker.ui.networks.FormSectionFooter
import net.amiantos.lurker.ui.networks.FormSectionHeader
import net.amiantos.lurker.ui.message.ProseReveals
import net.amiantos.lurker.ui.message.ProseText
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.shell.StatusDot
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkRow
import net.amiantos.lurkerkit.model.NotifyLadder
import net.amiantos.lurkerkit.model.NotifyRung
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.model.channelAccess
import net.amiantos.lurkerkit.model.notifyRung
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant

/**
 * The buffer info page's state — what `BufferInfoViewController` keeps beside its table: the live
 * inputs, and the most recent refusal of a connection or DCC verb, with the bookkeeping that keeps a
 * slow, stale answer from landing under rows it no longer describes. And the refusal of a notification
 * pick, which has its own.
 */
class BufferInfoState(private val model: ChatViewModel, val buffer: Buffer, private val scope: CoroutineScope) {
    private val inputsFlow = MutableStateFlow(BufferInfoInputs.of(model.state, buffer))
    val inputs = inputsFlow.asStateFlow()

    /** The topic's opened spoilers, the page's so a row's disposal keeps them (lurker#1297). */
    val topicReveals = ProseReveals()

    /**
     * The latest refusal, pinned under its section. A footer rather than an alert, for the reason the
     * networks screen uses the row's subtitle: the answer arrives on its own schedule, and an alert
     * competing with whatever the user did meanwhile is the worse of the two. Cleared by the next
     * attempt, and when the connection (or session) moves.
     */
    var actionError by mutableStateOf<String?>(null)
        private set

    /** Which attempt may write [actionError]: bumped per tap, so a slow earlier request can't answer for a later one. */
    private var attempts = 0

    /** The connection and session as last drawn — see `BufferInfoModel.Subject`. */
    private var shown = BufferInfoModel.Subject.of(inputsFlow.value)

    /**
     * Set when picking a rung went nowhere, and shown under the Notifications section the way
     * [actionError] is shown under Connection. Its own slot because a server log has both sections,
     * and a refusal belongs under the one that was tapped.
     */
    var notifyError by mutableStateOf<String?>(null)
        private set

    private val notifyScope: NotifyLadder.Scope? = NotifyLadder.Scope.of(buffer.key)

    /** The rung as last seen, so the echo that moves it retires a refusal from before. */
    private var shownRung: NotifyRung? = inputsFlow.value.notifyRung

    /** Whether the socket could carry a write as last seen, for the same reason. */
    private var shownWritable = inputsFlow.value.socketWritable

    init {
        scope.launch {
            // Narrowed and compared first — cheaply, the member list and the ignore set by identity — and
            // only a frame that moved something here is counted, all of it off the main thread: the count
            // runs the ignore rules over every member of a busy channel (see `BufferInfoSource`).
            model.statePublisher
                .conflate()
                .map { BufferInfoSource.of(it, buffer.key) }
                .distinctUntilChanged(BufferInfoSource::same)
                .map { it.inputs(buffer) }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect { next ->
                    val subject = BufferInfoModel.Subject.of(next)
                    if (subject != shown) {
                        // A refusal is about the connection as it was when the verb was sent; once it moves,
                        // the footer would contradict the rows above it.
                        shown = subject
                        actionError = null
                        notifyError = null
                    }
                    retireNotifyError()
                    inputsFlow.value = next
                }
        }
        // Connect is offered off `Network.blocked`, which the store learns from the roster — read when
        // the socket opens, and otherwise only when something writes a network. The networks screen
        // re-reads the roster on every appearance, so without this the two could disagree about an
        // allowlist change for the life of the socket. Same cadence, then; the answer folds into state.
        if (buffer.kind == BufferKind.Server) scope.launch { model.refreshNetworks() }
    }

    /**
     * Run a connection verb and pin its refusal. Nothing is applied optimistically: the server
     * acknowledges the instruction and the transition arrives separately as `state` events, so the rows
     * move — Connect becoming Disconnect — when the store says they have. The page stays up so that is
     * visible. ⚠ WRITES: it connects, disconnects or reconnects the network for every device.
     */
    fun perform(action: NetworkAction) {
        val networkId = buffer.networkId ?: return
        attempt { model.perform(action, networkId) }
    }

    /**
     * End or restart this DCC chat — the connection verbs' rules, the session standing in for the
     * connection. Starting one takes the app to the buffer when the server has minted it, which closes
     * this dialog (what happens next is narrated in the chat); ending one leaves it up so its rows can
     * be seen to move. ⚠ WRITES.
     */
    fun perform(action: DccChatAction) {
        val networkId = buffer.networkId ?: return
        val nick = BufferInfoModel.dccPeer(buffer.key)
        attempt {
            when (action) {
                DccChatAction.Start -> model.openDccChat(networkId = networkId, nick = nick)
                DccChatAction.End -> model.closeDccChat(networkId = networkId, nick = nick)
            }
        }
    }

    /**
     * Ask for a rung. Nothing moves here: the mark follows state, and state follows the server's echo —
     * so a tap that went nowhere leaves the list as it was and says why underneath, and one that went
     * out is answered by the row changing. ⚠ WRITES, for every device.
     *
     * The view model plans against the state at this moment, not the one this list was drawn from, so
     * a change that landed from another device in between is reconciled rather than fought (web
     * lurker#1136).
     */
    fun pick(rung: NotifyRung) {
        val scope = notifyScope ?: return
        // Catch up first. State reaches this page a hop late, and a rung that has moved retires a
        // refusal — so a move still on its way here would otherwise be taken for the answer to this
        // tap and clear the refusal set just below.
        retireNotifyError()
        notifyError = if (model.setNotifyRung(rung, scope)) null else BufferListModel.NOT_CONNECTED
    }

    /**
     * Drop a refusal the state has since answered. A rung that moved is the server answering — this
     * tap's echo, or another device's — and "not connected" under a list that just changed would
     * contradict it. And "not connected" is about Lurker's own socket, which can come back with the
     * network's connection and the rung exactly as they were.
     *
     * Read from the view model's state, not from the inputs being delivered: those are a hop behind,
     * and [pick] compares against the same thing.
     */
    private fun retireNotifyError() {
        val state = model.state
        val rung = notifyScope?.let { state.notifyRung(it) }
        if (rung != shownRung) {
            shownRung = rung
            notifyError = null
        }
        val writable = state.socketWritable
        if (writable != shownWritable) {
            shownWritable = writable
            if (writable) notifyError = null
        }
    }

    private fun attempt(verb: suspend () -> String?) {
        // Whatever the last attempt said is now stale. The counter keeps a SLOW earlier attempt from
        // answering for this one: tap Disconnect, watch the row flip to Connect, tap that, and the first
        // request's late refusal would otherwise land under the second.
        attempts += 1
        val mine = attempts
        val sentAgainst = shown
        actionError = null
        scope.launch {
            // Run to its reply even if the page goes: a request torn down mid-flight is one the server
            // may or may not have acted on. A reply nobody can see then goes nowhere.
            val refusal = withContext(NonCancellable) { verb() } ?: return@launch
            // ⚠ The connection check is the other half of "a refusal is about the connection as it was":
            // a Disconnect whose reply timed out after the server had already acted would otherwise
            // print "Disconnect failed" under a row that says Offline.
            if (mine == attempts && shown == sentAgainst) actionError = refusal
        }
    }
}

/** What the info page's rows ask of the dialog — pushes, mostly; Back returns here. */
internal class BufferInfoActions(
    val onMembers: () -> Unit,
    val onWhois: () -> Unit,
    val onChannelSettings: () -> Unit,
    val onModeList: (letter: String, name: String) -> Unit,
    val onNetworkVerb: (NetworkAction) -> Unit,
    val onDccVerb: (DccChatAction) -> Unit,
    /** Put the buffer on a rung of its notification ladder (lurker#1087). */
    val onNotifyRung: (NotifyRung) -> Unit = {},
    /** Search this buffer: the dialog closes and search opens, seeded with [InfoRow.Search.scope] (U7). */
    val onSearch: (scope: String) -> Unit = {},
)

/**
 * What this buffer *is* — the conversation's info button opens this (lurker-ios's
 * `BufferInfoViewController`). The rows are `BufferInfoModel`'s.
 *
 * Its deeper pages are pushed inside the same dialog and Back returns here: Members, a DM's Whois,
 * Channel Settings and each list. (iOS dismisses this sheet and presents the member list as a sheet of
 * its own, because its chat screen owns that presentation; here every page is one stack, and the
 * members you opened from the info page are one Back away from it.)
 */
@Composable
internal fun BufferInfoPage(state: BufferInfoState, exit: PageExit, onExit: () -> Unit, actions: BufferInfoActions) {
    val inputs by state.inputs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val moments = remember(context) { MomentText(context) }
    BufferInfoContent(
        title = BufferInfoModel.title(state.buffer, inputs),
        sections = BufferInfoModel.sections(inputs, state.actionError, moments::dateTime, moments::longDate, state.notifyError),
        exit = exit,
        onExit = onExit,
        actions = actions,
        reveals = state.topicReveals,
    )
}

@Composable
private fun BufferInfoContent(
    title: String,
    sections: List<InfoSection>,
    exit: PageExit,
    onExit: () -> Unit,
    actions: BufferInfoActions,
    reveals: ProseReveals,
) {
    DialogPage(title = title, exit = exit, onExit = onExit) { padding ->
        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) { StateView(title = BufferInfoModel.EMPTY) }
            return@DialogPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            sections.forEachIndexed { index, section ->
                item(key = "section$index") {
                    Column {
                        section.header?.let { FormSectionHeader(it) }
                        section.rows.forEach { row -> InfoRowView(row, actions, reveals) }
                        if (section.holdsRefusals) {
                            FormRefusalFooter(section.footer, announces = section.announcesFooter)
                        } else {
                            section.footer?.let { FormSectionFooter(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRowView(row: InfoRow, actions: BufferInfoActions, reveals: ProseReveals) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    when (row) {
        // Prose of arbitrary length, not a label — it wraps, because a truncated topic is the half you
        // don't need — drawn as the log draws a body (lurker#1297): links, colours, `:shortcode:`
        // glyphs. The muted placeholder is the page's own words and stays plain.
        is InfoRow.Topic -> ListItem(
            colors = clear,
            headlineContent = {
                if (row.muted) {
                    Text(row.text, color = muted)
                } else {
                    ProseText(
                        row.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        revealed = reveals.ordinalsFor(row.text),
                        onToggleSpoiler = { reveals.toggle(row.text, it) },
                    )
                }
            },
        )
        is InfoRow.ChannelSettings -> NavRow(LurkerIcons.Tune, "Channel Settings", row.modes, actions.onChannelSettings)
        is InfoRow.ModeList -> NavRow(LurkerIcons.ListBullet, row.name, null) { actions.onModeList(row.letter, row.name) }
        is InfoRow.Members -> NavRow(LurkerIcons.Group, "Members", row.count?.toString(), actions.onMembers)
        // No nick alongside it — the title already says whose DM this is.
        InfoRow.Whois -> NavRow(LurkerIcons.AccountCircle, "Whois", null, actions.onWhois)
        // Named for the buffer rather than "Search in Buffer": the title already says which one.
        is InfoRow.Search -> NavRow(LurkerIcons.Search, "Search This Conversation", null) { actions.onSearch(row.scope) }
        // One of a list, and the row says which: a check is a picture, `selectable` is what TalkBack
        // reads as "selected".
        is InfoRow.Notify -> ListItem(
            modifier = Modifier.selectable(selected = row.selected, role = Role.RadioButton) { actions.onNotifyRung(row.rung) },
            colors = clear,
            headlineContent = { Text(row.rung.label) },
            trailingContent = if (row.selected) {
                { Icon(LurkerIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
        )
        // The networks screen's row in miniature, and the light the title behind this dialog shows.
        is InfoRow.Connection -> StatusRow(row.label, row.light)
        is InfoRow.DccStatus -> StatusRow(row.label, row.light)
        is InfoRow.NetworkVerb -> VerbRow(row.title) { actions.onNetworkVerb(row.action) }
        is InfoRow.DccVerb -> VerbRow(row.title) { actions.onDccVerb(row.action) }
    }
}

/** A row that goes somewhere: glyph, title, value, chevron — iOS's disclosure row. */
@Composable
private fun NavRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = muted) },
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) Text(value, color = muted, modifier = Modifier.padding(end = 8.dp))
                Icon(LurkerIcons.ChevronRight, contentDescription = null, tint = muted)
            }
        },
    )
}

/** "Status · Connected", with the light. The words carry it; the dot repeats them. */
@Composable
private fun StatusRow(label: String, light: StatusLight) {
    ListItem(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text("Status") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(light, Modifier.size(8.dp))
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
        },
    )
}

/** A verb, tinted as a grouped form's button row. */
@Composable
private fun VerbRow(title: String, onClick: () -> Unit) {
    Text(
        title,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FormInset, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

// MARK: - Previews

private fun previewInputs(kind: BufferKind, topic: String? = null, connection: NetworkRow? = null, dccLive: Boolean? = null): BufferInfoInputs {
    val notifyScope = when (kind) {
        BufferKind.Channel -> NotifyLadder.Scope.Channel(networkId = 1, target = "#lurker")
        BufferKind.Dm, BufferKind.Dcc -> NotifyLadder.Scope.Dm(networkId = 1, target = "bob")
        BufferKind.Server -> NotifyLadder.Scope.Network(networkId = 1)
        BufferKind.System -> null
    }
    return BufferInfoInputs(
        kind = kind,
        topic = topic,
        networkName = "Libera",
        memberCount = 214,
        modes = ChannelModeState(modes = "nt", createdAt = Instant.ofEpochSecond(1_600_000_000), topicSetBy = "alice!a@host", topicSetAt = Instant.ofEpochSecond(1_790_000_000)),
        access = ChatState().channelAccess(Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel).key),
        connection = connection,
        dccLive = dccLive,
        notifyScope = notifyScope,
        notifyRung = notifyScope?.base,
    )
}

private val previewActions = BufferInfoActions({}, {}, {}, { _, _ -> }, {}, {})

@Composable
private fun InfoPreview(dark: Boolean, title: String, inputs: BufferInfoInputs, error: String? = null) {
    LurkerTheme(darkTheme = dark) {
        BufferInfoContent(
            title = title,
            sections = BufferInfoModel.sections(inputs, error, { "Sep 21, 2026, 10:00" }, { "September 13, 2020" }),
            exit = PageExit.Close,
            onExit = {},
            actions = previewActions,
            reveals = remember { ProseReveals() },
        )
    }
}

@Preview(name = "Channel info — light", heightDp = 800)
@Composable
private fun ChannelInfoPreviewLight() =
    InfoPreview(dark = false, title = "#lurker", inputs = previewInputs(BufferKind.Channel, topic = "Lurker — an IRC client for the web, iOS and Android"))

@Preview(name = "Channel info — dark", heightDp = 800)
@Composable
private fun ChannelInfoPreviewDark() =
    InfoPreview(dark = true, title = "#lurker", inputs = previewInputs(BufferKind.Channel, topic = "Lurker — an IRC client for the web, iOS and Android"))

@Preview(name = "Server info, refused — light")
@Composable
private fun ServerInfoPreviewLight() =
    InfoPreview(
        dark = false,
        title = "Libera",
        inputs = previewInputs(BufferKind.Server, connection = NetworkRow(ConnectionState.Connected, isBlocked = false)),
        error = "Your account is paused.",
    )

@Preview(name = "Server info, refused — dark")
@Composable
private fun ServerInfoPreviewDark() =
    InfoPreview(
        dark = true,
        title = "Libera",
        inputs = previewInputs(BufferKind.Server, connection = NetworkRow(ConnectionState.Connected, isBlocked = false)),
        error = "Your account is paused.",
    )

@Preview(name = "DCC info — light")
@Composable
private fun DccInfoPreviewLight() = InfoPreview(dark = false, title = "=bob", inputs = previewInputs(BufferKind.Dcc, dccLive = true))

@Preview(name = "DCC info — dark")
@Composable
private fun DccInfoPreviewDark() = InfoPreview(dark = true, title = "=bob", inputs = previewInputs(BufferKind.Dcc, dccLive = true))

@Preview(name = "System info — light")
@Composable
private fun SystemInfoPreviewLight() = InfoPreview(dark = false, title = "Lurker", inputs = previewInputs(BufferKind.System))

@Preview(name = "System info — dark")
@Composable
private fun SystemInfoPreviewDark() = InfoPreview(dark = true, title = "Lurker", inputs = previewInputs(BufferKind.System))
