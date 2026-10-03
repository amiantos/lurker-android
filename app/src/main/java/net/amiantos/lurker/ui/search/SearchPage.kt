// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import net.amiantos.lurker.ui.feeds.FeedController
import net.amiantos.lurker.ui.feeds.FeedList
import net.amiantos.lurker.ui.feeds.FeedListContent
import net.amiantos.lurker.ui.feeds.FeedModel
import net.amiantos.lurker.ui.feeds.FeedPageState
import net.amiantos.lurker.ui.feeds.FeedPlaceholder
import net.amiantos.lurker.ui.feeds.FeedSnapshot
import net.amiantos.lurker.ui.feeds.StateWords
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Full-text search over everything the account has ever said or been told, across every buffer, held
 * for the life of its dialog: the field, the committed question, the debounce, and the list — one
 * implementation of each, as iOS's `MessageSearchViewController` is.
 *
 * **With nothing typed it shows your recent highlights** — iMessage's search opens the same way, on a
 * quick-jump surface rather than a blank prompt: the lines addressed to you since you last looked, both
 * the set most likely to be what you came for and the set that goes stale if you don't. Highlights are
 * what the landing view holds today, not a definition of it, so it carries no heading of its own.
 *
 * Built fresh per open, so the landing view's answer — which expires — is fetched each time search is
 * opened, and a jump out leaves the next open on the landing view (iOS empties the field when a result
 * is picked). A rotation keeps all of it (`FeedFlow`).
 *
 * ⚠ Every fetch is a READ: `GET /api/highlights` while landing, `GET /api/search` for a query.
 */
class SearchState(private val model: ChatViewModel, seed: String, private val scope: CoroutineScope) : FeedPageState {
    private val ledger = SearchQueryLedger(seed)

    /** The field, cursor and all. Starts holding the seed, the cursor after it. */
    var field by mutableStateOf(TextFieldValue(seed, TextRange(seed.length)))
        private set

    /** What the list answers, for the placeholders' words — moved only by a commit. */
    var showing by mutableStateOf(ledger.showing)
        private set
    var shownQuery by mutableStateOf(ledger.query)
        private set

    /**
     * Each keystroke asks a different question, so a reload replaces the one in flight rather than being
     * dropped — otherwise the list settles on the answer to a prefix of what was typed.
     */
    override val feed = FeedController(
        scope = scope,
        supersedes = true,
        visible = { items -> FeedModel.visible(items, model.state.ignores) },
        fetch = ::fetch,
    )

    override var closedNotice by mutableStateOf<String?>(null)

    /** The keystroke waiting to become a query; replaced by the next, so a burst costs one search. */
    private var debounce: Job? = null

    init {
        feed.reload()
    }

    /**
     * One page — of highlights while landing, of matches once there's a real query. Both arrive as a
     * `HighlightsPage` with a real `nextBefore` cursor, which is why the list never knows which it's
     * showing. `TooShort` is answered here, locally, and never reaches the wire — as an empty page, not
     * null: null means "we couldn't ask", which would put an error in front of someone mid-word.
     */
    private suspend fun fetch(cursor: FeedCursor?): HighlightsPage? {
        val before = cursor?.beforeMessage
        return when (ledger.showing) {
            SearchShowing.Landing -> model.fetchHighlights(before)
            SearchShowing.TooShort -> HighlightsPage(items = emptyList(), nextBefore = null)
            SearchShowing.Results -> model.searchMessages(ledger.query, before)
        }
    }

    /**
     * The field changed. Debounced ([SearchQueryLedger.DEBOUNCE_MS]); a cursor move or selection alone
     * schedules nothing.
     *
     * The pending commit is cancelled BEFORE the up-to-date check: editing back to the committed text
     * inside the window — type `hip`, backspace to `hi` — would otherwise leave `hip` armed, and the
     * screen would search for text the field no longer holds.
     */
    fun edit(value: TextFieldValue) {
        val changed = value.text != field.text
        field = value
        if (!changed) return
        debounce?.cancel()
        if (!ledger.isNew(value.text)) return
        val text = value.text
        debounce = scope.launch {
            delay(SearchQueryLedger.DEBOUNCE_MS)
            commit(text)
        }
    }

    /** The keyboard's search key: what's typed, now, without waiting out the debounce. */
    fun submit() {
        debounce?.cancel()
        commit(field.text)
    }

    /** Empty the field — back to the landing view at once. */
    fun clear() {
        field = TextFieldValue("")
        submit()
    }

