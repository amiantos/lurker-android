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
import kotlin.math.pow

/**
 * What a letter, a silhouette or a placeholder glyph is drawn in over a tile's colour: near-white
 * or near-black, whichever reads on it. Mirrors `avatarInk` in the web's `shared/avatarTiles.ts`.
 */
enum class AvatarInk(val rawValue: String) {
    Light("light"),
    Dark("dark");

    companion object {
        fun fromRawValue(raw: String): AvatarInk? = entries.firstOrNull { it.rawValue == raw }

        /**
         * Above this relative luminance a tile's colour takes dark ink, at or below it light. The
         * two built-in nick palettes sit either side (the dark theme's pastels start at 0.305, the
         * light theme's stop at 0.274), so each keeps the ink it was drawn for. The dark theme's
         * muted grey (#939293, 0.289), which the apps put under a server line's mark, is above it
         * too.
         */
        const val luminanceThreshold = 0.28

        /** WCAG 2 relative luminance of an sRGB colour, channels 0..1 (clamped), 0 (black) .. 1 (white). */
        fun relativeLuminance(red: Double, green: Double, blue: Double): Double {
            fun linear(channel: Double): Double {
                val c = channel.coerceIn(0.0, 1.0)
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }

        /** The ink for a tile of this sRGB colour, channels 0..1. */
        fun on(red: Double, green: Double, blue: Double): AvatarInk =
            if (relativeLuminance(red, green, blue) > luminanceThreshold) Dark else Light
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
        data class Tiles(val baseUrl: String, val count: Int = 0, val ext: String = "webp") : Kind {
            /** The directory's name under `/avatars`, which is what its manifest is read and kept by. */
            val dir: String get() = baseUrl.substringAfterLast('/')

            override fun countedBy(manifests: Map<String, AvatarSetManifest>): Tiles {
                val manifest = manifests[dir] ?: return this
                return copy(count = manifest.count, ext = manifest.ext.ifEmpty { ext })
            }
        }

        /**
         * One silhouette kept in both inks, each its own directory of tiles with its own
         * manifest: the tile's colour says which ([AvatarInk.on]).
         */
        data class Plain(val light: Tiles, val dark: Tiles) : Kind {
            override fun countedBy(manifests: Map<String, AvatarSetManifest>): Plain =
                Plain(light = light.countedBy(manifests), dark = dark.countedBy(manifests))
        }

        /** No files: the first letter of the shown name. */
        data object Initial : Kind {
            override fun countedBy(manifests: Map<String, AvatarSetManifest>): Initial = this
        }

        /** The tiles a nick on `ink` draws from, null for a set with no files. */
        fun tiles(ink: AvatarInk): Tiles? = when (this) {
            is Tiles -> this
            is Plain -> if (ink == AvatarInk.Light) light else dark
            Initial -> null
        }

        /** Every directory of tiles the set has. */
        val directories: List<Tiles>
            get() = when (this) {
                is Tiles -> listOf(this)
                is Plain -> listOf(light, dark)
                Initial -> emptyList()
            }

        /** The set's tiles, each directory counted by its own manifest where it has been read. */
        fun countedBy(manifests: Map<String, AvatarSetManifest>): Kind
    }

    /**
     * How many tiles the set draws from; a Plain set's two directories hold the same tiles, so
     * either says.
     */
    val tileCount: Int
        get() = kind.directories.maxOfOrNull { it.count } ?: 0

    /**
     * The server-relative path of `nick`'s tile in this set, or null when there is no image to
     * draw: an Initial set, a tiles set whose manifest hasn't landed, or no nick. `ink` is what
     * reads on the nick's colour — a Plain set has its silhouette in each. `stopChars` is
     * the colour's (`look.nick.color_stop_chars`), resolved once by the caller for every row.
     */
    fun tilePath(nick: String?, stopChars: Set<Int>, ink: AvatarInk): String? {
        val tiles = kind.tiles(ink) ?: return null
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
    /** The bundled tile directories whose manifests have been read, by directory name (`/avatars/<name>`). */
    val manifests: Map<String, AvatarSetManifest> = emptyMap(),
    val uploaded: List<UploadedAvatarSet> = emptyList(),
) {
    /** Every set, bundled first in the web's order, then the uploaded ones as listed. */
    val all: List<AvatarSet>
        get() {
            val bundled = builtins.map { set -> set.copy(kind = set.kind.countedBy(manifests)) }
            val mine = uploaded.map { row ->
                AvatarSet(
                    id = row.id.toString(), name = row.name,
                    kind = AvatarSet.Kind.Tiles(baseUrl = "/api/avatar-sets/${row.id}", count = row.count, ext = "webp"),
                    builtin = false,
                )
            }
            return bundled + mine
        }

    /** The set an id names, an id from before the ink was automatic included. */
    fun set(id: String): AvatarSet? {
        val current = currentSetId(id)
        return all.firstOrNull { it.id == current }
    }

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
    fun tilePath(nick: String?, settings: Settings, ink: AvatarInk): String? =
        active(settings = settings).tilePath(nick = nick, stopChars = stopChars(settings = settings), ink = ink)

    companion object {
        const val settingKey = "look.avatars.set"
        /** What the registry defaults to and where a dangling pointer falls back. */
        const val defaultSetId = "initial"
        const val ealainSetId = "ealain"

        /**
         * The sets that ship with the client, counts unknown until their manifests are read.
         * Same ids, names and order as the web.
         */
        val builtins: List<AvatarSet> = listOf(
            AvatarSet(id = ealainSetId, name = "Ealain", kind = AvatarSet.Kind.Tiles(baseUrl = "/avatars/ealain"), builtin = true),
            AvatarSet(
                id = "plain", name = "Plain",
                kind = AvatarSet.Kind.Plain(
                    light = AvatarSet.Kind.Tiles(baseUrl = "/avatars/plain-light"),
                    dark = AvatarSet.Kind.Tiles(baseUrl = "/avatars/plain-dark"),
                ),
                builtin = true,
            ),
            AvatarSet(id = "initial", name = "Initial", kind = AvatarSet.Kind.Initial, builtin = true),
        )

        val none = AvatarSets()

        /**
         * Initial and Plain each came in a light and a dark ink the user chose between, until the
         * ink followed the tile's colour. The server moves a stored pointer (its schema v28); a
         * server from before that still holds the old id, which means the set it became here.
         */
        fun currentSetId(id: String): String = when (id) {
            "initial-light", "initial-dark" -> "initial"
            "plain-light", "plain-dark" -> "plain"
            else -> id
        }

        /**
         * The id to write for a set: its own, or on a server from before schema v28 (one whose
         * registry default is still an old id) the dark-ink id the set had there. That server's
         * web client knows no `plain` or `initial` and would draw its default for either; the
         * dark ink is the one its default had. Read back, either id is the same set here
         * ([currentSetId]).
         */
        fun storedSetId(id: String, settings: Settings): String =
            storedSetId(id = id, serverDefault = settings.registry[settingKey]?.default?.stringValue)

        /** [storedSetId] for a caller already holding the registry's default for the key. */
        fun storedSetId(id: String, serverDefault: String?): String {
            if (serverDefault == null || currentSetId(serverDefault) == serverDefault) return id
            return when (id) {
                "initial" -> "initial-dark"
                "plain" -> "plain-dark"
                else -> id
            }
        }

        /**
         * The bundled directories to fetch a manifest from, by name. A Plain set has one per ink,
         * each counted by its own, as the web counts them.
         */
        val builtinManifestDirs: List<String>
            get() = builtins.flatMap { set -> set.kind.directories.map { it.dir } }

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
