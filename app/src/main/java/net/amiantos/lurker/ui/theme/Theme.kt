// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** The scheme's [LurkerColors]. Static: it changes only when the whole theme does. */
val LocalLurkerColors = staticCompositionLocalOf<LurkerColors> {
    error("LurkerColors read outside LurkerTheme")
}

/**
 * Lurker's theme: [LurkerColors] for the scheme the system is in, and a Material scheme derived
 * from them.
 *
 * No dynamic colour. A wallpaper-derived scheme would put a different app on every phone, and
 * the point of the palette is that Lurker looks like Lurker beside the web client.
 *
 * Typography is Material's defaults in the system font. The project's rule is one font size for
 * message text; a scale beyond Material's own is not invented here.
 */
@Composable
fun LurkerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) LurkerColors.Dark else LurkerColors.Light
    // Two palettes, two schemes, built once: `isSystemInDarkTheme` reads the configuration, so
    // this recomposes on every rotation and fold, and a 40-slot scheme is not worth rebuilding.
    val scheme = if (darkTheme) darkScheme else lightScheme
    CompositionLocalProvider(LocalLurkerColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            content = content,
        )
    }
}

/** `LurkerTheme.colors`, as `MaterialTheme.colorScheme` reads. */
object LurkerTheme {
    val colors: LurkerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLurkerColors.current
}

/**
 * Material's slots, filled from the palette. Every slot is set: one left to the builder's default
 * is Material's baseline purple or grey, which would show up in whichever component reads it.
 *
 * The palette has one accent, so primary, secondary and tertiary are all it; their containers
 * are a wash of it over [LurkerColors.bg], as `highlightBubble` is a wash of `warn`.
 *
 * ⚠ `outline` is [LurkerColors.fgMuted], not `border`. Material draws an unfocused text field's
 * edge and an outlined button's in `outline`, and `border` is 1.3:1 against `bg` in both schemes
 * — an interactive edge needs 3:1 (WCAG 1.4.11), and on e-ink it would vanish. `border` is
 * `outlineVariant`, the decorative-divider slot, which is what it draws on the web (a chip's edge).
 *
 * The surface ramp runs `bg` → `bgSoft` → `border`, which climbs in dark and darkens in light —
 * the direction each scheme's elevation goes. `surfaceTint` is the surface itself, so tonal
 * elevation adds no purple cast to Lurker's greys.
 */
private val darkScheme: ColorScheme by lazy { lurkerColorScheme(LurkerColors.Dark) }
private val lightScheme: ColorScheme by lazy { lurkerColorScheme(LurkerColors.Light) }

internal fun lurkerColorScheme(colors: LurkerColors): ColorScheme {
    val wash = colors.accent.copy(alpha = 0.24f).compositeOver(colors.bg)
    val errorWash = colors.bad.copy(alpha = 0.24f).compositeOver(colors.bg)
    // The other scheme's accent: it sits on `inverseSurface` (a snackbar's action), which is the
    // other scheme's ground.
    val inverseAccent = (if (colors.isDark) LurkerColors.Light else LurkerColors.Dark).accent
    // `copy` by name, from the matching builder, so a slot Material adds later starts from that
    // scheme's baseline rather than shifting a positional list.
    val base = if (colors.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.bg,
        primaryContainer = wash,
        onPrimaryContainer = colors.fg,
        inversePrimary = inverseAccent,
        secondary = colors.accent,
        onSecondary = colors.bg,
        secondaryContainer = wash,
        onSecondaryContainer = colors.fg,
        tertiary = colors.accent,
        onTertiary = colors.bg,
        tertiaryContainer = wash,
        onTertiaryContainer = colors.fg,
        background = colors.bg,
        onBackground = colors.fg,
        surface = colors.bgSoft,
        onSurface = colors.fg,
        surfaceVariant = colors.bgSoft,
        onSurfaceVariant = colors.fgMuted,
        surfaceTint = colors.bgSoft,
        inverseSurface = colors.fg,
        inverseOnSurface = colors.bg,
        error = colors.bad,
        onError = colors.bg,
        errorContainer = errorWash,
        onErrorContainer = colors.fg,
        outline = colors.fgMuted,
        outlineVariant = colors.border,
        scrim = Color.Black,
        surfaceBright = if (colors.isDark) colors.border else colors.bg,
        surfaceContainer = colors.bgSoft,
        surfaceContainerHigh = colors.bgSoft,
        surfaceContainerHighest = colors.border,
        surfaceContainerLow = colors.bg,
        surfaceContainerLowest = colors.bg,
        surfaceDim = if (colors.isDark) colors.bg else colors.border,
    )
}
