// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.time.Instant

/**
 * A line in a buffer. Faithful to the server's MessageEvent, trimmed to what a
 * client renders. `id` is the persisted message id (0 for ephemeral events).
 *
 * Port note: immutable, with a private `copy`. In LurkerKit every field is either a `let` or
 * `private(set)`, so nothing outside `Message.swift` can change a line; the three transforms
 * below (`unhighlighted`, `relayed`, `showingReply`) are the only writers. Here the primary
 * constructor — all 28 fields — is private and `copy` is private with it, and the public
 * constructor is LurkerKit's `init`: the 24 wire fields, without the four that only a
 * transform sets (`replyQuote`, `unstrippedText`, `relayBot`, `relaySource`). Equality covers
 * all 28, as the Swift's does.
 *
 * Port note: `id` is a message id, so it is a `Long` here (PORTING.md, Types).
 */
@ConsistentCopyVisibility
data class Message private constructor(
    val id: Long,
    val type: EventType,
    /**
     * Who said it. **Changed only by `relayed(...)`, which swaps it for the speaker a relay bot
     * was quoting** — like `matched`, it is writable from this class and nowhere else.
     */
    val nick: String?,
    /** What they said. Changeable for the same one reason `nick` is. */
    val text: String?,
    val isSelf: Boolean,
    val time: String?,
    /**
     * `time` parsed once, at the wire boundary. Rendering formats it and grouping
     * compares it, and both run for every visible row on every reload — so it's parsed
     * here rather than re-derived per read.
     */
    val date: Instant?,
    /**
     * A highlight rule matched this line — renders as a mention.
     *
     * Server-stamped, and writable only from this class, by `unhighlighted()`: the server
     * decides what matched, and the one thing a client may do is take a match *off*. `copy`
     * is private rather than internal so even the store — the layer that must not
     * write it — can't, which is where the invariant is actually stated.
     */
    val matched: Boolean,
    /**
     * Severity, carried only by system-buffer lines. The server does NOT encode severity
     * in `type` — an error is `type: "system"` with `level: "error"` — so styling a
     * system line means reading this, never the type.
     */
    val level: SystemLevel?,
    /**
     * Which network a system line is *about*, when it's about one. The system buffer is
     * app-scoped (`networkId == null`), so this is what lets a line name its network in
     * the prefix column instead of the generic "System".
     */
    val originNetworkId: Int?,
    /**
     * The new name on a `nick` event. `nick` holds the old one, so a rename needs both to
     * render "alice is now bob" and to follow an identity across a consolidation run.
     */
    val newNick: String?,
    /** The target of a `kick` — who was removed. `nick` holds the actor doing the kicking. */
    val kicked: String?,
    /** The target of an `invite` — who was invited. `nick` holds the inviter. */
    val invited: String?,
    /**
     * The parsed changes on a `mode` event (empty otherwise). `text` carries the same
     * changes as a flat string; this is the structured form, so the line can render
     * "+o alice" rather than a raw mode string.
     */
    val modes: List<ModeChange>,
    /**
     * The post-change ident and host on a `chghost` event. Either half can be absent — the
     * server stores an empty string when it lacks one — so the mask is built from whichever
     * are present rather than assuming both.
     */
    val newIdent: String?,
    val newHost: String?,
    /**
     * The sender's `nick!ident@host` as the server stamped it, when it had one. Server
     * messages and synthesized lines carry none.
     *
     * On a `chghost` line this is the mask *before* the change — the new one is `newIdent`/
     * `newHost` — which is what makes "alice (old@mask) changed host to new@mask" readable.
     */
    val userhost: String?,
    /**
     * The joining user's services account, from extended-join. Null on networks without the
     * cap and for a logged-out user (the server stores the `*` sentinel as null), which are
     * the same thing as far as a renderer is concerned: nothing to show.
     */
    val account: String?,
    /**
     * Whether this line was saved *as of the moment the server sent it*. Absent on the wire
     * means unsaved (the server omits the field rather than sending false), so nearly every
     * row costs nothing to carry it.
     *
     * **Don't render from this — ask `ChatState.isBookmarked(_:)`.** This is the wire seed,
     * frozen at parse time; the store's id set is what also reflects a toggle made since,
     * here or on another device. There is no bookmark snapshot in the connect burst, so this
     * field is how the set learns about saves it didn't witness — for any line the client
     * actually loads. The Bookmarks feed itself is the other source: its rows carry no flag
     * (they're all saved) and seed the set through `noteBookmarked(ids:)`.
     */
    val bookmarked: Boolean,
    /**
     * The server's IRCv3 message id, when the network supplied one (`message-tags`; our own
     * lines learn theirs from `echo-message`). What a reaction or a reply names — a line
     * without one can't be reacted or replied to. Null on untagged networks.
     */
    val msgid: String?,
    /**
     * An end-to-end encrypted line (RPE2E). Reactions and reply tags are cleartext, so the
     * server sends none on one, and this client doesn't offer to.
     */
    val isE2E: Boolean,
    /**
     * The reactions standing on this line *as of the moment the server sent it* — null when
     * none. **Don't render from this — ask `ChatState.reactionGroups(for:)`.** Like
     * `bookmarked`, it's the wire seed: the store's side map is what also reflects a
     * `reaction` frame since, and the row is authoritative for itself only when it arrives.
     */
    val reactions: List<MessageReaction>?,
    /**
     * Set when this line is an IRCv3 reply (`+reply` / `+draft/reply`, lurker-ios#184): the
     * msgid it answers and that line as the server found it, or a null `parent` when it found
     * none. Show it through `Replies.shown`, which also screens ignores and unwraps relay bots.
     */
    val replyTo: ReplyContext?,
    /**
     * A reply to one of your lines, from someone else — stamped by the server at insert, and a
     * highlight. ⚠ Never derive that from `replyTo.parent.isSelf`: the parent can be gone or
     * stored after the reply, and the stamp is what the server's counts and feeds read.
     *
     * Not read to draw the tint, on purpose: the server sends `matched` with the stamp, and this
     * client tints from `matched` — it doesn't re-evaluate highlight rules live, which is the
     * only way the web's tint could lose a reply and why the web ORs this in. Kept so the row
     * says what it is, and for the day this client does evaluate rules itself.
     */
    val replyToSelf: Boolean,
    /**
     * The reply's quote as it should SHOW — set by `showingReply`, the one producer, from
     * `Replies.shown`: null on a reply means "original message unavailable" (gone, never held, or
     * from someone ignored since). Meaningless on a line that isn't a reply.
     */
    val replyQuote: ReplyQuote?,
    /**
     * What `text` was before `showingReply` took the address off it — null when nothing was
     * taken. Read through `copyText`.
     */
    val unstrippedText: String?,
    /**
     * The relay bot this line actually came from, once it has been re-attributed
     * (lurker#277) — the only real IRC entity on the row, since `nick` now names someone
     * with no presence here.
     *
     * Null on every line that isn't a re-attributed one, which is nearly all of them. Absent from
     * the public constructor on purpose: re-attribution is a *display* transform with exactly one
     * producer (`relayed(...)`, below), so there is no path by which a line arrives from the wire
     * already claiming to be relayed.
     */
    val relayBot: String?,
    /**
     * The `[source]` tag the envelope carried — "Discord", "Telegram", the bridged network's
     * name. Null when it carried none (a bare `<nick> message` relay), which is a real and common
     * case, not a missing value: those envelopes simply don't say where the speaker was.
     */
    val relaySource: String?,
) {
    constructor(
        id: Long,
        type: EventType,
        nick: String?,
        text: String?,
        isSelf: Boolean = false,
        time: String? = null,
        date: Instant? = null,
        matched: Boolean = false,
        level: SystemLevel? = null,
        originNetworkId: Int? = null,
        newNick: String? = null,
        kicked: String? = null,
        invited: String? = null,
        modes: List<ModeChange> = emptyList(),
        newIdent: String? = null,
        newHost: String? = null,
        userhost: String? = null,
        account: String? = null,
        bookmarked: Boolean = false,
        msgid: String? = null,
        isE2E: Boolean = false,
        reactions: List<MessageReaction>? = null,
        replyTo: ReplyContext? = null,
        replyToSelf: Boolean = false,
    ) : this(
        id = id,
        type = type,
        nick = nick,
        text = text,
        isSelf = isSelf,
        time = time,
        date = date,
        matched = matched,
        level = level,
        originNetworkId = originNetworkId,
        newNick = newNick,
        kicked = kicked,
        invited = invited,
        modes = modes,
        newIdent = newIdent,
        newHost = newHost,
        userhost = userhost,
        account = account,
        bookmarked = bookmarked,
        msgid = msgid,
        isE2E = isE2E,
        reactions = reactions,
        replyTo = replyTo,
        replyToSelf = replyToSelf,
        replyQuote = null,
        unstrippedText = null,
        relayBot = null,
        relaySource = null,
    )

    /**
     * This line with its highlight taken off — what a `NOHIGHLIGHT` ignore rule leaves behind
     * (lurker #301): still visible, still counted, but never drawn as a mention.
     *
     * The server already suppresses the match at insert time for rules that existed then, so
     * this is what makes a rule added *later* apply to backlog the server stamped before it —
     * and, just as importantly, what lets the wash come back when the rule is removed, since
     * the demotion happens per render rather than being written into the store.
     */
    fun unhighlighted(): Message {
        if (!matched) return this
        return copy(matched = false)
    }

    /**
     * This line as spoken by the person a relay bot was quoting (lurker#277): `nick` and
     * `text` become the embedded speaker and their words, and the bot moves to `relayBot`.
     *
     * Everything else is carried over deliberately, `id` included — this is the same log line,
     * shown differently, so a bookmark, a jump and a mark-read all still address it. `isSelf`
     * comes along too and is always false, because `RelayBotSet.reattributing` won't touch a line
     * you sent.
     *
     * The one producer of a relayed `Message`, which is why the two fields it sets are writable
     * from this class alone. It takes a non-null `speaker` because a parse that produced no
     * nick isn't a re-attribution at all — see the empty-nick check in `RelayEnvelope.parse`.
     */
    fun relayed(speaker: String, text: String, bot: String, source: String?): Message =
        copy(nick = speaker, text = text, relayBot = bot, relaySource = source)

    /**
     * This reply as it reads (lurker-ios#184): its quote, and its text without the address the
     * quote makes redundant. A display transform like `relayed`, applied at render time — the
     * stored row keeps its text, so ignoring the author later brings the address back with the
     * quote's loss, and a cancelled ignore restores both.
     */
    fun showingReply(quote: ReplyQuote?, text: String?): Message =
        copy(
            replyQuote = quote,
            unstrippedText = if (text != this.text) this.text else unstrippedText,
            text = text,
        )

    /** The text as it was sent — what Copy puts on the clipboard, a reply's address included. */
    val copyText: String? get() = unstrippedText ?: text

    /**
     * The `user@host` half of `userhost` (which arrives as the full `nick!user@host`), or null
     * when either piece is missing.
     *
     * Both halves are required: the server stores an empty ident or host when it lacks one
     * (`nick!@host` / `nick!ident@`), and a half-mask like `@host` reads worse than nothing.
     * Same rule the web applies in `eventHostSuffix()`.
     *
     * Port note: splits on the first `!` and `@` UTF-16 units, as the web does. LurkerKit
     * searches by `Character`, so a `!` or `@` carrying a combining mark is not a separator
     * there and is one here.
     */
    val userHostMask: String?
        get() {
            val userhost = userhost ?: return null
            val bang = userhost.indexOf('!')
            if (bang < 0) return null
            val rest = userhost.substring(bang + 1)
            val at = rest.indexOf('@')
            if (at < 0) return null
            val user = rest.substring(0, at)
            val host = rest.substring(at + 1)
            if (user.isEmpty() || host.isEmpty()) return null
            return "$user@$host"
        }

    /**
     * The post-change mask on a `chghost` line — `ident@host`, or whichever half the server
     * actually sent. A half-mask like `@host` reads worse than the host alone, so the two
     * are only joined with an `@` when both are present. Mirrors the web's `chghostMask()`
     * and the server's own mask construction in the CHGHOST handler.
     */
    val chghostMask: String
        get() {
            val ident = newIdent ?: ""
            val host = newHost ?: ""
            if (ident.isNotEmpty() && host.isNotEmpty()) return "$ident@$host"
            return if (host.isEmpty()) ident else host
        }

    /**
     * Whether this event has anything to show.
     *
     * An activity line (join/part/nick/mode/…) synthesizes its body from structured fields
     * — a join carries *no* `text` at all, yet renders "alice joined" — so its renderability
     * is whether the fields its line is built from are present, not whether `text` is. A
     * `nick` with no `newNick` or a `mode` with neither a change list nor text has nothing
     * to say: rendering it would produce a placeholder ("…is now someone", "chan set "), and
     * it would seed consolidation with an empty-nick identity. Those are filtered here rather
     * than papered over downstream.
     *
     * Everything else draws its body from `text`, so an event with no text is a blank row.
     * The server streams state-only events to a buffer alongside its log lines — a
     * `usermode` carrying `modes`, an `away-state` carrying an `away` object, `lag`,
     * `peer-presence` — none of which have a `text` field. The client parses them as
     * `Other` (it consumes none of them yet) and, left in, each renders as an empty line. The
     * web client either folds them into state or filters them; this is how
     * the client keeps them off screen without modeling every one.
     */
    val isRenderable: Boolean
        get() = when (type) {
            // Built from the actor nick; a reason/topic is optional.
            EventType.Join, EventType.Part, EventType.Quit, EventType.Topic -> hasNick
            // Both ends of the rename are needed to say "alice is now bob".
            EventType.Nick -> hasNick && !newNick.isNullOrEmpty()
            EventType.Kick -> hasNick && !kicked.isNullOrEmpty()
            EventType.Invite -> hasNick && !invited.isNullOrEmpty()
            // Needs somewhere to have changed *to*: with neither half of the new mask the line
            // reads "alice changed host to " and says nothing.
            EventType.Chghost -> hasNick && chghostMask.isNotEmpty()
            // A structured change list, or the raw mode string as a fallback.
            EventType.Mode -> modes.isNotEmpty() || hasText
            // Everything else draws its body from `text`.
            else -> hasText
        }

    private val hasNick: Boolean get() = !nick.isNullOrEmpty()

    /**
     * Foundation's whitespace, not `trim()`'s: a line that is nothing but a zero-width space is
     * blank, and is filtered out rather than drawn as an empty row.
     */
    private val hasText: Boolean get() = (text ?: "").trimmingWhitespacesAndNewlines().isNotEmpty()
}

