// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.profile

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.amiantos.lurker.platform.MomentText
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormSectionFooter
import net.amiantos.lurker.ui.networks.FormSectionHeader
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.AnnouncedSlot
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.ProfileStatus
import net.amiantos.lurkerkit.model.WhoisResult
import net.amiantos.lurkerkit.session.ChatViewModel
import java.time.Instant

/**
 * Who someone is (lurker-ios#12) — the page behind every nick: a member row, a DM's Whois row, and
 * (U3, U6) `/whois` and a message's actions. They all mean the same question.
 *
 * **The whois is asked on open, every time.** Presence, idle time and channel list go stale within
 * minutes, so the cached reply is here to render *immediately* while the round trip is out, not to save
 * it. `ChatViewModel.requestWhois` owns the in-flight bookkeeping that keeps a reopen from spamming the
 * server without leaving a failed lookup un-retryable. Asked once per page — returning from the note
 * editor isn't opening the profile again.
 *
 * Nothing here writes to the store. The note is server-authoritative: the editor asks, and the note
 * changes when `nick-note-updated` comes back — from this device or a browser, by the identical route.
 *
 * ⚠ [nick] has had any DCC `=` peeled already (the flow does it, the one door every profile comes
 * through): a chat is with bob, so this is bob's profile.
 */
class ProfileState(private val model: ChatViewModel, val networkId: Int, val nick: String, scope: CoroutineScope) {
    private val inputsFlow = MutableStateFlow(ProfileInputs.of(model.state, networkId, nick))

    /** What the page draws — mapped and distinct off the publisher, never the raw state. */
    val inputs = inputsFlow.asStateFlow()

    init {
        scope.launch {
            model.statePublisher.conflate().map { ProfileInputs.of(it, networkId, nick) }.distinctUntilChanged().collect { inputsFlow.value = it }
        }
        // After the first frame, so the cached reply (if any) is already on screen when the request
        // goes out rather than a frame behind it. ⚠ Sends an IRC WHOIS.
        scope.launch { model.requestWhois(networkId = networkId, nick = nick) }
    }

    /** A no-op while a lookup is already out — `requestWhois`'s own rule — so a double tap can't queue a second WHOIS. */
    fun refresh() {
        model.requestWhois(networkId = networkId, nick = nick)
    }
}

/**
 * @param onOpenBuffer go to a conversation — Send Message. Handed back rather than done here: this page
 *   is inside a dialog, and its host owns closing it before anything replaces the screen behind it.
 *   ⚠ Null means Send Message and the channel rows are not offered at all — see
 *   `UserProfileModel.sections`.
 * @param onJoinChannel a channel row: through the one join path (lurker-ios#57).
 */
@Composable
internal fun UserProfilePage(
    state: ProfileState,
    exit: PageExit,
    onExit: () -> Unit,
    onEditNote: () -> Unit,
    onSendMessage: (() -> Unit)?,
    onJoinChannel: ((String) -> Unit)?,
) {
    val inputs by state.inputs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val moments = remember(context) { MomentText(context) }
    val sections = UserProfileModel.sections(inputs, state.nick, canOpenBuffers = onSendMessage != null, dateTime = moments::dateTime)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    UserProfileContent(
        title = state.nick,
        sections = sections,
        outcome = UserProfileModel.lookupOutcome(inputs, state.nick),
        exit = exit,
        onExit = onExit,
        onRow = { row ->
            when (row) {
                is ProfileRow.Detail -> if (row.copyable) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(row.title, row.value))) }
                }
                // Through the one join path: a channel you're in opens at once, one you aren't opens
                // when the server says you're in, and a refusal shows over this dialog rather than
                // leaving you on a screen for a channel you never got into. Opening closes the dialog
                // first, as a notification tap does. ⚠ A JOIN is a WRITE.
                is ProfileRow.Channel -> onJoinChannel?.invoke(row.entry.name)
                is ProfileRow.EditNote -> onEditNote()
                ProfileRow.SendMessage -> onSendMessage?.invoke()
                ProfileRow.Refresh -> state.refresh()
                is ProfileRow.Status, is ProfileRow.Flag, is ProfileRow.Note -> Unit
            }
        },
    )
}

