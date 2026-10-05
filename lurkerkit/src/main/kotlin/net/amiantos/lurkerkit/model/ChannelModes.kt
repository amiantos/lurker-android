// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.Result
import net.amiantos.lurkerkit.support.unicodeRegex
import java.time.Duration
import java.time.Instant

/**
 * A network's channel-mode vocabulary, as the server parsed it from ISUPPORT (lurker#727,
 * `shared/channelModes.ts`). The client never reads 005 itself: everything a mode control,
 * a rank gate or a MODE line needs is in this one shape.
 *
 * ⚠⚠ `Network.modeSpec` is **null until the network's registration burst has ended**, and null
 * means "unknown", never the RFC defaults. Before the burst the server only has defaults, and
 * a default is not the network saying so.
 *
 * ⚠ `q` is the classic trap: a quiet LIST mode on solanum, an owner PREFIX on InspIRCd and
 * Unreal. Nothing here hardcodes it; which group a letter sits in is the server's answer.
 */
data class ModeSpec(
    /** CHANMODES group A — list modes: bans, exceptions, invite exceptions, quiets. */
    val list: String,
    /** Group B — always take a param (`k`). */
    val always: String,
    /** Group C — take a param only when set (`l`). */
    val onSet: String,
    /** Group D — plain flags. */
    val flags: String,
    /** Membership modes, highest rank first. */
    val prefix: List<PrefixMode>,
    /** How many param-taking changes one MODE line may carry; null is no limit. */
    val maxModes: Int?,
    /** The longest topic the server accepts, in BYTES; null when not advertised. */
    val topicLen: Int?,
)

/** One membership mode from PREFIX, e.g. `o` / `@`. */
data class PrefixMode(
    val mode: String,
    val symbol: String,
)

/** Ranking members by the network's own PREFIX — the port of `rankIndex` / `hasRankAtLeast`. */
object ChannelRank {
    /**
     * The conventional ladder, used only to pick a stand-in when a gate names a letter the
     * network doesn't have.
     */
    private val conventional = listOf("q", "a", "o", "h", "v")

    /**
     * Where a member's highest mode sits in PREFIX order: 0 for the top rank, null when they
     * hold none. Scans by rank, never by array position.
     */
    fun index(modes: List<String>, prefix: List<PrefixMode>): Int? =
        prefix.indexOfFirst { modes.contains(it.mode) }.takeIf { it >= 0 }

    /**
     * Whether a member ranks at or above `letter` (`"o"` for "op or higher").
     *
     * When the network has no `letter`, the gate moves UP the conventional ladder to the next
     * letter it does have — a network without halfops asks "op or higher" of a halfop gate.
     * Rounding up is the direction that can't hand out a control the server will refuse.
     */
    fun atLeast(modes: List<String>, prefix: List<PrefixMode>, letter: String): Boolean {
        val held = index(modes, prefix = prefix) ?: return false
        var threshold = prefix.indexOfFirst { it.mode == letter }.takeIf { it >= 0 }
        if (threshold == null) {
            val start = conventional.indexOf(letter)
            if (start >= 0) {
                for (candidate in conventional.subList(0, start).asReversed()) {
                    threshold = prefix.indexOfFirst { it.mode == candidate }.takeIf { it >= 0 }
                    if (threshold != null) break
                }
            }
        }
        if (threshold == null) return false
        return held <= threshold
    }
}

/**
 * A channel's mode and topic metadata, as the server last stated it — a side table on
 * `ChatState` beside `members`, keyed the same way.
 *
 * ⚠⚠ **Never carries the key.** `modes` has the letter `k` and nothing else: the value lives
 * only in the network config (`GET /api/networks` → `channels[].key`) and on the `mode` row
 * that set it.
 *
 * Port note: immutable. LurkerKit's fields are `var`s the store assigns one by one; here a
 * change is a `copy(...)`.
 */
