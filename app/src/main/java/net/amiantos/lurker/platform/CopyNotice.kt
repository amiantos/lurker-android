// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.os.Build

/**
 * Say "Copied" after a copy, where the system doesn't. Android 13 and up confirm a copy
 * themselves, and a second notice for the same thing is noise (the platform's guidance); below
 * that — Fire OS 7 is Android 9 — a copy is otherwise silent.
 */
fun AppEvents?.confirmCopy() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) this?.send(AppEvent.Notice("Copied"))
}
