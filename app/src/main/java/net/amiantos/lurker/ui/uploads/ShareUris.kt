// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

/**
 * Which addresses a share from another app may name — the policy half of the share sheet's
 * confused-deputy guard, pure so it is held to by a test.
 *
 * ⚠⚠ `MainActivity` is exported with `ACTION_SEND` filters and no permission, and `EXTRA_STREAM`
 * is whatever the sending app put there. `ContentResolver.openInputStream` resolves a `file://`
 * address by opening the path directly — as THIS app — so an address inside Lurker's own sandbox
 * (`shared_prefs/lurker_ui.xml`, `lurker_defaults.xml`) was readable on a stranger's behalf,
 * staged, uploaded to the user's server and its link put in the composer next to the stranger's
 * text. A `content://` address on one of this app's own authorities (the camera `FileProvider`)
 * is the same trick one layer up. Only another app's provider is a share; the canonical-path check
 * in `AndroidUploadPlatform.stage` is the second line, for a provider that hands back a descriptor
 * onto a private file anyway.
 */
object ShareUris {
    /**
     * Whether a shared address may be read at all: a `content://` address whose authority is
     * another app's. [packageName] is this app's; its providers' authorities start with it
     * (`${packageName}.files`).
     */
    fun isAcceptable(scheme: String?, authority: String?, packageName: String): Boolean {
        if (scheme != "content") return false
        if (authority.isNullOrEmpty()) return false
        return !authority.startsWith(packageName)
    }
}