data class ChannelModeState(
    /** Every set letter, e.g. `"ntkl"`. */
    val modes: String = "",
    /** The values of set param modes, e.g. `["l": "50"]`. */
    val params: Map<String, String> = emptyMap(),
    /** When the channel was created (329), when the server has said. */
    val createdAt: Instant? = null,
    /**
     * Who last set the topic and when (333, or a live TOPIC). Either may be null: a 333 can
     * carry the time alone.
     */
    val topicSetBy: String? = null,
    val topicSetAt: Instant? = null,
) {
    /**
     * "Set by alice · 1 Sep 2026 at 10:00" — whatever of the two the server said, or null.
     *
     * Port note: a function taking the date's formatter, where LurkerKit has a property that
     * formats with `formatted(date: .abbreviated, time: .shortened)`. The words are the kit's;
     * how a date reads is the app's. A plain JVM module can only ask `java.time` for the
     * locale's own style, which cannot see the device's 24-hour setting — so the line would say
     * 3:00 PM beside every other time on screen saying 15:00.
     */
    fun topicSetterLine(formatted: (Instant) -> String): String? {
        val nick = topicSetBy?.let(ChannelModeForm::setterNick)
        val time = topicSetAt?.let(formatted)
        return when {
            nick != null && time != null -> "Set by $nick · $time"
            nick != null -> "Set by $nick"
            time != null -> "Set $time"
            else -> null
        }
    }
}

/** One entry of a list mode — a ban, exception, invite exception or quiet. */
data class ModeListEntry(
    val mask: String,
    val setBy: String?,
    val setAt: Instant?,
)

/** The answer to `get-mode-list`. */
sealed interface ModeListResult {
    data class Entries(val entries: List<ModeListEntry>) : ModeListResult

    /**
     * The network isn't connected (or our socket dropped under the ask) — worth asking again
     * once it is, unlike a refusal.
     */
    data object Offline : ModeListResult

    /** Why there's no list, worded for the screen. */
    data class Failed(val message: String) : ModeListResult
}

/**
 * The channel's `error` rows read as the answer to a change this screen just sent.
 *
 * ⚠ By timing, because nothing better exists: MODE changes are untracked by the server's reply
 * router (a no-op change gets no reply, so there's no reliable end to wait for), so a refusal —
 * 482, 467, 478 — arrives as the channel's `error` row with nothing tying it to the change. The
 * rows that land soon after a send are taken as its answer; a server answers a MODE in moments,
 * and an unrelated error minutes later is not this change's. The web modal draws the same line.
 *
 * Port note: immutable. LurkerKit's `arm` and `note` mutate the struct in place; here each
 * returns the updated copy (`refusals = refusals.note(text)`). ⚠ Equality is
 * NOT LurkerKit's, which compares [current] alone: with copies instead of mutation in place, a
 * holder that keeps the latest value only when it differs (`MutableStateFlow`, Compose state)
 * would drop `arm()` — nothing current before, nothing current after — and every refusal after
 * it. Two of these are equal when everything they have seen is.
 */
@ConsistentCopyVisibility
data class ChannelRefusals private constructor(
    private val seen: List<Seen>,
    private val armed: Armed?,
) {
    private data class Seen(val text: String, val at: Instant)

    private data class Armed(val from: Int, val at: Instant)

    constructor() : this(seen = emptyList(), armed = null)

    /** A change just went out: errors from here on are its answer. */
    fun arm(now: Instant = Instant.now()): ChannelRefusals = copy(armed = Armed(from = seen.size, at = now))

    /** An `error` row for this channel arrived. */
    fun note(text: String, now: Instant = Instant.now()): ChannelRefusals = copy(seen = seen + Seen(text = text, at = now))

    /** The errors answering the latest change — those inside the window after it was sent. */
    val current: List<String>
        get() {
            val armed = armed
            if (armed == null || armed.from > seen.size) return emptyList()
            return seen.drop(armed.from)
                .filter { Duration.between(armed.at, it.at) < window }
                .map { it.text }
        }

    companion object {
        val window: Duration = Duration.ofSeconds(10)
    }
}

/** One MODE change to send — `param` present exactly when the mode takes one in that direction. */
data class OutgoingModeChange(
    val sign: Char,
    val letter: String,
    val param: String? = null,
)

/**
 * The channel-settings form as pure functions — the port of the web's `channelModeForm.ts`
 * and `modeListPatch.ts`. Which rows to draw from the spec, what MODE changes an edit amounts
 * to, and how a fetched list stays current.
 */
object ChannelModeForm {
    /**
     * The names a letter has on every ircd we know. Anything else shows as `+X` — a
     * hand-written table that guesses is how gamja came to describe `+n` backwards.
     */
    private val names: Map<String, String> = mapOf(
        "n" to "No outside messages",
        "t" to "Only operators set the topic",
        "i" to "Invite only",
        "m" to "Moderated",
        "s" to "Secret",
        "p" to "Private",
        "k" to "Key",
        "l" to "User limit",
    )

