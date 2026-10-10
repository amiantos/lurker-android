// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FullScreenDialog
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.theme.monoTextStyle
import net.amiantos.lurker.ui.uploads.Attachments
import net.amiantos.lurker.ui.uploads.receivesPastedImages

/**
 * The composer's Edit Color (lurker#1117): the draft full screen, with a palette under it that rides
 * the keyboard. iOS's `ColorEditorViewController`.
 *
 * The text IS the preview. Select words the ordinary way and tap a colour, and they change where they
 * sit; with only a caret, the colour is what you type next ([ComposerState.pen]). No sample sentence
 * and no hidden pen to keep track of: what the field shows is what sends.
 *
 * ⚠ It edits the composer's own field — the same `TextFieldState` and colour, shown larger — rather
 * than a copy. So there's nothing to hand back and nothing to fall out of step: what's typed here saves
 * and syncs as typing in the bar does, a finished upload's link lands here as it would there, and
 * closing it any way at all (✕, Done, back) keeps everything. [ComposerState.editorOpen] keeps the bar
 * from taking the keyboard back while it's up.
 *
 * Sixteen colours, not mIRC's 99: the sixteen are the ones every client paints, and the list draws
 * them in the same palette (`LurkerColors.mirc`). Colour changes don't join the field's undo, which
 * holds text only.
 *
 * The field is the bar's in all but size: the same capitalisation, and a pasted image uploads. Not its
 * keys — Enter here is a line break, as on iOS: this is where a longer message gets written, and it's
 * sent from the bar once you close this.
 */
@Composable
internal fun ColorEditor(
    state: ComposerState,
    colorOutput: OutputTransformation,
    capitalizes: Boolean,
    attachments: Attachments?,
    onClose: () -> Unit,
) {
    var layer by rememberSaveable { mutableStateOf(ComposerColors.Layer.Text) }
    val focus = remember { FocusRequester() }
    FullScreenDialog(onDismissRequest = onClose) {
        // Only the ✕: closing keeps everything, so a Done would be a second way to do the same thing,
        // and sending is the bar's job once you're back in it.
        DialogPage(title = "Edit Color", exit = PageExit.Close, onExit = onClose) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                BasicTextField(
                    state = state.field,
                    inputTransformation = state.colorInput,
                    outputTransformation = colorOutput,
                    // The message list's fixed-width face, as in the composer this edits for; what's
                    // written here is read against the list's own ground (the dialog's `background`).
                    textStyle = monoTextStyle().copy(color = LurkerTheme.colors.fg),
                    cursorBrush = SolidColor(LurkerTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (capitalizes) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .focusRequester(focus)
                        .receivesPastedImages(attachments),
                )
                // Here, inside the dialog's content: its window composes after the dialog attaches, and a
                // request made from outside it would reach a requester with no field behind it yet.
                LaunchedEffect(Unit) { focus.requestFocus() }
                ColorPalette(
                    state = state,
                    layer = layer,
                    onLayer = { layer = it },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** mIRC's own names, for TalkBack — the colour the code means to every other client. */
private val NAMES = listOf(
    "White", "Black", "Blue", "Green", "Red", "Brown", "Purple", "Orange",
    "Yellow", "Light Green", "Teal", "Cyan", "Light Blue", "Pink", "Grey", "Light Grey",
)

/**
 * Text / Highlight, None, and the sixteen in two rows of eight: big enough to hit on a narrow phone,
 * small enough to leave the text most of the screen above the keyboard. A ring marks the colour the
 * selection (or the caret) is in.
 */
@Composable
private fun ColorPalette(
    state: ComposerState,
    layer: ComposerColors.Layer,
    onLayer: (ComposerColors.Layer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LurkerTheme.colors.mirc
    val current = ComposerColors.slot(state.currentColors, layer)
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                ComposerColors.Layer.entries.forEachIndexed { index, each ->
                    SegmentedButton(
                        selected = layer == each,
                        onClick = { onLayer(each) },
                        shape = SegmentedButtonDefaults.itemShape(index, ComposerColors.Layer.entries.size),
                    ) { Text(if (each == ComposerColors.Layer.Text) "Text" else "Highlight") }
                }
            }
            Swatch(color = null, name = "No color", selected = current == null, modifier = Modifier.size(44.dp)) {
                state.pickColor(null, layer)
            }
        }
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 8) {
                    val slot = row * 8 + column
                    Swatch(
                        color = palette.getOrNull(slot),
                        name = NAMES[slot],
                        selected = current == slot,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { state.pickColor(slot, layer) }
                }
            }
        }
    }
}

/**
 * One round colour, or — with none — the "no colour" choice. A hairline keeps white readable on the
 * light canvas and black on the dark one.
 *
 * ⚠ A plain `clickable`, which takes no focus on touch: the field keeps it, so the keyboard stays up
 * and the selection stays where it is while you pick.
 */
@Composable
private fun Swatch(color: Color?, name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LurkerTheme.colors
    Box(
        modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(40.dp).border(2.5.dp, colors.fg, CircleShape))
        if (color != null) {
            Box(Modifier.size(32.dp).background(color, CircleShape).border(1.dp, colors.border, CircleShape))
        } else {
            Icon(LurkerIcons.Block, contentDescription = null, tint = colors.fgMuted, modifier = Modifier.size(28.dp))
        }
    }
}
