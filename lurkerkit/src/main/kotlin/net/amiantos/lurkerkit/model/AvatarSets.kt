// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.asObjects
import net.amiantos.lurkerkit.client.int
import net.amiantos.lurkerkit.client.intOrNull
import net.amiantos.lurkerkit.client.string
import net.amiantos.lurkerkit.rendering.AvatarTiles
import kotlin.math.max

/** The ink an Initial set draws its letter in, and a placeholder tile draws its glyph in. */
enum class AvatarInk(val rawValue: String) {
    Light("light"),
    Dark("dark");

    companion object {
        fun fromRawValue(raw: String): AvatarInk? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One set generated avatars can draw from (lurker#607): a bundled one, or one the user uploaded
 * on the web. Mirrors `shared/avatarTiles.ts`'s `BUILTIN_AVATAR_SETS` plus the rows of
 * `GET /api/avatar-sets`.
 */
data class AvatarSet(
    /**
     * A bundled id, or an uploaded set's numeric id as a decimal string — what
     * `look.avatars.set` holds.
     */
    val id: String,
    val name: String,
    val kind: Kind,
    val builtin: Boolean,
) {
    sealed interface Kind {
        /**
         * Static image files under `baseUrl` (server-relative), numbered `000.<ext>` up to
         * `count`. Zero tiles means the manifest hasn't been read yet: draw colour only.
         */
        data class Tiles(val baseUrl: String, val count: Int, val ext: String) : Kind

        /** No files: the first letter of the shown name, in `ink`. */
        data class Initial(val ink: AvatarInk) : Kind
    }

    /**
     * The ink for a letter or a placeholder glyph on this set's tiles: an Initial set's own,
     * light for a tiles set — the web's `NickAvatar` makes the same choice.
     */
    val ink: AvatarInk
        get() = (kind as? Kind.Initial)?.ink ?: AvatarInk.Light

    val tileCount: Int
        get() = (kind as? Kind.Tiles)?.count ?: 0

    /**
     * The server-relative path of `nick`'s tile in this set, or null when there is no image to
     * draw: an Initial set, a tiles set whose manifest hasn't landed, or no nick. `stopChars` is
     * the colour's (`look.nick.color_stop_chars`), resolved once by the caller for every row.
     */
    fun tilePath(nick: String?, stopChars: Set<Int>): String? {
        val tiles = kind as? Kind.Tiles ?: return null
        val index = AvatarTiles.index(nick = nick, count = tiles.count, stopChars = stopChars) ?: return null
        return AvatarTiles.url(base = tiles.baseUrl, index = index, ext = tiles.ext)
    }
}

/** An uploaded set as `GET /api/avatar-sets` lists it. */
data class UploadedAvatarSet(
    val id: Int,
    val name: String,
    val count: Int,
)

/** A bundled tiles set's `manifest.json`. */
data class AvatarSetManifest(
    val count: Int,
    val ext: String = "webp",
)

/**
 * Which sets exist and which one `look.avatars.set` points at (lurker#607, Mobile lurker#1203).
 *
 * REST-fed rather than frame-fed, so it lives beside the view model, not in `ChatState`: the
 * bundled sets' tile counts come from each set's `manifest.json`, the uploaded sets from
 * `GET /api/avatar-sets` — on connect and again on `avatar-sets-changed`. The setting itself is
 * in `ChatState.settings` like every other key; `active(settings)` resolves the pointer.
 */
data class AvatarSets(
    /** The bundled tiles sets whose manifests have been read, by id. */
    val manifests: Map<String, AvatarSetManifest> = emptyMap(),
    val uploaded: List<UploadedAvatarSet> = emptyList(),
) {
    /** Every set, bundled first in the web's order, then the uploaded ones as listed. */
    val all: List<AvatarSet>
        get() {
            val bundled = builtins.map { set ->
                val tiles = set.kind as? AvatarSet.Kind.Tiles ?: return@map set
                val manifest = manifests[set.id] ?: return@map set
                AvatarSet(
                    id = set.id, name = set.name,
                    kind = AvatarSet.Kind.Tiles(
                        baseUrl = tiles.baseUrl, count = manifest.count,
                        ext = if (manifest.ext.isEmpty()) tiles.ext else manifest.ext,
                    ),
                    builtin = true,
                )
            }
            val mine = uploaded.map { row ->
                AvatarSet(
                    id = row.id.toString(), name = row.name,
                    kind = AvatarSet.Kind.Tiles(baseUrl = "/api/avatar-sets/${row.id}", count = row.count, ext = "webp"),
                    builtin = false,
                )
            }
            return bundled + mine
        }

    fun set(id: String): AvatarSet? = all.firstOrNull { it.id == id }

    /**
     * The set `look.avatars.set` points at, or the default when it names nothing we know: an
     * uploaded set not fetched yet, one deleted from another device, or an id from a newer
     * client. The web's `activeSetId` resolves the same way.
     */
    fun active(settings: Settings): AvatarSet {
        val pointer = settings.string(settingKey, default = defaultSetId)
        return set(id = pointer) ?: set(id = defaultSetId) ?: builtins[builtins.size - 1]
    }

    /**
     * `nick`'s tile in the active set — `AvatarSet.tilePath` after `active(settings)`. For one
     * lookup; a list resolves the set once and asks it per row.
     */
    fun tilePath(nick: String?, settings: Settings): String? =
        active(settings = settings).tilePath(nick = nick, stopChars = stopChars(settings = settings))

    companion object {
        const val settingKey = "look.avatars.set"
        /** What the registry defaults to and where a dangling pointer falls back. */
        const val defaultSetId = "initial-dark"
        const val ealainSetId = "ealain"

        /**
         * The sets that ship with the client, counts unknown until their manifests are read.
         * Same ids, names and order as the web.
         */
        val builtins: List<AvatarSet> = listOf(
            AvatarSet(id = ealainSetId, name = "Ealain", kind = AvatarSet.Kind.Tiles(baseUrl = "/avatars/ealain", count = 0, ext = "webp"), builtin = true),
            AvatarSet(id = "plain-light", name = "Plain (Light)", kind = AvatarSet.Kind.Tiles(baseUrl = "/avatars/plain-light", count = 0, ext = "webp"), builtin = true),
            AvatarSet(id = "plain-dark", name = "Plain (Dark)", kind = AvatarSet.Kind.Tiles(baseUrl = "/avatars/plain-dark", count = 0, ext = "webp"), builtin = true),
            AvatarSet(id = "initial-light", name = "Initial (Light)", kind = AvatarSet.Kind.Initial(ink = AvatarInk.Light), builtin = true),
            AvatarSet(id = "initial-dark", name = "Initial (Dark)", kind = AvatarSet.Kind.Initial(ink = AvatarInk.Dark), builtin = true),
        )

        val none = AvatarSets()

        /** The bundled ids that have files to fetch a manifest for. */
        val builtinTileSetIds: List<String>
            get() = builtins.mapNotNull { set -> if (set.kind is AvatarSet.Kind.Tiles) set.id else null }

        /** The colour's stop characters as the tile hash wants them (`look.nick.color_stop_chars`). */
        fun stopChars(settings: Settings): Set<Int> =
            AvatarTiles.stopChars(settings.string("look.nick.color_stop_chars", default = "_|"))

        // MARK: - Decoding

        /**
         * `GET /api/avatar-sets` → `{items:[{id, name, count, bytes, createdAt}]}`. Null for a body
         * that isn't that shape, so a caller keeps what it had rather than reading garbage as
         * "no sets".
         *
         * Port note: takes the body as text where LurkerKit takes `Data` — the client reads bodies
         * with `utf8OrNull()` — and decodes through `FrameParser.jsonObject`, the same guarded path
         * a frame takes (never throws, nesting bounded), where LurkerKit calls
         * `JSONSerialization` directly.
         */
        fun parseList(text: String): List<UploadedAvatarSet>? {
            val obj = FrameParser.jsonObject(text) ?: return null
            val items = obj["items"]?.asObjects() ?: return null
            return items.mapNotNull { item ->
                val id = item.intOrNull("id") ?: return@mapNotNull null
                val name = item.string("name")
                if (name.isEmpty()) return@mapNotNull null
                UploadedAvatarSet(id = id, name = name, count = max(0, item.int("count")))
            }
        }

        /**
         * A bundled set's `manifest.json` → `{count, ext}`. Null unless `count` is a positive
         * integer; `ext` defaults to webp, as the web's `ensureBuiltins` reads it.
         *
         * Port note: text in, as `parseList`.
         */
        fun parseManifest(text: String): AvatarSetManifest? {
            val obj = FrameParser.jsonObject(text) ?: return null
            val count = obj.intOrNull("count") ?: return null
            if (count <= 0) return null
            val ext = obj.string("ext")
            return AvatarSetManifest(count = count, ext = if (ext.isEmpty()) "webp" else ext)
        }
    }
}
