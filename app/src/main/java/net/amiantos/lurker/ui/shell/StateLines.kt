// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerTheme

/**
 * A place for words that may arrive while the reader is on the screen — a refusal, a failed page,
 * a lookup's answer — which TalkBack reads out where they land (#20).
 *
 * ⚠⚠ **Always composed, words or not.** A polite live region announces a change to its OWN
 * content; a node that is inserted (an `if (error != null)` row, an item that only exists once
 * there's a footer) is new to the accessibility tree, not changed, and is never announced. So the
 * slot is there before its words are: empty, unannounced and drawing nothing while [words] is null,
 * and the same node once they come.
 *
 * ⚠ Never zero-sized, either: Compose leaves a node with empty bounds out of the tree it reports, so
 * an empty slot laid out at 0×0 would be just as new when its words arrived as an inserted one. A
 * 1dp floor keeps it in. (The explicit alternative, `View.announceForAccessibility` or a
 * `TYPE_ANNOUNCEMENT` event, is deprecated as of API 36.)
 *
 * [content] draws the words, and only while there are some. The slot's semantics are the words
 * alone, so nothing inside it may need its own (a button goes beside the slot, not in it — see
 * [RetryRow]). [live] false keeps words that are a standing note or a wait ("Looking up alice…")
 * readable on focus without announcing them.
 */
@Composable
fun AnnouncedSlot(
    words: String?,
    modifier: Modifier = Modifier,
    live: Boolean = words != null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .sizeIn(minWidth = 1.dp, minHeight = 1.dp)
            .clearAndSetSemantics {
                if (words != null) contentDescription = words
                if (live) liveRegion = LiveRegionMode.Polite
            },
    ) {
        if (words != null) content()
    }
}

/**
 * "Couldn't load more." and the button that asks again, at the foot of a paged list (the feeds,
 * Search, the uploads grid). Paging fires as rows come on screen, and at the bottom none ever will
 * again — so the way to ask again is said, and tapped, here.
 *
 * Composed whether or not a page has failed (see [AnnouncedSlot]): 1dp of nothing until one does,
 * so the failure is read out to a reader who has scrolled to the bottom and is waiting on it. The
 * button sits beside the announcement, as its own stop.
 */
@Composable
fun RetryRow(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnnouncedSlot(words = if (failed) RETRY_WORDS else null, modifier = Modifier.weight(1f)) {
            Text(RETRY_WORDS, style = MaterialTheme.typography.bodyMedium, color = LurkerTheme.colors.fgMuted)
        }
        if (failed) TextButton(onClick = onRetry) { Text("Try Again", style = MaterialTheme.typography.bodyMedium) }
    }
}

private const val RETRY_WORDS = "Couldn't load more."

@Preview(name = "Retry row — light")
@Composable
private fun RetryRowPreviewLight() {
    LurkerTheme(darkTheme = false) { Box(Modifier.background(LurkerTheme.colors.bg).sizeIn(minWidth = 320.dp)) { RetryRow(failed = true, onRetry = {}) } }
}

@Preview(name = "Retry row — dark")
@Composable
private fun RetryRowPreviewDark() {
    LurkerTheme(darkTheme = true) { Box(Modifier.background(LurkerTheme.colors.bg).sizeIn(minWidth = 320.dp)) { RetryRow(failed = true, onRetry = {}) } }
}