    /**
     * One list mode the server can fetch: its letter, and what to call it.
     *
     * Port note: the Swift uses a named tuple, `(letter: String, name: String)`.
     */
    data class ListMode(val letter: String, val name: String)

    /**
     * The list modes the server can fetch, in the order they're offered, with their names.
     * Only these: the server's reply router knows the numerics of nothing else.
     */
    val lists: List<ListMode> = listOf(
        ListMode("b", "Bans"), ListMode("e", "Exceptions"), ListMode("I", "Invite Exceptions"), ListMode("q", "Quiets"),
    )

    fun name(letter: String): String? = names[letter]

    /**
     * The nick out of a setter as the server names one: a 333 or a list entry often carries the
     * full `nick!user@host`, which is noise on a phone-width line.
     *
     * Port note: cuts at the first `!` UTF-16 unit. LurkerKit splits on the `Character`, so a
     * `!` carrying a combining mark does not end the nick there and does here.
     */
    fun setterNick(setter: String): String = setter.substringBefore('!')

    /** The fetchable lists this network has, in display order. */
    fun lists(spec: ModeSpec): List<ListMode> =
        lists.filter { spec.list.contains(it.letter) }

    enum class RowKind {
        Flag,
        Param,
        Key,
    }

    /** Port note: built by [rows] only, as in LurkerKit, where the struct has no public `init`. */
    @ConsistentCopyVisibility
    data class Row internal constructor(
        val letter: String,
        val kind: RowKind,
        val name: String?,
    )

    /**
     * Every flag and param mode the network advertises, the well-known ones first. List modes
     * have their own screens, and prefix modes are people, not the channel.
     */
    fun rows(spec: ModeSpec): List<Row> {
        val out = mutableListOf<Row>()
        for (letter in letters(spec.flags)) {
            out.add(Row(letter = letter, kind = RowKind.Flag, name = name(letter)))
        }
        for (letter in letters(spec.always + spec.onSet)) {
            out.add(Row(letter = letter, kind = if (letter == "k") RowKind.Key else RowKind.Param, name = name(letter)))
        }
        // Stable: the network's own order within each half.
        return out.filter { it.name != null } + out.filter { it.name == null }
    }

    /**
     * A mode string as its letters. Port-only.
     *
     * Port note: one per code point, where the Swift's `map(String.init)` yields one per
     * `Character`. A mode letter is a single ASCII character on every network, so the two
     * differ only for a string no ISUPPORT sends (a letter with a combining mark, a flag
     * emoji: one row there, one per code point here).
     */
    private fun letters(modes: String): List<String> =
        modes.codePoints().toArray().map { Character.toChars(it).concatToString() }

    /** One row as the user left it, or as the channel has it. */
    data class DraftRow(
        val on: Boolean,
        val value: String,
    )

    /** The channel's modes as the form diffs against them. */
    data class Live(
        /** Every set letter. */
        val modes: String,
        /**
         * Values of set param modes. The server never sends the key; the screen puts the one
         * it knows here while the channel is `+k`, so an untouched key field reads as "keep".
         */
        val params: Map<String, String>,
    ) {
        fun row(letter: String): DraftRow =
            DraftRow(on = modes.contains(letter), value = params[letter] ?: "")
    }

