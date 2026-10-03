// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.MessageActionKey

/**
 * What you can do with one message, as a modal bottom sheet (lurker-ios#60, lurker-android#37) —
 * lurker-ios's `MessageActionsViewController`, the Discord shape rather than a context menu.
 *
 * ⚠⚠ Not a peek. iOS tried lifting the pressed row out of the list (`UIContextMenuInteraction`) and it
 * was a dead end twice over: a peek is a picture *of the list*, coupled to how the list draws itself,
 * and the list redraws on every arriving message. A sheet is presented over the list instead; nothing
 * the list does underneath can reach it.
 *
 * The subject is named at the top. That isn't decoration: it's the only thing confirming *which*
 * line you pressed, and a menu that can act on the wrong message without showing you is worse than one
 * that's a bit taller. Sized to its rows, not half the screen: the conversation behind it is what
 * you're acting on, and worth leaving visible.
 *
 * Dismiss first, act second ([onPick] fires once the sheet is down): Reply raises the keyboard, which
 * can't take while a sheet's window still holds the focus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsSheet(
    header: ActionHeader,
    rows: List<ActionRow>,
    onDismiss: () -> Unit,
    onPick: (ActionKey) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Rows stay tappable through the dismissal animation, so without this a quick double tap on
    // Share Link would queue two share sheets.
    val ran = remember { booleanArrayOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        MessageActionsContent(header, rows) { key ->
            if (ran[0]) return@MessageActionsContent
            ran[0] = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { onPick(key) }
        }
    }
}

/** The sheet's contents, stateless — for previews, and so it draws only what it's given. */
@Composable
internal fun MessageActionsContent(header: ActionHeader, rows: List<ActionRow>, onPick: (ActionKey) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // Capped by the sheet at the screen; a long quoted message scrolls rather than pushing
            // the actions off the bottom.
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        SheetHeader(header.title, header.detail, detailLines = 3)
        for (row in rows) ActionRowItem(row, onPick)
    }
}

/**
 * Title over detail, centred, above what you can do — the shape a share sheet uses, because this is
 * the same kind of object: a thing, then what you can do to it. Shared with the reaction sheet.
 */
@Composable
internal fun SheetHeader(title: String, detail: String?, detailLines: Int) {
    val colors = LurkerTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!detail.isNullOrEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
                textAlign = TextAlign.Center,
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One action: its glyph and its words. Taller than a settings row — a few deliberate choices under a thumb. */
@Composable
private fun ActionRowItem(row: ActionRow, onPick: (ActionKey) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button) { onPick(row.key) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon(row.glyph), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.title, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A row's glyph, from the app's icons. */
internal fun icon(glyph: ActionGlyph): ImageVector =
    when (glyph) {
        ActionGlyph.Reply -> LurkerIcons.Reply
        ActionGlyph.React -> LurkerIcons.Smile
        ActionGlyph.Copy -> LurkerIcons.ContentCopy
        ActionGlyph.Bookmark -> LurkerIcons.BookmarkBorder
        ActionGlyph.Bookmarked -> LurkerIcons.BookmarkFilled
        ActionGlyph.Profile -> LurkerIcons.AccountCircle
        ActionGlyph.OpenLink -> LurkerIcons.OpenInNew
        ActionGlyph.Share -> LurkerIcons.Share
        ActionGlyph.Ignore -> LurkerIcons.Block
    }

// MARK: - Previews

private val previewHeader = ActionHeader("alice via relaybot", "morning — did the deploy land? https://lurker.chat/changelog")

private val previewRows = listOf(
    ActionRow(ActionKey.Kit(MessageActionKey.Reply), "Reply to alice", ActionGlyph.Reply),
    ActionRow(ActionKey.Kit(MessageActionKey.React), "React", ActionGlyph.React),
    ActionRow(ActionKey.Kit(MessageActionKey.Copy), "Copy Text", ActionGlyph.Copy),
    ActionRow(ActionKey.Kit(MessageActionKey.Bookmark), "Save Message", ActionGlyph.Bookmark),
    ActionRow(ActionKey.Kit(MessageActionKey.Profile), "Profile of relaybot", ActionGlyph.Profile),
    ActionRow(ActionKey.Ignore, "Ignore relaybot…", ActionGlyph.Ignore),
)

private val previewLinkRows = listOf(
    ActionRow(ActionKey.Kit(MessageActionKey.OpenLink), "Open Link", ActionGlyph.OpenLink),
    ActionRow(ActionKey.Kit(MessageActionKey.CopyLink), "Copy Link", ActionGlyph.Copy),
    ActionRow(ActionKey.Kit(MessageActionKey.ShareLink), "Share Link", ActionGlyph.Share),
)

@Composable
private fun ActionsPreview(dark: Boolean, link: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 16.dp)) {
            if (link) {
                MessageActionsContent(ActionHeader("lurker.chat", "https://lurker.chat/changelog"), previewLinkRows) {}
            } else {
                MessageActionsContent(previewHeader, previewRows) {}
            }
        }
    }
}

@Preview(name = "Message actions — light", widthDp = 360)
@Composable
private fun ActionsPreviewLight() = ActionsPreview(dark = false, link = false)

@Preview(name = "Message actions — dark", widthDp = 360)
@Composable
private fun ActionsPreviewDark() = ActionsPreview(dark = true, link = false)

@Preview(name = "Link actions — light", widthDp = 360)
@Composable
private fun LinkActionsPreviewLight() = ActionsPreview(dark = false, link = true)

@Preview(name = "Link actions — dark", widthDp = 360)
@Composable
private fun LinkActionsPreviewDark() = ActionsPreview(dark = true, link = true)
