// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A stored setting value, in its decoded form. Mirrors the server's `SettingValue`
 * (`shared/settingsRegistry.ts`).
 *
 * Modelled as a closed type rather than `Any` so a value can survive the round trip —
 * bootstrap → store → a control → `PATCH` — without anything having to guess what it is
 * at each hop.
 *
 * Port note: the cases keep LurkerKit's names (`.string`, `.int`, `.bool`, `.stringList`),
 * which in PascalCase puts a `String` and an `Int` inside this interface. ⚠ Within its body
 * those names mean the cases, so the builtin types are written `kotlin.String` and
 * `kotlin.Int` here. Nothing outside the body is affected.
 */
sealed interface SettingValue {
    data class String(val value: kotlin.String) : SettingValue
    data class Int(val value: kotlin.Int) : SettingValue
    data class Bool(val value: Boolean) : SettingValue
    data class StringList(val value: List<kotlin.String>) : SettingValue

    /** Back to a JSON-encodable value for `PATCH /api/settings`. */
    val jsonValue: JsonElement
        get() = when (this) {
            is String -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Bool -> JsonPrimitive(value)
            is StringList -> JsonArray(value.map { JsonPrimitive(it) })
        }

    val boolValue: Boolean? get() = if (this is Bool) value else null
    val intValue: kotlin.Int? get() = if (this is Int) value else null
    val stringValue: kotlin.String? get() = if (this is String) value else null

    companion object {
        /**
         * Decode from the JSON shapes the server actually emits. `string`, `color`, `secret`
         * and `enum` all arrive as strings; `int` as a number; `bool` as a boolean;
         * `string-list` as an array of strings.
         *
         * Note the bool/number split. On iOS `JSONSerialization` decodes both `true` and `1`
         * into `NSNumber`, so a bare cast would happily claim a boolean as an int and any
         * nonzero int as a boolean. The trap has the same shape here: kotlinx's
         * `JsonPrimitive.boolean`, `.int` and `.content` all coerce (`"true"` → true,
         * `"1"` → 1, `1` → "1"), so the quoted/unquoted test comes first and nothing below
         * reads through them unguarded. Getting it wrong turns every `bool` setting into
         * `Int(1)`.
         *
         * Port note: a JSON number is read the way `NSNumber.intValue` reads it — a fraction
         * is truncated toward zero. ⚠ But LurkerKit's `Int` is 64-bit and this one is 32: a
         * number that doesn't fit is skipped (null) rather than wrapped, by the same rule as
         * the mixed array below — a value we can't represent exactly is one the caller should
         * skip.
         */
        fun from(raw: JsonElement): SettingValue? {
            when (raw) {
                is JsonPrimitive -> {
                    if (raw is JsonNull) return null
                    if (raw.isString) return String(raw.content)
                    raw.booleanOrNull?.let { return Bool(it) }
                    val number = raw.longOrNull
                        ?: raw.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
                        ?: return null
                    if (number < kotlin.Int.MIN_VALUE || number > kotlin.Int.MAX_VALUE) return null
                    return Int(number.toInt())
                }
                is JsonArray -> {
                    // No lossy fallback for a mixed array. Picking the strings out of it would be
                    // coercion wearing a decode's clothing: the value we'd hold — and write back —
                    // would be a different list than the server sent. A key we can't represent
                    // exactly is one the caller should skip, which is what returning null gets it.
                    val list = raw.map { element ->
                        (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    }
                    return StringList(list)
                }
                is JsonObject -> return null
            }
        }
    }
}

/** How a setting is edited — the discriminant the settings screen renders from. */
enum class SettingType(val rawValue: kotlin.String) {
    String("string"),
    Color("color"),
    Secret("secret"),
    Int("int"),
    Bool("bool"),
    Enum("enum"),
    StringList("string-list");

