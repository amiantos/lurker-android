// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * The kinds the uploads browser filters by, and which mimes each one covers.
 *
 * A port of the server's `shared/uploadKinds.ts`, which exists there for the same reason it
 * exists here: two sides have to answer the same question and must not drift. The server builds
 * its `WHERE` clause from it, and the client decides which glyph a row without a thumbnail gets.
 *
 * ⚠⚠ **The FILTER is the server's, not this.** `kind=` goes on the wire and the server decides
 * what comes back — the client only ever holds the pages it has scrolled through, so a
 * render-time filter over them would be filtering the wrong set (`UploadsRequest` says the same
 * thing about search). This type is here so the *presentation* of a kind can't invent a second,
 * disagreeing rule.
 *
 * ⚠⚠ `application/json` is the whole reason the extra-mimes table exists. It is a text file that
 * IANA files under `application/` (RFC 8259 registers `application/json`; there is no
 * `text/json`), so a prefix match on `text/` misses it — and an uploaded `.json` would be drawn
 * with a generic glyph while the server files it under Text (lurker#788). Hand-writing
 * `mime.startsWith("text/")` here is exactly the trap the shared module was written to stop.
 */
enum class UploadKind(val rawValue: String) {
    Image("image"),
    Video("video"),
    Audio("audio"),
    Text("text");

    /**
     * Does an upload of this mime belong under this kind? The single definition, mirroring
     * `mimeMatchesKind` on the server.
     */
    fun matches(mime: String?): Boolean {
        val value = mime ?: ""
        return value.startsWith("$rawValue/") || (extraMimes[this] ?: emptyList()).contains(value)
    }

    /** What the filter is called in the UI, matching the web client's chips. */
    val label: String
        get() = when (this) {
            Image -> "Images"
            Video -> "Video"
            Audio -> "Audio"
            Text -> "Text"
        }

    companion object {
        /** Mimes a kind covers that its `<kind>/…` prefix does not. Empty for every kind but text. */
        private val extraMimes: Map<UploadKind, List<String>> = mapOf(
            Text to listOf("application/json"),
        )

        fun fromRawValue(raw: String): UploadKind? = entries.firstOrNull { it.rawValue == raw }

        /**
         * Which kind a mime falls under, or null for one no filter covers (a PDF, an archive).
         *
         * ⚠ Never sent to the server — the row already carries its mime and the server derives the
         * kind from it there. This is for choosing a glyph.
         */
        fun of(mime: String?): UploadKind? = entries.firstOrNull { it.matches(mime) }
    }
}
