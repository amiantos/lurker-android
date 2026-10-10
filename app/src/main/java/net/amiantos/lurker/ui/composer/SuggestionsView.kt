// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.message.MessageText
import net.amiantos.lurker.ui.message.MessageTextStyle
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.theme.monoTextStyle
import net.amiantos.lurkerkit.commands.CommandRegistry

/**
 * The completion suggestions — lurker-ios's `SuggestionsView`: a horizontal row of plain chips in
 * the composer's status row, best candidate first (leading), scrolling sideways when there are more
 * than fit. Plain rather than raised — they live inside the slab, and a shadow inside a shadow is
 * noise. How many there are is the caller's call: nicks run to ten, channels to four, commands to
 * however many match.
 *
 * Dumb by design: [ComposerState] computes the suggestions and this only draws chips and reports
 * taps. A chip doesn't take focus, so tapping one leaves the keyboard where it was. Each chip takes
 * touches across the row's full height, though it draws at the text's size: a target that short is
 * easy to miss.
 */
@Composable
internal fun SuggestionsView(suggestions: List<Suggestion>, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return
    val style = rememberMessageTextStyle()
    val scroll = rememberScrollState()
    // Rebuilt wholesale on each keystroke; the row starts at the best candidate again.
    LaunchedEffect(suggestions) { scroll.scrollTo(0) }
    Row(
        modifier.horizontalScroll(scroll).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (suggestion in suggestions) {
            Chip(suggestion, style, onPick)
        }
    }
}

/** One suggestion as a tappable chip: its title in its own colour on a faint fill. */
@Composable
private fun Chip(suggestion: Suggestion, style: MessageTextStyle, onPick: (Suggestion) -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .clickable(role = Role.Button) { onPick(suggestion) }
            .clearAndSetSemantics {
                contentDescription = suggestion.accessibility
                role = Role.Button
                onClick { onPick(suggestion); true }
            }
            // Half the gap to a neighbour on each side, so two never overlap.
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            suggestion.title,
            style = monoTextStyle(),
            color = suggestion.color(style),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * A chip's colour: a command in the accent (an action), a channel in the label colour, a nick in the
 * colour the conversation above draws it in — through the list's own function, so its palette rules
 * are this one's. Never your own nick: completion doesn't offer you.
 */
internal fun Suggestion.color(style: MessageTextStyle): Color = when (kind) {
    Suggestion.Kind.Command -> style.colors.accent
    Suggestion.Kind.Channel -> style.colors.fg
    Suggestion.Kind.Nick -> MessageText.nickColor(value, isSelf = false, style = style)
}

// MARK: - Previews

@Composable
private fun SuggestionsPreview(dark: Boolean, suggestions: List<Suggestion>) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).height(40.dp)) {
            SuggestionsView(suggestions, onPick = {}, Modifier.fillMaxHeight())
        }
    }
}

private val previewNicks = listOf("alice", "bob", "carol_", "dave").map(Suggestion::nick)
private val previewCommands = CommandRegistry.matching("").map(Suggestion::command)

@Preview(name = "Nick chips — light")
@Composable
private fun NicksLight() = SuggestionsPreview(dark = false, suggestions = previewNicks)

@Preview(name = "Nick chips — dark")
@Composable
private fun NicksDark() = SuggestionsPreview(dark = true, suggestions = previewNicks)

@Preview(name = "Command chips — light")
@Composable
private fun CommandsLight() = SuggestionsPreview(dark = false, suggestions = previewCommands)

@Preview(name = "Command chips — dark")
@Composable
private fun CommandsDark() = SuggestionsPreview(dark = true, suggestions = previewCommands)