    /**
     * The MODE changes that turn the live state into the draft, or the first problem that
     * stops it. Rows the draft left alone are not looked at.
     *
     * Port note: the letters are walked in UTF-16 order, where Swift sorts by Unicode scalar;
     * mode letters are ASCII, where the two orders are one.
     */
    fun changes(
        spec: ModeSpec,
        live: Live,
        draft: Map<String, DraftRow>,
    ): Result<List<OutgoingModeChange>, ChangeError> {
        val out = mutableListOf<OutgoingModeChange>()
        val kinds = mutableMapOf<String, RowKind>()
        for (row in rows(spec)) {
            if (row.letter !in kinds) kinds[row.letter] = row.kind
        }
        // Sorted, so the same draft always makes the same lines.
        for (letter in draft.keys.sorted()) {
            val want = draft[letter] ?: continue
            val kind = kinds[letter] ?: continue
            val was = live.row(letter)
            val value = want.value.trimmingWhitespacesAndNewlines()
            if (kind == RowKind.Flag) {
                if (want.on != was.on) out.add(OutgoingModeChange(sign = if (want.on) '+' else '-', letter = letter))
                continue
            }
            if (!want.on) {
                if (!was.on) continue
                // A B-group mode names its value to unset it (`*` when we never learned it);
                // the server fills in -k's.
                val needsParam = spec.always.contains(letter) && kind != RowKind.Key
                out.add(
                    OutgoingModeChange(
                        sign = '-',
                        letter = letter,
                        param = if (needsParam) (if (was.value.isEmpty()) "*" else was.value) else null,
                    )
                )
                continue
            }
            // Only now: a value being turned off doesn't need to be a valid one.
            if (value.any { it.isSwiftWhitespace() }) return Result.Failure(ChangeError.Spaces(letter))
            if (kind == RowKind.Key) {
                if (value.isEmpty()) {
                    if (!was.on) return Result.Failure(ChangeError.KeyRequired)
                    continue // on, and no new key: keep the one it has
                }
                if (was.on && value == was.value) continue
                // Replacing a key: several ircds answer a bare +k over an existing one with
                // 467, so take the old one off first.
                if (was.on) out.add(OutgoingModeChange(sign = '-', letter = letter))
                out.add(OutgoingModeChange(sign = '+', letter = letter, param = value))
                continue
            }
            if (value.isEmpty()) return Result.Failure(ChangeError.ValueRequired(letter))
            if (!was.on || value != was.value) {
                out.add(OutgoingModeChange(sign = '+', letter = letter, param = value))
            }
        }
        return Result.Success(out)
    }

    /**
     * Port note: an `Error` in LurkerKit only so it can ride a `Result`; never thrown, so a
     * plain sealed interface here.
     */
    sealed interface ChangeError {
        data class Spaces(val letter: String) : ChangeError

        data object KeyRequired : ChangeError

        data class ValueRequired(val letter: String) : ChangeError

        val message: String
            get() = when (this) {
                is Spaces -> "+$letter can't contain spaces."
                KeyRequired -> "Enter a key."
                is ValueRequired -> "Enter a value for +$letter."
            }
    }

    /** A topic's length as the server counts it: bytes, not characters. */
    fun topicBytes(topic: String): Int = topic.toByteArray(Charsets.UTF_8).size

    private val lineBreaks = unicodeRegex("[\\r\\n]+")

    /** A topic is one line on the wire; a pasted newline becomes a space. */
    fun topicToSend(text: String): String = text.replace(lineBreaks, " ")

    /**
     * The channel's key as the newest `±k` in `rows` says: a `+k <key>` names it, a `-k` means
     * there is none (so a key the config still remembers doesn't come back). `None` — no ±k
     * seen at all — leaves the config's copy standing.
     *
     * ⚠ A `+k` whose value is hidden (`*`, or none) is still the newest word: a key is set and
     * we don't know it. Skipping it would fall back to an OLDER key and reveal that one. The
     * config's copy is no better — the server keeps its stored key over a `+k *` (a mask is not
     * a key) — so it reads as `SetUnknown`, which suppresses both.
     */
    fun lastKeyChange(rows: List<Message>): KeySighting {
        for (row in rows.asReversed()) {
            for (change in row.modes.asReversed()) {
                if (change.mode == "-k") return KeySighting.Removed
                if (change.mode == "+k") {
                    val param = change.param
                    if (param.isNullOrEmpty() || param == "*") return KeySighting.SetUnknown
                    return KeySighting.Set(param)
                }
            }
        }
        return KeySighting.None
    }

    sealed interface KeySighting {
        data object None : KeySighting

        data object Removed : KeySighting

        data class Set(val key: String) : KeySighting

        /** A key is set, and its value was hidden from us. */
        data object SetUnknown : KeySighting
    }

    /**
     * `entries` with every ±`letter` list change in `rows` applied, in order. Masks match
     * case-insensitively, as irssi's do.
     *
     * ⚠⚠ This is how an open list stays current after a Save — never a refetch. A fetch on
     * the wire claims a 482 aimed at the MODE just sent (`server/services/modeList.ts`).
     *
     * Port note: masks fold with `lowercase()`, which differs from LurkerKit's `lowercased()`
     * only by the final-sigma rule (see `BufferKey.id`).
     */
    fun patch(entries: List<ModeListEntry>, rows: List<Message>, letter: String): List<ModeListEntry> {
        val out = entries.toMutableList()
        for (row in rows) {
            for (change in row.modes) {
                val param = change.param
                if (change.kind != ModeChangeKind.List || change.letter != letter || param.isNullOrEmpty()) continue
                val at = out.indexOfFirst { it.mask.lowercase() == param.lowercase() }
                if (change.isGrant) {
                    if (at < 0) out.add(ModeListEntry(mask = param, setBy = row.nick, setAt = row.date))
                } else if (at >= 0) {
                    out.removeAt(at)
                }
            }
        }
        return out
    }
}

