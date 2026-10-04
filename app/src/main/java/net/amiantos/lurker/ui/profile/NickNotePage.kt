// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormActionRow
import net.amiantos.lurker.ui.networks.FormErrorRow
import net.amiantos.lurker.ui.networks.FormInset
import net.amiantos.lurker.ui.networks.FormSectionFooter
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * The note editor's state. The draft is seeded once from the store and then owned by the editor — a
 * live subscription would rewrite the field under someone mid-sentence if another device saved while
 * they were typing. Kept here, with the page, so a rotation keeps what was typed.
 *
 * The caret starts at the end rather than selecting everything: this is used to *resume* editing an
 * existing note, and a select-all means the first keystroke silently replaces it.
 */
class NickNoteState(private val model: ChatViewModel, val networkId: Int, val nick: String) {
    /** What was there when the page opened — whether there is anything for Delete Note to delete. */
    val original: String = model.state.nickNotes.note(networkId = networkId, nick = nick)?.note ?: ""

    var draft by mutableStateOf(TextFieldValue(original, selection = TextRange(original.length)))

    var confirmingDelete by mutableStateOf(false)

    /** Why the last Save or Delete couldn't go out — shown under the field, the editor still open. */
    var refusal by mutableStateOf<String?>(null)
        private set

    /**
     * ⚠ Sent verbatim; the server trims and decides. A whitespace-only note is a DELETE there, so
     * pre-trimming here would only hide which of the two happened — the `nick-note-updated` echo
     * settles it either way. ⚠ A WRITE, to every device. False (and [refusal] set) when it couldn't
     * reach the server — the kit asks both connection signals and the send. Only our socket matters:
     * a note is the account's, not the IRC network's, so a network that's down is no reason.
     */
    fun save(): Boolean = send(draft.text)

    /** An empty note IS the delete verb — the server's own encoding. ⚠ A WRITE, to every device. */
    fun delete(): Boolean {
        confirmingDelete = false
        return send("")
    }

    private fun send(note: String): Boolean {
        refusal = NickNoteModel.NOT_CONNECTED.takeUnless { model.setNickNote(networkId = networkId, nick = nick, note = note) }
        return refusal == null
    }

    /**
     * Take an edit unless it would put the note past the server's cap (sweep L14), which cuts
     * silently and can split an emoji — the web's `maxlength`.
     */
    fun edit(value: TextFieldValue) {
        if (NickNote.fits(value.text)) draft = value
    }
}

/**
 * Write what you know about someone (lurker-ios#12) — "lives in Berlin", "spouse: Pat". lurker-ios's
 * `NickNoteViewController`.
 *
 * The account's own memory aid, not a profile field: nobody else can see it, it is scoped to one
 * network (the same nick elsewhere may be a different person), and it follows the account to the
 * browser.
 *
 * **Nothing is written locally.** Save asks, and the note changes when `nick-note-updated` comes back —
 * the route a note written on the web takes too. So a save the server refuses simply never appears,
 * rather than showing here and quietly not existing.
 *
 * @param onDone Save or Delete went out — back to the profile.
 */
@Composable
internal fun NickNotePage(state: NickNoteState, onBack: () -> Unit, onDone: () -> Unit) {
    NickNoteContent(
        nick = state.nick,
        draft = state.draft,
        onDraftChange = state::edit,
        offersDelete = NickNoteModel.offersDelete(state.original),
        onBack = onBack,
        refusal = state.refusal,
        onSave = { if (state.save()) onDone() },
        onDelete = { state.confirmingDelete = true },
        focusOnOpen = true,
    )
    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { state.confirmingDelete = false },
            title = { Text(NickNoteModel.DELETE_TITLE) },
            text = { Text(NickNoteModel.deleteMessage(state.nick)) },
            confirmButton = {
                TextButton(
                    onClick = { if (state.delete()) onDone() },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { state.confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NickNoteContent(
    nick: String,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    offersDelete: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    focusOnOpen: Boolean,
    refusal: String? = null,
) {
    val focus = remember { FocusRequester() }
    // A page whose only content is one field should not need a tap to start typing.
    if (focusOnOpen) LaunchedEffect(Unit) { focus.requestFocus() }
    DialogPage(title = "Note", exit = PageExit.Back, onExit = onBack, confirmTitle = "Save", onConfirm = onSave) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            // Grows with its content rather than scrolling inside a fixed height, so the whole note is
            // visible while it's being written.
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 8.dp).focusRequester(focus),
                label = { Text(NickNoteModel.fieldLabel(nick)) },
                placeholder = { Text(NickNoteModel.PLACEHOLDER) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            FormSectionFooter(UserProfileModel.NOTE_FOOTER)
            FormErrorRow(refusal)
            // Only once there is something to delete — see `NickNoteModel`.
            if (offersDelete) FormActionRow(title = "Delete Note", onClick = onDelete, destructive = true)
        }
    }
}

// MARK: - Previews

@Composable
private fun NotePreview(dark: Boolean, text: String) {
    LurkerTheme(darkTheme = dark) {
        NickNoteContent(
            nick = "alice",
            draft = TextFieldValue(text),
            onDraftChange = {},
            offersDelete = text.isNotEmpty(),
            onBack = {},
            onSave = {},
            onDelete = {},
            focusOnOpen = false,
        )
    }
}

@Preview(name = "Note — light")
@Composable
private fun NotePreviewLight() = NotePreview(dark = false, text = "Lives in Berlin.")

@Preview(name = "Note — dark")
@Composable
private fun NotePreviewDark() = NotePreview(dark = true, text = "Lives in Berlin.")

@Preview(name = "New note — light")
@Composable
private fun NewNotePreviewLight() = NotePreview(dark = false, text = "")

@Preview(name = "New note — dark")
@Composable
private fun NewNotePreviewDark() = NotePreview(dark = true, text = "")