/**
 * What class of thing a single mode change is, as classified by the SERVER.
 *
 * Telling `+o alice` (op churn) from `+b alice` (a ban whose mask happens to look like a
 * nick) needs the network's ISUPPORT `PREFIX` and `CHANMODES`, which no client is sent. So
 * the server classifies each change at publish time and stamps it; the stamp rides
 * `extra.modes` into storage, so a backlog row answers the same way a live one does.
 *
 * ⚠ Never derive this locally from a hardcoded `q a o h v` set. That was tried on the web
 * and disagreed with solanum, where `+q` is a *quiet* — a list mode whose mask is often a
 * bare nick (lurker#486). The member `modes` letters elsewhere in this file are a display
 * ordering, not a classification set.
 *
 * Port note: the cases keep LurkerKit's names, which in PascalCase puts a `List` inside this
 * enum. Within its body that name means the case.
 */
enum class ModeChangeKind(val rawValue: String) {
    /** A member's status changed; `param` is a nick. The churn the filters care about. */
    Prefix("prefix"),

    /** A mask went onto or off a list mode — a ban, exemption or quiet. */
    List("list"),

    /** A channel flag or parameter mode: `+m`, `+k`, `+l`. */
    Chan("chan");

    companion object {
        fun fromRawValue(raw: String): ModeChangeKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One entry from a `mode` event's change list, e.g. `+o` on `alice`. `param` is null for
 * paramless channel flags like `+n`/`+t`.
 */
data class ModeChange(
    /** The signed mode token, e.g. `"+o"` or `"-v"`. */
    val mode: String,
    /**
     * The argument the mode applies to, when it takes one (a nick for `+o`, a mask for
     * `+b`, a key for `+k`). Null for a bare channel flag.
     */
    val param: String?,
    /**
     * The server's classification. Null on rows stored before the stamp existed — treat
     * missing as "not prefix", which is the fail-visible direction: an unclassified row
     * shows and never folds.
     */
    val kind: ModeChangeKind? = null,
) {
    /**
     * The letter of the signed token, i.e. `+o` → `o`.
     *
     * Port note: the sign is the first UTF-16 unit. LurkerKit's `hasPrefix` compares
     * `Character`s, so a `+` or `-` carrying a combining mark is not a sign there (the token
     * is its own letter, and a grant) and is one here. The server sends neither.
     */
    val letter: String
        get() = if (mode.startsWith("+") || mode.startsWith("-")) mode.substring(1) else mode

    /** Whether the change is a member-status grant (`+`) rather than a revocation. */
    val isGrant: Boolean get() = !mode.startsWith("-")
}

/**
 * Severity of a system-buffer line. Absent/unrecognized → `Info`, matching the server's
 * own default.
 */
enum class SystemLevel(val rawValue: String) {
    Info("info"),
    Warn("warn"),
    Error("error");

    companion object {
        fun fromRawValue(raw: String): SystemLevel? = entries.firstOrNull { it.rawValue == raw }

        fun from(raw: String?): SystemLevel {
            if (raw == null) return Info
            return fromRawValue(raw) ?: Info
        }
    }
}

/**
 * The event enum the domain renders against. The server sends far more `type`s
 * than a 1.0 client special-cases (join/part/quit/mode/names/typing/presence/…);
 * everything not yet handled folds into `Other` until a feature needs it.
 *
 * Port note: the cases keep LurkerKit's names, which in PascalCase puts a `Message`, an
 * `Error` and a `System` inside this enum. Within its body those names mean the cases.
 * `Other`'s raw value is the empty string, as in LurkerKit — so `fromRawValue("")` is `Other`.
 */
enum class EventType(val rawValue: String) {
    Message("message"),
    Action("action"),
    Notice("notice"),
    Error("error"),
    System("system"),
    Join("join"),
    Part("part"),
    Quit("quit"),
    Nick("nick"),
    Kick("kick"),
    Mode("mode"),
    Chghost("chghost"),
    Topic("topic"),
    Motd("motd"),
    Invite("invite"),
    E2e("e2e"),
    Ctcp("ctcp"),
    Other("");

    /** Types that render as someone speaking (vs. a structural/system line). */
    val isSpeech: Boolean get() = this == Message || this == Action || this == Notice

    /**
     * Structural "narration" about the room: membership churn and channel state. Each
     * names its actor inside the synthesized sentence ("alice joined", "bob is now
     * bob_afk", "mode by chan: +o"), so — exactly like an `action` — it renders header-less,
     * since an author header would say the name twice.
     *
     * This set also defines what participates in join consolidation (see `Consolidation`):
     * a run of consecutive activity lines collapses into one net-effect summary.
     */
    val isActivity: Boolean
        get() = when (this) {
            Join, Part, Quit, Nick, Kick, Mode, Topic, Invite, Chghost -> true
            else -> false
        }

    /**
     * Whether the line's author is separate from its text — which is what earns it an author
     * header in the list, and what lets several of them stack under one.
     *
     * (Named `isBubble` from when the iOS list drew these as chat bubbles. The distinction
     * outlived the bubbles: it's still exactly "does this line need to be told who said it".)
     *
     * One category rather than a taxonomy sorted by how "conversational" a line was judged to
     * be — that taxonomy kept drawing plain conversation as log output: your DM to NickServ was
     * captioned while its reply wasn't, and a `-SaslServ-` notice sat bare under a run of
     * captioned lines for no reason a reader could see. So message, notice, and the server
     * buffer's own text (motd/system/error/…) all take a header — a nick, or the network
     * speaking in its own voice.
     *
     * The exceptions are `action` and the `isActivity` events: they put the actor inside the
     * sentence, so a header would name them twice. They render header-less, flush with where a
     * nick would be — as they do in IRCCloud, Slack and Telegram.
     */
    val isBubble: Boolean get() = this != Action && !isActivity

    companion object {
        fun fromRawValue(raw: String): EventType? = entries.firstOrNull { it.rawValue == raw }

        fun from(raw: String?): EventType {
            if (raw == null) return Other
            return fromRawValue(raw) ?: Other
        }
    }
}
