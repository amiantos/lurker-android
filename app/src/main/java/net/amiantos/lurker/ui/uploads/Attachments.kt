// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.UploadContentTypes

/**
 * The composer's way to attach — the paperclip's two sources, a pasted image, and the run they start.
 * lurker-ios's `presentAttachmentSources` / `pick` / `handlePick` / `uploadPastedImage`, over Android's
 * pickers: the **Photo Picker** for photos and videos (out of process, so no media permission is
 * asked for, as iOS's `PHPickerViewController`), and the **document picker** for files.
 *
 * Every entry point goes through the one busy gate ([UploadRunner.busy]) so a second pick or paste
 * can't start atop the first.
 */
class Attachments internal constructor(
    private val runner: UploadRunner,
    private val launchPhotos: () -> Unit,
    private val launchFiles: () -> Unit,
    /** Whether a run is under way — the paperclip is off until it has really ended. */
    val busy: Boolean,
    /** The readout above the composer, while a run is under way. */
    val readout: UploadReadout?,
) {
    /** "Photo Library": photos and videos, as many as the picker allows. */
    fun pickPhotos() {
        if (!busy) launchPhotos()
    }

    /** "Files": the document picker, over `UploadContentTypes.forOpening`. */
    fun pickFiles() {
        if (!busy) launchFiles()
    }

    /** The readout's ✕. */
    fun cancel() = runner.cancel()

    /**
     * Upload images pasted into the field — the same path as a pick, so a big screenshot gets the shrink
     * too, and the same progress, link insert and cleanup. False when a run is already going, which
     * leaves the paste unhandled (iOS drops it the same way).
     */
    fun paste(uris: List<Uri>): Boolean {
        if (busy || uris.isEmpty()) return false
        return runner.start(uris.map { AttachmentSource.Content(it.toString()) })
    }
}

/**
 * The attach controls for a conversation's composer, or null where there's nothing to attach — the
 * system buffer, the app's command console (iOS hides its paperclip there too) — or no uploads (a
 * preview).
 */
@Composable
fun rememberAttachments(services: UploadServices?, attaches: Boolean): Attachments? {
    if (services == null || !attaches) return null
    val runner = services.runner
    // Picks hand straight to the run. The pickers return their own handles, staged one at a time at
    // each file's turn (see `AttachmentSource`); a cancelled pick returns nothing and starts nothing.
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) runner.start(uris.map { AttachmentSource.Content(it.toString()) })
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runner.start(uris.map { AttachmentSource.Content(it.toString()) })
    }
    val busy by runner.busy.collectAsStateWithLifecycle()
    val readout by runner.readout.collectAsStateWithLifecycle()
    // Unlimited, deliberately: files stage and upload one at a time, peak disk is one file, the
    // readout says which of how many, and cancel stops the rest — so picking too many is recoverable
    // in a tap rather than something to pre-empt with a number. (The picker's own ceiling still applies.)
    val photoRequest = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo) }
    return Attachments(
        runner = runner,
        launchPhotos = { photos.launch(photoRequest) },
        // ⚠⚠ The list is `UploadContentTypes`', and it is the whole of what the picker lets you choose:
        // a type absent from it is greyed out, which reads as "Lurker can't send this" (#125).
        launchFiles = { files.launch(UploadContentTypes.forOpening.toTypedArray()) },
        busy = busy,
        readout = readout,
    )
}

/**
 * Register a conversation's composer as the place outside text lands while it's on screen — an
 * upload's link, Add to Message, a share's text ([ComposerInserts]).
 */
@Composable
fun ComposerInsertTarget(services: UploadServices?, key: BufferKey, insert: (String, Boolean) -> Unit) {
    if (services == null) return
    val current by rememberUpdatedState(insert)
    val scope = rememberCoroutineScope()
    DisposableEffect(services, key.id) {
        var handle: Any? = null
        // A frame late, not now: the composer is the scaffold's bottom bar, which is composed during
        // layout — after this effect — and text held for this buffer goes in at the caret with the
        // keyboard, whose focus request needs the field to exist.
        val mounting = scope.launch {
            withFrameNanos { }
            handle = services.inserts.mount(key) { text, atCaret -> current(text, atCaret) }
        }
        onDispose {
            mounting.cancel()
            handle?.let(services.inserts::unmount)
        }
    }
}

/**
 * Take an image pasted into the field — from the clipboard or a keyboard's image insert — as an
 * upload rather than letting the field try to show it. Anything else (text) falls straight through.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.receivesPastedImages(attachments: Attachments?): Modifier {
    if (attachments == null) return this
    return contentReceiver { content ->
        if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
        val uris = mutableListOf<Uri>()
        val rest = content.consume { item ->
            val uri = item.uri
            if (uri != null) uris.add(uri)
            uri != null
        }
        if (attachments.paste(uris)) rest else content
    }
}
