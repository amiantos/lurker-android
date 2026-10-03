// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerTheme

/**
 * "Ignore alice" — who, and where (lurker-android#37). iOS has no Ignore on a message (its kit leaves
 * it to the screen that authors rules, and iOS authors them only by `/ignore`), so this is the web's
 * `IgnoreModal`: the mask, opened on the sender's identity so the rule survives a nick change, and the
 * scope — every network (the default, lurker#350) or this one.
 *
 * What goes out is a `/ignore` line through the kit's own parser ([MessageActionsModel.ignoreCommand]),
 * so the rule is built, scoped, written and receipted exactly as a typed one; the receipt prints in the
 * conversation. Ignore stays off while the field wouldn't parse as a single mask.
 *
 * @param onIgnore send this `/ignore` line. ⚠ A WRITE: the rule applies on every device.
 */
@Composable
internal fun IgnoreDialog(
    subject: String,
    defaultMask: String,
    onIgnore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mask by rememberSaveable(subject, defaultMask) { mutableStateOf(defaultMask) }
    var thisNetwork by rememberSaveable(subject) { mutableStateOf(false) }
    val command = MessageActionsModel.ignoreCommand(mask, thisNetwork)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ignore $subject") },
        text = {
            IgnoreFields(
                mask = mask,
                onMask = { mask = it },
                thisNetwork = thisNetwork,
                onScope = { thisNetwork = it },
                valid = command != null,
            )
        },
        confirmButton = {
            TextButton(onClick = { command?.let(onIgnore) }, enabled = command != null) { Text("Ignore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IgnoreFields(
    mask: String,
    onMask: (String) -> Unit,
    thisNetwork: Boolean,
    onScope: (Boolean) -> Unit,
    valid: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = mask,
            onValueChange = onMask,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mask") },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        Column(Modifier.selectableGroup()) {
            ScopeOption("Everywhere", selected = !thisNetwork) { onScope(false) }
            ScopeOption("This network", selected = thisNetwork) { onScope(true) }
        }
        Text(
            MessageActionsModel.ignorePreview(mask, thisNetwork),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ScopeOption(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row takes the tap and speaks for it.
        RadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

// MARK: - Previews

@Preview(name = "Ignore — light")
@Composable
private fun IgnorePreviewLight() = LurkerTheme(darkTheme = false) { IgnoreDialog("alice", "*!~alice@example.org", {}, {}) }

@Preview(name = "Ignore — dark")
@Composable
private fun IgnorePreviewDark() = LurkerTheme(darkTheme = true) { IgnoreDialog("alice", "*!~alice@example.org", {}, {}) }
