// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.model.IgnoreRule

// The slash-command surface, ported from the web client's `handleCommand` dispatcher
// (`vue_client/src/components/MessageInput.vue`). The two clients speak the same wire, so a
// command has to translate to the same verbs on both — `/me` is an `action`, `/join` is a
// `join`, `/nick` is a `raw NICK`, and anything unrecognized falls through to `raw` exactly
// as the web does. The parsing is pure and lives here (not in the composer) so the whole
// vocabulary is unit-testable without a UI.
//
// What the app deliberately does NOT carry, and why:
//  - `/set` `/get` — web-only settings console; the app's settings are a native screen
//    (lurker-ios#20).
//  - `/network` `/net` — network CRUD is REST-heavy and owns its own issue (lurker-ios#11).
//  - `/highlight` `/unhighlight` — highlight-rule management, still unported (lurker-ios#13).
//  - `/e2e` `/list` `/jitsi` — web-specific or unbuilt features. `/dcc` carries its chat verbs
//    only; DCC file transfers have no screen here, and say so.
//  - `/server` — adding a network is a form on this client (the networks screen), not a
//    command; intercepted with a note rather than left to the raw fallback.

// MARK: - Effects

/**
 * One thing a parsed command asks the app to do. A command resolves to zero or more of
 * these: `/cycle` is a part then a join, `/msg alice hi` is a send then an activate. The
 * parser only decides; `ChatViewModel` performs the I/O. Effects that touch a network
 * (`send`/`action`/`notice`/`raw`/`join`/`part`/`close`/`ctcp`) run against the issuing
 * buffer's network — they carry a target/channel but not the id, which the executor
 * supplies from context. So do `away`/`back` (lurker#994), which the server then scopes:
 * that network, or every network for `-all`, the `away.all_networks` setting, or no
 * network at all (the system buffer).
 */
sealed interface CommandEffect {
    /**
     * PRIVMSG to a target. The server splits it on newlines and byte-length, so the full
     * text goes as one `send` — the client never chunks (see `LurkerClient.sendMessage`).
     */
    data class Send(val target: String, val text: String) : CommandEffect

    /** CTCP ACTION — `/me`, `/slap`. */
    data class Action(val target: String, val text: String) : CommandEffect

    /** NOTICE. */
    data class Notice(val target: String, val text: String) : CommandEffect

    /**
     * A raw IRC line on the issuing network: the escape hatch for `NICK`, `MODE`, `KICK`,
     * `WHOIS`, service messages, server queries, and every unknown command.
     */
    data class Raw(val line: String) : CommandEffect

    /** Join a channel, with an optional key. */
    data class Join(val channel: String, val key: String?) : CommandEffect

    /** Part a channel with an optional reason. The buffer survives, parted. */
    data class Part(val channel: String, val reason: String?) : CommandEffect

    /** Close a buffer (parts a channel / untracks a DM). */
    data class Close(val target: String) : CommandEffect

    /**
     * Hide this buffer's history behind a `/clear` marker, or drop the marker (lurker-ios#121).
     *
     * Server-side and per-user, so it takes effect on every device — and NOT a local wipe:
     * nothing is deleted, the messages are hidden behind a boundary the user can undo from
     * the divider or with `/clear off`.
     */
    data class Clear(val target: String, val undo: Boolean) : CommandEffect

    /**
     * Away on the network it's typed on; an empty message clears it (the server treats
     * `/away` with no text as `/back`). `all` is the `-all` (true) or `-one` (false) flag, null
     * without one, which leaves the scope to the server's `away.all_networks` setting
     * (lurker#994).
     */
    data class Away(val message: String, val all: Boolean?) : CommandEffect

    /** Back on the network it's typed on, scoped like `away`. */
    data class Back(val all: Boolean?) : CommandEffect

    /** A CTCP request aimed at a target — `/ctcp`, `/ping`. */
    data class Ctcp(val target: String, val type: String, val args: String) : CommandEffect

    /**
     * Open the target buffer and switch the UI to it — the DM that `/msg` and `/query`
     * open. The executor turns this into navigation.
     */
    data class Activate(val target: String) : CommandEffect

