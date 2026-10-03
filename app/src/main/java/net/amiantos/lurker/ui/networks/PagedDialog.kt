// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.amiantos.lurker.platform.findActivity

/**
 * A full-screen dialog with pages inside it — iOS's sheet with its own navigation controller. The
 * networks dialogs (U4) and the conversation's buffer dialogs (U5) are both one of these: a stack of
 * pages, each holding its own state, and a scope their requests run in.
 *
 * ⚠ Not `remember`ed and not saved: a subclass is kept in [PagedFlowStore], which outlives a
 * configuration change. A rotation must not drop a half-typed form — but forms hold passwords, private
 * keys and channel keys, which have no business in a saved-instance Bundle written to disk across a
 * process death. In memory for the life of the dialog, then dropped.
 *
 * The scope outlives each page, so a reply that lands after its page was popped still reaches the page
 * under it. It's cancelled with the dialog ([close]), when there's no one left to tell; writes that
 * must finish run `NonCancellable`.
 *
 * Inner navigation is the flow's own, not the app's navigator: it's a detour inside one dialog, with
 * nothing in it a deep link or the system's back stack should know.
 */
internal abstract class PagedFlow<P : Any> {
    // `Main`, not `Main.immediate`: a flow is built inside composition, and its pages start loading in
    // their `init`. Dispatched, those requests begin after the frame rather than inline in the
    // composition pass that created them.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The page stack, root first. Never empty once open: back from the root dismisses the dialog instead. */
    val pages = mutableStateListOf<P>()

    /** Pop the top page. False at the root, where back means dismissing the dialog. */
    fun back(): Boolean {
        if (pages.size <= 1) return false
        popped(pages.removeAt(pages.lastIndex))
        return true
    }

    /** A page left the stack — for a subclass whose pages hold work of their own to stop. */
    protected open fun popped(page: P) {}

    open fun close() {
        scope.cancel()
    }
}

/**
 * The open paged dialogs, kept across configuration changes — see [PagedFlow]. Keyed by a token the
 * opener saves, so a dialog restored after a rotation finds its flow, and one restored after a process
 * death (token saved, store empty) starts fresh at its first page. One store for every kind of flow:
 * the tokens are unique per open dialog, whichever kind it is.
 */
internal class PagedFlowStore : ViewModel() {
    private val flows = mutableMapOf<String, PagedFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <F : PagedFlow<*>> flow(token: String, create: () -> F): F = flows.getOrPut(token, create) as F

    fun discard(token: String) {
        flows.remove(token)?.close()
    }

    override fun onCleared() {
        flows.values.forEach { it.close() }
        flows.clear()
    }
}

/**
 * The flow for the dialog open under [token], created on first ask.
 *
 * ⚠ Dropped when the dialog closes ([isOpen] false once this leaves composition) and when the host
 * leaves composition for good (sign-out swaps the whole scaffold out) — but NOT across a configuration
 * change, which is the one disposal the flow exists to survive.
 */
@Composable
internal fun <F : PagedFlow<*>> rememberPagedFlow(token: String, isOpen: () -> Boolean, create: () -> F): F {
    val store: PagedFlowStore = viewModel()
    val flow = store.flow(token, create)
    val activity = LocalContext.current.findActivity()
    DisposableEffect(token) {
        onDispose {
            if (!isOpen() || activity?.isChangingConfigurations != true) store.discard(token)
        }
    }
    return flow
}

/**
 * A paged dialog: its stack, drawn top page only. Back pops a pushed page (predictive back included),
 * then dismisses. A push slides in from the end, a pop back from the start — the platform's forward
 * and back.
 *
 * @param page draws one page; `depth` is 1 for the root, which closes rather than goes back.
 */
@Composable
internal fun <P : Any> PagedDialog(
    flow: PagedFlow<P>,
    onDismiss: () -> Unit,
    label: String,
    page: @Composable (depth: Int, page: P) -> Unit,
) {
    FullScreenDialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = flow.pages.size > 1) { flow.back() }
        AnimatedContent(
            targetState = flow.pages.size to flow.pages.last(),
            transitionSpec = {
                val forward = targetState.first >= initialState.first
                (slideInHorizontally { width -> if (forward) width / 4 else -width / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally { width -> if (forward) -width / 4 else width / 4 } + fadeOut())
            },
            label = label,
        ) { (depth, top) -> page(depth, top) }
    }
}