/**
 * The edits on a channel-settings screen that haven't been answered yet — only the rows the
 * user TOUCHED, never a copy of the channel taken when the screen opened.
 *
 * Everything else reads the live state, and Save diffs the draft against the live state at
 * that moment. So another op's change while the screen is open shows up, and Save never
 * re-sends or reverts it (obby's stale-baseline bug).
 *
 * ⚠⚠ An edit stays until the CHANNEL answers it, and is never cleared on the ack — the ack
 * only means the line went out. It goes when the live state matches it, or — for a row that
 * was saved — when that row's live state moves at all, since a server may echo a value
 * normalized (`+l 050` comes back as 50). A refusal moves nothing, so the edit stands beside
 * the error.
 *
 * ⚠ One exception to "goes when it matches": an edit made while its row's Save is still out, which
 * matches the channel only because the channel hasn't answered yet — an undo. It stays until the
 * channel moves, or until the Save is known not to be coming (`settle` unsent, `refused`).
 *
 * Port note: immutable. Every mutator here returns `Void` in LurkerKit (`setOn`, `setValue`,
 * `setTopic`, `noteSending`, `settle`, `noteTopicSending`, `settleTopic`, `reconcile`), so
 * each returns the updated copy instead (`drafts = drafts.setOn("m", true, live)`).
 * ⚠ Equality is NOT LurkerKit's, which compares `rows` and `topic` only: with copies instead of
 * mutation in place, a holder that keeps the latest value only when it differs
 * (`MutableStateFlow`, Compose state) would drop `noteSending`, which changes neither, and with
 * it the record of what is out and unanswered. Two of these are equal when all of it is; a
 * screen that wants "same on screen" compares `rows` and `topic`.
 */
