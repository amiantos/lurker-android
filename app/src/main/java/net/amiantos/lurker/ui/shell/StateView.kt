// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme

/**
 * A centred placeholder for a surface that has nothing to show yet: a glyph *or* a spinner, a
 * title, and an optional subtitle. lurker-ios's `StateView` — a reusable primitive rather than one
 * screen's copy, so every screen's loading, empty and failed states read as one family (#20).
 *
 * One font size, the app's rule: everything is `bodyMedium`, and the hierarchy is carried by colour
 * and weight, not size (the glyph does the rest of the work).
 *
 * [symbol] is iOS's SF Symbol, drawn as the Material glyph every Android user already reads
 * (`LurkerIcons`, see [vector]); [isLoading] swaps it for a spinner — the two never appear at once,
 * because "we're fetching" and "here's an icon for the empty result" are different moments.
 *
 * [actionTitle] is iOS's action button — "Add Network" on an empty account, "Try Again" on a failed
 * load. A borderless (text) button, as iOS's is: it's the one thing on this view a person is meant to
 * do, so it has to look like it and carry a button's semantics. Outside the announcement, so
 * TalkBack reads the state, then offers the button as its own stop.
 *
 * **TalkBack.** The glyph, title and subtitle are ONE element, and a settled state is a polite live
 * region: a state that changes while the reader is on the screen — a load that fails, "Loading
 * messages…" settling to "No messages yet" — is read out without moving focus. The words are set as
 * one description on one node, not merged from the texts: a live region announces when ITS content
 * changes, and a change inside a merged child is reported against the child. So a caller should draw
 * a screen's states through ONE `StateView` call fed from a `when` (a [StateModel]), not one call per
 * branch — separate calls are separate nodes, and a node that replaces another isn't a change to
 * announce.
 *
 * A loading state is NOT live: it's the moment before the answer, and search re-enters it on every
 * debounced keystroke — "Searching…" read over the reader's own typing is noise. It says it's in
 * progress instead, as a spinner does.
 *
 * [announces] false keeps even a settled state quiet, for a screen whose states follow the reader's
 * own typing: Search lands on "No matches" or "Keep typing" at the end of every debounced keystroke,
 * and announcing each would talk over the typing as "Searching…" would. Read on focus all the same.
 *
 * [onRetry] is for a state whose way out is a gesture TalkBack can't make — "Pull to try again."
 * Material's pull-to-refresh offers no accessibility action, where iOS's refresh control answers
 * VoiceOver, so the same retry is offered as the element's "Try Again" action. The copy stays iOS's.
 * ⚠ The retry must NOT take the pull's path: a pull hides this view behind its own spinner, so the
 * focused element would vanish and a repeat failure would come back as a new, unannounced one. A
 * caller retries as an ordinary reload, which puts THIS view into its loading state and back.
 */
@Composable
fun StateView(
    title: String,
    modifier: Modifier = Modifier,
    symbol: StateSymbol? = null,
    subtitle: String? = null,
    isLoading: Boolean = false,
    actionTitle: String? = null,
    onAction: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    announces: Boolean = true,
) {
    val spoken = if (subtitle == null) title else "$title, $subtitle"
    val currentRetry by rememberUpdatedState(onRetry)
    val retryActions = remember(onRetry != null) {
        if (onRetry == null) null else listOf(CustomAccessibilityAction("Try Again") { currentRetry?.invoke(); true })
    }
    Box(modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                modifier = Modifier.widthIn(max = 320.dp).clearAndSetSemantics {
                    contentDescription = spoken
                    if (isLoading) {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    } else if (announces) {
                        liveRegion = LiveRegionMode.Polite
                    }
                    if (retryActions != null) customActions = retryActions
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 3.dp,
                    )
                    // The glyph/spinner want a touch more air above the text than the two lines
                    // want between themselves — iOS's custom spacing after the top element.
                    Spacer(Modifier.height(6.dp))
                } else if (symbol != null) {
                    // A tier quieter than the words (iOS's `.tertiaryLabel` glyph): it says which
                    // kind of blank this is at a glance; the words are what's read.
                    Icon(
                        symbol.vector,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (actionTitle != null) {
                TextButton(onClick = onAction) { Text(actionTitle, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** [StateView] drawn from a [StateModel]. */
@Composable
fun StateView(
    model: StateModel,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    announces: Boolean = true,
) =
    StateView(
        title = model.title,
        modifier = modifier,
        symbol = model.symbol,
        subtitle = model.subtitle,
        isLoading = model.isLoading,
        actionTitle = model.actionTitle,
        onAction = onAction,
        onRetry = onRetry,
        announces = announces,
    )

/** Each [StateSymbol] as its Material glyph — iOS's symbol name in the enum's KDoc. */
internal val StateSymbol.vector: ImageVector
    get() = when (this) {
        StateSymbol.Warning -> LurkerIcons.Warning
        StateSymbol.Notice -> LurkerIcons.ErrorOutline
        StateSymbol.Search -> LurkerIcons.Search
        StateSymbol.Ellipsis -> LurkerIcons.MoreHoriz
        StateSymbol.Bookmark -> LurkerIcons.BookmarkBorder
        StateSymbol.Mention -> LurkerIcons.AlternateEmail
        StateSymbol.Conversation -> LurkerIcons.ChatBubble
        StateSymbol.Server -> LurkerIcons.Dns
        StateSymbol.Welcome -> LurkerIcons.AutoAwesome
        StateSymbol.Buffers -> LurkerIcons.Forum
        StateSymbol.Network -> LurkerIcons.Language
        StateSymbol.NoNetworks -> LurkerIcons.PublicOff
        StateSymbol.Star -> LurkerIcons.StarBorder
        StateSymbol.Filter -> LurkerIcons.FilterList
        StateSymbol.Uploads -> LurkerIcons.PhotoLibrary
    }

@Composable
private fun StateViewPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Row(Modifier.background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.size(240.dp, 320.dp)) {
                StateView(
                    title = "Couldn't load networks",
                    symbol = StateSymbol.Warning,
                    subtitle = "Check your connection and try again.",
                    actionTitle = "Try Again",
                )
            }
            Box(Modifier.size(240.dp, 320.dp)) { StateView(title = "Loading messages…", isLoading = true) }
        }
    }
}

@Preview(name = "State — light", widthDp = 480, heightDp = 320)
@Composable
private fun StateViewPreviewLight() = StateViewPreview(dark = false)

@Preview(name = "State — dark", widthDp = 480, heightDp = 320)
@Composable
private fun StateViewPreviewDark() = StateViewPreview(dark = true)
