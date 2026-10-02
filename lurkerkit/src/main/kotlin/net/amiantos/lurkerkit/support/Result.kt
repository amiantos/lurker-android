// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

/**
 * One of two outcomes, each with its own type: the stand-in for Swift's
 * `Result<Success, Failure>`. Port-only.
 *
 * Not `kotlin.Result`: that one's failure is any `Throwable`, so the `E` LurkerKit's callers
 * switch on (`case .failure(let refusal)`) would have to be cast back out of it, and a `when`
 * over it could not be exhaustive. This is the Swift enum by PORTING.md's own rule for an
 * enum with associated values — a sealed interface, its cases as data classes — so
 * `.success(x)` is `Result.Success(x)`, `.failure(e)` is `Result.Failure(e)`, and both compare
 * by value in a test.
 *
 * ⚠ A file that uses it imports it by name. Without the import, `Result` is `kotlin.Result`
 * and the type arguments do not fit — a compile error, not a silent substitution.
 */
sealed interface Result<out T, out E> {
    data class Success<out T>(val value: T) : Result<T, Nothing>

    data class Failure<out E>(val error: E) : Result<Nothing, E>
}
