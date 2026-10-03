// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme

/*
 * The rows a settings-shaped form needs — lurker-ios's `FormCells.swift` (a labelled text field, a
 * labelled switch, a multi-line box, a one-of-a-few menu), as Material components: outlined text
 * fields, a switch row, and the grouped form's tinted action rows. Reusable primitives rather than
 * one screen's, because a form is the one thing the app keeps needing; the next (a highlight rule, a
 * nick note) should start from these.
 *
 * Nothing here holds a value: every field reports each change as it happens and draws what it's
 * handed, so the form's draft is the one copy. (On iOS that was forced by cell reuse; here it's what
 * keeps a field's text and the value Save sends the same thing.)
 */

/** The horizontal inset every form row shares. */
internal val FormInset = 16.dp

/** A section's header — iOS's grouped-table header, as Material's list subheader: small, accent, a heading. */
@Composable
internal fun FormSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FormInset, end = FormInset, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A section's footer — the guidance under it, in the secondary colour. */
@Composable
internal fun FormSectionFooter(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A labelled text field.
 *
 * @param identifier the keyboard's helpfulness off, for a value that is not prose — a hostname, a
 *   nick, a SASL account. Autocapitalising a nick is how you end up connecting as "Amiantos" and
 *   wondering why nobody's highlights fire. iOS's `typedAsIdentifier`.
 * @param supportingText a line under the field that stays visible whether or not it has focus.
 */
@Composable
internal fun FormTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    identifier: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    supportingText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 4.dp),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions ?: if (identifier) {
            KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboardType)
        } else {
            KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = keyboardType)
        },
        keyboardActions = keyboardActions,
    )
}

/**
 * A password field: masked, with a reveal toggle, and a line under it saying what a blank field is
 * going to do whenever that isn't "nothing" — "Saved — type to replace", "Will be removed". A blank
 * masked field can't say that for itself, and an outlined field's placeholder only shows once
 * focused, so it is supporting text rather than a placeholder.
 *
 * @param placeholderPersists whether [placeholder] says something a blank field would otherwise hide
 *   (a saved secret, an armed removal) — then it stays under the field, not only in it while focused.
 */
@Composable
internal fun FormSecretField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    placeholderPersists: Boolean,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 4.dp),
        label = { Text(label) },
        // One or the other, so a focused, empty field doesn't say the same thing twice.
        placeholder = if (placeholderPersists) null else ({ Text(placeholder) }),
        supportingText = if (placeholderPersists) ({ Text(placeholder) }) else null,
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) LurkerIcons.VisibilityOff else LurkerIcons.Visibility,
                    contentDescription = if (revealed) "Hide password" else "Show password",
                )
            }
        },
    )
}

/** A label and a switch, the whole row toggling it. iOS's `FormSwitchCell`. */
@Composable
internal fun FormSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = FormInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // The row carries the semantics and the click; the switch only draws.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A row that does something, drawn the way a button in a grouped form is: tinted, red when it
 * destroys something, dimmed (and announced as disabled) when it can't be used right now.
 */
@Composable
internal fun FormActionRow(title: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        title,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = FormInset, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
}

/** A refusal: the warning glyph and the reason, in the refusal colour. */
@Composable
internal fun FormErrorRow(message: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LurkerIcons.Warning, contentDescription = "Error", tint = LurkerTheme.colors.badText, modifier = Modifier.size(20.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = LurkerTheme.colors.badText)
    }
}

/** A label and its value on one line — iOS's value cell ("Expires · Oct 3, 2026"). */
@Composable
internal fun FormValueRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = FormInset, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One sentence in a form — a status that isn't a label and a value. */
@Composable
internal fun FormSentenceRow(text: String, isProblem: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (isProblem) LurkerTheme.colors.badText else MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Normal,
    )
}
