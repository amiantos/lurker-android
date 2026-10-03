// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.dcc

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.DccChatOffer

/**
 * Asks about a DCC chat offer (lurker#270, lurker-android#38): "bob wants to chat directly" —
 * Decline, Not Now, Accept. lurker-ios's `DccOfferPrompt`, shaped like AirDrop's prompt there, which
 * is the same question: someone wants a direct connection, yes or no.
 *
 * A dialog rather than a snackbar because an offer is a decision someone is waiting on. It never
 * auto-accepts — accepting makes the server dial an address the peer chose, so it stays a deliberate
 * act. Hosted by `MainScaffold`, like `ServerErrorDialog`, so it stands over whatever is on screen and
 * survives navigation. Back and a tap outside are Not Now: the offer stands.
 */
@Composable
fun DccOfferDialog(offers: DccOffers) {
    DccOfferDialog(offers.prompt, onAnswer = offers::answer)
}

@Composable
private fun DccOfferDialog(prompt: StateFlow<DccOfferPrompt?>, onAnswer: (DccOfferPrompt, DccAnswer) -> Unit) {
    val current by prompt.collectAsStateWithLifecycle()
    current?.let { DccOfferContent(it, onAnswer = { answer -> onAnswer(it, answer) }) }
}

@Composable
private fun DccOfferContent(prompt: DccOfferPrompt, onAnswer: (DccAnswer) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(DccAnswer.NotNow) },
        title = { Text(prompt.title) },
        text = { Text(prompt.message) },
        // Accept is the preferred action, trailing-most, as Material puts a dialog's confirm.
        confirmButton = { TextButton(onClick = { onAnswer(DccAnswer.Accept) }) { Text("Accept") } },
        // Decline is iOS's destructive button — in the error colour; Not Now is its cancel.
        dismissButton = {
            Row {
                TextButton(onClick = { onAnswer(DccAnswer.Decline) }) {
                    Text("Decline", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { onAnswer(DccAnswer.NotNow) }) { Text("Not Now") }
            }
        },
    )
}

// MARK: - Previews

private val previewPrompt = DccOfferPrompt(DccChatOffer(id = 1, networkId = 1, nick = "bob", passive = true), "Libera")

@Preview(name = "DCC offer — light")
@Composable
private fun DccOfferPreviewLight() = LurkerTheme(darkTheme = false) { DccOfferContent(previewPrompt) {} }

@Preview(name = "DCC offer — dark")
@Composable
private fun DccOfferPreviewDark() = LurkerTheme(darkTheme = true) { DccOfferContent(previewPrompt) {} }