@Composable
private fun UserProfileContent(
    title: String,
    sections: List<ProfileSection>,
    outcome: String?,
    exit: PageExit,
    onExit: () -> Unit,
    onRow: (ProfileRow) -> Unit,
) {
    // The status line — "Looking up alice…", "alice isn't on this network." — leads in a place of its
    // own that's there whatever the lookup's state, rather than as a row that comes and goes: a lookup
    // lands while the reader waits, and only a node that was already there can announce it
    // (`AnnouncedSlot`), or keep TalkBack's focus when the line it was on is answered. Quiet while the
    // lookup is out; on landing it says the [outcome] — the miss, or a hit as the Status row's value
    // ("alice, Online"), which with no line to show is a place 1dp tall that's read but not drawn.
    val statusLine = sections.firstOrNull()?.rows?.singleOrNull() as? ProfileRow.Status
    val rest = if (statusLine != null) sections.drop(1) else sections
    DialogPage(title = title, exit = exit, onExit = onExit) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "status") {
                AnnouncedSlot(words = statusLine?.text ?: outcome, modifier = Modifier.fillMaxWidth(), live = outcome != null) {
                    if (statusLine != null) StatusLineRow(statusLine)
                }
            }
            rest.forEachIndexed { index, section ->
                item(key = "section$index") {
                    Column {
                        section.header?.let { FormSectionHeader(it) }
                        section.rows.forEach { row -> ProfileRowView(row, onRow) }
                        section.footer?.let { FormSectionFooter(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRowView(row: ProfileRow, onRow: (ProfileRow) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = MaterialTheme.colorScheme.primary
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    when (row) {
        // Drawn in the page's status place (`UserProfileContent`), not among the sections.
        is ProfileRow.Status -> StatusLineRow(row)
        // Label above, value below — on every row, not just the long ones: a hostmask always needs the
        // room, and one row in a different shape reads as something gone wrong. The LABEL is the quiet
        // caption; the value is what you came to read.
        is ProfileRow.Detail -> ListItem(
            modifier = if (row.copyable) {
                Modifier.clickable(onClickLabel = "copy", role = Role.Button) { onRow(row) }
            } else {
                Modifier.semantics(mergeDescendants = true) {}
            },
            colors = clear,
            overlineContent = { Text(row.title, color = muted) },
            headlineContent = { Text(row.value) },
            trailingContent = if (row.copyable) ({ Icon(LurkerIcons.ContentCopy, contentDescription = null, tint = muted) }) else null,
        )
        is ProfileRow.Flag -> ListItem(
            colors = clear,
            leadingContent = { Icon(flagIcon(row.icon), contentDescription = null, tint = muted) },
            headlineContent = { Text(row.title) },
        )
        is ProfileRow.Channel -> ListItem(
            modifier = Modifier.clickable(onClickLabel = "join", role = Role.Button) { onRow(row) },
            colors = clear,
            headlineContent = { Text(row.title) },
            trailingContent = { Icon(LurkerIcons.ChevronRight, contentDescription = null, tint = muted) },
        )
        // Prose of arbitrary length, so it wraps — the truncated half of a note is the half you wrote it for.
        is ProfileRow.Note -> ListItem(colors = clear, headlineContent = { Text(row.text) })
        is ProfileRow.EditNote -> ActionRow(if (row.hasNote) LurkerIcons.Pencil else LurkerIcons.Add, row.title, tint) { onRow(row) }
        ProfileRow.SendMessage -> ActionRow(LurkerIcons.ChatBubble, "Send Message", tint) { onRow(row) }
        ProfileRow.Refresh -> ActionRow(LurkerIcons.Refresh, "Refresh", tint) { onRow(row) }
    }
}

/** The lookup's status line, as iOS draws it: the glyph and the words, muted. Its semantics are its place's. */
@Composable
private fun StatusLineRow(row: ProfileRow.Status) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(if (row.line == ProfileStatus.StatusLine.NotFound) LurkerIcons.HelpOutline else LurkerIcons.MoreHoriz, null, tint = muted)
        },
        headlineContent = { Text(row.text, color = muted) },
    )
}

/** A tinted row that does something — the grouped form's button row, with iOS's glyph. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, tint: Color, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(title, color = tint) },
    )
}

private fun flagIcon(icon: ProfileFlagIcon): ImageVector =
    when (icon) {
        ProfileFlagIcon.Registered -> LurkerIcons.CheckCircle
        ProfileFlagIcon.Operator -> LurkerIcons.Shield
        ProfileFlagIcon.HelpOp -> LurkerIcons.HelpOutline
        ProfileFlagIcon.Bot -> LurkerIcons.Memory
        ProfileFlagIcon.Relay -> LurkerIcons.Antenna
    }

// MARK: - Previews

private val previewWhois = WhoisResult(
    nick = "alice",
    ident = "~alice",
    hostname = "user/alice",
    realName = "Alice Liddell",
    server = "tungsten.libera.chat",
    serverInfo = "Libera.Chat",
    account = "alice",
    channelsLine = "@#lurker +#swift #kotlin",
    registeredNick = "is a registered nick",
    isSecure = true,
    idleSeconds = 11_520,
    signedOn = Instant.ofEpochSecond(1_790_000_000),
)

@Composable
private fun ProfilePreview(dark: Boolean, inputs: ProfileInputs) {
    LurkerTheme(darkTheme = dark) {
        UserProfileContent(
            title = "alice",
            sections = UserProfileModel.sections(inputs, "alice", canOpenBuffers = true, dateTime = { "Sep 21, 2026, 10:00" }),
            outcome = UserProfileModel.lookupOutcome(inputs, "alice"),
            exit = PageExit.Back,
            onExit = {},
            onRow = {},
        )
    }
}

private val previewLoaded = ProfileInputs(
    whois = previewWhois,
    isLookingUp = false,
    presence = FriendPresence.Online,
    note = NickNote("alice", "Lives in Berlin. Ask about the trip.", updatedAt = Instant.ofEpochSecond(1_790_100_000)),
    isRelay = false,
    selfNick = "lurker",
)

private val previewWaiting = ProfileInputs(null, isLookingUp = true, presence = FriendPresence.Unknown, note = null, isRelay = false, selfNick = "lurker")

@Preview(name = "Profile — light", heightDp = 900)
@Composable
private fun ProfilePreviewLight() = ProfilePreview(dark = false, inputs = previewLoaded)

@Preview(name = "Profile — dark", heightDp = 900)
@Composable
private fun ProfilePreviewDark() = ProfilePreview(dark = true, inputs = previewLoaded)

@Preview(name = "Profile, looking up — light")
@Composable
private fun ProfileWaitingPreviewLight() = ProfilePreview(dark = false, inputs = previewWaiting)

@Preview(name = "Profile, looking up — dark")
@Composable
private fun ProfileWaitingPreviewDark() = ProfilePreview(dark = true, inputs = previewWaiting)
