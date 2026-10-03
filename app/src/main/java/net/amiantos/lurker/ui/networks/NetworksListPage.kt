// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.shell.StatusDot
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * The networks screen (lurker-ios#11): what the account is configured to connect to, and the four
 * things you can do to one. lurker-ios's `NetworksViewController`.
 *
 * The screen that makes the app self-sufficient: without it an account with no networks is a dead
 * end, and one with networks has to go to the browser to connect one that was left off.
 *
 * The tap edits; the "⋮" is for everything that changes the connection rather than the
 * configuration. The primary action of a row in a list of things you configure is to configure it.
 */
@Composable
internal fun NetworksListPage(
    model: ChatViewModel,
    state: NetworksListState,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
) {
    // Only the connection states, and only when they actually move — see `NetworksListModel.liveStates`.
    val liveFlow = remember(model) { model.statePublisher.map(NetworksListModel::liveStates).distinctUntilChanged() }
    val initialLive = remember(model) { NetworksListModel.liveStates(model.state) }
    val live by liveFlow.collectAsStateWithLifecycle(initialValue = initialLive)
    LaunchedEffect(live) { state.onLiveStates(live) }
    // Every appearance: the first composition (whose load is already in flight, so this skips), and
    // every return from a page pushed over it.
    LaunchedEffect(state) { state.appeared() }

    NetworksListContent(
        load = state.load,
        live = live,
        actionError = state.actionError,
        onClose = onClose,
        onAdd = onAdd,
        onEdit = onEdit,
        // ⚠ Which actions a row offers is decided when its menu opens, from the state then, not
        // when the row was last drawn: a network that transitions between those two moments would
        // otherwise offer Connect on a connected network.
        actionsFor = { config -> NetworksListModel.row(config, NetworksListModel.liveStates(model.state)).actions },
        onAction = { action, config -> state.perform(action, config) },
        // One button, two meanings — whichever state is on screen owns it. The empty state and the
        // failure state can't both be showing, so this can't be ambiguous.
        onPlaceholderAction = { if (state.load is NetworksLoad.Loaded) onAdd() else state.reload() },
    )

    val deleting = state.confirmingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { state.confirmingDelete = null },
            title = { Text(NetworksListModel.deleteTitle(deleting)) },
            text = { Text(NetworksListModel.DELETE_MESSAGE) },
            confirmButton = {
                TextButton(onClick = { state.delete(deleting) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { state.confirmingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** The list itself, stateless so the previews can draw it. */
@Composable
private fun NetworksListContent(
    load: NetworksLoad,
    live: Map<Int, ConnectionState>,
    actionError: RowError?,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    actionsFor: (NetworkConfig) -> List<NetworkAction>,
    onAction: (NetworkAction, NetworkConfig) -> Unit,
    onPlaceholderAction: () -> Unit,
) {
    DialogPage(
        title = "Networks",
        exit = PageExit.Close,
        onExit = onClose,
        actions = {
            IconButton(onClick = onAdd) { Icon(LurkerIcons.Add, contentDescription = "Add") }
        },
    ) { padding ->
        val placeholder = NetworksListModel.placeholder(load)
        if (placeholder != null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                StateView(
                    title = placeholder.title,
                    subtitle = placeholder.subtitle,
                    isLoading = placeholder.isLoading,
                    actionTitle = placeholder.actionTitle,
                    onAction = onPlaceholderAction,
                )
            }
            return@DialogPage
        }
        val configs = NetworksListModel.configs(load)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(configs, key = { it.id }) { config ->
                NetworkListRow(
                    config = config,
                    live = live,
                    error = actionError?.takeIf { it.id == config.id }?.message,
                    onEdit = { onEdit(config) },
                    actionsFor = { actionsFor(config) },
                    onAction = { onAction(it, config) },
                )
            }
            val footer = NetworksListModel.footer(configs)
            if (footer != null) item(key = "footer") { FormSectionFooter(footer) }
        }
    }
}

/**
 * A network's row: the dot, the name, and one line of explanation — `host:port · state`, or the
 * server's refusal of the last thing tried, which beats a host and port the user can already see.
 * It clears on the next state change, action or reload.
 */
@Composable
private fun NetworkListRow(
    config: NetworkConfig,
    live: Map<Int, ConnectionState>,
    error: String?,
    onEdit: () -> Unit,
    actionsFor: () -> List<NetworkAction>,
    onAction: (NetworkAction) -> Unit,
) {
    val row = NetworksListModel.row(config, live)
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "edit", role = Role.Button, onClick = onEdit),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // The colour carries the state; the subtitle repeats it in words for anyone who can't use colour.
        leadingContent = { StatusDot(row.light, Modifier.size(10.dp)) },
        headlineContent = { Text(config.name) },
        supportingContent = {
            if (error != null) {
                Text(error, color = LurkerTheme.colors.badText)
            } else {
                Text(NetworksListModel.subtitle(config, row), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = { NetworkActionsButton(name = config.name, actionsFor = actionsFor, onAction = onAction) },
    )
}

/**
 * The "⋮" and its menu. The menu's items are read as it opens ([actionsFor]), so the button never
 * needs rebuilding to stay correct, and a live transition elsewhere can't close it under the finger:
 * Compose keeps this composable (and its open menu) across a recomposition of the row.
 */
@Composable
private fun NetworkActionsButton(name: String, actionsFor: () -> List<NetworkAction>, onAction: (NetworkAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Kept apart from `expanded`, so the items don't change under the menu's closing fade.
    var shown by remember { mutableStateOf<List<NetworkAction>>(emptyList()) }
    Box {
        IconButton(
            onClick = {
                shown = actionsFor()
                expanded = true
            },
        ) {
            Icon(LurkerIcons.MoreVert, contentDescription = "Actions for $name")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            shown.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    colors = if (action.isDestructive) {
                        MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                    } else {
                        MenuDefaults.itemColors()
                    },
                    onClick = {
                        expanded = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

// MARK: - Previews

private fun previewConfigs() = listOf(
    NetworkConfig(id = 1, name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "lurker"),
    NetworkConfig(id = 2, name = "OFTC", host = "irc.oftc.net", port = 6697, tls = true, nick = "lurker"),
    NetworkConfig(id = 3, name = "Work", host = "irc.example.com", port = 6667, tls = false, nick = "lurker", blocked = true),
)

@Composable
private fun ListPreview(dark: Boolean, load: NetworksLoad, error: RowError? = null) {
    LurkerTheme(darkTheme = dark) {
        NetworksListContent(
            load = load,
            live = mapOf(1 to ConnectionState.Connected, 2 to ConnectionState.Reconnecting),
            actionError = error,
            onClose = {},
            onAdd = {},
            onEdit = {},
            actionsFor = { emptyList() },
            onAction = { _, _ -> },
            onPlaceholderAction = {},
        )
    }
}

@Preview(name = "Networks — light")
@Composable
private fun NetworksPreviewLight() = ListPreview(dark = false, load = NetworksLoad.Loaded(previewConfigs()))

@Preview(name = "Networks — dark")
@Composable
private fun NetworksPreviewDark() = ListPreview(dark = true, load = NetworksLoad.Loaded(previewConfigs()))

@Preview(name = "Networks, refused — light")
@Composable
private fun NetworksRefusedPreviewLight() =
    ListPreview(dark = false, load = NetworksLoad.Loaded(previewConfigs()), error = RowError(2, "Your account is paused."))

@Preview(name = "Networks, refused — dark")
@Composable
private fun NetworksRefusedPreviewDark() =
    ListPreview(dark = true, load = NetworksLoad.Loaded(previewConfigs()), error = RowError(2, "Your account is paused."))

@Preview(name = "No networks — light")
@Composable
private fun NoNetworksPreviewLight() = ListPreview(dark = false, load = NetworksLoad.Loaded(emptyList()))

@Preview(name = "No networks — dark")
@Composable
private fun NoNetworksPreviewDark() = ListPreview(dark = true, load = NetworksLoad.Loaded(emptyList()))

@Preview(name = "Load failed — light")
@Composable
private fun FailedPreviewLight() = ListPreview(dark = false, load = NetworksLoad.Failed)

@Preview(name = "Load failed — dark")
@Composable
private fun FailedPreviewDark() = ListPreview(dark = true, load = NetworksLoad.Failed)

@Preview(name = "Loading — light")
@Composable
private fun LoadingPreviewLight() = ListPreview(dark = false, load = NetworksLoad.Loading)

@Preview(name = "Loading — dark")
@Composable
private fun LoadingPreviewDark() = ListPreview(dark = true, load = NetworksLoad.Loading)
