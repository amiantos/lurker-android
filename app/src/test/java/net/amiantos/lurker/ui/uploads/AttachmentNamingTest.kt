// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The staged file's name and claim — lurker-ios's `AttachmentPicker.copy` rules. */
class AttachmentNamingTest {
    /** A slice of `MimeTypeMap`, which a JVM test can't reach. */
    private val types = mapOf(
        "jpg" to "image/jpeg", "png" to "image/png", "heic" to "image/heic", "mov" to "video/quicktime",
        "mp4" to "video/mp4", "txt" to "text/plain", "json" to "application/json", "md" to "text/markdown",
    )
    private val extensions = mapOf("image/jpeg" to "jpg", "video/mp4" to "mp4", "image/png" to "png", "text/plain" to "txt")

    private fun name(displayName: String?, providerType: String?) =
        AttachmentNaming.name(displayName, providerType, { types[it] }, { extensions[it] })

    @Test
    fun aNamedFileKeepsItsNameAndClaimsItsExtensionsType() {
        val named = name("IMG_4821.HEIC", "image/heic")
        assertEquals("IMG_4821.HEIC", named.filename)
        assertEquals("HEIC", named.extension)
        assertEquals("image/heic", named.mime)
        assertFalse(named.isVideo)
    }

    @Test
    fun anExtensionlessItemTakesTheProvidersExtensionOnTheNameToo() {
        // The name is the half that travels: the server reads the dialect from it first.
        val named = name("1000001234", "image/jpeg")
        assertEquals("1000001234.jpg", named.filename)
        assertEquals("jpg", named.extension)
        assertEquals("image/jpeg", named.mime)
    }

    @Test
    fun anExtensionlessItemWithNoTypeSaysNothingConfident() {
        // ⚠⚠ Never `.jpg` for "not video" — the iOS bug that sent text up as image/jpeg.
        val named = name("README", null)
        assertEquals("README", named.filename)
        assertEquals("", named.extension)
        assertEquals("application/octet-stream", named.mime)
    }

    @Test
    fun unregisteredTextClaimsPlainTextNotOctetStream() {
        // ⚠⚠ An octet-stream claim on a script with `<svg ` in it is classified as an active SVG.
        val named = name("deploy.sh", "text/x-sh")
        assertEquals("deploy.sh", named.filename)
        assertEquals("text/plain", named.mime)
    }

    @Test
    fun aCharsetNeverRidesTheClaim() {
        val named = name("notes", "text/plain; charset=utf-8")
        assertEquals("notes.txt", named.filename)
        assertEquals("text/plain", named.mime)
    }

    @Test
    fun videoIsKnownByExtensionOrByTheProvider() {
        assertTrue(name("clip.mov", null).isVideo)
        assertTrue(name("clip", "video/mp4").isVideo)
        assertEquals("clip.mp4", name("clip", "video/mp4").filename)
        // An unregistered video extension the provider vouches for still compresses, and claims mp4.
        val odd = name("clip.mkv", "video/x-matroska")
        assertTrue(odd.isVideo)
        assertEquals("video/mp4", odd.mime)
    }

    @Test
    fun jsonIsTextTheServerFilesUnderApplication() {
        assertEquals("application/json", name("data.json", "application/json").mime)
    }

    @Test
    fun aDotfileHasNoExtension() {
        assertEquals("", AttachmentNaming.extension(".profile"))
        assertEquals("", AttachmentNaming.extension("trailing."))
        assertEquals("gz", AttachmentNaming.extension("a.tar.gz"))
    }

    @Test
    fun aTranscodeRenamesTheExtension() {
        assertEquals("IMG_1.mp4", AttachmentNaming.replacingExtension("IMG_1.MOV", "mp4"))
        assertEquals("clip.mp4", AttachmentNaming.replacingExtension("clip", "mp4"))
        assertEquals("shot.jpg", AttachmentNaming.replacingExtension("shot.heic", "jpg"))
    }

    @Test
    fun noNameAtAllIsStillAName() {
        assertEquals("upload.png", name(null, "image/png").filename)
        assertEquals("upload.png", name("", "image/png").filename)
    }

    @Test
    fun theLaunchCleanupKnowsTheKitsUploadBodiesAndNothingElse() {
        assertTrue(isUploadBody("lurker-upload-F37F1953-12FF-4D1A-A6FE-9882DFCE3C73.multipart"))
        assertFalse(isUploadBody("lurker-upload-notes.txt"))
        assertFalse(isUploadBody("preview-media"))
        assertFalse(isUploadBody("some-other.multipart"))
    }
}
