// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A centred placeholder for a surface that has nothing to show yet: a spinner, a title, and an
 * optional subtitle. lurker-ios's `StateView` — a reusable primitive rather than one screen's copy:
 * the buffer list is its first caller here, and the conversation's "nothing yet" (U2) is the next.
 *
 * One font size, the app's rule: everything is `bodyMedium`, and the hierarchy is carried by colour
 * and weight, not size.
 *
 * iOS shows an SF Symbol in place of the spinner for an empty result. There is no Material icon
 * set in this build (material3 dropped `material-icons-core`), so the empty states are words
 * alone; the words were always what said which kind of blank it is.
 *
 * [actionTitle] is iOS's action button — "Add Network" on an empty account, "Try Again" on a failed
 * load. A borderless (text) button, as iOS's is: it's the one thing on this view a person is meant to
 * do, so it has to look like it and carry a button's semantics. Outside the merged announcement, so
 * TalkBack reads the state, then offers the button as its own stop.
 */
@Composable
fun StateView(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isLoading: Boolean = false,
    actionTitle: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                // One announcement for the whole state, title then subtitle.
                modifier = Modifier.widthIn(max = 320.dp).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "We're fetching" and "here's the empty result" are different moments, so the
                // spinner only ever comes with a loading state.
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 3.dp,
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
