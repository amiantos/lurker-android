// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.amiantos.lurker.ui.media.DecodeSize
import net.amiantos.lurker.ui.media.MediaSource
import net.amiantos.lurker.ui.media.PreviewImageLoader
import net.amiantos.lurker.ui.media.rememberPreviewStill
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurkerkit.model.AvatarInk
import net.amiantos.lurkerkit.model.AvatarSet
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.store.ChatState

/** The placeholder glyph for a head that isn't a person's. */
enum class AvatarMark {
    /** `-nick-`: a notice, the bot talking at the room. */
    Notice,

    /** The server itself, or the app: a system line, raw server text. */
    Server,
}

/**
 * Everything one tile needs, resolved from the store once per row — lurker-ios's `NickAvatarView.Spec`
 * (lurker#607, Mobile lurker#1203), the web's `NickAvatar.vue` props.
 *
 * A square in the nick's colour. On it, in this order of preference: the picture the user set through
 * IRCv3 metadata, as the server minted it; else one image from the active tile set, picked by
 * `AvatarTiles.index` so every client draws the same one; else, for an Initial set, the first letter of
 * the shown name. A notice's head and a server line's head get a glyph over the colour instead of a
 * face — a megaphone, a rack — as the web gives them.
 */
data class AvatarSpec(
    /** What the tile stands for — for TalkBack, and the hash behind [tilePath]. */
    val nick: String?,
    val color: Color,
    /** The metadata avatar's server-minted path, or null for the generated tile. */
    val imagePath: String?,
    /** The active set's tile for this nick, or null for an Initial set or an unread manifest. */
    val tilePath: String?,
    /** The letter an Initial set draws, upper-cased. */
    val letter: String?,
    val ink: AvatarInk,
    val mark: AvatarMark?,
) {
    /** Whether a tap on the tile is about a person. A placeholder is decoration: a tap falls through to the row. */
    val isPerson: Boolean get() = mark == null && !nick.isNullOrEmpty()

    companion object {
        /**
         * What [message]'s head draws. A re-attributed relay line (`relayBot != null`) gets no metadata —
         * not the avatar, not the display name's initial: the bridged speaker isn't a network nick, and a
         * record under that spelling would be someone else's. Its generated tile still hashes the shown
         * name, as the web's does.
         *
         * [active] and [stopChars] are resolved once per list by the caller (`AvatarSets.active`,
         * `AvatarSets.stopChars`), not per row: the resolution walks every set.
         */
        fun of(
            message: Message,
            networkId: Int?,
            state: ChatState,
            active: AvatarSet,
            stopChars: Set<Int>,
            networkName: String?,
            style: MessageTextStyle,
        ): AvatarSpec {
            val nick: String?
            val color: Color
            val mark: AvatarMark?
            // The one classification the head uses (`MessageListLayout.headKind`), so the name and
            // the tile can't disagree about who a line is from.
            when (MessageListLayout.headKind(message)) {
                MessageListLayout.HeadKind.Notice -> {
                    nick = message.nick
                    color = MessageText.nickColor(message.nick, message.isSelf, style)
                    mark = AvatarMark.Notice
                }
                MessageListLayout.HeadKind.Server -> {
                    nick = networkName
                    color = MessageText.captionColor(message, networkName, style)
                    mark = AvatarMark.Server
                }
                MessageListLayout.HeadKind.Person, MessageListLayout.HeadKind.None -> {
                    nick = message.nick
                    color = MessageText.nickColor(message.nick, message.isSelf, style)
                    mark = null
                }
            }
            val relayed = message.relayBot != null
            val meta = if (relayed || mark != null) null else state.metadata(networkId = networkId, nick = nick)
            var letter: String? = null
            if (mark == null && active.kind is AvatarSet.Kind.Initial) {
                val shown = if (relayed) null else state.plainDisplayName(networkId = networkId, nick = nick)
                // The first code point, as the web's `Array.from(name)[0]`, upper-cased.
                letter = (shown ?: nick)?.takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))).uppercase() }
            }
            return AvatarSpec(
                nick = nick,
                color = color,
                imagePath = meta?.avatarUrl,
                tilePath = if (mark == null) active.tilePath(nick, stopChars) else null,
                letter = letter,
                ink = active.ink,
                mark = mark,
            )
        }
    }
}

