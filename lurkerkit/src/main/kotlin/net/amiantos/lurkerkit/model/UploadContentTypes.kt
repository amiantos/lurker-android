// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

// Port note: no MIME wildcard is spelled out inside a block comment in this file, because a
// slash followed by a star opens a nested comment in Kotlin. The KDoc below says "the `text`
// wildcard" instead.

/**
 * What the system file picker will let you choose (lurker-ios#125).
 *
 * ⚠⚠ A type absent from this list is not merely unsuggested — the picker GREYS THE FILE OUT,
 * with no "All Files" escape hatch. So this list is the whole of what can be uploaded from
 * Files, and an omission reads to the user as "Lurker can't send this" rather than as a filter.
 * The web picker's `accept` attribute had the same trap.
 *
 * Lives here rather than beside the picker because on iOS `AttachmentPicker` is in the app
 * target, which has no test bundle.
 */
object UploadContentTypes {

    /**
     * The MIME patterns for the system document picker (`ACTION_OPEN_DOCUMENT`'s
     * `EXTRA_MIME_TYPES`, which is what `OpenMultipleDocuments` takes).
     *
     * ⚠⚠ The whole `text` type, not `text/plain` — and not a hand-written list of dialects,
     * which is what this started as on iOS and got wrong. There, `public.json` does NOT conform
     * to `public.plain-text`: they are siblings under `public.text`, so `[.plainText, .json]`
     * looked complete and would have left `.yaml` and every other text sibling greyed out.
     * Naming the parent is both smaller and strictly wider, and it removes the need to be right
     * about the hierarchy at all.
     *
     * Port note: LurkerKit returns `[.image, .movie, .text]` as `UTType`s, which the iOS picker
     * matches by conformance. Android's picker matches a document's MIME type against patterns,
     * and a wildcard reaches no further than its own top-level type — so the same trap comes
     * back from the other side. `application/json` is text that IANA files under
     * `application/` (`UploadKind` carries a one-entry table for exactly this), so the `text`
     * wildcard alone would grey out every `.json`, one of the three dialects lurker#788 added.
     * It is named here because the wildcard cannot reach it. And unlike `public.text`, this is
     * NOT strictly wider than a hand-written list: text that a provider reports under some
     * other `application/…` type, or under no type at all (a `.yaml`, a `.log`), is offered on
     * iOS and is not offered by these patterns.
     *
     * ⚠ Nothing here becomes active content by being offered. The server classifies from the
     * BYTES, and any text file that is not one of its three dialects is normalised to
     * `text/plain` (`contentClass.ts`: `dialectFromFilename(...) ?? claimed ?? PLAIN_TEXT`) — so
     * an `.html` or `.xml` picked here is stored and served as inert text, not as itself. SVG is
     * the one text format that is treated as an image, and it is already offered by the `image`
     * wildcard regardless of this entry.
     *
     * ⚠ On iOS, text was missing entirely before lurker-ios#125 — not just the `.md`/`.json`
     * dialects lurker#788 added, but `.txt`, which the upload route has accepted the whole
     * time. The dialects are the reason to touch this; plain text was already broken.
     */
    val forOpening: List<String>
        get() = listOf("image/*", "video/*", "text/*", "application/json")
}
