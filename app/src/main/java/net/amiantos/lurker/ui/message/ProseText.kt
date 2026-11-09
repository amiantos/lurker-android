// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextStyle
import net.amiantos.lurker.ui.shell.ProvideSafeUriHandler

/**
 * Prose a form shows rather than edits — a channel's topic in buffer info and channel settings
 * (lurker#1297) — drawn the way the log draws a body: [MessageText.prose] for the colours, links,
 * spoilers and `:shortcode:` glyphs, with the links opening through the app's one door
 * ([ProvideSafeUriHandler]) and a spoiler opening on a tap, as the web's topic does. A topic that
 * read `:skull:` here while the message list showed 💀 was the gap.
 *
 * iOS's `FormProseCell`. Stateless: [revealed] and [onToggleSpoiler] are the page's ([ProseReveals]),
 * so a row that leaves a lazy list and returns draws its boxes as the reader left them. The font is
 * the row's own ([textStyle]), not the log's compact face: this is a row among the form's rows.
 */
@Composable
fun ProseText(
    text: String,
    color: Color,
    revealed: Set<Int>,
    onToggleSpoiler: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val style = rememberMessageTextStyle()
    val toggle by rememberUpdatedState(onToggleSpoiler)
    val rendered = remember(text, style, color, revealed) {
        MessageText.prose(text, style, color, revealed) { ordinal -> toggle(ordinal) }
    }
    ProvideSafeUriHandler {
        // What TalkBack reads: the spoken form hides what a spoiler hides and keeps the links, which
        // its menu opens through the same door a finger's tap does; and one action per hidden box,
        // since the tap that opens one is aimed at a point (`MessageRow`).
        val uriHandler = LocalUriHandler.current
        val spoken = remember(rendered, uriHandler) { MessageText.spokenAnnotated(rendered) { uriHandler.openUri(it) } }
        val ordinals = remember(rendered) { MessageText.hiddenSpoilerOrdinals(rendered) }
        val semantics = Modifier.clearAndSetSemantics {
            this.text = spoken
            if (ordinals.isNotEmpty()) {
                customActions = ordinals.mapIndexed { position, ordinal ->
                    val name = if (ordinals.size == 1) "Reveal spoiler" else "Reveal spoiler ${position + 1} of ${ordinals.size}"
                    CustomAccessibilityAction(name) {
                        toggle(ordinal)
                        true
                    }
                }
            }
        }
        Text(rendered, modifier = modifier.then(semantics), style = textStyle)
    }
}
