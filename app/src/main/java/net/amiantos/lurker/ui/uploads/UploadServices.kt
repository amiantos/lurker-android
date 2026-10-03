// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app-long upload pieces, in one hand-off from `LurkerApp` down to the screens: the run, the
 * composers' registry, and the share waiting for a conversation. Built once per process — an upload
 * must outlive the activity (a rotation) and the conversation it started in (a buffer switch).
 */
class UploadServices(
    val runner: UploadRunner,
    val inserts: ComposerInserts,
    val shares: ShareInbox,
) {
    /** Sign-out: nothing of the previous account's carries into the next. */
    fun reset() {
        runner.reset()
        inserts.clear()
        shares.clear()
    }
}

/**
 * The app's [UploadServices], for the conversation's composer — provided by `MainScaffold`, null outside
 * it (previews), where the paperclip simply isn't drawn.
 */
val LocalUploadServices = staticCompositionLocalOf<UploadServices?> { null }