    /**
     * Ask the server to store an ignore rule (lurker-ios#86).
     *
     * Unlike every wire effect above, this carries its own scope rather than running on the
     * issuing buffer's network — because the two aren't the same question. **A null `scope` is
     * a GLOBAL rule, applying on every network**, and it's the default: `-network` is what
     * opts a rule into the connection it was typed on (lurker#350). (Note that's the opposite
     * of the null convention buffers use, where no network means the app-scoped system buffer.)
     *
     * `receipt` is the line to print *if the frame reaches a socket*. It rides along rather
     * than being a separate `info` effect because whether it may be said isn't known until
     * the verb is sent: there is no queue behind these, so a composer used before the socket
     * is up (the cold-launch window, where `start()` awaits a REST call first) would
     * otherwise print "ignore added" for a rule that went nowhere.
     */
    data class AddIgnore(val scope: Int?, val rule: IgnoreRule, val receipt: String) : CommandEffect

    /**
     * Ask the server to drop ignore rules — by `id` (what a listed index resolves to) or by
     * `mask` (every rule carrying it). `scope` and `receipt` read as they do for `addIgnore`;
     * a by-mask removal on a network scope clears matching globals too, which is the server's
     * rule and why the receipt counts what the client can see rather than claiming a total.
     */
    data class RemoveIgnore(val scope: Int?, val id: Int?, val mask: String?, val receipt: String) : CommandEffect

    /**
     * Ask the server to mark or unmark a nick as a relay/bridge bot (lurker#277).
     *
     * `networkId` is carried rather than supplied by the executor because a mark is *about* a
     * connection rather than sent *on* one — it's per-(network, nick) view state, like a nick
     * note, and it's stored whether or not that network is currently up.
     *
     * `pattern` is the custom envelope template; empty means the built-in formats. `receipt`
     * rides along and is withheld when the verb never reached a socket, exactly as it does for
     * the two ignore effects above.
     */
    data class SetRelayBot(
        val networkId: Int,
        val nick: String,
        val marked: Boolean,
        val pattern: String,
        val receipt: String,
    ) : CommandEffect

    /**
     * Open a person's profile (lurker-ios#12) — what `/whois` does now.
     *
     * ⚠ Deliberately NOT paired with a `.raw("WHOIS …")`. The profile asks for itself on
     * open, through `requestWhois`, which owns the in-flight bookkeeping; sending one here
     * too would put two WHOIS on the wire for one command, and the second would be dropped
     * by that very bookkeeping. The server buffer still gets the raw numerics either way —
     * they arrive by the default-show `raw` path, not because of who asked.
     */
    data class ShowProfile(val nick: String) : CommandEffect

    /**
     * Start the issuing buffer's network — `/connect` (lurker-ios#152).
     *
     * The three lifecycle effects are REST verbs (`POST /api/networks/:id/connect` and its
     * siblings), not socket frames: they answer with a refusal or with nothing, and the
     * transition itself arrives later as `state` events. They carry no network id for the
     * same reason the wire effects don't — the executor supplies the issuing buffer's.
     */
    data object Connect : CommandEffect

    /**
     * Stop the issuing buffer's network — `/disconnect`, `/quit`. A null reason lets the
     * server send its default quit message, the same line an auto-disconnect uses.
     *
     * ⚠ Never a `.raw("QUIT …")`. A raw QUIT leaves irc-framework's `requested_disconnect`
     * flag unset, so the close looks unexpected and the server reconnects on its own; the
     * disconnect endpoint is what records the intent (the web learned this in lurker#785).
     */
    data class Disconnect(val reason: String?) : CommandEffect

    /**
     * Restart the issuing buffer's network — `/reconnect`. Idempotent server-side: it works
     * whether the network is up, mid-retry, or stopped after a `/disconnect`.
     */
    data object Reconnect : CommandEffect

    /**
     * Open a DCC chat with `nick` on the issuing buffer's network — `/dcc chat` (lurker#270).
     * Also how an offer `nick` made us is ACCEPTED: the server answers a waiting offer rather
     * than making a counter-offer, the same doubling irssi's `/dcc chat` has.
     *
     * A REST verb (`POST /api/dcc/chat`), like the connection ones: the server answers once the
     * offer is away, and everything after — connected, refused, timed out — arrives as notices
     * in the chat's `=nick` buffer. `passive` asks the peer to listen instead of us.
     */
    data class DccChat(val nick: String, val passive: Boolean) : CommandEffect

