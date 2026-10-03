// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Join a channel: type its name, pick the network. lurker-ios's `JoinChannelViewController`; the
 * rules are [JoinChannelModel]'s.
 *
 * The channel name is the first field and the keyboard is up as the dialog opens, because the name
 * is what you came here to type. The network is a picker under it with a sensible default, which a
 * one-network account never touches.
 *
 * Joining doesn't navigate: [onJoin] hands the name to `requestJoin`, which opens the channel when
 * the server says you're in, and says why when it doesn't (lurker-ios#57).
 */
@Composable
internal fun JoinChannelDialog(model: ChatViewModel, onDismiss: () -> Unit, onJoin: (networkId: Int, channel: String) -> Unit) {
    // A network finishing its connect while this is open makes it selectable, so the list follows the
    // socket rather than a snapshot taken when it opened — mapped to just what the rows draw.
    val optionsFlow = remember(model) { model.statePublisher.map(JoinChannelModel::options).distinctUntilChanged() }
    val initialOptions = remember(model) { JoinChannelModel.options(model.state) }
    val options by optionsFlow.collectAsStateWithLifecycle(initialValue = initialOptions)

    var channel by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(JoinChannelModel.defaultSelection(initialOptions)) }
    // Drawn from the reconciled selection on the frame the networks move, and committed right after,
    // so the radio never sits a frame on a network that just went down.
    val shown = JoinChannelModel.reconcile(selected, options)
    LaunchedEffect(options) { selected = JoinChannelModel.reconcile(selected, options) }

    fun join() {
        val target = JoinChannelModel.target(shown, options) ?: return
        val name = JoinChannelModel.channelToSend(channel) ?: return
        onJoin(target.id, name)
    }

    FullScreenDialog(onDismissRequest = onDismiss) {
        JoinChannelContent(
            channel = channel,
            onChannelChange = { channel = it },
            options = options,
            selected = shown,
            onSelect = { selected = it },
            canJoin = JoinChannelModel.canJoin(shown, options, channel),
            onJoin = ::join,
            onClose = onDismiss,
            focusOnOpen = true,
        )
    }
}

@Composable
private fun JoinChannelContent(
    channel: String,
    onChannelChange: (String) -> Unit,
    options: List<JoinNetworkOption>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    canJoin: Boolean,
    onJoin: () -> Unit,
    onClose: () -> Unit,
    focusOnOpen: Boolean,
) {
    val focus = remember { FocusRequester() }
    // The name is what you came here to type, so the keyboard is up before you decide anything else.
    if (focusOnOpen) LaunchedEffect(Unit) { focus.requestFocus() }
    DialogPage(
        title = "Join Channel",
        exit = PageExit.Close,
        onExit = onClose,
        confirmTitle = "Join",
        confirmEnabled = canJoin,
        onConfirm = onJoin,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            FormTextField(
                label = "Channel",
                value = channel,
                onValueChange = onChannelChange,
                modifier = Modifier.focusRequester(focus),
                placeholder = "#lurker",
                // Said where it applies, because "#" is the part people leave off.
                supportingText = JoinChannelModel.FIELD_FOOTER,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                // The keyboard's own key says go, so it has to do what it says — when there's
                // something to join.
                keyboardActions = KeyboardActions(onGo = { if (canJoin) onJoin() }),
            )
            FormSectionHeader("Network")
            Column(Modifier.selectableGroup()) {
                for (option in options) {
                    NetworkOptionRow(option = option, isSelected = option.id == selected, onSelect = { onSelect(option.id) })
                }
            }
        }
    }
}

/**
 * A network to join on. Not disabled-looking-but-tappable: a JOIN with no socket to travel down goes
 * nowhere and nothing comes back to say so. Shown rather than hidden because the network is still
 * yours, and a list that silently omits it just looks wrong — and announced as disabled, because
 * greying a row says nothing to a screen reader.
 */
@Composable
private fun NetworkOptionRow(option: JoinNetworkOption, isSelected: Boolean, onSelect: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, enabled = option.connected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 56.dp)
            .padding(horizontal = FormInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = isSelected, onClick = null, enabled = option.connected)
        Column {
            Text(
                option.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (option.connected) MaterialTheme.colorScheme.onSurface else dim,
            )
            if (!option.connected) {
                Text(JoinChannelModel.NOT_CONNECTED, style = MaterialTheme.typography.bodyMedium, color = dim)
            }
        }
    }
}

// MARK: - Previews

@Composable
private fun JoinPreview(dark: Boolean) {
    val options = listOf(
        JoinNetworkOption(1, "Libera", ConnectionState.Connected),
        JoinNetworkOption(2, "OFTC", ConnectionState.Disconnected),
        JoinNetworkOption(3, "Rizon", ConnectionState.Connected),
    )
    LurkerTheme(darkTheme = dark) {
        JoinChannelContent(
            channel = "swift",
            onChannelChange = {},
            options = options,
            selected = 1,
            onSelect = {},
            canJoin = true,
            onJoin = {},
            onClose = {},
            focusOnOpen = false,
        )
    }
}

@Preview(name = "Join channel — light")
@Composable
private fun JoinPreviewLight() = JoinPreview(dark = false)

@Preview(name = "Join channel — dark")
@Composable
private fun JoinPreviewDark() = JoinPreview(dark = true)
