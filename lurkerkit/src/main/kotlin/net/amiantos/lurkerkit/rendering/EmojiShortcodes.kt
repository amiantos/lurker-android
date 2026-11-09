// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.rendering

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.substring
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.unicodeRegex

/**
 * Slack-style `:shortcode:` emoji (lurker#1294) — the web's `emojiShortcodes.ts` and
 * `emojiData.ts` in one: the gemoji table and the grammar that finds a shortcode in text.
 *
 * The web turns `:skull:` into 💀 twice, and so do the apps: in the composer on the closing
 * colon ([completed]), and at render time for anything that arrives as a literal shortcode from
 * a client that doesn't ([matches], applied by the body renderer). `/react` and the reaction
 * sheet's field take a name too ([reaction]). All three read the same table, so the three
 * clients resolve the same set.
 *
 * A shortcode is `:name:` where `name` is one or more of `[a-z0-9_+-]` — the gemoji character
 * set (`:+1:`, `:e-mail:`, `:thumbsup:`). Matching is case-insensitive: a typed `:Bone:`
 * resolves straight against the (lowercase) table. The opening `:` must not sit right behind
 * another shortcode character, so a clock like `12:00` or `word:bone:` never reads as a
 * shortcode start, and ASCII smileys fall out for free: `:-)` never closes because `)` isn't a
 * shortcode character. An unknown name stays literal.
 *
 * ⚠ `emoji.json` beside `builtinNetworks.json` is a **copy** of the web's table: `{name: glyph}`,
 * derived from `vue_client/src/utils/emojiData.ts` in the lurker repo, which
 * `tools/gen-emoji-data.mjs` there generates from github/gemoji `db/emoji.json` (pinned at
 * revision 0eca75db9301421efc8710baf7a7576793ae452a — 1870 emoji, 1913 aliases). Byte for byte
 * LurkerKit's `Resources/emoji.json`. Regenerate it from that file when the web's moves, so the
 * three clients keep resolving the same names. The emoji data is gemoji's, distributed under
 * the MIT License (Copyright (c) 2019 GitHub, Inc.).
 */
object EmojiShortcodes {

    /**
     * One resolvable shortcode in a string: where it sits, in UTF-16 units, and the glyph it
     * stands for.
     */
    data class Match(val range: TextRange, val glyph: String)

    /**
     * The gemoji shortcode character set. Defined once so the body pattern and the
     * opening-boundary lookbehind can't drift apart — the web's `NAME_CHARS`.
     */
    private const val NAME_CHARS = "[a-z0-9_+-]"

    /**
     * Every completed `:name:` whose opening colon isn't glued to a preceding shortcode
     * character — the web's `shortcodeScanRegex`.
     */
    private val scan = unicodeRegex("(?<!$NAME_CHARS):($NAME_CHARS+):", ignoreCase = true)

    /**
     * A completed `:name:` ending exactly at the end of the string — the web's `CLOSE_RE`, run on
     * the text up to the caret.
     */
    private val closing = unicodeRegex("(?<!$NAME_CHARS):($NAME_CHARS+):$", ignoreCase = true)

    /**
     * A whole typed reaction that is one shortcode, closing colon optional — the web's
     * `reactionFromInput`. Nothing may follow it, so `:tada` is unambiguous.
     */
    private val whole = unicodeRegex("^:($NAME_CHARS+):?$", ignoreCase = true)

    private const val resource = "net/amiantos/lurkerkit/emoji.json"

    /**
     * Shortcode body (no colons, lowercase) → glyph. Read once from the classpath; a resource
     * that doesn't reach it leaves an empty table, and every shortcode stays literal — which
     * `testTheTableLoadsFromTheBundle` is there to catch.
     *
     * Port note: `Bundle.module` in LurkerKit; here a classpath resource read through the class
     * loader, as `BuiltinNetworks` does.
     */
    internal val table: Map<String, String> by lazy {
        val text = EmojiShortcodes::class.java.classLoader?.getResourceAsStream(resource)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@lazy emptyMap()
        try {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

    /**
     * The glyph for a shortcode body (case-insensitive), or null when it isn't a known emoji —
     * the web's `emojiForShortcode`.
     */
    fun glyph(name: String): String? = table[name.lowercase()]

    /**
     * Every resolvable `:name:` in [text], in order, as the render pass replaces them. An unknown
     * name is left out (it stays literal), and so is a match touching any of [protecting] — the
     * caller's spoilers and links, which the web never splits for emoji either: a glyph inside a
     * spoiler would be a visible shape in a box of invisible ones, and a URL's path is text, not
     * a shortcode.
     */
    fun matches(text: String, protecting: List<TextRange> = emptyList()): List<Match> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Match>()
        for (found in scan.findAll(text)) {
            val glyph = glyph(found.groupValues[1]) ?: continue
            val range = TextRange(found.range.first, found.range.last + 1)
            if (protecting.any { it.start < range.end && range.start < it.end }) continue
            out.add(Match(range, glyph))
        }
        return out
    }

    /**
     * The composer's moment: the user just typed the closing `:` of a known `:name:`, so the
     * character before [caret] ends a shortcode. The match to swap for its glyph, or null —
     * including for an unknown name, which stays as typed. The web's `findCompletedShortcode`
     * and the lookup that follows it.
     */
    fun completed(text: String, caret: Int): Match? {
        if (caret <= 0 || caret > text.length) return null
        val found = closing.find(text.substring(0, caret)) ?: return null
        val glyph = glyph(found.groupValues[1]) ?: return null
        return Match(TextRange(found.range.first, found.range.last + 1), glyph)
    }

    /**
     * A reaction typed as text — `/react`'s argument, the sheet's field: a whole `:name:` or
     * `:name` that the table knows becomes its glyph; anything else, an unknown name included,
     * goes out as typed (trimmed). The web's `reactionFromInput`. `:D` and `:-)` are reactions
     * people type on purpose: `d` is no emoji's name, and `)` is no name character.
     */
    fun reaction(raw: String): String {
        val text = raw.trimmingWhitespacesAndNewlines()
        val found = whole.find(text) ?: return text
        return glyph(found.groupValues[1]) ?: text
    }

    /**
     * [text] with [match] swapped for its glyph — the composer's edit, and a test's. The render
     * pass goes through its own body type instead, whose marks have to move with the text.
     */
    fun replaced(text: String, match: Match): String =
        text.substring(0, match.range.start) + match.glyph + text.substring(match.range.end)

    /**
     * Where a span boundary lands once [range] has been swapped for something [replacementLength]
     * units long — the rule a body's marks follow when a `:name:` becomes its glyph, pinned here
     * where it can be tested on the host.
     *
     * A boundary before the swap stays; one after it moves by the difference; one INSIDE it goes
     * to the end of the glyph — so a span that began before the shortcode and ended inside it
     * takes the glyph in (a bold `:tada:` is a bold 🎉, iOS's "the glyph takes the attributes of
     * the first character"), a span that began inside and ran on starts after the glyph, and a
     * span wholly inside (a nick coloured inside the name) collapses to nothing.
     */
    fun moved(position: Int, range: TextRange, replacementLength: Int): Int = when {
        position <= range.start -> position
        position >= range.end -> position - range.length + replacementLength
        else -> range.start + replacementLength
    }
}
