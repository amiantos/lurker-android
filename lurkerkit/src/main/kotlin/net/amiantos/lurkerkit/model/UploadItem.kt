// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * One row of the account's upload history — a file this user put somewhere, kept so it can be
 * found again and re-shared without being re-uploaded (lurker-ios#138).
 *
 * The server owns every field here; nothing is derived on the client. In particular
 * `canDelete` is server-derived from whether the driver captured a delete handle *and* its
 * uploader config still exists — see the note on that property.
 */
data class UploadItem(
    val id: Int,
    /**
     * The public address of the stored object. This is what gets pasted into a message, opened
     * in the viewer, and shared — the origin URL, never a proxy path.
     */
    val url: String,
    /** As uploaded. Null for a pasted screenshot, which never had one. */
    val filename: String? = null,
    /**
     * Sniffed from the bytes by the server, so it is the truth about what the file is rather
     * than what its name claims. `UploadKind.of(mime)` maps it to a kind.
     */
    val mime: String? = null,
    val byteSize: Long? = null,
    val createdAt: Instant? = null,
    /** Starred by the owner. Server-side state, so the same quick-access set is on every device. */
    val favorite: Boolean = false,
    /**
     * Whether deleting this row would actually destroy the stored bytes.
     *
     * ⚠⚠ **Never offer Delete without it.** The route refuses a delete for a row whose bytes
     * can't be destroyed (no ref, a driver that can't delete, a config since removed, a
     * moderation tombstone), so a Delete offered anyway would be one that reliably fails.
     * Anonymous and legacy rows can't be deleted, and that is a fact about where the file
     * went, not a permission. Such a row is taken off the list instead — see `removal`.
     */
    val canDelete: Boolean = false,
    /**
     * Where to fetch this row's thumbnail, or null when there isn't one.
     *
     * ⚠ Either a path on this instance (`/api/uploads/<id>/thumb`, bearer-gated) or an absolute
     * URL on the uploader's own CDN — the server picks, and nothing distinguishes them on the
     * wire. Both are handled by the same media request builder the link-preview proxy uses, so
     * this must be passed through it rather than concatenated onto the base URL.
     */
    val thumbnailPath: String? = null,
    /**
     * The hosted operator moderated this upload away. The row survives as a tombstone; its
     * bytes are gone, so there is nothing to view, share, or copy a working link to.
     */
    val removed: Boolean = false,
) {
    /** Which filter chip this row belongs under, or null for a mime no kind covers. */
    val kind: UploadKind? get() = UploadKind.of(mime = mime)

    /**
     * What to call this row when there is no filename. Matches the web client's "(pasted)":
     * a file that arrived from the clipboard genuinely never had a name, and inventing one
     * from the URL's last path segment would show the storage key, which means nothing.
     */
    val displayName: String get() = filename ?: "(pasted)"

    /**
     * The one way this row leaves the list (lurker#1289). The web's tile offers the same pair:
     * the trash where the file can be destroyed, "remove from list" everywhere else.
     */
    val removal: UploadRemoval get() = if (canDelete) UploadRemoval.Destroy else UploadRemoval.Forget

    /**
     * What the confirmation says before a row is taken off the list with its file left alone.
     * A tombstone's file is already gone, so there is no file to reassure anyone about.
     */
    val forgetMessage: String?
        get() = if (removed) null else "The file stays where it is. This only takes it off your uploads list."
}

/** How an upload's row can be removed. */
enum class UploadRemoval {
    /** `DELETE /api/uploads/:id`: the stored bytes are destroyed, then the row goes. */
    Destroy,

    /** `DELETE /api/uploads/:id/record`: the row goes and the file stays wherever it is. */
    Forget,
}

/**
 * One page of `GET /api/uploads`.
 *
 * ⚠ There is no `nextBefore` in the envelope, unlike highlights/bookmarks/search: this route
 * pages on a keyset cursor the *client* carries, so the next page's `before` is the last row's
 * id and "there is more" is `items.size == limit`. `UploadsRequest.hasMore` is where that rule
 * lives, because the starred view breaks it — see `UploadsFilter.favoritesOnly`.
 */
data class UploadsPage(
    val items: List<UploadItem>,
)
