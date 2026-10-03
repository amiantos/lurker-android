// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BuiltinNetworks
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkPreset

/**
 * "Which network?" — the first step of adding one. lurker-ios's `NetworkPickerViewController`; the
 * rules are [NetworkPickerModel]'s.
 *
 * The search field sits above the list rather than scrolling away with it, where iOS pins its own
 * (`hidesSearchBarWhenScrolling = false`): it's how anyone reaches one of 95 names.
 *
 * @param exit ✕ when this is the dialog's root (Add Network from the buffer list), ← when it was
 *   pushed over the networks list.
 */
@Composable
internal fun NetworkPickerPage(state: NetworkPickerState, exit: PageExit, onExit: () -> Unit, onPicked: (NetworkDraft) -> Unit) {
    NetworkPickerContent(
        offered = state.offered,
        allowsCustom = state.allowsCustom,
        query = state.query,
        onQueryChange = { state.query = it },
        exit = exit,
        onExit = onExit,
        onPicked = onPicked,
    )
}

@Composable
private fun NetworkPickerContent(
    offered: List<NetworkPreset>,
    allowsCustom: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    exit: PageExit,
    onExit: () -> Unit,
    onPicked: (NetworkDraft) -> Unit,
) {
    DialogPage(title = "Add Network", exit = exit, onExit = onExit) { padding ->
        val direction = LocalLayoutDirection.current
        val rows = NetworkPickerModel.rows(offered, allowsCustom, query)
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(direction),
                    end = padding.calculateEndPadding(direction),
                ),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 8.dp),
                placeholder = { Text("Search networks") },
                leadingIcon = { Icon(LurkerIcons.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
            )
            val placeholder = NetworkPickerModel.placeholder(offered, allowsCustom, query)
            if (placeholder != null) {
                Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                    StateView(placeholder)
                }
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            ) {
                items(rows) { row -> PickerRowItem(row = row, onClick = { onPicked(NetworkPickerModel.draft(row)) }) }
                val footer = NetworkPickerModel.footer(allowsCustom, rows)
                if (footer != null) item { FormSectionFooter(footer) }
            }
        }
    }
}

@Composable
private fun PickerRowItem(row: PickerRow, onClick: () -> Unit) {
    val modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    when (row) {
        is PickerRow.Preset -> ListItem(
            modifier = modifier,
            colors = colors,
            headlineContent = { Text(row.preset.name) },
            supportingContent = {
                Text(NetworkPickerModel.subtitle(row.preset), color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
        PickerRow.Custom -> ListItem(
            modifier = modifier,
            colors = colors,
            headlineContent = { Text(NetworkPickerModel.CUSTOM_TITLE) },
        )
    }
}

// MARK: - Previews

@Composable
private fun PickerPreview(dark: Boolean, offered: List<NetworkPreset>, allowsCustom: Boolean, query: String = "") {
    LurkerTheme(darkTheme = dark) {
        NetworkPickerContent(
            offered = offered,
            allowsCustom = allowsCustom,
            query = query,
            onQueryChange = {},
            exit = PageExit.Close,
            onExit = {},
            onPicked = {},
        )
    }
}

private val instancePreset = NetworkPreset(name = "Home", host = "irc.home.example", port = 6697, tls = true, isInstance = true)

@Preview(name = "Picker — light")
@Composable
private fun PickerPreviewLight() = PickerPreview(dark = false, offered = listOf(instancePreset) + BuiltinNetworks.all, allowsCustom = true)

@Preview(name = "Picker — dark")
@Composable
private fun PickerPreviewDark() = PickerPreview(dark = true, offered = listOf(instancePreset) + BuiltinNetworks.all, allowsCustom = true)

@Preview(name = "Picker, locked down — light")
@Composable
private fun LockedPreviewLight() = PickerPreview(dark = false, offered = listOf(instancePreset), allowsCustom = false)

@Preview(name = "Picker, locked down — dark")
@Composable
private fun LockedPreviewDark() = PickerPreview(dark = true, offered = listOf(instancePreset), allowsCustom = false)

@Preview(name = "Picker, nothing offered — light")
@Composable
private fun NothingPreviewLight() = PickerPreview(dark = false, offered = emptyList(), allowsCustom = false)

@Preview(name = "Picker, nothing offered — dark")
@Composable
private fun NothingPreviewDark() = PickerPreview(dark = true, offered = emptyList(), allowsCustom = false)

@Preview(name = "Picker, no matches — light")
@Composable
private fun NoMatchesPreviewLight() = PickerPreview(dark = false, offered = listOf(instancePreset), allowsCustom = false, query = "zzz")

@Preview(name = "Picker, no matches — dark")
@Composable
private fun NoMatchesPreviewDark() = PickerPreview(dark = true, offered = listOf(instancePreset), allowsCustom = false, query = "zzz")
