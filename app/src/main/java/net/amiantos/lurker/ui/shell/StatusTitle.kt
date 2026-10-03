// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.StatusLight

/**
 * What a screen's title reads: its name, and a subtitle saying how it's doing in words.
 * lurker-ios's `StatusTitle`.
 *
 * On iOS this replaced a floating title pill that was positioned by hand over the bar and stayed
 * behind when the bar moved; a title and subtitle are the bar's own. Here it is the top app bar's
 * title slot, for the same reason: it goes wherever the bar goes. The buffer list is its first
 * reader (always "Lurker", following the socket); the conversation (U2) is the next, with a
 * network as [detail] and a DM's [peer].
 */
data class StatusTitle(
    val title: String,
    val status: StatusLight,
    /**
     * What the status is about when the title doesn't already say — a channel's network. Null
     * when the title *is* the thing (a server buffer, Lurker itself).
     */
    val detail: String? = null,
    /** A DM's other person, whose presence the subtitle reports in place of the network's. */
    val peer: FriendPresence? = null,
) {
    /** "Connected", "Libera · Online", "Libera · Away" — see `StatusLight.subtitle`. */
    val subtitle: String get() = status.subtitle(detail = detail, peer = peer)
}

/**
 * [status] in a top app bar's title slot: the title in the bar's own style, the subtitle under it
 * beside the status light. The words are the signal and the dot repeats them, so the state never
 * rests on a colour alone (e-ink).
 *
 * One node for TalkBack ("Lurker, Connected"), as the bar's title and subtitle are on iOS.
 */
@Composable
fun StatusTitleText(status: StatusTitle, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(status.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(status.status)
            Text(
                status.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A status light: a 7dp dot, the size the buffer list's network headers draw theirs. */
@Composable
internal fun StatusDot(light: StatusLight, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(LurkerTheme.colors.color(light), CircleShape))
}