    /** End a DCC chat with `nick`, cancel our offer to them, or decline theirs — `/dcc close chat`. */
    data class DccCloseChat(val nick: String) : CommandEffect

    /**
     * React to the last line someone else said in the issuing buffer — `/react`
     * (lurker-ios#183). The parser has already checked the value; which line it lands on is
     * the executor's question, since only the store knows what was said last.
     */
    data class React(val value: String) : CommandEffect

    /**
     * A local, ephemeral info line printed into the issuing buffer: `/commands` output, a
     * usage hint, or a "not in the app yet" note. Never touches the network.
     */
    data class Info(val text: String) : CommandEffect
}

/** What a raw line of composer input turned out to be. */
sealed interface ParsedInput {
    /**
     * Ordinary text to PRIVMSG to the current buffer — a plain message, or a `//`-escaped
     * line sent literally with the leading slash stripped.
     */
    data class Message(val text: String) : ParsedInput

    /**
     * Non-command input typed into the system buffer, which has no network to send to. The
     * caller prints the nudge rather than dropping it silently.
     */
    data object NotCommand : ParsedInput

    /** A slash command, resolved to the effects to carry out in order. */
    data class Command(val effects: List<CommandEffect>) : ParsedInput
}

// MARK: - Command table

/** How a command groups in the `/commands` cheatsheet and in the completion chips. */
enum class CommandCategory(val rawValue: String) {
    Messaging("Messaging"),
    Channels("Channels"),
    Moderation("Moderation"),
    Server("Server"),
    Status("Status"),
    App("App");