@ConsistentCopyVisibility
data class ChannelModeDrafts private constructor(
    val rows: Map<String, ChannelModeForm.DraftRow>,
    /** The topic as typed; null until the user types, and the field shows the live topic. */
    val topic: String?,
    /**
     * Per saved row: the live state it was saved from, and the edit that went out. Only that
     * edit is the echo's to clear — the fields stay editable while the ack is out, and a newer
     * edit (untick +m again before +m comes back) is the user's to keep, even when it matches
     * the channel as it still is — see `reconcile`.
     */
    private val savedRows: Map<String, SavedRow>,
    private val savedTopic: SavedTopic?,
    /**
     * The send whose outcome isn't known yet — one at a time, as the screen sends one Save at a
     * time. An echo may dissolve its edits before the answer comes (the line went out, the
     * channel moved), and if the answer then says it never went out, those edits come back:
     * they are kept here until it's settled.
     */
    private val pendingLetters: Set<String>,
    private val dissolvedWhilePending: Map<String, ChannelModeForm.DraftRow>,
    private val topicPending: Boolean,
    private val topicDissolvedWhilePending: String?,
) {
    /** Port note: a tuple in the Swift, `(live: String, sent: String)`. */
    private data class SavedTopic(val live: String, val sent: String)

    @ConsistentCopyVisibility
    data class SavedRow internal constructor(
        internal val live: ChannelModeForm.DraftRow,
        internal val sent: ChannelModeForm.DraftRow,
    )

    /**
     * What a Save's mode changes are about to send, taken at the moment of Save — before any
     * suspension, since the fields stay editable while it's out. Recorded by `noteSending` only
     * once the changes actually go out.
     *
     * Port note: `rows` is `fileprivate` in the Swift. Kotlin has no visibility that lets the
     * enclosing class in and keeps the rest of the module out, so it is `internal`.
     */
    @ConsistentCopyVisibility
    data class Sending internal constructor(
        internal val rows: Map<String, SavedRow>,
    )

    constructor() : this(
        rows = emptyMap(),
        topic = null,
        savedRows = emptyMap(),
        savedTopic = null,
        pendingLetters = emptySet(),
        dissolvedWhilePending = emptyMap(),
        topicPending = false,
        topicDissolvedWhilePending = null,
    )

    fun shown(letter: String, live: ChannelModeForm.Live): ChannelModeForm.DraftRow =
        rows[letter] ?: live.row(letter)

    fun setOn(letter: String, on: Boolean, live: ChannelModeForm.Live): ChannelModeDrafts =
        copy(rows = rows + (letter to ChannelModeForm.DraftRow(on = on, value = shown(letter, live = live).value)))

    fun setValue(letter: String, value: String, live: ChannelModeForm.Live): ChannelModeDrafts =
        copy(rows = rows + (letter to ChannelModeForm.DraftRow(on = shown(letter, live = live).on, value = value)))

    fun setTopic(text: String): ChannelModeDrafts = copy(topic = text)

    /** The topic Save would send, or null when it wouldn't change anything. */
    fun topicChange(live: String): String? {
        val topic = topic ?: return null
        val out = ChannelModeForm.topicToSend(topic)
        return if (out == live) null else out
    }

    /** Capture what `changes` will send, as the rows stand now. */
    fun sending(changes: List<OutgoingModeChange>, live: ChannelModeForm.Live): Sending =
        Sending(
            rows = changes.map { it.letter }.toSet().associateWith { letter ->
                SavedRow(live = live.row(letter), sent = shown(letter, live = live))
            }
        )

    /**
     * The changes are going out: the echo may now dissolve exactly those edits — tentatively,
     * until `settle` says whether they did.
     *
     * ⚠ Recorded only as they go, never at Save: a row recorded for a change that never left
     * would be dissolved by any later move of that mode — another op's +m then -m quietly
     * throwing away the user's unsent +m.
     */
    fun noteSending(sending: Sending): ChannelModeDrafts =
        copy(
            savedRows = savedRows + sending.rows,
            pendingLetters = sending.rows.keys.toSet(),
            dissolvedWhilePending = emptyMap(),
        )

    /**
     * The answer came. `wentOut` false means nothing can have reached IRC: the record is taken
     * back (unless a later Save recorded over it), and an edit the echo dissolved meanwhile is
     * restored — unless the user has typed something newer in that row.
     */
    fun settle(sending: Sending, wentOut: Boolean): ChannelModeDrafts {
        val savedRows = savedRows.toMutableMap()
        val rows = rows.toMutableMap()
        if (!wentOut) {
            for ((letter, row) in sending.rows) {
                if (savedRows[letter] == row) savedRows.remove(letter)
            }
            for ((letter, edit) in dissolvedWhilePending) {
                if (rows[letter] == null) rows[letter] = edit
            }
        }
        return copy(
            rows = rows,
            savedRows = savedRows,
            pendingLetters = emptySet(),
            dissolvedWhilePending = emptyMap(),
        )
    }

    /** The topic is going out — tentatively, as for the modes. */
    fun noteTopicSending(topic: String, liveTopic: String): ChannelModeDrafts =
        copy(
            savedTopic = SavedTopic(live = liveTopic, sent = topic),
            topicPending = true,
            topicDissolvedWhilePending = null,
        )

    /**
     * The channel refused the Save — an error row inside its window (`ChannelRefusals`) — or the
     * socket that carried it is gone. Either way the channel won't move for it, so nothing waits on
     * it any more: an undo kept while it was out goes on the next reconcile, rather than lingering
     * until somebody else's change of the same mode turns it into a revert of theirs.
     *
     * ⚠ Every row's record, not just the refused one's: an error row doesn't say which change it
     * answers. An undo of a change in the same Save that did land is lost with it — the narrow price
     * of never reverting another op.
     */
    fun refused(): ChannelModeDrafts = copy(savedRows = emptyMap(), savedTopic = null)

    /** …and the answer came. */
    fun settleTopic(topic: String, wentOut: Boolean): ChannelModeDrafts {
        var savedTopic = savedTopic
        var typed = this.topic
        if (!wentOut) {
            if (savedTopic?.sent == topic) savedTopic = null
            val edit = topicDissolvedWhilePending
            if (typed == null && edit != null) typed = edit
        }
        return copy(
            topic = typed,
            savedTopic = savedTopic,
            topicPending = false,
            topicDissolvedWhilePending = null,
        )
    }

    /**
     * Let the live state answer what it can. Call on every state change.
     */
    fun reconcile(live: ChannelModeForm.Live, liveTopic: String): ChannelModeDrafts {
        val rows = rows.toMutableMap()
        val savedRows = savedRows.toMutableMap()
        val dissolvedWhilePending = dissolvedWhilePending.toMutableMap()
        for ((letter, want) in this.rows) {
            val was = live.row(letter)
            val matches = want.on == was.on &&
                (!want.on || want.value.trimmingWhitespacesAndNewlines() == was.value)
            val saved = savedRows[letter]
            val moved = saved?.let { it.live != was } ?: false
            if (moved) savedRows.remove(letter)
            if (matches) {
                // The channel is as the user wanted, whatever happens to the send — unless that
                // send is still out and this is an undo of it. Untick +m before +m comes back and
                // the untick matches the channel as it still is; dropped here, the +m then lands
                // and the switch turns back on, the undo lost. Kept until the channel moves off
                // the state it was saved from: then it's a real difference, shown, and Save sends
                // it. (A send that never left is taken back by `settle`, and a refused one by
                // `refused`; the edit goes then.) A matching edit whose saved row hasn't moved IS
                // that undo: it equals the row's baseline, which is never what the Save sent.
                if (saved == null || moved) rows.remove(letter)
            } else if (moved && saved?.sent == want) {
                if (pendingLetters.contains(letter)) dissolvedWhilePending[letter] = want
                rows.remove(letter)
            }
        }
        val reconciled = copy(rows = rows, savedRows = savedRows, dissolvedWhilePending = dissolvedWhilePending)
        val topic = topic ?: return reconciled
        val sending = ChannelModeForm.topicToSend(topic)
        val moved = savedTopic?.let { it.live != liveTopic } ?: false
        var typed: String? = topic
        var topicDissolvedWhilePending = topicDissolvedWhilePending
        if (sending == liveTopic) {
            // Typed back to the old topic while the new one is out: kept until the new one lands,
            // as for the modes above.
            if (savedTopic == null || moved) typed = null
        } else if (moved && savedTopic?.sent == sending) {
            if (topicPending) topicDissolvedWhilePending = topic
            typed = null
        }
        return reconciled.copy(
            topic = typed,
            savedTopic = if (moved) null else savedTopic,
            topicDissolvedWhilePending = topicDissolvedWhilePending,
        )
    }
}

