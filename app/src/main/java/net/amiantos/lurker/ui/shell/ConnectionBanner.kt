// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState

/**
 * The loud counterpart to the title's status subtitle: a floating capsule that drops down from
 * under the top bar to say, in words, when the connection is unhappy — "No internet connection",
 * "Connecting…", "Reconnecting…". The subtitle is always-on ambient and easy to miss; this appears
 * only when something is wrong, and a chat app that hides its connection state is worse than one
 * missing features (lurker-ios#19). lurker-ios's `ConnectionBanner`.
 *
 * Reusable on purpose: the connection is the app's state, not one screen's, so the buffer list
 * carries one and the conversation (U2) carries the same one at the same position — never both on
 * screen at once (see `MainScaffold`).
 *
 * It never eats touches — the rows scroll under it — and it debounces: a state has to persist past
 * a short grace before it shows, so the ~1s connect on a normal launch doesn't flash a
 * "Connecting…" pill on every cold start, and a wifi micro-drop doesn't blink the banner.
 *
 * iOS draws it in Liquid Glass; Android has no glass, so it is a raised capsule on the surface
 * colour — the Material way of saying "floating over the content".
 */
@Composable
fun ConnectionBanner(state: ConnectionBannerState, modifier: Modifier = Modifier) {
    // Whether the banner occupies its slot. Lags `state` by the grace on the way in; follows it
    // at once on the way out.
    var shown by remember { mutableStateOf(false) }
    // What it last said, kept through the exit so the words don't vanish before the capsule does.
    var lastShown by remember { mutableStateOf(state) }
    val words = if (state != ConnectionBannerState.Hidden) state else lastShown
    SideEffect { if (state != ConnectionBannerState.Hidden) lastShown = state }

    // Keyed on "should it be up at all", not on the state: Connecting → Reconnecting while the
    // grace is running must not restart the grace (iOS keeps its one pending show), and once up
    // a new state restyles in place — `words` above — rather than going round again.
    val wanted = state != ConnectionBannerState.Hidden
    LaunchedEffect(wanted) {
        if (!wanted) {
            shown = false
        } else if (!shown) {
            delay(GRACE_MS)
            shown = true
        }
    }

    // Nudged up under the bar so it slides down into place rather than fading in flat. A fade-out
    // interrupted by a new outage reverses from where it is, which is what iOS's "pull it back
    // in" branch had to do by hand.
    val tuck = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = fadeIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { -tuck },
        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { -tuck },
    ) {
        BannerCapsule(words)
    }
}

/**
 * The capsule itself. A `Box` with a background rather than a Material `Surface`: a non-clickable
 * `Surface` still swallows pointer input, and this is a status readout, not a control — a touch
 * on it belongs to the row underneath.
 */
@Composable
private fun BannerCapsule(state: ConnectionBannerState) {
    val text = connectionWords(state)
    val colors = LurkerTheme.colors
    Row(
        modifier = Modifier
            .shadow(3.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // Announced, politely, each time the words change — iOS's `.updatesFrequently`.
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Working states spin (amber, "still trying"); offline and incompatible show a settled red
        // dot — nothing is in flight until the user brings a path back, or one side updates.
        if (state.isWorking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = colors.warn, strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(8.dp).background(colors.bad, CircleShape))
        }
        // The surface's own text colour, not the severity colour: amber/red text on the capsule
        // reads as low-contrast, and the leading dot/spinner already carries the severity. The
        // words just have to be legible.
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The banner's state as Compose state, for a screen that has nothing else to read from the store.
 * Mapped to the one value before it becomes state — see `BufferListInputs` for why a raw
 * `ChatState` must never be.
 */
@Composable
fun rememberConnectionBannerState(model: ChatViewModel): ConnectionBannerState {
    fun banner(state: ChatState) = ConnectionBannerState.of(reachable = state.reachable, connection = state.connection)
    val flow = remember(model) { model.statePublisher.map(::banner).distinctUntilChanged() }
    val initial = remember(model) { banner(model.state) }
    val state by flow.collectAsStateWithLifecycle(initialValue = initial)
    return state
}

/**
 * How long a non-hidden state must persist before the banner appears. Long enough to swallow a
 * normal launch's connect and a brief blip; short enough that a real outage is named almost at
 * once.
 */
private const val GRACE_MS = 600L

@Preview(name = "Banner — light")
@Composable
private fun ConnectionBannerPreviewLight() {
    LurkerTheme(darkTheme = false) {
        Column(Modifier.background(LurkerTheme.colors.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BannerCapsule(ConnectionBannerState.Reconnecting)
            BannerCapsule(ConnectionBannerState.Offline)
        }
    }
}

@Preview(name = "Banner — dark")
@Composable
private fun ConnectionBannerPreviewDark() {
    LurkerTheme(darkTheme = true) {
        Column(Modifier.background(LurkerTheme.colors.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BannerCapsule(ConnectionBannerState.Connecting)
            BannerCapsule(ConnectionBannerState.Incompatible(Incompatibility.ServerTooOld))
        }
    }
}
