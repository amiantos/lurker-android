// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.commands.CommandRegistry
import net.amiantos.lurkerkit.rendering.NickColor

/**
 * The completion suggestions — lurker-ios's `SuggestionsView`: the best few candidates floating
 * above the composer as separate capsules, best candidate at the BOTTOM — likelihood equals
 * proximity to the field, so the pill you almost certainly want is the shortest reach. Centred, not
 * leading-aligned: they hang in the middle over the field, a thumb's reach from either hand, and
 * mixed-width titles read as one group. How many there are is the caller's call: nicks and channels
 * cap at four, command chips run to six.
 *
 * Dumb by design: [ComposerState] computes the suggestions and this only draws pills and reports
 * taps. A pill doesn't take focus, so tapping one leaves the keyboard where it was.
 */
@Composable
internal fun SuggestionsView(suggestions: List<Suggestion>, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Best-first in, so reversed out: the head of the list lands nearest the composer.
        for (suggestion in suggestions.asReversed()) {
            Pill(suggestion, onPick)
        }
    }
}

@Composable
private fun Pill(suggestion: Suggestion, onPick: (Suggestion) -> Unit) {
    val colors = LurkerTheme.colors
    Box(
        Modifier
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(role = Role.Button) { onPick(suggestion) }
            .clearAndSetSemantics {
                contentDescription = suggestion.accessibility
                role = Role.Button
                onClick { onPick(suggestion); true }
            }
            // Generous on purpose: one-shot tap targets mid-typing, roughly the composer's height,
            // with wider shoulders for the thumb.
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            suggestion.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = suggestion.color(colors),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A pill's colour: a command in the accent (an action), a channel in the label colour, a nick in its
 * own palette colour — the one the conversation above draws it in.
 */
internal fun Suggestion.color(colors: LurkerColors): Color = when (kind) {
    Suggestion.Kind.Command -> colors.accent
    Suggestion.Kind.Channel -> colors.fg
    Suggestion.Kind.Nick -> colors.nick[NickColor.index(value, paletteCount = colors.nick.size)]
}

// MARK: - Previews

@Composable
private fun SuggestionsPreview(dark: Boolean, suggestions: List<Suggestion>) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(LurkerTheme.colors.bg).padding(16.dp)) {
            SuggestionsView(suggestions, onPick = {})
        }
    }
}

private val previewNicks = listOf("alice", "bob", "carol_", "dave").map(Suggestion::nick)
private val previewCommands = CommandRegistry.matching("").map(Suggestion::command)

@Preview(name = "Nick pills — light")
@Composable
private fun NicksLight() = SuggestionsPreview(dark = false, suggestions = previewNicks)

@Preview(name = "Nick pills — dark")
@Composable
private fun NicksDark() = SuggestionsPreview(dark = true, suggestions = previewNicks)

@Preview(name = "Command pills — light")
@Composable
private fun CommandsLight() = SuggestionsPreview(dark = false, suggestions = previewCommands)

@Preview(name = "Command pills — dark")
@Composable
private fun CommandsDark() = SuggestionsPreview(dark = true, suggestions = previewCommands)
