// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

/**
 * What a staged file is called and what it claims to be — the naming half of lurker-ios's
 * `AttachmentPicker.copy`, pure so the rules that bit iOS can be held to by a test.
 *
 * The server reads an upload's dialect from the FILENAME first and the claimed mime second
 * (`classifyUpload`), and overrules both from the bytes where it can — so a confident wrong claim is
 * worse than none, and the name is the half that travels.
 */
object AttachmentNaming {
    /**
     * A name and a claim for one picked item.
     *
     * @property filename what the upload is called — the provider's display name, with the inferred
     *   extension added when it had none.
     * @property extension what the staged copy is named with, or "" when nothing knows.
     */
    data class Named(val filename: String, val extension: String, val mime: String, val isVideo: Boolean)

    /**
     * Name one picked item.
     *
     * @param displayName the provider's name for it (`OpenableColumns.DISPLAY_NAME`), or null.
     * @param providerType the provider's type for it (`ContentResolver.getType`), or null. Only ever a
     *   fallback here — for the extension an extensionless item is missing, and for whether it's video.
     *   ⚠⚠ Never the image shrink's type: that one is the decoder's (`ImageConverter`).
     * @param mimeForExtension the platform's extension→MIME table (`MimeTypeMap`).
     * @param extensionForMime the platform's MIME→extension table.
     */
    fun name(
        displayName: String?,
        providerType: String?,
        mimeForExtension: (String) -> String?,
        extensionForMime: (String) -> String?,
    ): Named {
        // A provider type can carry parameters (`text/plain; charset=utf-8`); ⚠⚠ a charset never rides
        // a claim (lurker#788 — the server's classification takes a bare type).
        val type = providerType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val name = displayName?.takeIf { it.isNotEmpty() } ?: "upload"
        val ownExtension = extension(name)
        // ⚠⚠ iOS once inferred `isVideo ? "mov" : "jpg"`, reading "not video" as "image" — and an
        // extensionless text file went up as `….jpg` claiming `image/jpeg`. The fallback here is what the
        // PROVIDER says the item is, which Android providers know (iOS's documents don't), and nothing at
        // all when it doesn't say.
        val inferred = if (ownExtension.isEmpty()) type?.let(extensionForMime).orEmpty() else ""
        val ext = ownExtension.ifEmpty { inferred }
        val isVideo = isVideo(ext, type, mimeForExtension)
        val mime = claim(ext, type, isVideo, mimeForExtension)
        // ⚠ The filename carries the inferred extension too. A staged copy and a claim saying `.jpg`
        // while the uploaded name says nothing is the two halves disagreeing, and the name is the half
        // the server reads first. When the source named itself this changes nothing.
        val filename = if (ownExtension.isEmpty() && inferred.isNotEmpty()) "$name.$inferred" else name
        return Named(filename = filename, extension = ext, mime = mime, isVideo = isVideo)
    }

    /**
     * The claim. The extension's registered type first — the same order iOS reads (`UTType` from the
     * extension) — then:
     *
     * ⚠⚠ A text item with no registered type claims `text/plain`, NOT octet-stream, and the difference
     * is a misclassification with teeth. `.log`, `.sh` and `.c` have no entry in the platform's table,
     * and the server exempts a claim from its SVG probe only when the claim is already a text dialect —
     * so an octet-stream claim on a shell script with `<svg ` in its first kilobyte is classified
     * `image/svg+xml`: a 415 on hosted, and on self-host a file served as an ACTIVE SVG rather than as
     * the text it is. Claiming `text/plain` keeps it on the text path, where the filename still gets the
     * last word.
     */
    fun claim(extension: String, providerType: String?, isVideo: Boolean, mimeForExtension: (String) -> String?): String {
        val registered = extension.takeIf { it.isNotEmpty() }?.let { mimeForExtension(it.lowercase()) }
        if (registered != null) return registered
        return when {
            providerType?.startsWith("text/") == true -> "text/plain"
            isVideo -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    /**
     * True for video — the one class compressed on the device before uploading. Audio passes straight
     * through (it's already small), and images are only ever redrawn smaller (`ImageConverter`).
     */
    fun isVideo(extension: String, providerType: String?, mimeForExtension: (String) -> String?): Boolean {
        val byExtension = extension.takeIf { it.isNotEmpty() }?.let { mimeForExtension(it.lowercase()) }
        return (byExtension ?: providerType)?.startsWith("video/") == true
    }

    /** The name's extension, or "" — never the whole of a dotfile's name (`.profile`). */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    /** [name] with its extension swapped for [extension] — a transcode's `….mov` → `….mp4`. */
    fun replacingExtension(name: String, extension: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot <= 0) name else name.substring(0, dot)
        return "$stem.$extension"
    }
}
