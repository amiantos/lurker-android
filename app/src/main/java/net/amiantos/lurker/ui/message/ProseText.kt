// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * iOS's `FormProseCell`. The font is the row's own ([textStyle]), not the log's compact face: this
 * is a row among the form's rows.
 */
@Composable
fun ProseText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val style = rememberMessageTextStyle()
    // The spoilers opened in this text, the row's own — no message stands behind a topic to key
    // them by — and closed again when the text changes.
    var revealed by remember(text) { mutableStateOf(emptySet<Int>()) }
    val rendered = remember(text, style, color, revealed) {
        MessageText.prose(text, style, color, revealed) { ordinal ->
            revealed = if (ordinal in revealed) revealed - ordinal else revealed + ordinal
        }
    }
    // What TalkBack reads: the spoken form hides what a spoiler hides and keeps the links, and one
    // action per hidden box, since the tap that opens one is aimed at a point (`MessageRow`).
    val spoken = remember(rendered) { MessageText.spokenAnnotated(rendered) }
    val ordinals = remember(rendered) { MessageText.hiddenSpoilerOrdinals(rendered) }
    val semantics = Modifier.clearAndSetSemantics {
        this.text = spoken
        if (ordinals.isNotEmpty()) {
            customActions = ordinals.mapIndexed { position, ordinal ->
                val name = if (ordinals.size == 1) "Reveal spoiler" else "Reveal spoiler ${position + 1} of ${ordinals.size}"
                CustomAccessibilityAction(name) {
                    revealed = revealed + ordinal
                    true
                }
            }
        }
    }
    ProvideSafeUriHandler { Text(rendered, modifier = modifier.then(semantics), style = textStyle) }
}
