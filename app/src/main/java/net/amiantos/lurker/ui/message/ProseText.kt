// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import net.amiantos.lurker.ui.shell.SafeUriHandler

/**
 * Prose a form shows rather than edits — a channel's topic in buffer info and channel settings
 * (lurker#1297) — drawn the way the log draws a body: [MessageText.prose] for the colours, links,
 * spoilers and `:shortcode:` glyphs, with the links opening through the app's one door
 * ([SafeUriHandler]), as a tap in the conversation does. The web renders its topic through
 * `LinkedText`, and a topic that read `:skull:` here while the message list showed 💀 was the gap.
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
    val rendered = remember(text, style, color) { MessageText.prose(text, style, color) }
    val platform = LocalUriHandler.current
    val uriHandler = remember(platform) { SafeUriHandler(platform) }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Text(rendered, modifier = modifier, style = textStyle)
    }
}