    /** Adopt [text] as the query and run it, when it asks something new (`SearchQueryLedger.commit`). */
    private fun commit(text: String) {
        val reload = ledger.commit(text)
        shownQuery = ledger.query
        if (!reload) return
        showing = ledger.showing
        feed.reload()
    }

    fun words(placeholder: FeedPlaceholder): StateWords =
        when (placeholder) {
            FeedPlaceholder.Loading -> SearchWords.loading(showing)
            FeedPlaceholder.Empty -> SearchWords.empty(showing, shownQuery)
            FeedPlaceholder.Error -> SearchWords.error(showing)
        }
}

/**
 * Search, full screen — Material's search pattern: the field in the top bar, the results under it, the
 * keyboard up on open (you asked for search; you shouldn't have to tap the field you just asked for).
 * Rows are read-only: this is somewhere you pass through on the way to a conversation.
 */
@Composable
internal fun SearchPage(model: ChatViewModel, state: SearchState, onClose: () -> Unit, onSelect: (HighlightItem) -> Unit) {
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    // Scrolling the results puts the keyboard away. This screen is read while being typed at, so the
    // keyboard covers half of what was just fetched, and the gesture to dismiss it should be the one the
    // reader is already making.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { keyboard?.hide() }
    }
    SearchScaffold(
        field = state.field,
        onEdit = state::edit,
        onSubmit = {
            state.submit()
            keyboard?.hide()
        },
        onClear = state::clear,
        onClose = onClose,
    ) { modifier ->
        FeedList(model = model, state = state, words = state::words, onSelect = onSelect, modifier = modifier, listState = listState)
    }
}

/** The page's frame: ✕, the field, a clear button while it holds anything; then the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScaffold(
    field: TextFieldValue,
    onEdit: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = LurkerTheme.colors
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Scaffold(
        containerColor = colors.bg,
        // The dialog's window doesn't resize for the keyboard, so the content pads for it (see `DialogPage`).
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(LurkerIcons.Close, contentDescription = "Close") } },
                title = {
                    BasicTextField(
                        value = field,
                        onValueChange = onEdit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = "Search messages" },
                        textStyle = textStyle,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // The filter grammar is typed, not tapped, so the field mustn't fight it:
                        // capitalizing turns `from:` into `From:`, and autocorrect rewrites nicks and
                        // channel names into English words.
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        decorationBox = { inner ->
                            Box {
                                if (field.text.isEmpty()) {
                                    Text("Search messages", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                inner()
                            }
                        },
                    )
                },
                actions = {
                    if (field.text.isNotEmpty()) {
                        IconButton(onClick = onClear) { Icon(LurkerIcons.Cancel, contentDescription = "Clear") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

// MARK: - Previews

@Composable
private fun SearchPreview(dark: Boolean, text: String, words: StateWords) {
    LurkerTheme(darkTheme = dark) {
        SearchScaffold(
            field = TextFieldValue(text),
            onEdit = {},
            onSubmit = {},
            onClear = {},
            onClose = {},
        ) { modifier ->
            FeedListContent(
                sections = emptyList(),
                snapshot = FeedSnapshot(placeholder = FeedPlaceholder.Empty),
                words = { words },
                onSelect = {},
                onRefresh = {},
                onShown = {},
                onRemove = null,
                listState = rememberLazyListState(),
                modifier = modifier,
            )
        }
    }
}

@Preview(name = "Search landing — light", heightDp = 640)
@Composable
private fun SearchLandingPreviewLight() = SearchPreview(false, "", SearchWords.empty(SearchShowing.Landing, ""))

@Preview(name = "Search landing — dark", heightDp = 640)
@Composable
private fun SearchLandingPreviewDark() = SearchPreview(true, "", SearchWords.empty(SearchShowing.Landing, ""))

@Preview(name = "Search too short — light", heightDp = 640)
@Composable
private fun SearchShortPreviewLight() = SearchPreview(false, "h", SearchWords.empty(SearchShowing.TooShort, "h"))

@Preview(name = "Search too short — dark", heightDp = 640)
@Composable
private fun SearchShortPreviewDark() = SearchPreview(true, "h", SearchWords.empty(SearchShowing.TooShort, "h"))

@Preview(name = "Search no matches — light", heightDp = 640)
@Composable
private fun SearchNoMatchesPreviewLight() = SearchPreview(false, "in:#lurker zebra", SearchWords.empty(SearchShowing.Results, "in:#lurker zebra"))

@Preview(name = "Search no matches — dark", heightDp = 640)
@Composable
private fun SearchNoMatchesPreviewDark() = SearchPreview(true, "in:#lurker zebra", SearchWords.empty(SearchShowing.Results, "in:#lurker zebra"))
