// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import net.amiantos.lurkerkit.client.UploadError
import net.amiantos.lurkerkit.client.UploadResponse
import net.amiantos.lurkerkit.client.UploadServerProgress
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.support.Result
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * [UploadPlatform] on a device: staging from content providers, the platform image decoder, media3's
 * transcoder, and the kit's upload. lurker-ios's `AttachmentPicker.stage` plus the glue in
 * `performUpload`.
 *
 * @param scope the app's main scope — where progress callbacks from other threads are queued to.
 */
class AndroidUploadPlatform(
    context: Context,
    private val model: ChatViewModel,
    private val scope: CoroutineScope,
) : UploadPlatform {
    private val context = context.applicationContext

    /**
     * Where staged copies, redraws and transcodes go — the app's cache, which the system may trim but
     * never backs up. Each is deleted the moment its upload ends; whatever a killed process left behind
     * is cleared on the next launch ([clearLeftovers]).
     */
    private val directory = File(this.context.cacheDir, "uploads").apply { mkdirs() }

    private val images = ImageConverter(directory)
    private val videos = VideoCompressor(this.context, directory)

    override val uploadCapBytes: Long get() = model.uploadCapBytes
    override val maxStaticImageDimension: Int? get() = model.maxStaticImageDimension

    /**
     * Copy one item into the cache, off the main thread. Cancellable between chunks — a cancel during a
     * cloud provider's download actually stops, rather than running the copy out (iOS's
     * `loadFileRepresentation` needed a hand-built handoff for the same promise).
     *
     * A copy at all, not a read straight from the provider: the grant on a picked or shared address
     * belongs to the activity that received it, and the upload outlives it.
     *
     * ⚠ Any failure but a cancel is "couldn't be read", not just the I/O ones: a provider answers a
     * stale or foreign address with whatever it likes — `IllegalArgumentException` ("Unknown URI"),
     * `IllegalStateException`, `UnsupportedOperationException`, a `NullPointerException` — and one
     * escaping here would end the app rather than one file.
     *
     * The copy is claimed ([withOwnedFiles]) the moment it's named, so a cancel landing as the copy
     * finishes deletes it rather than dropping it.
     */
    override suspend fun stage(source: AttachmentSource.Content): StageResult =
        withOwnedFiles(Dispatchers.IO) { claim ->
            val uri = source.uri.toUri()
            val resolver = context.contentResolver
            val named = AttachmentNaming.name(
                displayName = displayName(uri),
                providerType = try { resolver.getType(uri) } catch (_: RuntimeException) { null },
                mimeForExtension = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
                extensionForMime = { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) },
            )
            val stem = "lurker-attach-${UUID.randomUUID()}"
            val dest = claim(File(directory, if (named.extension.isEmpty()) stem else "$stem.${named.extension}"))
            try {
                val input = resolver.openInputStream(uri) ?: return@withOwnedFiles StageResult.Failed("Couldn't read the file.")
                input.use { stream ->
                    FileOutputStream(dest).use { out ->
                        val chunk = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive()
                            val count = stream.read(chunk)
                            if (count < 0) break
                            out.write(chunk, 0, count)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                dest.delete()
                throw cancelled
            } catch (error: Exception) {
                // A lapsed grant (a share whose sending app has gone) is a `SecurityException`; a full
                // disk an `IOException`; a provider's own refusal anything at all.
                dest.delete()
                return@withOwnedFiles StageResult.Failed(error.message ?: "Couldn't read the file.")
            }
            StageResult.Staged(Picked(dest, named.filename, named.mime, named.isVideo))
        }

    private fun displayName(uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (_: RuntimeException) {
            uri.lastPathSegment
        }

    override suspend fun prepareImage(file: File, maxStaticImageDimension: Int?): PreparedImage? =
        images.prepare(file, maxStaticImageDimension)

    override suspend fun prepareVideo(file: File, maxBytes: Long, onProgress: (Double) -> Unit): PreparedVideo =
        videos.prepare(file, maxBytes, onProgress)

    /**
     * The kit's upload, called from the main thread: the kit moves its own disk work (the multipart
     * copy) onto IO and keeps the rest — the progress sinks, the auth handling — on main, where its
     * client lives.
     *
     * The progress callbacks arrive on OkHttp's thread and are queued to the main thread, as iOS hops
     * each to the main actor — never run inline, which the run's staleness gate relies on.
     */
    override suspend fun upload(
        file: File,
        filename: String,
        mime: String,
        progressToken: String,
        onProgress: (Double) -> Unit,
        onServerProgress: (UploadServerProgress) -> Unit,
    ): Result<UploadResponse, UploadError> =
        model.upload(
            fileURL = file,
            filename = filename,
            mime = mime,
            progressToken = progressToken,
            onProgress = { fraction -> scope.launch(Dispatchers.Main) { onProgress(fraction) } },
            // Already on the main thread (the socket's frames are handled there), but queued all the
            // same so the two legs reach the run in one order of arrival.
            onServerProgress = { frame -> scope.launch(Dispatchers.Main) { onServerProgress(frame) } },
        )

    override fun delete(file: File) {
        file.delete()
    }

    /** Anything a killed process left in the staging directory — called once at launch. */
    fun clearLeftovers() {
        scope.launch(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
    }

    /** One write for every link that had nowhere to land. */
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Links", text))
    }
}