/**
 * What this account may do in a channel, as the channel settings screens ask it.
 *
 * Port note: built by `ChatState.channelAccess` only, as in LurkerKit, where the struct has
 * no public `init`.
 */
@ConsistentCopyVisibility
data class ChannelAccess internal constructor(
    /** The network's vocabulary, or null while it's unknown. */
    val spec: ModeSpec?,
    /**
     * In the channel. A parted channel's modes are last-known, and a TOPIC or MODE from outside
     * it only draws a 442.
     */
    val joined: Boolean,
    /** Op or higher, by the network's own PREFIX. */
    val canEditModes: Boolean,
    /** Halfop or higher under `+t`; anyone in the channel without it. */
    val canSetTopic: Boolean,
)

/**
 * Who may do what in `key`'s channel. The server has the last word — its refusal shows on
 * the screen — so this only decides which controls are offered.
 *
 * Port note: an extension on `ChatState` here as in LurkerKit; the receiver is written out in
 * full so this file's imports stay as they were before the store was ported.
 */
fun net.amiantos.lurkerkit.store.ChatState.channelAccess(key: BufferKey): ChannelAccess {
    val network = key.networkId?.let { networks[it] }
    val spec = network?.modeSpec
    val joined = buffers[key.id]?.joined == true
    val mine = members[key.id]?.member(named = network?.nick ?: "")?.modes ?: emptyList()
    // ⚠ No rank gate opens before the vocabulary arrives. A conventional ladder in its place
    // would rank letters this network may not have, and offer a +t topic edit to someone
    // below the rank it actually needs. A -t topic needs no rank, so it stays editable.
    val modes = channelModes[key.id]?.modes ?: ""
    val atLeast = { letter: String -> spec?.let { ChannelRank.atLeast(mine, prefix = it.prefix, letter = letter) } ?: false }
    return ChannelAccess(
        spec = spec,
        joined = joined,
        canEditModes = joined && atLeast("o"),
        canSetTopic = joined && (!modes.contains("t") || atLeast("h")),
    )
}
