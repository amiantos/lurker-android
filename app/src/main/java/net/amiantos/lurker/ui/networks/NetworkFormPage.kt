// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BuiltinNetworks
import net.amiantos.lurkerkit.model.CertificateExport
import net.amiantos.lurkerkit.model.CertificateResult
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkProxy
import net.amiantos.lurkerkit.model.NetworkSaveResult
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.SecretEdit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Add or edit a network (lurker-ios#11) — the screen that makes a fresh install usable without a
 * browser. lurker-ios's `NetworkFormViewController`, section for section; the rules are
 * [NetworkFormModel]'s and the state [NetworkFormState]'s.
 *
 * Always a pushed page (over the picker when adding, over the list when editing), so its way out is
 * ←, and where Save lands is the flow's business.
 *
 * A scrolling column, not a lazy list: every field stays composed, so one scrolled off screen can't
 * lose the keyboard or its caret. And every row is keyed, so a row appearing above another (the
 * refusal at the top, a clear row) doesn't hand one field's focus to its neighbour.
 */
@Composable
internal fun NetworkFormPage(state: NetworkFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val resolver = LocalContext.current.contentResolver

    // The message lands at the top of the form, which is off screen if the user saved from the
    // bottom — so go to it rather than leaving them looking at an unchanged screen wondering whether
    // the button worked.
    LaunchedEffect(state.errorShown) { if (state.errorShown > 0) scroll.animateScrollTo(0) }

    // ⚠ Every type rather than a list of certificate ones: a type left off greys the file out in the
    // picker with no way around it (lurker-ios#125), and providers label `.pem`, `.key` and `.crt`
    // inconsistently — often as `application/octet-stream`, or not at all. The pair often lives in
    // two files, so several can be picked at once.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        state.importFiles { withContext(Dispatchers.IO) { uris.joinToString("\n") { readPicked(resolver, it) } } }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PEM_MIME)) { uri ->
        if (uri == null) {
            state.cancelExport()
        } else {
            state.finishExport { pem -> withContext(Dispatchers.IO) { writeExport(resolver, uri, pem) } }
        }
    }
    // The fetched pair goes straight to the system's save dialog — Android's counterpart of iOS's
    // share sheet (Save to Files) — and from there straight into the file the user picked. No copy
    // of the unencrypted key is ever written anywhere else, so there is nothing to clean up.
    val pending = state.pendingExport
    LaunchedEffect(pending) {
        val existing = state.existing
        if (pending != null && existing != null && !state.exportPickerOpen) {
            state.exportPickerOpen = true
            exporter.launch(NetworkFormModel.exportFileName(existing.id))
        }
    }

    DialogPage(
        title = NetworkFormModel.title(state.isEditing),
        exit = PageExit.Back,
        onExit = onBack,
        confirmTitle = NetworkFormModel.saveTitle(state.isEditing),
        confirmEnabled = state.saveEnabled,
        onConfirm = {
            focus.clearFocus()
            state.save()
        },
    ) { padding ->
        // Padded outside the scroll, so the keyboard shrinks the viewport rather than lengthening the
        // content — and a focused field the keyboard rises over is scrolled back into view.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(bottom = 24.dp),
        ) {
            for (section in state.sections) {
                key(section.id) {
                    section.header?.let { FormSectionHeader(it) }
                    for (row in section.rows) {
                        key(row) {
                            FormRowContent(
                                row = row,
                                state = state,
                                onImport = { importer.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    section.footer?.let { FormSectionFooter(it) }
                }
            }
        }
    }

    if (state.confirmingRemove) {
        AlertDialog(
            onDismissRequest = { state.confirmingRemove = false },
            title = { Text("Remove Certificate?") },
            text = NetworkFormModel.removeCertificateMessage(state.certificate)?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = state::remove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { state.confirmingRemove = false }) { Text("Cancel") } },
        )
    }
}

/** One row of the form. */
@Composable
private fun FormRowContent(row: FormRow, state: NetworkFormState, onImport: () -> Unit) {
    val draft = state.draft
    val existing = state.existing
    when (row) {
        FormRow.Error -> state.error?.let { FormErrorRow(it) }
        FormRow.Name -> FormTextField(
            label = "Name",
            value = draft.name,
            placeholder = "Libera",
            onValueChange = { v -> state.edit { it.copy(name = v) } },
        )
        FormRow.Host -> FormTextField(
            label = "Server",
            value = draft.host,
            placeholder = "irc.libera.chat",
            identifier = true,
            keyboardType = KeyboardType.Uri,
            onValueChange = { v -> state.edit { it.copy(host = v) } },
        )
        FormRow.Port -> FormTextField(
            label = "Port",
            value = state.portText,
            placeholder = "6697",
            identifier = true,
            keyboardType = KeyboardType.Number,
            onValueChange = state::setPort,
        )
        FormRow.Tls -> FormSwitchRow("Use TLS", draft.tls, onCheckedChange = { v -> state.edit { it.copy(tls = v) } })
        // ⚠ Autocapitalisation off matters here specifically: an autocapitalised nick connects you as
        // someone else's spelling of your name, and every highlight rule keyed to the lowercase one
        // goes quiet.
        FormRow.Nick -> FormTextField(
            label = "Nickname",
            value = draft.nick,
            identifier = true,
            onValueChange = { v -> state.edit { it.copy(nick = v) } },
        )
        FormRow.Realname -> FormTextField(
            label = "Real name",
            value = draft.realname ?: "",
            placeholder = "Optional",
            onValueChange = { v -> state.edit { it.copy(realname = v) } },
        )
        FormRow.SaslAccount -> FormTextField(
            label = "Account",
            value = draft.saslAccount ?: "",
            placeholder = NetworkFormModel.saslAccountPlaceholder(draft),
            identifier = true,
            onValueChange = { v -> state.edit { it.copy(saslAccount = v) } },
        )
        FormRow.SaslPassword -> SecretRow(
            label = "Password",
            edit = draft.saslPassword,
            saved = existing?.hasSaslPassword == true,
            onChange = { e -> state.edit { it.copy(saslPassword = e) } },
        )
        FormRow.ClearSaslPassword -> ClearRow(draft.saslPassword, "SASL Password") { e -> state.edit { it.copy(saslPassword = e) } }
        FormRow.ServerPassword -> SecretRow(
            label = "Server password",
            edit = draft.password,
            saved = existing?.hasPassword == true,
            onChange = { e -> state.edit { it.copy(password = e) } },
        )
        FormRow.ClearServerPassword -> ClearRow(draft.password, "Server Password") { e -> state.edit { it.copy(password = e) } }
        FormRow.CertificateStatus -> CertificateStatusRow(state)
        FormRow.CertificateError -> state.certificateError?.let { FormErrorRow(it) }
        FormRow.GenerateCertificate -> FormActionRow(
            "Generate Certificate",
            enabled = state.canAddCertificate,
            onClick = { if (state.canAddCertificate) state.addCertificate(CertificateSource.Generate) },
        )
        // A file, not a paste box: every CertFP guide hands you a .pem, and it's what Export writes.
        FormRow.ImportCertificate -> FormActionRow(
            "Import Certificate…",
            enabled = state.canAddCertificate,
            onClick = { if (state.canAddCertificate) onImport() },
        )
        FormRow.ExportCertificate -> FormActionRow("Export Certificate…", onClick = state::export)
        FormRow.RemoveCertificate -> FormActionRow(
            "Remove Certificate",
            destructive = true,
            enabled = !state.certificateBusy,
            onClick = state::requestRemove,
        )
        FormRow.UndoCertificate -> FormActionRow("Undo", onClick = state::undoCertificate)
        FormRow.DefaultChannel -> FormTextField(
            label = "Channels",
            value = draft.defaultChannel ?: "",
            placeholder = "#lurker",
            identifier = true,
            onValueChange = { v -> state.edit { it.copy(defaultChannel = v) } },
        )
        FormRow.ProxyEnabled -> FormSwitchRow(
            "Connect through a proxy",
            draft.proxy.enabled,
            onCheckedChange = { v -> state.edit { it.copy(proxy = it.proxy.edited(enabled = v)) } },
        )
        FormRow.ProxyType -> ProxyTypeRow(draft.proxy.type, onPick = state::setProxyType)
        FormRow.ProxyHost -> FormTextField(
            label = "Address",
            value = draft.proxy.host,
            placeholder = "127.0.0.1",
            identifier = true,
            keyboardType = KeyboardType.Uri,
            onValueChange = { v -> state.edit { it.copy(proxy = it.proxy.edited(host = v)) } },
        )
        // Empty for 0, and 0 for a cleared field, for the reasons the server's port gives.
        FormRow.ProxyPort -> FormTextField(
            label = "Port",
            value = state.proxyPortText,
            placeholder = draft.proxy.type.defaultPort.toString(),
            identifier = true,
            keyboardType = KeyboardType.Number,
            onValueChange = state::setProxyPort,
        )
        FormRow.ProxyUsername -> FormTextField(
            label = "Username",
            value = draft.proxy.username ?: "",
            placeholder = "Optional",
            identifier = true,
            onValueChange = { v -> state.edit { it.copy(proxy = it.proxy.edited(username = v)) } },
        )
        FormRow.ProxyPassword -> SecretRow(
            label = "Password",
            edit = draft.proxy.password,
            saved = existing?.proxy?.hasPassword == true,
            onChange = { e -> state.edit { it.copy(proxy = it.proxy.edited(password = e)) } },
        )
        FormRow.ClearProxyPassword ->
            ClearRow(draft.proxy.password, "Proxy Password") { e -> state.edit { it.copy(proxy = it.proxy.edited(password = e)) } }
        // Raw wire lines, not slash commands, and `WAIT` in context — a worked example beats a
        // sentence describing the format. Grows with what's typed, so the whole script is visible
        // while it's being written.
        FormRow.ConnectCommands -> FormTextField(
            label = "Connect commands",
            value = draft.connectCommands ?: "",
            placeholder = "PRIVMSG NickServ :IDENTIFY hunter2\nWAIT 5\nOPER admin hunter2",
            singleLine = false,
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            onValueChange = { v -> state.edit { it.copy(connectCommands = v) } },
        )
        FormRow.Autoconnect -> FormSwitchRow("Connect on startup", draft.autoconnect, onCheckedChange = { v -> state.edit { it.copy(autoconnect = v) } })
        // Named for what it does, not for the column it sets. `trusted_certificates` reads like
        // permission to accept anything and means the opposite.
        FormRow.VerifyCertificate -> FormSwitchRow(
            "Verify TLS certificate",
            draft.trustedCertificates,
            onCheckedChange = { v -> state.edit { it.copy(trustedCertificates = v) } },
        )
    }
}

/** A password field over a [SecretEdit]: what's typed is the edit, and a blank field asks for nothing. */
@Composable
private fun SecretRow(label: String, edit: SecretEdit, saved: Boolean, onChange: (SecretEdit) -> Unit) {
    FormSecretField(
        label = label,
        value = NetworkFormModel.secretText(edit),
        placeholder = NetworkFormModel.secretPlaceholder(edit, saved),
        placeholderPersists = saved || edit == SecretEdit.Cleared,
        onValueChange = { onChange(NetworkFormModel.secretEdit(it)) },
    )
}

/** "Remove Saved …" / "Keep Saved …": records the intent; nothing is removed until Save. */
@Composable
private fun ClearRow(edit: SecretEdit, what: String, onChange: (SecretEdit) -> Unit) {
    val armed = edit == SecretEdit.Cleared
    FormActionRow(
        NetworkFormModel.clearRowTitle(armed, what),
        destructive = !armed,
        onClick = { onChange(NetworkFormModel.toggledClear(edit)) },
    )
}

/**
 * One choice among a few fixed options: a menu in place, rather than a page — for two options a
 * whole page is a trip away from the form and back to learn nothing the menu couldn't show.
 * iOS's `FormMenuCell`, as Material's exposed dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyTypeRow(current: ProxyType, onPick: (ProxyType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = NetworkFormModel.proxyTypeTitle(current),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (type in ProxyType.entries) {
                DropdownMenuItem(
                    text = { Text(NetworkFormModel.proxyTypeTitle(type)) },
                    onClick = {
                        expanded = false
                        onPick(type)
                    },
                )
            }
        }
    }
}

@Composable
private fun CertificateStatusRow(state: NetworkFormState) {
    when (val status = NetworkFormModel.certificateStatus(state.certificate, state.draft.certificate, Instant.now())) {
        is CertificateStatus.Expiry -> FormValueRow(status.label, formatDate(status.date))
        CertificateStatus.Attached -> FormValueRow("Certificate", "Attached")
        is CertificateStatus.Sentence -> FormSentenceRow(status.text, isProblem = status.isProblem)
        null -> Unit
    }
}

/** iOS's `.formatted(date: .abbreviated, time: .omitted)` — "Oct 3, 2026" in English. */
private fun formatDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(date)

/** What the export is saved as — the type a `.pem` file is, so the save dialog names it right. */
private const val PEM_MIME = "application/x-pem-file"

/** A picked file's text, bounded — see [NetworkFormModel.readPickedFile]. "" for one that can't be opened. */
private fun readPicked(resolver: ContentResolver, uri: Uri): String =
    try {
        resolver.openInputStream(uri)?.use { NetworkFormModel.readPickedFile(it) } ?: ""
    } catch (_: Exception) {
        ""
    }

/** Write the exported pair into the file the user chose. False when it couldn't be written. */
private fun writeExport(resolver: ContentResolver, uri: Uri, pem: String): Boolean =
    try {
        val out = resolver.openOutputStream(uri, "w")
        out?.use { it.write(pem.toByteArray(Charsets.UTF_8)) }
        out != null
    } catch (_: Exception) {
        false
    }

// MARK: - Previews

/** Writes that go nowhere — for previews. */
private object PreviewWrites : NetworkFormWrites {
    override suspend fun createNetwork(draft: NetworkDraft) = NetworkSaveResult.Failure("Preview")
    override suspend fun updateNetwork(id: Int, draft: NetworkDraft) = NetworkSaveResult.Failure("Preview")
    override suspend fun attachCertificate(networkId: Int, source: CertificateSource) = CertificateResult.Failure("Preview")
    override suspend fun removeCertificate(networkId: Int) = CertificateResult.Failure("Preview")
    override suspend fun exportCertificate(networkId: Int) = CertificateExport.Failure("Preview")
}

@Composable
private fun FormPreview(dark: Boolean, editing: Boolean) {
    val scope = rememberCoroutineScope()
    val state = remember {
        if (editing) {
            val config = NetworkConfig(
                id = 7,
                name = "Libera",
                host = "irc.libera.chat",
                port = 6697,
                tls = true,
                nick = "lurker",
                saslAccount = "lurker",
                hasSaslPassword = true,
                clientCertificate = ClientCertificate.Usable(expires = Instant.parse("2027-10-03T00:00:00Z")),
                proxy = NetworkProxy(enabled = true, type = ProxyType.Socks5, host = "127.0.0.1", port = 9050, hasPassword = true),
            )
            NetworkFormState(PreviewWrites, scope, config, NetworkDraft(editing = config), onSaved = {}, onCertificateChanged = {})
        } else {
            val draft = BuiltinNetworks.all.firstOrNull()?.draft() ?: NetworkDraft()
            NetworkFormState(PreviewWrites, scope, null, draft, onSaved = {}, onCertificateChanged = {})
        }
    }
    LurkerTheme(darkTheme = dark) { NetworkFormPage(state = state, onBack = {}) }
}

@Preview(name = "Add network — light", heightDp = 1600)
@Composable
private fun AddPreviewLight() = FormPreview(dark = false, editing = false)

@Preview(name = "Add network — dark", heightDp = 1600)
@Composable
private fun AddPreviewDark() = FormPreview(dark = true, editing = false)

@Preview(name = "Edit network — light", heightDp = 1800)
@Composable
private fun EditPreviewLight() = FormPreview(dark = false, editing = true)

@Preview(name = "Edit network — dark", heightDp = 1800)
@Composable
private fun EditPreviewDark() = FormPreview(dark = true, editing = true)
