// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * A Swift `Task { … }` started from main-confined code: launched in this scope, and yielding
 * before [block] runs, so no part of it runs before the statement that started it returns.
 *
 * ⚠ Under `Dispatchers.Main.immediate` a bare `launch` runs its body at once, up to its first
 * suspension — and a `delay` of zero does not suspend. A stored handle (`flushTask`,
 * `reaskTask`) would then be assigned AFTER a body that had already cleared and replaced it,
 * orphaning the replacement where `reset()` can never cancel it. `yield` dispatches even on an
 * immediate dispatcher.
 */
internal fun CoroutineScope.task(block: suspend CoroutineScope.() -> Unit): Job =
    launch {
        yield()
        block()
    }