    companion object {
        fun fromRawValue(raw: String): CommandCategory? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What an argument slot expects — the signal that drives autocomplete. Only `.channel` and
 * `.nick` produce completion chips; the rest are free text or opaque tokens the app can't
 * suggest for (and where an `@`-mention can still fire).
 */
enum class ArgKind {
    Channel,
    Nick,

    /** Free text running to the end of the line (a message body, a reason, a topic). */
    Text,

    /** A single opaque token with nothing to suggest (a channel key, a raw mode string). */
    Word,

    /** Your own new nick — a value only you can supply. */
    NewNick,

    /**
     * A literal word, typed as-is — `chat` in `/dcc chat <nick>`. Shown bare in usage, never
     * completed, and what tells one of a command's forms from another.
     */
    Keyword,

    /**
     * An option that may be typed in this position — `-passive`. Always optional: a token that
     * doesn't start with `-` skips past it.
     */
    Flag,
    None,
}

/**
 * One positional argument in a command's grammar. Used for the usage hints in `/commands`
 * and to tell the completer what the slot under the caret wants.
 */
data class ArgSpec(
    val label: String,
    val kind: ArgKind,
    val optional: Boolean = false,
    /**
     * Whether this slot swallows the rest of the line. A trailing `.text` reason or body is
     * `rest`; a channel or a nick is a single token.
     */
    val rest: Boolean = false,
)

/**
 * A command's identity for the table: its names (canonical first, then aliases), where it
 * files, a one-line summary, and its argument grammar. This is the single source the
 * `/commands` help and the completion chips both read; the actual wire translation lives in
 * `CommandParser` (a `when`, mirroring the web's `handleCommand`), the same split the web
 * keeps between its `COMMANDS_LINES` cheatsheet and its dispatcher.
 *
 * Port note: the primary constructor holds what LurkerKit *stores* (`forms`, already
 * resolved); the secondary one is LurkerKit's `init`, which takes `args` or `forms` and
 * defaults the rest. The table below is written against the secondary.
 */
data class CommandSpec(
    val names: List<String>,
    val category: CommandCategory,
    val summary: String,
    /**
     * The grammar, one entry per form. Nearly every command has one. `/dcc` has two —
     * `chat [-passive] <nick>` and `close chat <nick>` — told apart by their keywords, because
     * one positional list could only describe them as a shape neither form actually has.
     */
    val forms: List<List<ArgSpec>>,
    /**
     * Runs without an active network — the system buffer can issue it (`/away`, `/back`,
     * `/commands`, `/ignore`, `/unignore`). Everything else needs a channel or DM.
     *
     * Documentation, not the gate: what actually lets a verb run there is its branch sitting
     * above `CommandParser.resolve`'s `networkId == null` gate, and nothing reads this field.
     * Kept as the table's statement of the same fact — the two are checked against each other
     * by `testEveryNetworkAgnosticSpecIsActuallyReachableFromTheSystemBuffer`.
     */
    val networkAgnostic: Boolean,
) {
    constructor(
        names: List<String>,
        category: CommandCategory,
        summary: String,
        args: List<ArgSpec> = emptyList(),
        forms: List<List<ArgSpec>>? = null,
        networkAgnostic: Boolean = false,
    ) : this(
        names = names,
        category = category,
        summary = summary,
        forms = forms ?: listOf(args),
        networkAgnostic = networkAgnostic,
    )

    /** The first form: a single-form command's whole grammar. */
    val args: List<ArgSpec> get() = forms.firstOrNull() ?: emptyList()

    /** The canonical name, without the leading slash. */
    val name: String get() = names[0]

    /**
     * The kind of the argument being typed, given the whole tokens already typed after the verb
     * (`preceding`) and the part of this one before the caret (`typing`).
     *
     * Resolved from what was typed rather than by counting, so a form's keywords can pick it and
     * an optional flag can be there or not: `/dcc close chat b` and `/dcc chat -passive b` both
     * land on the nick. The first form the tokens fit answers. For a command with neither — every
     * command but `/dcc` — this is exactly a position count, with a trailing `rest` slot answering
     * past its end (so the third nick of `/op a b c` still reads as a nick).
     */
    fun argKind(preceding: List<String>, typing: String = ""): ArgKind {
        for (form in forms) {
            val kind = kind(form, preceding, typing)
            if (kind != null) return kind
        }
        return ArgKind.None
    }

    /** The usage line shown by `/commands`, e.g. `/msg <nick> [message]` — one per form, joined. */
    val usage: String
        get() = forms.joinToString(" · ") { form ->
            (listOf("/$name") + form.map { usage(it) }).joinToString(" ")
        }

    companion object {
        /** Walk one form. Null when the typed tokens don't fit it. */
        private fun kind(form: List<ArgSpec>, preceding: List<String>, typing: String): ArgKind? {
            var slot = 0

            // A flag slot is filled by a `-` token and skipped by anything else.
            fun skipFlags(token: String) {
                while (slot < form.size && form[slot].kind == ArgKind.Flag && !token.startsWith("-")) slot += 1
            }
            for (token in preceding) {
                skipFlags(token)
                if (slot >= form.size) {
                    // Past the end, only a trailing `rest` slot takes more.
                    if (form.lastOrNull()?.rest == true) continue
                    return null
                }
                val arg = form[slot]
                if (arg.kind == ArgKind.Keyword && token.lowercase() != arg.label.lowercase()) return null
                slot += 1
            }
            skipFlags(typing)
            if (slot < form.size) return form[slot].kind
            val last = form.lastOrNull()
            if (last != null && last.rest) return last.kind
            return null
        }

        private fun usage(arg: ArgSpec): String =
            when (arg.kind) {
                ArgKind.Keyword -> arg.label
                ArgKind.Flag -> "[${arg.label}]"
                else -> {
                    val inner = if (arg.rest && arg.kind == ArgKind.Nick) "${arg.label}…" else arg.label
                    if (arg.optional) "[$inner]" else "<$inner>"
                }
            }
    }
}

/** The client's command vocabulary. Order within a category is the order `/commands` prints. */
object CommandRegistry {
    val all: List<CommandSpec> = listOf(
        // Messaging
        CommandSpec(
            listOf("me"), CommandCategory.Messaging, "Send an action to this buffer",
            args = listOf(ArgSpec("action", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("msg", "query"), CommandCategory.Messaging, "Open a DM and optionally send a message",
            args = listOf(ArgSpec("nick", ArgKind.Nick), ArgSpec("message", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("notice"), CommandCategory.Messaging, "Send a NOTICE",
            args = listOf(ArgSpec("target", ArgKind.Nick), ArgSpec("message", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("react"), CommandCategory.Messaging, "React to the last line someone else said",
            args = listOf(ArgSpec("emoji or text", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("slap"), CommandCategory.Messaging, "Slap someone with a large trout",
            args = listOf(ArgSpec("nick", ArgKind.Nick)),
        ),
        CommandSpec(
            listOf("shrug"), CommandCategory.Messaging, "Say ¯\\_(ツ)_/¯, after your own text if any",
            args = listOf(ArgSpec("text", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("ctcp"), CommandCategory.Messaging, "Send a CTCP request",
            args = listOf(
                ArgSpec("target", ArgKind.Nick), ArgSpec("type", ArgKind.Word),
                ArgSpec("args", ArgKind.Text, optional = true, rest = true),
            ),
        ),
        CommandSpec(
            listOf("ping"), CommandCategory.Messaging, "CTCP PING a user",
            args = listOf(ArgSpec("nick", ArgKind.Nick)),
        ),
        // irssi's syntax exactly (lurker#270), type-first on close: `/dcc close chat bob`.
        // `-passive` asks the peer to listen, for when this server can't be reached.
        CommandSpec(
            listOf("dcc"), CommandCategory.Messaging, "Start a direct (DCC) chat, or end one",
            forms = listOf(
                listOf(
                    ArgSpec("chat", ArgKind.Keyword), ArgSpec("-passive", ArgKind.Flag, optional = true),
                    ArgSpec("nick", ArgKind.Nick),
                ),
                listOf(ArgSpec("close", ArgKind.Keyword), ArgSpec("chat", ArgKind.Keyword), ArgSpec("nick", ArgKind.Nick)),
            ),
        ),

        // Channels
        CommandSpec(
            listOf("join", "j"), CommandCategory.Channels, "Join a channel",
            args = listOf(ArgSpec("channel", ArgKind.Channel), ArgSpec("key", ArgKind.Word, optional = true)),
        ),
        CommandSpec(
            listOf("part", "leave", "p"), CommandCategory.Channels, "Leave a channel (keeps the buffer)",
            args = listOf(
                ArgSpec("channel", ArgKind.Channel, optional = true),
                ArgSpec("reason", ArgKind.Text, optional = true, rest = true),
            ),
        ),
        CommandSpec(
            listOf("cycle", "hop"), CommandCategory.Channels, "Part and rejoin this channel",
            args = listOf(ArgSpec("reason", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(listOf("close"), CommandCategory.Channels, "Close this buffer"),
        // Both literals, and `.word` rather than `.text`: `off` and `undo` are the whole
        // vocabulary and each is a single token, so a free-text slot running to the end of the
        // line would invite arguments that silently do the opposite of what they read.
        CommandSpec(
            listOf("clear"), CommandCategory.Channels, "Hide this buffer's history (undo with /clear off)",
            args = listOf(ArgSpec("off|undo", ArgKind.Word, optional = true)),
        ),
        CommandSpec(
            listOf("topic"), CommandCategory.Channels, "View or set the channel topic",
            args = listOf(ArgSpec("new topic", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("nick"), CommandCategory.Channels, "Change your nick",
            args = listOf(ArgSpec("newnick", ArgKind.NewNick)),
        ),
        CommandSpec(
            listOf("whois"), CommandCategory.Channels, "Look up a user",
            args = listOf(ArgSpec("nick", ArgKind.Nick, optional = true)),
        ),
        // Channel-first too, as /kick takes it. Completion follows the first form: nothing in a
        // form can say "this token is a channel", so the second is for `/commands` to show.
        CommandSpec(
            listOf("invite"), CommandCategory.Channels, "Invite a user to a channel",
            forms = listOf(
                listOf(ArgSpec("nick", ArgKind.Nick), ArgSpec("channel", ArgKind.Channel, optional = true)),
                listOf(ArgSpec("channel", ArgKind.Channel), ArgSpec("nick", ArgKind.Nick)),
            ),
        ),

        // Moderation
        CommandSpec(
            listOf("kick"), CommandCategory.Moderation, "Kick a user from this channel",
            args = listOf(ArgSpec("nick", ArgKind.Nick), ArgSpec("reason", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("kickban"), CommandCategory.Moderation, "Ban a user from this channel, then kick them",
            args = listOf(ArgSpec("nick", ArgKind.Nick), ArgSpec("reason", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("mode"), CommandCategory.Moderation, "Set channel or user modes",
            args = listOf(ArgSpec("modes", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("op"), CommandCategory.Moderation, "Give operator status",
            args = listOf(ArgSpec("nick", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("deop"), CommandCategory.Moderation, "Remove operator status",
            args = listOf(ArgSpec("nick", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("voice"), CommandCategory.Moderation, "Give voice",
            args = listOf(ArgSpec("nick", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("devoice"), CommandCategory.Moderation, "Remove voice",
            args = listOf(ArgSpec("nick", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("halfop"), CommandCategory.Moderation, "Give half-operator status",
            args = listOf(ArgSpec("nick", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("dehalfop"), CommandCategory.Moderation, "Remove half-operator status",
            args = listOf(ArgSpec("nick", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("ban"), CommandCategory.Moderation, "Ban a mask",
            args = listOf(ArgSpec("mask", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("unban"), CommandCategory.Moderation, "Lift a ban",
            args = listOf(ArgSpec("mask", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("quiet"), CommandCategory.Moderation, "Quiet a mask",
            args = listOf(ArgSpec("mask", ArgKind.Nick, rest = true)),
        ),
        CommandSpec(
            listOf("unquiet"), CommandCategory.Moderation, "Lift a quiet",
            args = listOf(ArgSpec("mask", ArgKind.Nick, rest = true)),
        ),
        // Ignoring is personal moderation — it files with /ban and /quiet, which are the same
        // intent aimed at a channel instead of at your own screen. Network-agnostic: rules are
        // global by default, so the system buffer can list and write them.
        CommandSpec(
            listOf("ignore"), CommandCategory.Moderation, "Ignore someone, or list your ignore rules",
            args = listOf(
                ArgSpec("mask", ArgKind.Nick, optional = true),
                ArgSpec("levels", ArgKind.Text, optional = true, rest = true),
            ),
            networkAgnostic = true,
        ),
        CommandSpec(
            listOf("unignore"), CommandCategory.Moderation, "Drop an ignore rule by its listed number or mask",
            args = listOf(ArgSpec("index|mask", ArgKind.Word)), networkAgnostic = true,
        ),

        // Server
        CommandSpec(
            listOf("raw", "quote"), CommandCategory.Server, "Send a raw IRC line",
            args = listOf(ArgSpec("line", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("ns"), CommandCategory.Server, "Message NickServ",
            args = listOf(ArgSpec("message", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("cs"), CommandCategory.Server, "Message ChanServ",
            args = listOf(ArgSpec("message", ArgKind.Text, rest = true)),
        ),
        CommandSpec(
            listOf("names"), CommandCategory.Server, "List the users on a channel",
            args = listOf(ArgSpec("channel", ArgKind.Channel, optional = true)),
        ),
        CommandSpec(
            listOf("who"), CommandCategory.Server, "Run a WHO query",
            args = listOf(ArgSpec("mask", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("whowas"), CommandCategory.Server, "Look up a departed nick",
            args = listOf(ArgSpec("nick", ArgKind.Nick, optional = true)),
        ),
        CommandSpec(listOf("motd"), CommandCategory.Server, "Show the message of the day"),
        CommandSpec(listOf("version"), CommandCategory.Server, "Query server version"),
        CommandSpec(listOf("time"), CommandCategory.Server, "Query server time"),
        CommandSpec(listOf("lusers"), CommandCategory.Server, "Show network user counts"),
        CommandSpec(listOf("links"), CommandCategory.Server, "List server links"),
        CommandSpec(listOf("map"), CommandCategory.Server, "Show the network map"),
        CommandSpec(
            listOf("stats"), CommandCategory.Server, "Query server statistics",
            args = listOf(ArgSpec("query", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(listOf("admin"), CommandCategory.Server, "Show server admin info"),
        CommandSpec(listOf("info"), CommandCategory.Server, "Show server info"),
        CommandSpec(
            listOf("userhost"), CommandCategory.Server, "Look up a user's host",
            args = listOf(ArgSpec("nick", ArgKind.Nick, optional = true)),
        ),
        CommandSpec(
            listOf("ison"), CommandCategory.Server, "Check whether nicks are online",
            args = listOf(ArgSpec("nick", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(
            listOf("help"), CommandCategory.Server, "Ask the server for help",
            args = listOf(ArgSpec("topic", ArgKind.Text, optional = true, rest = true)),
        ),
        // Connection lifecycle (lurker-ios#152): REST verbs on this buffer's network, never raw
        // lines — see `CommandEffect.Disconnect`. `/disconnect` is the canonical name here
        // because it pairs with `/connect` and with the networks screen's own label; the web
        // lists `/quit` first and `/disconnect` as its alias. Same verbs either way.
        CommandSpec(listOf("connect"), CommandCategory.Server, "Connect this network"),
        CommandSpec(
            listOf("disconnect", "quit"), CommandCategory.Server, "Disconnect this network",
            args = listOf(ArgSpec("reason", ArgKind.Text, optional = true, rest = true)),
        ),
        CommandSpec(listOf("reconnect"), CommandCategory.Server, "Reconnect this network"),

        // Status / app
        // irssi's and WeeChat's flags: `-all` reaches every network, `-one` just this one.
        CommandSpec(
            listOf("away"), CommandCategory.Status, "Set yourself away (-all: every network, -one: this one)",
            args = listOf(
                ArgSpec("-all|-one", ArgKind.Flag, optional = true),
                ArgSpec("message", ArgKind.Text, optional = true, rest = true),
            ),
            networkAgnostic = true,
        ),
        CommandSpec(
            listOf("back"), CommandCategory.Status, "Clear your away status (-all: every network, -one: this one)",
            args = listOf(ArgSpec("-all|-one", ArgKind.Flag, optional = true)), networkAgnostic = true,
        ),
        // Files under App rather than Moderation, where `/ignore` sits: a relay mark hides
        // nothing and silences nobody, it tells this client how to *read* a bot's lines. The
        // thing it changes is the log, not the room.
        CommandSpec(
            listOf("relay"), CommandCategory.App, "Mark a bridge bot so its lines show the real speaker",
            args = listOf(
                ArgSpec("list|add|remove", ArgKind.Word, optional = true),
                ArgSpec("nick", ArgKind.Nick, optional = true),
                ArgSpec("pattern", ArgKind.Text, optional = true, rest = true),
            ),
        ),
        CommandSpec(listOf("commands"), CommandCategory.App, "List the commands you can run", networkAgnostic = true),
    )

    /** The spec whose names include `verb` (case-insensitive), or null for an unknown verb. */
    fun spec(verb: String): CommandSpec? {
        val needle = verb.lowercase()
        return all.firstOrNull { it.names.contains(needle) }
    }

    /**
     * A bare `/` can't rank by likelihood, so it shows a hand-picked starter set that spans
     * categories rather than the first N of the table (which would be one category's block).
     * Most useful first — the completer puts the head of the list nearest the composer.
     */
    val featured: List<String> = listOf("join", "msg", "me", "nick", "topic", "away")

    /**
     * Specs whose canonical name starts with `query` (case-insensitive), best first, capped
     * at `limit`. An empty query returns the `featured` starter set. Only the canonical name
     * is matched, not aliases — the chips shouldn't offer `/query` and `/msg` as two things.
     */
    fun matching(query: String, limit: Int = 6): List<CommandSpec> {
        if (query.isEmpty()) {
            return featured.mapNotNull { name -> all.firstOrNull { it.name == name } }.take(limit)
        }
        val needle = query.lowercase()
        return all.filter { it.name.startsWith(needle) }.take(limit)
    }

    /** The `/commands` cheatsheet as one multi-line block, grouped by category. */
    fun helpText(): String {
        val lines = mutableListOf("Commands you can run here:")
        for (category in CommandCategory.entries) {
            val specs = all.filter { it.category == category }
            if (specs.isEmpty()) continue
            lines.add("")
            lines.add(category.rawValue)
            for (spec in specs) {
                lines.add("  ${spec.usage} — ${spec.summary}")
            }
        }
        return lines.joinToString("\n")
    }
}