/** The avatar column's measurements — the web's `--avatar-size`, `--space-4` and `--space-5`. */
object AvatarColumn {
    /** The web's `--space-4` between the tile and the hairline. The tile itself sits at the row's side margin. */
    val gutterPadding: Dp = 8.dp

    /** The web's `--space-5` after the hairline, before the text. */
    val gutterAfterLine: Dp = 10.dp

    /** How far the text column sits in from the side margin while avatars are on: the tile, the gap, the hairline, the gap. */
    fun gutter(size: Dp): Dp = size + gutterPadding + gutterAfterLine

    /** Where the hairline runs, from the side margin. */
    fun hairline(size: Dp): Dp = size + gutterPadding

    /** The web's two inks: `rgba(255,255,255,0.85)` and `rgba(28,26,30,0.85)`. */
    fun ink(ink: AvatarInk): Color =
        when (ink) {
            AvatarInk.Light -> Color(1f, 1f, 1f, 0.85f)
            AvatarInk.Dark -> Color(28 / 255f, 26 / 255f, 30 / 255f, 0.85f)
        }
}

/**
 * The tile's side: the web's `min(2.25em, 2lh)` at phone width and `min(2.7em, 2lh)` wider, in the
 * compact face's units — so a tight line height (lurker#1186) shrinks the tile rather than letting it
 * outgrow the head and first body line it is centred on. In em of the font size, never N × a converted
 * character (the Android 14 nonlinear scaling rule), and whole dp so the hairline lands on the grid.
 */
@Composable
fun rememberAvatarSize(style: MessageTextStyle): Dp {
    val density = LocalDensity.current
    // The window's own width, not the device's: a split pane on a tablet is the narrow case too.
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    return remember(style, density, wide) {
        with(density) {
            val em = if (wide) 2.7f else 2.25f
            val twoLines = 2 * style.fontSizeSp * style.density.lineHeightPercent / 100f
            minOf(em * style.fontSizeSp, twoLines).sp.toDp().value.toInt().dp
        }
    }
}

/**
 * The tile beside an author in the compact layout — lurker-ios's `NickAvatarView`.
 *
 * Images come through [PreviewImageLoader]: a metadata avatar is a proxied fetch with the bearer token,
 * a set tile a static file, and both are cached and decoded the way preview pictures are, cropped to
 * the square. A metadata image that fails is latched by the loader and never arrives, so the tile
 * beneath it simply stays — the web's `@error` fallback without a second code path. An avatar already
 * decoded is the whole answer and no tile is asked for; on a first paint both are asked for, the tile
 * so the square isn't blank while the avatar loads (the web shows nothing until it does), at the cost
 * of one small static fetch per author with an avatar until the cache holds it.
 */
@Composable
fun NickAvatarTile(spec: AvatarSpec, size: Dp, media: MediaSource, onTap: (() -> Unit)?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
    val box = IntSize(px, px)
    val avatarCached = spec.imagePath?.let { PreviewImageLoader.anyCached(it, DecodeSize.Mode.Fill) }
    val avatar = rememberPreviewStill(spec.imagePath, media, box, DecodeSize.Mode.Fill)
    val tile = rememberPreviewStill(if (avatarCached != null) null else spec.tilePath, media, box, DecodeSize.Mode.Fill)
    val picture = avatar.value ?: tile.value
    val ink = AvatarColumn.ink(spec.ink)
    val description = spec.nick ?: ""
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(spec.color)
            .then(
                if (onTap != null) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "show profile", onClick = onTap)
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Button
                        }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            picture != null -> Image(picture.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            spec.mark != null -> Icon(
                if (spec.mark == AvatarMark.Notice) LurkerIcons.Campaign else LurkerIcons.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = ink,
            )
            // Half the tile, 600 weight, as the web's `.initial` has it.
            spec.letter != null -> Text(
                spec.letter,
                color = ink,
                fontSize = with(density) { (size * 0.5f).toSp() },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