    companion object {
        fun fromRawValue(raw: kotlin.String): SettingType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One "this setting is live when…" clause from the registry's `dependsOn`.
 *
 * The clauses on an option are ORed: it is live if any one of them holds. That matters for
 * the event tier, which is two keys (desktop and mobile) — a phone set to `none` must not
 * grey out consolidation knobs a desktop is actively using.
 */
data class SettingDependency(
    val key: String,
    val values: List<SettingValue>,
)

/**
 * One entry from the server's settings registry.
 *
 * Carried rather than duplicated in Kotlin: the registry is self-describing
 * (`CLIENT_PROTOCOL.md:723`), so labels, help text, bounds and choices come from the server
 * and can't drift out of sync with what it will actually accept. The app curates *which* keys
 * it honors; the server describes them.
 */
data class SettingOption(
    val key: String,
    val label: String,
    val description: String,
    val type: SettingType,
    val default: SettingValue,
    /** `enum` only — the permitted choices, in the order the server lists them. */
    val choices: List<String> = emptyList(),
    /**
     * `enum` only — display text per choice, for enums whose stored values are ids rather
     * than English. A choice with no entry falls back to its raw value, so a partial (or
     * absent) map is safe — which is also what an older server sends.
     */
    val choiceLabels: Map<String, String> = emptyMap(),
    /**
     * `int` only — the server-enforced bounds, so a stepper can't offer a value that will be
     * rejected on write.
     */
    val min: Int? = null,
    val max: Int? = null,
    /**
     * Conditions under which this setting does anything, ORed. Empty means unconditional.
     *
     * Registry data rather than a table maintained here (lurker#666). On iOS this screen used
     * to carry a hand-written `consolidate_max_names → consolidate_joins` map; the event tier
     * added eight more dependencies, which is more than a hardcoded copy survives.
     */
    val dependsOn: List<SettingDependency> = emptyList(),
) {
    /** The text to show for one of this option's `choices`, falling back to the raw value. */
    fun label(choice: String): String = choiceLabels[choice] ?: choice
}

/**
 * The user's settings: the server's registry plus whatever they've actually stored.
 *
 * **The two halves are not interchangeable.** `/api/settings/bootstrap` returns
 * `values: getUserSettings(userId)` — *stored* values only, with defaults NOT merged in
 * (`server/routes/settings.ts:14`). A user who has never opened settings gets `{}`. So a read
 * has to fall back through the registry, which is what [effective] is for and why nothing
 * should read `values` directly. Miss that and every bool reads false on a fresh account,
 * silently defaulting features off — the opposite of what the registry intends for most.
 *
 * Port note: immutable. LurkerKit's `load`, `apply(changes:)` and `replaceValues` mutate the
 * struct in place; here each returns the updated copy (`settings = settings.apply(changes)`).
 * The two constructors are LurkerKit's two initialisers, and they differ on `loaded` on
 * purpose: a registry and values handed in are a bootstrap (`loaded` defaults true), the
 * empty one is the state before it.
 */
data class Settings(
    /** Registry entries by key. Empty until bootstrap returns. */
    val registry: Map<String, SettingOption>,
    /** Explicitly stored values by key — only what the user has actually changed. */
    val values: Map<String, SettingValue>,
    /**
     * Whether bootstrap has landed. Before it does, `effective` can only answer from the
     * caller's fallback, so a screen can use this to avoid rendering controls it would have
     * to correct a moment later.
     */
    val loaded: Boolean = true,
) {
    constructor() : this(registry = emptyMap(), values = emptyMap(), loaded = false)

    /**
     * The value in force for `key`: what the user stored, else the registry default, else null
     * for a key this server doesn't know. Mirrors the server's `effectiveSetting`
     * (`settingsService.ts:14`) and the web's `settings.effective`.
     */
    fun effective(key: String): SettingValue? = values[key] ?: registry[key]?.default

    /**
     * `effective` with a caller-supplied fallback for the window before bootstrap returns —
     * and for a server too old to know the key at all. That second case is real rather than
     * defensive: a self-hosted instance updates on its owner's schedule, so it can legitimately
     * be older than the app talking to it, and a key it has never heard of is normal.
     *
     * The fallback should be the same default the registry carries, so behavior doesn't shift
     * under the user when bootstrap lands a moment after launch.
     */
    fun bool(key: String, default: Boolean): Boolean = effective(key)?.boolValue ?: default

    fun int(key: String, default: Int): Int = effective(key)?.intValue ?: default

    fun string(key: String, default: String): String = effective(key)?.stringValue ?: default

    /**
     * Whether a setting currently *does* anything, per its `dependsOn` clauses.
     *
     * Clauses are ORed; resolution is transitive, so an option whose dependency is itself
     * inactive is inactive too and the registry states each link once. A clause naming a key
     * this server doesn't know resolves on its value check alone — an older or newer server
     * is a normal condition here, not an error.
     *
     * Presentation only, exactly as on the web: the stored value is untouched, because
     * flipping the dependency back has to restore what the user had rather than a default.
     * Mirrors `vue_client/src/utils/settingsRegistry.ts:optionEnabled`.
     */
    fun isActive(key: String, depth: Int = 0): Boolean {
        val option = registry[key]
        if (option == null || option.dependsOn.isEmpty()) return true
        if (depth >= maxDependencyDepth) return false
        return option.dependsOn.any { dependency ->
            val current = effective(dependency.key)
            if (current == null || current !in dependency.values) return@any false
            isActive(dependency.key, depth = depth + 1)
        }
    }

    /** Replace everything — the bootstrap response. */
    fun load(registry: Map<String, SettingOption>, values: Map<String, SettingValue>): Settings =
        Settings(registry = registry, values = values, loaded = true)

    /**
     * Merge a `settings` change frame (or a seed from the cache). A patch, never a replace:
     * the frame carries only what changed (`wsHub.ts:1652`), so overwriting `values`
     * wholesale would drop every other stored setting until the next bootstrap.
     */
    fun apply(changes: Map<String, SettingValue>): Settings = copy(values = values + changes)

    /**
     * Replace the stored values with an authoritative full set — the `{values}` a REST reply
     * carries.
     *
     * Distinct from `apply(changes)` because a full set can be *smaller* than what we hold,
     * and merging would miss that. The server drops a row when a key is set back to its
     * default — "no override" (`settingsService.ts:72`) — so a `PATCH` that returns to the
     * default comes back as an ABSENCE, not as a value. Merged, the old override would
     * survive locally (and get persisted to the cache) while the server has none; that's a
     * setting stuck at a value the user has just cleared, for as long as it takes another
     * bootstrap to land.
     */
    fun replaceValues(next: Map<String, SettingValue>): Settings = copy(values = next)

    private companion object {
        /**
         * How deep a `dependsOn` chain is followed before giving up. Real chains are two links
         * (`consolidate_max_names → consolidate_joins → chat.events`); the cap only exists so a
         * server-side registry edit that accidentally makes a cycle greys a row out instead of
         * hanging the settings screen.
         */
        const val maxDependencyDepth = 8
    }
}
