// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.MultipartBody
import net.amiantos.lurkerkit.support.unicodeRegex
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the upload body to the server's multipart contract: the `progressToken` field
 * streams BEFORE the file (the server reads it as fields flow past a possibly-huge body),
 * the file field is named `image`, and a filename can never break out of its header.
 */
class MultipartBodyTests {

    private fun makeSourceFile(contents: String): File = makeSourceFile(contents.toByteArray(Charsets.UTF_8))

    private fun makeSourceFile(contents: ByteArray): File {
        val url = File(System.getProperty("java.io.tmpdir"), "mpbody-src-${UUID.randomUUID()}.bin")
        url.writeBytes(contents)
        return url
    }

    @Test
    fun testProgressTokenPrecedesTheImageField() {
        val source = makeSourceFile("PAYLOADBYTES")
        try {
            val body = MultipartBody.assemble(
                token = "tok-123", fileURL = source, filename = "clip.mov", mime = "video/mp4",
            )
            try {
                val text = body.fileURL.readText(Charsets.UTF_8)
                val tokenRange = assertNotNull(text.indexOf("name=\"progressToken\"").takeIf { it >= 0 })
                val imageRange = assertNotNull(text.indexOf("name=\"image\"").takeIf { it >= 0 })
                assertTrue(
                    tokenRange < imageRange,
                    "progressToken must stream before the file body",
                )
                assertTrue(text.contains("tok-123"))
                assertTrue(text.contains("PAYLOADBYTES"), "the source bytes are embedded verbatim")
                assertTrue(text.contains("Content-Type: video/mp4"))
                assertTrue(text.contains("filename=\"clip.mov\""))
            } finally {
                body.fileURL.delete()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun testContentTypeHeaderCarriesTheBoundaryThatClosesTheBody() {
        val source = makeSourceFile("x")
        try {
            val body = MultipartBody.assemble(
                token = "t", fileURL = source, filename = "a.jpg", mime = "image/jpeg",
            )
            try {
                val boundary = assertNotNull(
                    body.contentType.indexOf("boundary=").takeIf { it >= 0 }
                        ?.let { body.contentType.substring(it + "boundary=".length) },
                )
                val text = body.fileURL.readText(Charsets.UTF_8)
                assertTrue(text.startsWith("--$boundary\r\n"), "opens with the announced boundary")
                assertTrue(text.endsWith("--$boundary--\r\n"), "closes with the terminating boundary")
            } finally {
                body.fileURL.delete()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun testSanitizeFilenameStripsQuotesControlCharsAndDefaultsWhenEmpty() {
        assertEquals("name.mov", MultipartBody.sanitizeFilename("na\"me\r\n.mov"))
        assertEquals("upload", MultipartBody.sanitizeFilename("   "))
        assertEquals("upload", MultipartBody.sanitizeFilename(""))
        assertEquals("photo.heic", MultipartBody.sanitizeFilename("photo.heic"))
    }

    @Test
    fun testSanitizedFilenameIsWhatLandsInTheHeader() {
        val source = makeSourceFile("y")
        try {
            val body = MultipartBody.assemble(
                token = "t", fileURL = source, filename = "ev\"il\n.png", mime = "image/png",
            )
            try {
                val text = body.fileURL.readText(Charsets.UTF_8)
                assertTrue(text.contains("filename=\"evil.png\""))
                assertFalse(text.contains("ev\"il"), "an unescaped quote would break the header")
            } finally {
                body.fileURL.delete()
            }
        } finally {
            source.delete()
        }
    }

    // Port-only: the suite above pins the body by `contains`, which Foundation's writer needs
    // no more than. This one was written against a different I/O stack, so the whole body is
    // pinned byte for byte — including a payload that is not valid UTF-8, which a writer that
    // went through a `String` anywhere would mangle.
    @Test
    fun testTheWholeBodyIsByteForByteTheContract() {
        val payload = ByteArray(256) { it.toByte() }
        val source = makeSourceFile(payload)
        try {
            val body = MultipartBody.assemble(
                token = "tok-123", fileURL = source, filename = "clip.mov", mime = "video/mp4",
            )
            try {
                val prefix = "multipart/form-data; boundary="
                assertTrue(body.contentType.startsWith(prefix))
                val boundary = body.contentType.substring(prefix.length)
                // `UUID().uuidString` is uppercase, so the boundary is too.
                assertTrue(
                    unicodeRegex("LurkerBoundary-[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")
                        .matches(boundary),
                    boundary,
                )

                val head = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"progressToken\"\r\n\r\n" +
                    "tok-123\r\n" +
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"image\"; filename=\"clip.mov\"\r\n" +
                    "Content-Type: video/mp4\r\n\r\n"
                val tail = "\r\n--$boundary--\r\n"
                val expected = head.toByteArray(Charsets.UTF_8) + payload + tail.toByteArray(Charsets.UTF_8)
                assertContentEquals(expected, body.fileURL.readBytes())
            } finally {
                body.fileURL.delete()
            }
        } finally {
            source.delete()
        }
    }

    // Port-only: the copy loop reads 1 MB at a time, and a short read or an off-by-one at a
    // chunk edge only shows on a source longer than one chunk.
    @Test
    fun testASourceLongerThanOneChunkArrivesWhole() {
        val payload = ByteArray((1 shl 20) * 2 + 12_345) { (it * 31 + it / 7).toByte() }
        val source = makeSourceFile(payload)
        try {
            val body = MultipartBody.assemble(
                token = "t", fileURL = source, filename = "big.mp4", mime = "video/mp4",
            )
            try {
                val bytes = body.fileURL.readBytes()
                val boundary = body.contentType.substringAfter("boundary=")
                val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
                val start = bytes.size - tail.size - payload.size
                assertTrue(start > 0)
                assertContentEquals(payload, bytes.copyOfRange(start, start + payload.size))
                assertContentEquals(tail, bytes.copyOfRange(bytes.size - tail.size, bytes.size))
            } finally {
                body.fileURL.delete()
            }
        } finally {
            source.delete()
        }
    }

    // Port-only: `CharacterSet.controlCharacters` is Unicode Cc AND Cf, which Swift gets by
    // naming the set and this port spells out. Each expectation is what LurkerKit's own
    // `sanitizeFilename` returns for the same input (run on macOS, 2026-10-01).
    @Test
    fun testSanitizeFilenameStripsFormatCharactersAsFoundationDoes() {
        // A zero-width joiner, a BOM, a soft hyphen, a bidi override: all Cf.
        assertEquals("ab.png", MultipartBody.sanitizeFilename("a\u200Db.png"))
        assertEquals("a.png", MultipartBody.sanitizeFilename("\uFEFFa.png"))
        assertEquals("ab", MultipartBody.sanitizeFilename("a\u00ADb"))
        assertEquals("ab", MultipartBody.sanitizeFilename("a\u202Eb"))
        // An emoji family loses its joiners and keeps its people. Astral, so this is also the
        // code-point stepping.
        assertEquals(
            "👨👩👧.png",
            MultipartBody.sanitizeFilename("👨\u200D👩\u200D👧.png"),
        )
        // C0, DEL and C1 controls from the middle of a name.
        assertEquals("ab", MultipartBody.sanitizeFilename("a\u000Bb"))
        assertEquals("xy", MultipartBody.sanitizeFilename("x\u007Fy"))
        assertEquals("xy", MultipartBody.sanitizeFilename("x\u0085y"))
        // The trim takes every space separator, not just U+0020; a tab goes as a control.
        assertEquals("a.png", MultipartBody.sanitizeFilename("\u00A0a.png\u3000"))
        assertEquals("a.png", MultipartBody.sanitizeFilename("\ta.png\t"))
        // Nothing left but stripped characters and spaces is still "nothing".
        assertEquals("upload", MultipartBody.sanitizeFilename(" \u200B "))
    }
}
