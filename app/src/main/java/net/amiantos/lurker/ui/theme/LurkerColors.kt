// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.rendering.IRCPalette

/**
 * Lurker's own colours: the two built-in themes as the web client ships them — **Monokai Plus**
 * (dark) and **Monokai Plus Light** — from `shared/themePresets.ts`. Ported token for token from
 * lurker-ios's `Palette`.
 *
 * ⚠ This is the two built-ins' *values*, not a theme engine. The web client points each scheme
 * at a theme the user can edit; the app reads none of that, so a token here is always the
 * built-in. Keep them byte-identical to the presets: the same signal in every client has to be
 * the same colour, and the drift shows the moment you have two open.
 *
 * The light column is the official Monokai Pro Light filter wherever it defines a value (every
 * accent clears WCAG 3:1 on its own background). [fgMuted] deliberately takes Pro Light's
 * *semi-muted* tier rather than its comment tier — the comment gray is 2.4:1 here, and this role
 * carries timestamps and system events, which need the dark theme's ~5:1.
 *
 * Each token names both values where it is declared, dark first, as `Palette.dynamicHex` does —
 * one instance per scheme ([Dark], [Light]) rather than a colour that resolves per trait, which
 * is how Compose reads a scheme: `LurkerTheme` picks the instance and every reader gets it from
 * [LocalLurkerColors].
 *
 * Unlike iOS, where these appear in three places only and the rest is UIKit's semantic colours,
 * the Material `ColorScheme` is derived from them too (`lurkerColorScheme`): there is no native
 * Android palette that is Lurker's, and Material's baseline purple would be a third look.
 *
 * E-ink: nothing may depend on these alone. A signal they carry also reads in words or shape.
 */
