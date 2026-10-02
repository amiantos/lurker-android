// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.model.UploadKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /** a live row carries everything the grid draws */
    @Test
    fun parsesALiveRow() {
        val page = FrameParser.parseUploads(
            """
            {"items":[{"id":41,"provider":"local","url":"https://lurker.test/uploads/k.webp",
            "filename":"cat.png","mime":"image/webp","byte_size":8192,"width":100,"height":80,
            "created_at":"2026-08-20T11:04:05.123Z","favorite":true,"can_delete":true,
            "thumbnail_url":"/api/uploads/41/thumb"}],"providers":["local"],
            "maxUploadBytes":104857600}
            """.trimIndent(),
        )
        val row = assertNotNull(page.items.firstOrNull())
        assertEquals(41, row.id)
        assertEquals("https://lurker.test/uploads/k.webp", row.url)
        assertEquals("cat.png", row.filename)
        assertEquals(8192L, row.byteSize)
        assertTrue(row.favorite)
        assertTrue(row.canDelete)
        assertEquals("/api/uploads/41/thumb", row.thumbnailPath)
        assertFalse(row.removed)
        assertEquals(UploadKind.Image, row.kind)
        assertNotNull(row.createdAt)
    }

    /** a moderated tombstone reads back dead, not merely flagged */
    @Test
    fun parsesATombstone() {
        // ⚠⚠ The server drops `can_delete` and every thumbnail for a removed row because the
        // bytes are gone. Nothing may default those into looking live — a delete button on a
        // tombstone is one the route answers 409 to, and a thumbnail path is a dead fetch.
        val page = FrameParser.parseUploads(
            """
            {"items":[{"id":9,"provider":"catbox","url":"https://files.test/x.png",
            "filename":"x.png","mime":"image/png","byte_size":10,"created_at":"2026-08-01T00:00:00Z",
            "favorite":true,"removed":true}]}
            """.trimIndent(),
        )
        val row = assertNotNull(page.items.firstOrNull())
        assertTrue(row.removed)
        assertFalse(row.canDelete)
        assertNull(row.thumbnailPath)
        // ⚠ The star SURVIVES a takedown — the server keeps it, so the state comes back if the
        // row is ever restored. Which means a tombstone can arrive already starred, and the one
        // affordance it must still offer is unstarring: hiding it would strand that star with no
        // way in any UI to clear it.
        assertTrue(row.favorite)
    }

    /** a nameless row says so rather than showing its storage key */
    @Test
    fun pastedRowHasNoFilename() {
        val page = FrameParser.parseUploads(
            """{"items":[{"id":2,"url":"https://files.test/ab12.png","filename":null,"mime":"image/png"}]}""",
        )
        val row = assertNotNull(page.items.firstOrNull())
        assertNull(row.filename)
        assertEquals("(pasted)", row.displayName)
    }

    /** a body that isn't a page is an empty page, not a crash */
    @Test
    fun garbageIsEmpty() {
        assertTrue(FrameParser.parseUploads("not json").items.isEmpty())
        assertTrue(FrameParser.parseUploads("""{"items":[]}""").items.isEmpty())
    }
}
