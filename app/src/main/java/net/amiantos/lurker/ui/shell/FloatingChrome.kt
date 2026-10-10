// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerTheme

/*
 * The chrome floats over the conversation rather than boxing it in, as iOS's glass bars do: the
 * top bar draws no ground of its own, and the composer sits over the list rather than beside it,
 * so the messages scroll under both and off the edges of the screen. Android has no glass — a
 * blur needs API 31 and a render effect per frame, and the floor is API 28 — so the bars are
 * translucent instead: a scroll-edge fade under the top bar, a near-opaque fill behind the
 * composer.
 */

/** A top app bar with no ground: the fade under it ([TopEdgeFade]) is what keeps its icons legible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun transparentTopBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)

/**
 * The scroll-edge fade under a transparent top bar: the ground, solid behind the bar and fading out
 * a little below it, so a row scrolling up dissolves under the icons rather than colliding with
 * them. [barHeight] is the bar's, status bar included — the scaffold's top padding.
 */
@Composable
internal fun TopEdgeFade(barHeight: Dp, modifier: Modifier = Modifier) {
    val ground = LurkerTheme.colors.bg
    Box(
        modifier
            .fillMaxWidth()
            .height(barHeight + FADE)
            .background(
                Brush.verticalGradient(
                    0f to ground,
                    (barHeight / (barHeight + FADE)) to ground.copy(alpha = 0.9f),
                    1f to ground.copy(alpha = 0f),
                ),
            ),
    )
}

/** How far below the bar the fade runs: a short tail, so the bar ends about where an opaque one did. */
private val FADE = 8.dp

/** How much of the ground a bar over the list lets through: enough to see the rows move under it. */
internal const val CHROME_ALPHA = 0.9f
