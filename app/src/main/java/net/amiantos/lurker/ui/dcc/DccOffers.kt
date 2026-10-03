// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.dcc

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.amiantos.lurkerkit.session.ChatViewModel

/** How the user answered a DCC chat offer — iOS's three buttons. */
enum class DccAnswer { Accept, Decline, NotNow }

/**
 * The DCC offer prompt's state, for the life of the process (lurker-android#38) — lurker-ios's
 * `DccOfferPrompt` minus the alert, which `MainScaffold` draws (`DccOfferDialog`) from [prompt].
 *
 * A small StateFlow on `LurkerApp` rather than an `AppEvent`: an offer is a standing question, not a
 * one-shot ask. `AppEvents` drops what arrives while no screen is attached, and an offer that lands
 * in the snapshot as the app comes back — the usual case — would be lost in exactly that gap. Here it
 * waits until it's answered or it isn't an offer any more, through rotations and the app's absence.
 *
 * @param notice where a refused Accept or Decline says why — the app's snackbar.
 */
class DccOffers(
    private val model: ChatViewModel,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit,
) {
    private val queue = DccOfferQueue()
    private val current = MutableStateFlow<DccOfferPrompt?>(null)

    /** The offer to ask about now, or null. */
    val prompt: StateFlow<DccOfferPrompt?> = current.asStateFlow()

    /**
     * Follow the store's offers. Deduped on the offers and their networks' names, which is all the
     * prompt reads — not every frame, as iOS takes them for its retry, which a Compose dialog doesn't
     * need: nothing here can refuse to present.
     */
    /** The dialog for [prompt] is on screen — see `DccOfferQueue.shown`. */
    fun shown(prompt: DccOfferPrompt) {
        queue.shown(prompt.offer.id, SystemClock.uptimeMillis())
    }

    fun follow() {
        scope.launch {
            model.statePublisher
                .map { state ->
                    val offers = state.dccChatOffers
                    offers to offers.associate { it.networkId to state.networks[it.networkId]?.displayName }
                }
                .distinctUntilChanged()
                .collect { (offers, names) -> current.value = queue.update(offers, names) }
        }
    }

    /**
     * The prompt is answered: run the verb, report a refusal, and bring up the next offer if one was
     * queued behind this one. On a successful Accept the app is taken to the chat by
     * `onDccChatOpened` — once its `=nick` row exists (the kit's `PendingDccOpen`).
     *
     * Accept never passes `passive`, as iOS doesn't: accepting answers the peer's offer, whose shape
     * the server already holds. ⚠ Both verbs are WRITES.
     */
    fun answer(prompt: DccOfferPrompt, answer: DccAnswer) {
        val offer = prompt.offer
        // Only the question on screen, and only once its prompt has settled (`DccOfferQueue.answer`):
        // a second tap on a dialog that's on its way down, or on the next offer that just took its
        // place, is nothing.
        if (!queue.answer(offer.id, SystemClock.uptimeMillis())) return
        current.value = queue.current
        when (answer) {
            DccAnswer.NotNow -> Unit
            DccAnswer.Accept -> scope.launch {
                model.openDccChat(networkId = offer.networkId, nick = offer.nick)?.let(notice)
            }
            DccAnswer.Decline -> scope.launch {
                model.closeDccChat(networkId = offer.networkId, nick = offer.nick)?.let(notice)
            }
        }
    }
}