@Immutable
class LurkerColors private constructor(
    /** Which column this instance reads. */
    val isDark: Boolean,
) {
    private fun pick(dark: String, light: String): Color = hexColor(if (isDark) dark else light)

    // MARK: - Core palette

    /**
     * `look.color.bg` — what the message list sits on, rather than the system's near-black or
     * pure white. A dense monospaced log on pure black is all edge, and this charcoal is what
     * the PWA has always shown on a phone, so the clients look like the same product.
     */
    val bg: Color = pick(dark = "#212022", light = "#faf4f2")

    /**
     * `look.color.fg` — text on [bg]. Not pure white / pure black: the theme's foreground is a
     * shade off each, which is what keeps the log from glaring.
     */
    val fg: Color = pick(dark = "#fcfcfa", light = "#29242a")

    /** `look.color.fg_muted` — timestamps, system events, secondary labels on [bg]. */
    val fgMuted: Color = pick(dark = "#939293", light = "#706b6e")

    /**
     * `look.color.accent` — the web's unread colour (`--buffer-unread` is `var(--accent)`) and
     * its open-row edge. On iOS it is not the app's tint, because buttons and links there stay
     * the system's; here it is also Material's `primary`, there being no system tint to defer to.
     */
    val accent: Color = pick(dark = "#a99dec", light = "#7058be")

    /**
     * `look.color.bg_soft` — a reaction chip's fill, the surface the web lifts small controls
     * onto. Its own dark value, not one derived from [bg], so the clients' chips are the same
     * colour.
     */
    val bgSoft: Color = pick(dark = "#2c2a2e", light = "#ede7e5")

    /** `look.color.border` — a reaction chip's edge. */
    val border: Color = pick(dark = "#38353b", light = "#e0dad9")

    /**
     * One tier below [fgMuted]: text that is a *hint* rather than information — the
     * start-of-history rule, a relay line's source tag. Present enough to read when looked for,
     * quiet enough not to compete with the timestamp beside it.
     *
     * The web has no token for this — these are markers and asides the mobile list has and the
     * web doesn't — so it's **derived** from [fgMuted] rather than being a third hex pair. That
     * keeps it moving with the themeable token above it instead of drifting into a colour the
     * theme never chose.
     *
     * One tier, not one per feature: a second caller is exactly when a local constant becomes a
     * token, because two hand-picked alphas is how a palette stops being one.
     */
    val fgFaint: Color = fgMuted.copy(alpha = 0.7f)

    // MARK: - Signal colours

    /**
     * `look.color.good` / `warn` / `bad`. Material's error red is close but not these, and a
     * status that disagrees between clients is worse than one that's slightly off the platform
     * palette.
     *
     * ⚠ The light column is *not* the dark hex — the dark pastels are tuned for a dark canvas
     * and collapse on a light one (`#b3db82` is 1.6:1 on white, `#f9d978` is 1.4:1). Using one
     * value for both is what made the connection spinner and the title-bar light vanish in light
     * mode on iOS.
     */
    val good: Color = pick(dark = "#b3db82", light = "#269d69")

    /** See [good]. */
    val warn: Color = pick(dark = "#f9d978", light = "#cc7a0a")

    /** See [good]. Also Material's `error`. */
    val bad: Color = pick(dark = "#ed6c89", light = "#e14775")

    /** The colour for a status light. Never the only carrier: the subtitle says it in words. */
    fun color(light: StatusLight): Color =
        when (light) {
            StatusLight.Good -> good
            StatusLight.Warn -> warn
            StatusLight.Bad -> bad
        }

    // MARK: - Member mode prefixes

    /**
     * `look.color.member.*` — the ~ & @ % + glyphs in the member list. Same role pairing in both
     * schemes (owner = red, admin = orange, op = the accent purple, half-op = cyan, voice =
     * green), so a glance reads the same rank whichever way the phone is set.
     *
     * Only the *glyph* wears these, never the nick — the nick keeps its own hashed colour, which
     * is how the web draws it too. The glyph itself is the rank; the colour only repeats it.
     */
    val memberOwner: Color = pick(dark = "#ed6c89", light = "#e14775")

    /** See [memberOwner]. */
    val memberAdmin: Color = pick(dark = "#fc9867", light = "#e16032")

    /** See [memberOwner]. The accent, as on the web. */
    val memberOp: Color = accent

    /** See [memberOwner]. */
    val memberHalfop: Color = pick(dark = "#78dce8", light = "#1c8ca8")

    /** See [memberOwner]. */
    val memberVoice: Color = pick(dark = "#b3db82", light = "#269d69")

    /**
     * The colour for a `MemberPrefix.of` glyph, or null for a member holding no mode — the caller
     * leaves those in the ordinary text colour rather than inventing a sixth rank.
     */
    fun memberPrefix(glyph: String): Color? =
        when (glyph) {
            "~" -> memberOwner
            "&" -> memberAdmin
            "@" -> memberOp
            "%" -> memberHalfop
            "+" -> memberVoice
            else -> null
        }

    // MARK: - Derived

    /**
     * The fill behind a line a highlight rule matched. A warm wash of [warn] — the same gold the
     * web tints `.line.highlight` with — rather than a solid fill, so the sender's mIRC colours
     * and in-body nick colours still read over it. The web uses 12% (18% on its alt-striped
     * rows); the app's list has no striping, so it sits between at one value.
     *
     * Taken from this scheme's own [warn]: the two schemes' golds are different hues, not one hue
     * at two brightnesses, so the alpha has to land on whichever gold the scheme has.
     */
    val highlightBubble: Color = warn.copy(alpha = 0.16f)

    // MARK: - IRC colour tables

    /**
     * Per-nick colours, indexed by `NickColor.index` — the kit's `IRCPalette.nick` in dark and
     * `nickLight` in light, same order, so a nick is the same hue in both and on every client.
     */
    val nick: List<Color> = (if (isDark) IRCPalette.nick else IRCPalette.nickLight).map(::hexColor)

    /**
     * mIRC colours 0–15 — `IRCPalette.mirc` / `mircLight`. ⚠ Every slot is a literal colour and
     * none may become a theme reference: `^C00` means white, not "this theme's text", and a run
     * can carry its own background (see `IRCPalette.mirc`).
     */
    val mirc: List<Color> = (if (isDark) IRCPalette.mirc else IRCPalette.mircLight).map(::hexColor)

    companion object {
        /** Monokai Plus. */
        val Dark = LurkerColors(isDark = true)

        /** Monokai Plus Light. */
        val Light = LurkerColors(isDark = false)
    }
}
