// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.UploadKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a page of `GET /api/uploads`, and the mime→kind rule the rows are drawn by
 * (lurker-ios#138).
 */
class UploadHistoryTests {

    // MARK: - Kinds

    /** a JSON upload is TEXT, which no prefix match would say */
    @Test
    fun jsonIsText() {
        // ⚠⚠ The reason this rule is a shared table rather than `mime.startsWith("text/")`.
        // `application/json` is a text file IANA files under `application/` (RFC 8259 registers
        // it; there is no `text/json`), so a prefix match misses it — and the row would be drawn
        // with a generic glyph while the server files it under the Text filter (lurker#788).
        assertTrue(UploadKind.Text.matches(mime = "application/json"))
        assertEquals(UploadKind.Text, UploadKind.of(mime = "application/json"))
    }

    /** prefixes cover the ordinary cases */
    @Test
    fun prefixesMatch() {
        assertEquals(UploadKind.Image, UploadKind.of(mime = "image/webp"))
        assertEquals(UploadKind.Video, UploadKind.of(mime = "video/mp4"))
        assertEquals(UploadKind.Audio, UploadKind.of(mime = "audio/mpeg"))
        assertEquals(UploadKind.Text, UploadKind.of(mime = "text/markdown"))
    }

    /** a mime no kind covers is nil, not a wrong guess */
    @Test
    fun unknownMimeHasNoKind() {
        // A PDF is a real upload the server accepts and no filter chip claims. Forcing it under
        // one would put it in a list it does not belong to, which is worse than an unfiltered row.
        assertNull(UploadKind.of(mime = "application/pdf"))
        assertNull(UploadKind.of(mime = null))
        // ⚠ And `application/` alone must not fall under text on the strength of the json entry.
        assertFalse(UploadKind.Text.matches(mime = "application/zip"))
    }

    // MARK: - Parsing

    // Waiting on FrameParser, UploadItem: parsesALiveRow, parsesATombstone, pastedRowHasNoFilename,
    // garbageIsEmpty
}
