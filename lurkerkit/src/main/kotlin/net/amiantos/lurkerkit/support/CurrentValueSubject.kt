// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Combine's `CurrentValueSubject`: a current [value], and a [flow] that hands a new collector
 * that value and then every later one. Port-only.
 *
 * Not a `StateFlow`, for the reasons `LurkerStore`'s subject isn't one: every assignment
 * publishes, equal to the last or not, and a collector sees every value in order rather than
 * skipping to the latest when it has fallen behind. A replay of one with an unbounded buffer
 * is both — the buffer holds only what a live collector has not yet taken; with no collector it
 * holds the replay alone, so setting [value] never suspends and never fails.
 *
 * Confined to the thread its owner is (the main thread, for every owner in the kit).
 */
internal class CurrentValueSubject<T>(initial: T) {
    private val subject = MutableSharedFlow<T>(replay = 1, extraBufferCapacity = Int.MAX_VALUE - 1)

    init {
        check(subject.tryEmit(initial))
    }

    /** The latest value. Assigning publishes, equal or not — `subject.value = x` in Combine. */
    var value: T
        get() = subject.replayCache.first()
        set(newValue) {
            check(subject.tryEmit(newValue))
        }

    /** The current value on collection, then every assignment in order. */
    val flow: SharedFlow<T> = subject.asSharedFlow()
}
