// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import kotlin.coroutines.CoroutineContext

/**
 * Run [block] on [context] — the disk work of staging a copy or encoding a redraw — and delete every
 * file it [claim]ed if the caller is cancelled before the result reaches it.
 *
 * ⚠ `withContext` honours a cancel when it RESUMES: work that finished on the other thread a moment
 * after the cancel has its result thrown away, and a result that names a file (a just-staged 200 MB
 * copy, a just-encoded image) is then a file nothing will ever delete. Claimed the moment it's
 * created, the file is owned out here, outside the hop, whichever way the hop ends.
 *
 * On a normal return the files pass to whoever got the result.
 */
suspend fun <T> withOwnedFiles(context: CoroutineContext, block: suspend CoroutineScope.(claim: (File) -> File) -> T): T {
    val claimed = Collections.synchronizedList(mutableListOf<File>())
    try {
        return withContext(context) {
            block { file -> file.also(claimed::add) }
        }
    } catch (cancelled: CancellationException) {
        synchronized(claimed) { claimed.forEach { it.delete() } }
        throw cancelled
    }
}
