// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.UploadContentTypes
import kotlin.test.Test
import kotlin.test.assertTrue

// Port note: no MIME wildcard is spelled out inside a block comment in this file, because a
// slash followed by a star opens a nested comment in Kotlin.

/**
 * What the system file picker will let you pick (lurker-ios#125).
 *
 * ⚠⚠ Worth testing at all because the failure is INVISIBLE from the code: a missing type doesn't
 * error, it greys the file out in somebody else's app.
 *
 * ⚠⚠ On iOS this suite settles the **host** UTI database — CI runs `swift test` on macOS — and
 * iOS declares its own. That is close to the same database in practice, and every identifier
 * there is system-declared rather than app-declared, but it is not proof about the device: the
 * one thing lurker-ios#125 explicitly asked to confirm on-device (what `.md` reports as its
 * mime) is exactly the kind of thing that could differ and leave that suite green. Treated as a
 * floor, with the device check on the QA list.
 *
 * Port note: LurkerKit's list is `UTType`s and its suite asserts facts about the UTI database
 * (`conforms(to:)`, `preferredMIMEType`). This list is MIME patterns, and a plain JVM has no
 * such database to ask — the device's is `android.webkit.MimeTypeMap`, which this module cannot
 * see. So each assertion is carried over only where it has a true equivalent as a statement
 * about the patterns ("a document of this MIME type is offered"); the rest are listed at the
 * bottom of the class rather than replaced with something that only looks like them.
 */
class UploadContentTypesTests {

    // Port-only helper: whether the picker offers a document of this MIME type — a pattern
    // matches it exactly, or is `type/*` with the same top-level type.
    private fun offers(pattern: String, mime: String): Boolean =
        pattern == mime || (pattern.endsWith("/*") && mime.startsWith(pattern.dropLast(1)))

    private fun offered(mime: String): Boolean = UploadContentTypes.forOpening.any { offers(it, mime) }

    /** offers text as a family, not a hand-picked set of dialects */
    @Test
    fun offersTheTextFamily() {
        val types = UploadContentTypes.forOpening
        assertTrue(types.contains("text/*"))
        assertTrue(types.contains("image/*"))
        assertTrue(types.contains("video/*"))
    }

    /** naming the parent covers every dialect, including the ones a hand-written list missed */
    @Test
    fun textCoversTheDialects() {
        // ⚠⚠ The bug this list started with on iOS. `[.plainText, .json]` reads as complete and
        // is not: `public.json` conforms to `public.text` but NOT to `public.plain-text` —
        // siblings, not parent and child — so plain text alone greys out every `.json`, and
        // `.yaml` (also a sibling) stays greyed out even with `.json` added by hand. The picker
        // matches by conformance, so a wrong guess about the hierarchy is invisible until
        // somebody cannot select a file.
        //
        // Port note: the same trap from the other side. A MIME wildcard reaches no further
        // than its top-level type, and JSON is `application/json`, so `text/*` alone greys out
        // every `.json` — which is why the list names it. The MIME types are the ones the UTI
        // database gives for the dialects LurkerKit's suite loops over (`.plainText`, `.json`,
        // `.commaSeparatedText`, and `.md`); `.yaml` is at the bottom of the class.
        for (dialect in listOf("text/plain", "application/json", "text/csv")) {
            assertTrue(offered(dialect), "$dialect must be offered")
        }
        assertTrue(offered("text/markdown"))
    }

    /** SVG is offered by the image wildcard regardless, so naming text admits nothing new */
    @Test
    fun svgIsNotWidenedByText() {
        // ⚠ On iOS `.text` sounds like it would newly admit SVG — the one text-conforming type
        // the server treats as an image. It does conform, and it was already offered by
        // `.image`, so this change moves nothing. Recorded because "did we just start allowing
        // SVG?" is the first fair question to ask of widening a picker.
        //
        // Port note: as MIME, SVG is `image/svg+xml`, so the statement is that an `image/`
        // pattern offers it whatever the text entries say.
        val imagePatterns = UploadContentTypes.forOpening.filter { it.startsWith("image/") }
        assertTrue(imagePatterns.any { offers(it, "image/svg+xml") })
    }

    // Not ported — no equivalent as a statement about MIME patterns on a plain JVM:
    //
    // - textCoversTheDialects: `!UTType.json.conforms(to: .plainText)` and
    //   `!UTType.yaml.conforms(to: .plainText)` are facts about the UTI hierarchy, which MIME
    //   does not have. And "`.yaml` must be offered" has no MIME to assert against: iOS calls
    //   it `application/x-yaml`, IANA `application/yaml`, and AOSP's extension table has no
    //   entry for `.yaml`/`.yml` at all — these patterns do NOT offer it.
    // - dialectsDeriveTheRightMime (whole test): extension → MIME is `MimeTypeMap` on the
    //   device. Belongs in an instrumented test beside the picker in `:app`.
    // - muchOfferedTextHasNoMime (whole test): the same lookup. Its finding does not carry over
    //   as written, either — on iOS a mimeless `.log` is still OFFERED (by conformance) and the
    //   danger is the claim it is uploaded with; here a document with no MIME type matches no
    //   pattern, so it is not offered in the first place.
    // - svgIsNotWidenedByText: `UTType.svg.conforms(to: .text)`. As MIME, `image/svg+xml` is
    //   not under `text/` at all.
}
