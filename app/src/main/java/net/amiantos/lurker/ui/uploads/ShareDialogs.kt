// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FullScreenDialog
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Which conversation a share goes to — asked over whatever is on screen as soon as the signed-in app is
 * up, a share received while signed out included ([ShareInbox]). A plain list of conversations under
 * their networks: the question is "where", and the buffer list's unread marks and drag handles would be
 * noise in front of it.
 *
 * Its own small model off the store ([ShareTargets]): names and keys only, so it redraws when a
 * conversation comes or goes, not on every message.
 */
@Composable
fun SharePickerDialog(model: ChatViewModel, onPick: (BufferKey) -> Unit, onDismiss: () -> Unit) {
    val flow = remember(model) {
        model.statePublisher
            .conflate()
            .map { state -> ShareTargets.sections(state.networks, state.buffers.values) to state.rosterSettled }
            .distinctUntilChanged()
    }
    val initial = remember(model) { ShareTargets.sections(model.state.networks, model.state.buffers.values) to model.state.rosterSettled }
    val shown by flow.collectAsStateWithLifecycle(initialValue = initial)
    FullScreenDialog(onDismissRequest = onDismiss) {
        DialogPage(title = "Choose a Conversation", exit = PageExit.Close, onExit = onDismiss) { padding ->
            SharePickerList(sections = shown.first, settled = shown.second, onPick = onPick, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun SharePickerList(
    sections: List<ShareTargets.Section>,
    settled: Boolean,
    onPick: (BufferKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) {
        // A share can open the app cold: until the burst has landed, an empty list is "not yet", not "none".
        if (settled) {
            StateView(title = "No conversations", subtitle = "Join a channel or start a conversation, then share again.", modifier = modifier)
        } else {
            StateView(title = "Loading buffers…", isLoading = true, modifier = modifier)
        }
        return
    }
    val colors = LurkerTheme.colors
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        for (section in sections) {
            item(key = "h:${section.title}:${section.targets.firstOrNull()?.key?.networkId}") {
                Text(
                    section.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.fgMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                        .semantics { heading() },
                )
            }
            items(section.targets, key = { it.key.id }) { target ->
                Text(
                    target.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "send here") { onPick(target.key) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * A finished run's one dialog (`UploadReport`) — iOS's alert, over whatever is up: the run outlives the
 * buffer it started in, and its news belongs where the reader is now.
 */
@Composable
fun UploadReportDialog(runner: UploadRunner) {
    val report by runner.report.collectAsStateWithLifecycle()
    report?.let { shown ->
        AlertDialog(
            onDismissRequest = runner::acknowledge,
            title = { Text(shown.title) },
            text = { Text(shown.message) },
            confirmButton = { TextButton(onClick = runner::acknowledge) { Text("OK") } },
        )
    }
}

// MARK: - Previews

private val previewSections = listOf(
    ShareTargets.Section(
        "Libera",
        listOf(
            ShareTargets.Target(BufferKey(1, "#lurker"), "#lurker"),
            ShareTargets.Target(BufferKey(1, "##android"), "##android"),
            ShareTargets.Target(BufferKey(1, "alice"), "alice"),
        ),
    ),
    ShareTargets.Section("OFTC", listOf(ShareTargets.Target(BufferKey(2, "#debian"), "#debian"))),
)

@Composable
private fun PickerPreview(dark: Boolean, sections: List<ShareTargets.Section>, settled: Boolean = true) {
    LurkerTheme(darkTheme = dark) {
        SharePickerList(sections, settled, onPick = {}, modifier = Modifier.background(MaterialTheme.colorScheme.background))
    }
}

@Preview(name = "Share picker — light", widthDp = 360, heightDp = 400)
@Composable
private fun PickerLight() = PickerPreview(dark = false, sections = previewSections)

@Preview(name = "Share picker — dark", widthDp = 360, heightDp = 400)
@Composable
private fun PickerDark() = PickerPreview(dark = true, sections = previewSections)

@Preview(name = "Share picker, loading — light", widthDp = 360, heightDp = 400)
@Composable
private fun PickerLoadingLight() = PickerPreview(dark = false, sections = emptyList(), settled = false)

@Preview(name = "Share picker, loading — dark", widthDp = 360, heightDp = 400)
@Composable
private fun PickerLoadingDark() = PickerPreview(dark = true, sections = emptyList(), settled = false)
