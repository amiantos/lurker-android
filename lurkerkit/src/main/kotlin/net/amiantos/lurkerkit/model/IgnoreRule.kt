// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * One irssi-style ignore rule, as the server stores and ships it (lurker #301).
 *
 * A rule AND-s together the optional dimensions it carries — **who** (`mask`), **where**
 * (`channels`), **what** (`pattern`), **which** (`levels`) — so an unset dimension matches
 * everything. `isExcept` inverts the whole thing into a whitelist entry (longest mask wins),
 * and `expiresAt` lapses it.
 *
 * The rules themselves are server-authoritative: `/ignore` here (lurker-ios#86) and on the web
 * both *ask*, and the list only changes when the server fans `ignore-list-updated` back.
 * Nothing on this client mutates a rule locally, which is what makes a rule made anywhere
 * apply everywhere.
 */
data class IgnoreRule(
    /**
     * The server's row id, and how `/unignore <n>` addresses a rule (lurker-ios#86): the listed
     * index resolves to this. Zero for a rule this client has just parsed off a command line
     * and not yet sent — identity is the server's to assign, and it arrives on the echo.
     */
    val id: Int = 0,
    /**
     * Who this rule is about: null or `*` means anyone, a bare token is a nick glob, and a
     * `nick!user@host` form globs each part. See `IgnoreMatch.maskMatcher`.
     */
    val mask: String? = null,
    /**
     * Which buffers it applies in. Null/empty means every buffer on the rule's network(s);
     * entries are globs matched case-insensitively against the target.
     */
    val channels: List<String>? = null,
    /** A content pattern the message body must match, or null for "any body". */
    val pattern: String? = null,
    val patternKind: IgnorePatternKind = IgnorePatternKind.Substr,
    /**
     * Canonical level tokens (`ALL`, `PUBLIC`, `JOINS`, `NOHIGHLIGHT`, …). The server
     * canonicalizes aliases before storing, so these arrive in `IgnoreLevels`' vocabulary and
     * this client never has to parse irssi's singular/plural spellings.
     */
    val levels: List<String> = listOf("ALL"),
    /**
     * Inverts the rule into a whitelist entry: a matching `-except` with a *longer* mask
     * beats the hide/mute it would otherwise take.
     */
    val isExcept: Boolean = false,
    /**
     * When the rule lapses, parsed at the wire boundary. A lapsed rule never matches — the
     * server sweeps expired rows every minute, but expiry is honored here too so a rule stops
     * biting the instant it runs out rather than up to a minute later.
     */
    val expiresAt: Instant? = null,
) {
    /**
     * One line naming every dimension the rule constrains — `*zzz*  [global]  NICKS  #chan
     * "spam"  [except]  (expires Today at 4:15 PM)` — for the `/ignore` listing and the
     * confirmations `/ignore`/`/unignore` print (lurker-ios#86).
     *
     * `global` is the rule's *scope*, which isn't on the rule: the server keeps globals and
     * per-network rules in separate buckets and the row is identical in both, so the caller
     * (which knows which bucket it read) supplies it. See `IgnoreSet.listing`.
     *
     * Mirrors the web's `summarizeIgnoreEntry` field for field, so the same rule reads the
     * same on both clients — except the expiry, which is a wall-clock stamp for a person to
     * read rather than the web's raw ISO string.
     *
     * Port note: takes the expiry's formatter, where LurkerKit formats it itself with a private
     * `DateFormatter` (`ExpiryText`: short date, short time, relative day names, the current
     * locale). The words are the kit's; how a date reads is the app's — the same ruling as
     * `ChannelModeState.topicSetterLine`, and for the same reason: a plain JVM module cannot
     * see the device's 24-hour setting. What `formatted` should hand back is what `ExpiryText`
     * did — when the rule lapses, as a person reads it: local time, short, and relative where
     * the locale has a word for the day ("Today at 4:15 PM"). LurkerKit builds that formatter
     * once rather than per call — one is expensive to construct, and a rule listing formats one
     * per line — and gives it the auto-updating locale, because a formatter that lives that long
     * outlives a region change; the app's wants the same care. It is called only for a rule
     * that carries an `expiresAt`.
     */
    fun summary(global: Boolean, now: Instant = Instant.now(), formatted: (Instant) -> String): String {
        val parts = mutableListOf(mask ?: "*")
        if (global) parts.add("[global]")
        if (levels.isNotEmpty()) parts.add(levels.joinToString(","))
        if (channels != null && channels.isNotEmpty()) parts.add(channels.joinToString(","))
        if (pattern != null && pattern.isNotEmpty()) {
            parts.add(if (patternKind == IgnorePatternKind.Regex) "/$pattern/" else "\"$pattern\"")
        }
        if (isExcept) parts.add("[except]")
        if (expiresAt != null) {
            // Past tense when it has already run out. A lapsed rule is still a row on the
            // server until its sweeper gets to it — it keeps its place in the listing (see
            // `IgnoreSet.listing`) — but it has stopped hiding anything, and "expires" in the
            // past reads as a rule that's still working.
            val lapsed = expiresAt <= now
            parts.add("(${if (lapsed) "expired" else "expires"} ${formatted(expiresAt)})")
        }
        return parts.joinToString("  ")
    }
}

/**
 * How a rule's `pattern` is matched against a message body. Mirrors the server's
 * `patternKindToTextKind`, including its fallback: anything unrecognized is a substring
 * match, which is the least surprising reading of a pattern we can't classify.
 */
enum class IgnorePatternKind(val rawValue: String) {
    /** Case-insensitive substring — irssi's default `-pattern`. */
    Substr("substr"),

    /** Whole-word match of a literal (word-boundary anchored). */
    Full("full"),

    /** A raw regular expression. */
    Regex("regex");

    companion object {
        fun fromRawValue(raw: String): IgnorePatternKind? = entries.firstOrNull { it.rawValue == raw }

        fun from(raw: String?): IgnorePatternKind {
            if (raw == null) return Substr
            if (raw == "plain") return Full // the highlight engine's alias for the same thing
            return fromRawValue(raw) ?: Substr
        }
    }
}

/**
 * The ignore-level vocabulary (lurker #301), ported from `shared/ignoreLevels.ts`.
 *
 * Two halves, and until lurker-ios#86 this client only needed one. **Matching** needs the event
 * types each canonical token covers — rules arrive off the wire already canonicalized, so the
 * matcher never sees an alias. **Authoring** needs the rest: `/ignore bob nohilight` is a
 * person typing irssi's spelling, and the command line is parsed here now, so the alias table
 * and the canonical order have to be here too. The server canonicalizes again on insert; that
 * they agree is what keeps the stored CSV identical whichever client wrote the rule.
 */
object IgnoreLevels {

    /** What one level token covers. Port note: a named tuple in LurkerKit, `(types:dm:)`. */
    internal data class Def(val types: Set<EventType>, val dm: Boolean?)

    /**
     * Level token → the event types it covers. `PUBLIC` and `MSGS` split `message` by
     * channel-vs-DM, which is what `dm` disambiguates; every other level ignores it.
     *
     * `ALL` and the modifier levels are absent because they aren't event-type tokens — the
     * matcher handles them. `CTCPS` is accepted and maps to nothing: Lurker never persists
     * CTCP as a type, a documented no-op the server carries too.
     */
    internal val defs: Map<String, Def> = mapOf(
        "PUBLIC" to Def(setOf(EventType.Message), false),
        "MSGS" to Def(setOf(EventType.Message), true),
        "NOTICES" to Def(setOf(EventType.Notice), null),
        "ACTIONS" to Def(setOf(EventType.Action), null),
        "JOINS" to Def(setOf(EventType.Join), null),
        "PARTS" to Def(setOf(EventType.Part), null),
        // Host changes ride with QUITS rather than getting their own level: a chghost IS what
        // a client without the cap would have shown as a quit/rejoin pair, so someone who
        // silenced a nick's quits already expects these gone too (lurker #591).
        "QUITS" to Def(setOf(EventType.Quit, EventType.Chghost), null),
        "NICKS" to Def(setOf(EventType.Nick), null),
        "KICKS" to Def(setOf(EventType.Kick), null),
        "MODES" to Def(setOf(EventType.Mode), null),
        "TOPICS" to Def(setOf(EventType.Topic), null),
        "CTCPS" to Def(emptySet(), null),
    )

    /**
     * What an `ALL` rule covers — everything with a sender to ignore, so the system/self
     * rows (motd, error, usermode, names, the app's own system lines) are deliberately out.
     *
     * Listed literally rather than derived as the union of `defs`, which today it happens to
     * equal. The two answer different questions and are free to diverge: `defs` maps the
     * tokens a user can *name*, and `CTCPS` is already in it mapping to nothing. Deriving
     * would silently couple "what ALL means" to that vocabulary. `shared/ignoreLevels.ts`
     * spells out its `ALL_TYPES` for the same reason, so the two stay comparable by eye.
     */
    internal val all: Set<EventType> = setOf(
        EventType.Message, EventType.Action, EventType.Notice, EventType.Join, EventType.Part,
        EventType.Quit, EventType.Nick, EventType.Kick, EventType.Mode, EventType.Topic,
        EventType.Chghost,
    )

    /** The types a highlight can land on, and therefore the only ones `NOHIGHLIGHT` bounds. */
    internal val highlightable: Set<EventType> = setOf(EventType.Message, EventType.Action)

    /**
     * The "modifier" levels: they don't name a type to hide, they change how a still-visible
     * message is treated. Filtered out of the hide-level set so a modifier-only rule keeps
     * `hides` false (lurker #301 for NOHIGHLIGHT, lurker#359 for the two mute rungs).
     */
    internal val modifiers: Set<String> = setOf("NOHIGHLIGHT", "NOUNREAD", "NONOTIFY")

    /**
     * What an `ALL` token expands to before a subtractive level is taken off it
     * (`ALL -PUBLIC`, irssi's form). Derived from `defs` — the tokens a user can *name* — so
     * it can't drift from the vocabulary, and deliberately not from `all`, which answers the
     * different question of which event types `ALL` hides. Unordered, because the result goes
     * through `canonicalize` before anyone sees it.
     */
    internal val concrete: List<String> get() = defs.keys.toList()

    /**
     * alias → canonical token, accepting irssi's singular/plural and legacy spellings. The
     * only place a level token is spelled more than one way; everything downstream of
     * `canonical` is in canonical form.
     */
    internal val aliases: Map<String, String> = mapOf(
        "PUBLIC" to "PUBLIC", "PUBLICS" to "PUBLIC",
        "MSG" to "MSGS", "MSGS" to "MSGS",
        "NOTICE" to "NOTICES", "NOTICES" to "NOTICES",
        "ACTION" to "ACTIONS", "ACTIONS" to "ACTIONS",
        "JOIN" to "JOINS", "JOINS" to "JOINS",
        "PART" to "PARTS", "PARTS" to "PARTS",
        "QUIT" to "QUITS", "QUITS" to "QUITS",
        "NICK" to "NICKS", "NICKS" to "NICKS",
        "KICK" to "KICKS", "KICKS" to "KICKS",
        "MODE" to "MODES", "MODES" to "MODES",
        "TOPIC" to "TOPICS", "TOPICS" to "TOPICS",
        "CTCP" to "CTCPS", "CTCPS" to "CTCPS",
        "ALL" to "ALL",
        // Lurker calls them "highlights", so NOHIGHLIGHT(S) is canonical; irssi's
        // NOHILIGHT/NOHILITE spellings are accepted as aliases.
        "NOHIGHLIGHT" to "NOHIGHLIGHT", "NOHIGHLIGHTS" to "NOHIGHLIGHT",
        "NOHILIGHT" to "NOHIGHLIGHT", "NOHILITE" to "NOHIGHLIGHT",
        // The mute rungs (lurker #359). NOUNREAD suppresses the plain-unread signal (≙ irssi's
        // NO_ACT); NONOTIFY suppresses toast/push/sound.
        "NOUNREAD" to "NOUNREAD", "NOUNREADS" to "NOUNREAD",
        "NO_ACT" to "NOUNREAD", "NOACT" to "NOUNREAD", "NOACTIVITY" to "NOUNREAD",
        "NONOTIFY" to "NONOTIFY", "NONOTIFYS" to "NONOTIFY",
        "NONOTIFICATION" to "NONOTIFY", "NONOTIFICATIONS" to "NONOTIFY",
    )

    /**
     * The order a rule's levels are stored and listed in. Deterministic because the server
     * dedupes rules by comparing the stored CSV *as a string*: two clients that canonicalize
     * the same set into different orders would write the same rule twice.
     */
    internal val canonicalOrder: List<String> = listOf(
        "ALL", "PUBLIC", "MSGS", "NOTICES", "ACTIONS", "JOINS", "PARTS", "QUITS", "NICKS",
        "KICKS", "MODES", "TOPICS", "CTCPS", "NOHIGHLIGHT", "NOUNREAD", "NONOTIFY",
    )

    /**
     * Resolve one token to its canonical form, or null if it names no level. Null is what tells
     * the parser a token is a mask or a flag rather than a level, so "unknown" has to be
     * answerable rather than defaulted.
     *
     * Port note: a Swift dictionary finds a key by canonical equivalence, a Kotlin map by code
     * units. So a token spelled with U+212A KELVIN SIGN — which is canonically a `K`, and which
     * `uppercase()` leaves alone — is `KICK` on iOS and no level here, nor on the web.
     */
    internal fun canonical(token: String): String? = aliases[token.uppercase()]

    /** Canonicalize a level list: resolve aliases, drop unknowns, dedupe, and order. */
    internal fun canonicalize(levels: List<String>): List<String> {
        val set = levels.mapNotNull(::canonical).toSet()
        return canonicalOrder.filter(set::contains)
    }
}
