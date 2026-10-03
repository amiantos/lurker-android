// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ReactionGroup

/** What a choice on the sheet came to. */
enum class ReactionChoice {
    /** The toggle went out; the sheet closes, and the chip moves when the network echoes it. */
    Sent,

    /** No socket — nothing went out. The sheet says so and stays. */
    NotConnected,

    /** The line can't take one right now after all (the network dropped since it was drawn). */
    Refused,
}

/**
 * The reaction sheet (lurker-ios#183, lurker-android#37) — lurker-ios's `ReactionSheetViewController`,
 * the web's `ReactModal` shaped for a thumb.
 *
 * Who reacted with what (the only place a touch screen can see that: the web names them in a hover
 * title), a grid of quick picks, and a field for anything else — an emoji from the keyboard's emoji
 * panel, or plain text like "lol", which the spec allows and IRC people actually use. Every choice
 * toggles: picking a reaction you already gave takes it back.
 *
 * Live, unlike the actions sheet: [inputs] follows the store, so a reaction landing while it's open
 * shows up in the list instead of the sheet asserting an answer that has since moved.
 *
 * @param onChoose toggle that value — re-checked against the store at the tap by the caller, not
 *   trusted from whenever the buttons were drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionSheet(
    message: Message,
    target: String,
    inputs: ReactionSheetInputs,
    onChoose: (String) -> ReactionChoice,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Choosing dismisses, and the buttons stay live through the animation — a second tap would send a
    // second toggle, taking the first one straight back.
    val chosen = remember { booleanArrayOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ReactionSheetContent(
            message = message,
            target = target,
            inputs = inputs,
            problem = problem,
            onProblem = { problem = it },
            onChoose = choose@{ value ->
                if (chosen[0]) return@choose
                when (onChoose(value)) {
                    ReactionChoice.Sent -> {
                        chosen[0] = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                    ReactionChoice.NotConnected -> {
                        problem = ReactionSheetModel.NOT_CONNECTED
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                    ReactionChoice.Refused -> Unit
                }
            },
        )
    }
}

/** The sheet's contents, stateless but for the field — for previews, and so it draws only what it's given. */
@Composable
internal fun ReactionSheetContent(
    message: Message,
    target: String,
    inputs: ReactionSheetInputs,
    problem: String?,
    onProblem: (String?) -> Unit,
    onChoose: (String) -> Unit,
) {
    val colors = LurkerTheme.colors
    var typed by remember { mutableStateOf("") }
    val verdict = TypedReaction.of(typed)
    // The field's own problem wins while it has one; a refused send is said until the next edit.
    val shownProblem = if (verdict.tooLong) ReactionSheetModel.TOO_LONG else problem
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Who you're reacting to and the line itself, so the sheet can't act on the wrong one without
        // saying so — the job the actions sheet's header does too.
        SheetHeader(ReactionSheetModel.title(message), ReactionSheetModel.quote(message), detailLines = 2)

        if (inputs.canReact) {
            val mine = inputs.mine
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in ReactionSheetModel.quickRows()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (value in row) {
                            QuickPick(value, mine = value in mine, modifier = Modifier.weight(1f)) { onChoose(value) }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Android can't open a field on the emoji panel the way iOS's `EmojiTextField`
                    // does; the keyboard's emoji key is a tap away, and plain text works as typed.
                    OutlinedTextField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            onProblem(null)
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Any emoji or text") },
                        singleLine = true,
                        isError = verdict.tooLong,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { if (verdict.canSubmit) onChoose(verdict.value) }),
                    )
                    Button(onClick = { onChoose(verdict.value) }, enabled = verdict.canSubmit) { Text("React") }
                }
                if (shownProblem != null) {
                    Text(shownProblem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Text(
                ReactionSheetModel.offline(message, target),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        if (inputs.groups.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Reactions",
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.fgMuted,
                )
                for (group in inputs.groups) {
                    StandingRow(group, canReact = inputs.canReact) { onChoose(group.value) }
                }
            }
        }
    }
}

/**
 * One quick pick: the emoji, ours framed in the accent. A glyph button, so the emoji is drawn larger
 * than the text around it — the one-size rule is about text, not icons.
 */
@Composable
private fun QuickPick(value: String, mine: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LurkerTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clearAndSetSemantics {
                contentDescription = value
                selected = mine
                role = Role.Button
                onClick(label = if (mine) "take your reaction back" else "react") {
                    onClick()
                    true
                }
            }
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (mine) colors.accent.copy(alpha = 0.15f) else colors.bgSoft, shape)
            .border(1.dp, if (mine) colors.accent else colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * One standing reaction: the value — whole, wrapping, the one place a long text reaction is shown in
 * full — and everyone who gave it. Tapping it toggles yours. Not greyed when it can't be tapped: the
 * list of who reacted would look like an error, and it's still information.
 */
@Composable
private fun StandingRow(group: ReactionGroup, canReact: Boolean, onClick: () -> Unit) {
    val colors = LurkerTheme.colors
    val shape = RoundedCornerShape(4.dp)
    val line = buildAnnotatedString {
        withStyle(SpanStyle(color = if (group.mine) colors.accent else colors.fg)) { append(group.value) }
        withStyle(SpanStyle(color = colors.fgMuted)) { append("   " + ReactionSheetModel.names(group)) }
    }
    val spoken = ReactionSheetModel.spoken(group)
    Text(
        line,
        modifier = Modifier
            .clearAndSetSemantics {
                contentDescription = spoken
                selected = group.mine
                if (canReact) {
                    role = Role.Button
                    onClick(label = if (group.mine) "take your reaction back" else "add your reaction") {
                        onClick()
                        true
                    }
                }
            }
            .fillMaxWidth()
            .clip(shape)
            .background(if (group.mine) colors.accent.copy(alpha = 0.12f) else colors.bgSoft, shape)
            .then(if (canReact) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

// MARK: - Previews

private val previewMessage = Message(id = 7, type = EventType.Message, nick = "alice", text = "shipped it \u000304today\u0003", msgid = "x")

private val previewGroups = listOf(
    ReactionGroup("👍", listOf("bob", "me"), mine = true),
    ReactionGroup("🎉", listOf("carol"), mine = false),
    ReactionGroup("a very long text reaction that wraps onto a second line", listOf("dave"), mine = false),
)

@Composable
private fun ReactionsPreview(dark: Boolean, canReact: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 16.dp)) {
            ReactionSheetContent(
                message = previewMessage,
                target = "#lurker",
                inputs = ReactionSheetInputs(previewGroups, canReact = canReact),
                problem = null,
                onProblem = {},
                onChoose = {},
            )
        }
    }
}

@Preview(name = "Reaction sheet — light", widthDp = 360)
@Composable
private fun ReactionsPreviewLight() = ReactionsPreview(dark = false, canReact = true)

@Preview(name = "Reaction sheet — dark", widthDp = 360)
@Composable
private fun ReactionsPreviewDark() = ReactionsPreview(dark = true, canReact = true)

@Preview(name = "Reaction sheet, offline — light", widthDp = 360)
@Composable
private fun ReactionsOfflinePreviewLight() = ReactionsPreview(dark = false, canReact = false)

@Preview(name = "Reaction sheet, offline — dark", widthDp = 360)
@Composable
private fun ReactionsOfflinePreviewDark() = ReactionsPreview(dark = true, canReact = false)
