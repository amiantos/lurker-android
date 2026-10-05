// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.core.net.toUri
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import net.amiantos.lurker.platform.AppEvents
import net.amiantos.lurker.platform.LocalAppEvents
import net.amiantos.lurker.platform.confirmCopy
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.RetryRow
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.UploadItem
import net.amiantos.lurkerkit.model.UploadKind
import net.amiantos.lurkerkit.model.UploadsFilter
import java.time.Instant

/**
 * Everything this account has ever uploaded, newest first — browse it, search it, and send one again
 * without re-uploading it (lurker-ios#138). lurker-ios's `UploadsViewController`, as a full-screen
 * dialog page.
 *
 * **Re-sharing is why this is worth having on a phone.** Every other surface here is a way of reading;
 * this one is a way of sending.
 *
 * **Tap copies the address; press-and-hold carries everything else, viewing included.** You come here
 * to SEND something you already have, so the free gesture is the one that gets it into a message;
 * viewing is the occasional case, and that is what a press-and-hold is for.
 *
 * **The filters are the server's** (`UploadsRequest`): filename search, kind and starred all go on the
 * wire — this screen holds only the pages it has scrolled through, and the point of the search is
 * finding one it hasn't. The filter is a menu (five kinds and a starred toggle don't fit across a phone
 * as chips), so its button lights up when anything is set and an empty grid names the filter in words.
 *
 * @param onAddToMessage put a file in the composer behind the dialog, or null where there's none (the
 *   buffer list) — which is what keeps Add to Message out of the menu there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadsPage(
    state: UploadsBrowserState,
    onClose: () -> Unit,
    onAddToMessage: ((String) -> Unit)?,
    /** Open the media viewer on a gallery of the grid's viewable rows, at the picked one. */
    onView: (List<LinkPreview>, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val events = LocalAppEvents.current
    val grid = state.grid
    val keyboard = LocalSoftwareKeyboardController.current
    // One formatter for the page, rebuilt only when the locale changes — not one per tile per frame.
    val relative = rememberRelativeAge()
    val gridState = rememberLazyGridState()
    // Once per answer, not once per composition: this state outlives a rotation, and a bare
    // "scrollToTop > 0" threw the restored position back to the top on every one after a search.
    LaunchedEffect(state.scrollToTop) {
        val answer = state.scrollToTop
        if (answer > state.scrolledToTop) {
            gridState.scrollToItem(0)
            // After, not before: a page torn down mid-scroll hasn't been to the top, and its
            // successor should go.
            state.scrolledToTop = answer
        }
    }
    // Scrolling puts the keyboard away: this screen is read while being typed at, and the gesture for
    // getting rid of the keyboard should be the one the reader is already making.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.filter { it }.collect { keyboard?.hide() }
    }
    val perform: (UploadItem, UploadAction) -> Unit = { item, action ->
        when (action) {
            UploadAction.View -> when {
                item.removed -> state.reportTombstone()
                // A gallery over every viewable row in the grid, positioned on this one; nothing to
                // present after all, and the browser can have it.
                else -> UploadTiles.gallery(grid.items, item)?.let { (previews, start) -> onView(previews, start) }
                    ?: openInBrowser(context, item.url)
            }
            UploadAction.OpenInBrowser -> if (item.removed) state.reportTombstone() else openInBrowser(context, item.url)
            UploadAction.AddToMessage -> onAddToMessage?.invoke(item.url)
            UploadAction.Star, UploadAction.Unstar -> state.toggleStar(item)
            UploadAction.CopyLink -> if (item.removed) state.reportTombstone() else copyLink(context, item.url, events)
            UploadAction.Share -> share(context, item.url)
            UploadAction.Delete -> state.askToDelete(item)
        }
    }
    DialogPage(
        title = "Uploads",
        exit = PageExit.Close,
        onExit = onClose,
        actions = { FilterMenu(grid.filter, onChange = state::setFilter) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(state.query, onEdit = state::edit, onSubmit = { keyboard?.hide() })
            PullToRefreshBox(
                isRefreshing = grid.refreshing,
                onRefresh = { state.reload(byPull = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = UploadsColumns,
                    state = gridState,
                    contentPadding = PaddingValues(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(grid.items, key = { _, item -> item.id }) { index, item ->
                        LaunchedEffect(item.id, index) { state.shown(index) }
                        UploadTile(
                            item = item,
                            thumbnails = state.thumbnails,
                            relative = relative,
                            actions = UploadTiles.actions(item, canInsert = onAddToMessage != null),
                            onTap = { perform(item, UploadAction.CopyLink) },
                            onAction = { action -> perform(item, action) },
                        )
                    }
                    // A failed page-in under tiles already shown: tiles ask for the next page as they come
                    // on screen, and at the bottom none ever will again — so the way to ask again is here.
                    // There under any tiles, failed or not, so a failure is a change TalkBack reads out (`RetryRow`).
                    if (grid.items.isNotEmpty()) {
                        item(key = "retry", span = { GridItemSpan(maxLineSpan) }) {
                            RetryRow(failed = grid.pageInFailed, onRetry = state::retryMore, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                    grid.footer?.let { footer ->
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                footer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LurkerTheme.colors.fgMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                val placeholder = grid.placeholder
                // A pull shows its own spinner, so the page's stays away while one is out.
                if (placeholder != null && !(placeholder.isLoading && grid.refreshing)) {
                    StateView(
                        placeholder,
                        // "Pull to try again." — a pull TalkBack can't make, so offered as an action too, as
                        // an ordinary reload: this view goes to Loading and back rather than hiding behind
                        // the pull's spinner (see `StateView`).
                        onRetry = if (!placeholder.isLoading && grid.loadFailed) {
                            { state.reload() }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
    state.alert?.let { alert ->
        AlertDialog(
            onDismissRequest = { state.alert = null },
            title = { Text(alert.title) },
            text = { Text(alert.message) },
            confirmButton = { TextButton(onClick = { state.alert = null }) { Text("OK") } },
        )
    }
    state.confirmingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { state.confirmingDelete = null },
            title = { Text("Delete ${item.displayName}?") },
            text = { Text("The file is removed from storage. Links to it in past messages will stop working.") },
            dismissButton = { TextButton(onClick = { state.confirmingDelete = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = { state.delete(item) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/** As many square tiles across as fit at a readable size, never fewer than two (`UploadTiles.columns`). */
private object UploadsColumns : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = UploadTiles.columns(availableSize.toDp().value)
        val usable = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        val base = usable / count
        val remainder = usable % count
        return List(count) { index -> base + if (index < remainder) 1 else 0 }
    }
}

/**
 * The filename search, under the bar — where a thumb already is on a phone is iOS's argument for its
 * bottom field; under the title is Material's place for a page's own filter field.
 */
@Composable
private fun SearchField(text: String, onEdit: (String) -> Unit, onSubmit: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LurkerIcons.Search, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = text,
            onValueChange = onEdit,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // A filename is not English: capitalizing turns `img_4821` into `Img_4821`, and autocorrect
            // rewrites the stems people actually type.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .semantics { contentDescription = "Search filenames" },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("Search filenames", style = style, color = muted, maxLines = 1)
                    inner()
                }
            },
        )
        if (text.isNotEmpty()) {
            IconButton(onClick = { onEdit("") }) { Icon(LurkerIcons.Cancel, contentDescription = "Clear", tint = muted) }
        }
    }
}

/**
 * The filter menu, opposite ✕: All and the kinds, mutually exclusive; then Starred Only on its own —
 * ⚠⚠ it COMPOSES with a kind ("my starred gifs"), so it isn't a sixth kind. The button takes the accent
 * while anything is set: an active filter is otherwise invisible until the menu is opened.
 */
@Composable
private fun FilterMenu(filter: UploadsFilter, onChange: ((UploadsFilter) -> UploadsFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val narrowed = filter.kind != null || filter.favoritesOnly
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { stateDescription = if (narrowed) "Filtered" else "All uploads" },
        ) {
            Icon(
                LurkerIcons.FilterList,
                contentDescription = "Filter",
                tint = if (narrowed) LurkerTheme.colors.accent else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun choose(change: (UploadsFilter) -> UploadsFilter) {
                expanded = false
                onChange(change)
            }
            KindItem("All", selected = filter.kind == null) { choose { it.copy(kind = null) } }
            for (kind in UploadKind.entries) {
                KindItem(kind.label, selected = filter.kind == kind) { choose { it.copy(kind = kind) } }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Starred Only") },
                leadingIcon = { Icon(if (filter.favoritesOnly) LurkerIcons.Star else LurkerIcons.StarBorder, contentDescription = null) },
                trailingIcon = { if (filter.favoritesOnly) Icon(LurkerIcons.Check, contentDescription = null) },
                onClick = { choose { it.copy(favoritesOnly = !it.favoritesOnly) } },
                modifier = Modifier.semantics { stateDescription = if (filter.favoritesOnly) "On" else "Off" },
            )
        }
    }
}

@Composable
private fun KindItem(title: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        trailingIcon = { if (selected) Icon(LurkerIcons.Check, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.semantics { if (selected) stateDescription = "Selected" },
    )
}

/** One tile, loading its thumbnail — see [UploadTileContent]. */
@Composable
private fun UploadTile(
    item: UploadItem,
    thumbnails: UploadThumbnails,
    relative: (Instant) -> String,
    actions: List<UploadAction>,
    onTap: () -> Unit,
    onAction: (UploadAction) -> Unit,
) {
    val path = item.thumbnailPath
    val targetPx = with(LocalDensity.current) { UploadTiles.MIN_TILE_DP.dp.roundToPx() }
    // Keyed by the path, so a reused slot never paints one upload's picture on another's tile.
    val thumbnail by produceState(initialValue = path?.let(thumbnails::cached), path) {
        value = if (path == null) null else thumbnails.cached(path) ?: thumbnails.load(path, targetPx)
    }
    UploadTileContent(item, thumbnail, relative, actions, onTap, onAction)
}

/**
 * One upload in the grid: a square of artwork, the filename under it, and when and how big. A file
 * with no thumbnail — audio, text, a video the instance couldn't poster — gets a type glyph in the same
 * square rather than a different layout, so it reads as a peer of one that shows a photo.
 *
 * The star is a STATE badge as much as a control, so it stays visible once set: the starred set is
 * meant to be recognisable while scanning the unfiltered grid. It isn't tappable itself — a tiny target
 * inside a tile that's already a button would be a coin toss.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UploadTileContent(
    item: UploadItem,
    thumbnail: ImageBitmap?,
    /** "5 min. ago" for an instant — the page's one formatter ([rememberRelativeAge]). */
    relative: (Instant) -> String,
    actions: List<UploadAction>,
    onTap: () -> Unit,
    onAction: (UploadAction) -> Unit,
) {
    val context = LocalContext.current
    val colors = LurkerTheme.colors
    var menu by remember { mutableStateOf(false) }
    val meta = UploadTiles.metaLine(item, relative = relative, bytes = { Formatter.formatFileSize(context, it) })
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = "copy link",
                    onLongClickLabel = "show actions",
                    onClick = onTap,
                    onLongClick = { menu = true },
                )
                // One element for TalkBack: the name, starred, the meta line; the tap copies, and every
                // menu entry is a custom action beside it — reachable without the long press.
                .clearAndSetSemantics {
                    contentDescription = UploadTiles.accessibility(item, meta)
                    onClick(label = "copy link") { onTap(); true }
                    onLongClick(label = "show actions") { menu = true; true }
                    customActions = actions.map { action -> CustomAccessibilityAction(action.title) { onAction(action); true } }
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    // A tombstone is dimmed rather than hidden: still a true record of something you
                    // uploaded, and a grid that skipped them would make files vanish with no account.
                    .alpha(if (item.removed) 0.5f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(glyphIcon(UploadTiles.glyph(item)), contentDescription = null, tint = colors.fgMuted, modifier = Modifier.size(32.dp))
                }
                if (item.favorite) {
                    // Its own scrim: a yellow glyph over an unknown picture can land on anything.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(LurkerIcons.Star, contentDescription = null, tint = STAR_YELLOW, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Text(
                item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fg,
                maxLines = 1,
                // The MIDDLE, not the tail: uploaded names share long prefixes (`IMG_4821`, `Screenshot
                // 2026-08-…`) and differ in the part a tail cut eats first.
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 5.dp),
            )
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (action in actions) {
                DropdownMenuItem(
                    text = {
                        Text(action.title, color = if (action == UploadAction.Delete) MaterialTheme.colorScheme.error else Color.Unspecified)
                    },
                    leadingIcon = { Icon(actionIcon(action), contentDescription = null) },
                    onClick = {
                        menu = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

/** iOS's `systemYellow`. */
private val STAR_YELLOW = Color(0xFFFFCC00)

private fun glyphIcon(glyph: UploadTiles.Glyph): ImageVector =
    when (glyph) {
        UploadTiles.Glyph.Removed -> LurkerIcons.Block
        UploadTiles.Glyph.Image -> LurkerIcons.Image
        UploadTiles.Glyph.Video -> LurkerIcons.Movie
        UploadTiles.Glyph.Audio -> LurkerIcons.Audiotrack
        UploadTiles.Glyph.Text -> LurkerIcons.Description
        UploadTiles.Glyph.File -> LurkerIcons.InsertDriveFile
    }

private fun actionIcon(action: UploadAction): ImageVector =
    when (action) {
        UploadAction.View -> LurkerIcons.Visibility
        UploadAction.OpenInBrowser -> LurkerIcons.OpenInNew
        UploadAction.AddToMessage -> LurkerIcons.Reply
        UploadAction.Star -> LurkerIcons.StarBorder
        UploadAction.Unstar -> LurkerIcons.Star
        UploadAction.CopyLink -> LurkerIcons.Link
        UploadAction.Share -> LurkerIcons.Share
        UploadAction.Delete -> LurkerIcons.Delete
    }

/**
 * "now", "5 min. ago", "3 days ago" — iOS's abbreviated, named relative formatter, in the device's
 * locale. The unit is [RelativeAge]'s. One formatter, remembered for the page and rebuilt when the
 * locale changes: building one is real work, and a grid redraws many tiles a frame.
 */
@Composable
private fun rememberRelativeAge(): (Instant) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val formatter = RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale),
            null,
            RelativeDateTimeFormatter.Style.SHORT,
            DisplayContext.CAPITALIZATION_NONE,
        )
        val format: (Instant) -> String = { then -> relativeAge(formatter, then) }
        format
    }
}

private fun relativeAge(formatter: RelativeDateTimeFormatter, then: Instant): String =
    when (val age = RelativeAge.of(then, Instant.now())) {
        RelativeAge.Now -> formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        is RelativeAge.Ago -> formatter.format(
            age.amount.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            when (age.span) {
                RelativeAge.Span.Seconds -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
                RelativeAge.Span.Minutes -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
                RelativeAge.Span.Hours -> RelativeDateTimeFormatter.RelativeUnit.HOURS
                RelativeAge.Span.Days -> RelativeDateTimeFormatter.RelativeUnit.DAYS
                RelativeAge.Span.Weeks -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
                RelativeAge.Span.Months -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
                RelativeAge.Span.Years -> RelativeDateTimeFormatter.RelativeUnit.YEARS
            },
        )
    }

/** Put the address on the clipboard, saying so where the system won't (`confirmCopy`). */
private fun copyLink(context: Context, url: String, events: AppEvents?) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", url))
    events.confirmCopy()
}

/** The system share sheet, with the address. */
private fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the device shares text — vanishingly rare, and a tap that does nothing beats a crash.
    }
}

/**
 * Hand the file to the browser — text, and whatever the media viewer can't present (`UploadTiles.preview`).
 */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser — nothing to hand it to.
    }
}

// MARK: - Previews

private val previewItems = listOf(
    UploadItem(id = 3, url = "https://up.example/a.png", filename = "Screenshot 2026-08-14 at 10.42.17.png", mime = "image/png", byteSize = 482_133, createdAt = Instant.now().minusSeconds(300), favorite = true, canDelete = true),
    UploadItem(id = 2, url = "https://up.example/b.mp4", filename = "IMG_4821.mp4", mime = "video/mp4", byteSize = 31_400_000, createdAt = Instant.now().minusSeconds(86_400 * 3)),
    UploadItem(id = 1, url = "https://up.example/c.txt", filename = "notes.txt", mime = "text/plain", byteSize = 1_204, createdAt = Instant.now().minusSeconds(86_400 * 40), removed = true),
)

@Composable
private fun TilesPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.background).padding(11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (item in previewItems) {
                Box(Modifier.weight(1f)) {
                    UploadTileContent(item, thumbnail = null, relative = { "5 min. ago" }, actions = UploadTiles.actions(item, canInsert = true), onTap = {}, onAction = {})
                }
            }
        }
    }
}

@Preview(name = "Upload tiles — light", widthDp = 400)
@Composable
private fun TilesLight() = TilesPreview(dark = false)

@Preview(name = "Upload tiles — dark", widthDp = 400)
@Composable
private fun TilesDark() = TilesPreview(dark = true)

@Composable
private fun EmptyPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(MaterialTheme.colorScheme.background).size(360.dp, 400.dp)) {
            val placeholder = UploadsGrid().placeholder!!
            StateView(placeholder)
        }
    }
}

@Preview(name = "Uploads, none yet — light")
@Composable
private fun EmptyLight() = EmptyPreview(dark = false)

@Preview(name = "Uploads, none yet — dark")
@Composable
private fun EmptyDark() = EmptyPreview(dark = true)

@Composable
private fun SearchPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { SearchField("march", onEdit = {}, onSubmit = {}) }
    }
}

@Preview(name = "Uploads search — light", widthDp = 360)
@Composable
private fun SearchLight() = SearchPreview(dark = false)

@Preview(name = "Uploads search — dark", widthDp = 360)
@Composable
private fun SearchDark() = SearchPreview(dark = true)
