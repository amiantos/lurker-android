// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.support.graphemeBoundaries
import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.RelayBot
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.RelayEnvelope
import net.amiantos.lurkerkit.model.ScopedIgnoreRule
import net.amiantos.lurkerkit.support.Result
import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.splitOnSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespaces
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.time.Instant

/**
 * Turns a line of composer input into a `ParsedInput`. Pure and total — every string maps
 * to something, and nothing here does I/O. A faithful port of the web client's `submit()`
 * gating plus its `handleCommand` dispatcher, so both clients translate a given command to
 * the same wire verbs.
 */
object CommandParser {

    /**
     * A user-authored chat body, on its way to a channel or DM: `||spoiler||` becomes IRC
     * spoiler codes here and nowhere else.
     *
     * ⚠⚠ Opt-in PER CALL SITE, deliberately — never fold this into `ChatViewModel.send` or
     * `LurkerClient.sendMessage`. `/ns` and `/cs` build a raw `PRIVMSG NickServ :…` whose body
     * is usually `identify <password>`, and rewriting bytes headed for an auth handshake is a
     * bug, not a feature. Routing each chat verb through this is what keeps those untouched by
     * construction rather than by a guard someone has to remember. (They emit `.raw` rather
     * than `.send`, so on this client they're separated by shape too — but the rule is the
     * rule, and the web learned it the hard way.)
     *
     * Applied to: plain text, `//`-escaped text, `/me`, `/msg`, `/query`, `/notice`. Not to
     * `/slap` (its body is generated, not typed), nor `/raw`, `/quote`, `/ctcp`, `/ns`, `/cs`.
     * That set matches the web's `chatBody` callers; keep them in step.
     *
     * ⚠ Only the PAYLOAD is rewritten. Anything showing the user their own line back — a
     * failed-send notice, input history — must keep the TYPED text, so what they see and recall
     * is `||…||` rather than raw control codes.
     */
    private fun chatBody(text: String): String =
        SpoilerMarkup.apply(text)

    /**
     * Classify `input` typed in the buffer identified by (`networkId`, `target`).
     *
     * The rules, in order (matching the web's `submit`):
     *  - `//…` is an escape: send the rest literally, one slash stripped, so you *can* say a
     *    line that starts with a slash.
     *  - `/…` is a command.
     *  - anything else is a plain message — except in the system buffer, which has no
     *    network to send to, where it's `notCommand`.
     *
     * `ignores` is the account's rules, passed in rather than reached for so this stays pure:
     * `/ignore` with no arguments prints them and `/unignore <n>` addresses one by its
     * position. The whole set goes in rather than a materialized listing so that the two
     * verbs that need one build it and the other fifty don't — every plain message comes
     * through here too. It defaults to empty, the honest answer for a caller that has none.
     *
     * `relayBots` rides along for the same reason and with the same null convention: `/relay`
     * with no arguments prints the marks on this network, and null means "they haven't arrived
     * yet", so the listing says so rather than claiming there are none.
     *
     * `modeSpec` is the network's mode vocabulary, null while it's unknown: `/quiet` reads it to
     * tell a quiet list from an owner rank, and the mode shortcuts read its MODES limit.
     *
     * `hasBuffer` answers whether this network has a buffer by that name open. `/part`, `/topic`
     * and `/mode` ask it about a leading `&`, `+` or `!` word, which is a channel only when one by
     * that name exists (see `leadsWithChannel`). It defaults to "none", which reads every such
     * word as text.
     *
     * `now` is likewise injected, for `/ignore -time` and for lapsed rules.
     *
     * Port note: `formatted` is here and not in LurkerKit. It is the expiry formatter
     * `IgnoreRule.summary` takes — the kit never formats a date for display — handed straight
     * through to the `/ignore` listing and to the `/ignore` and `/unignore` receipts, the only
     * lines that can carry one. It has no default because there is no honest one; see
     * `IgnoreRule.summary` for what it should hand back.
     *
     * Port note: the line is cut up by UTF-16 unit where LurkerKit cuts it by `Character`, a
     * whole grapheme cluster. The two agree unless a combining mark, a joiner or a variation
     * selector follows one of the characters the parser looks for — which fuses the two into a
     * cluster that is no longer that character to LurkerKit — or a prepending mark (U+0600 and
     * its kind) precedes a space and fuses with it the same way:
     *  - a `/` wearing a mark doesn't open a command there (the line is a message), and does
     *    here; a second `/` wearing one doesn't make the `//` escape there (the line is a
     *    command, whose verb starts with that slash and goes raw), and does here. `startsWith`
     *    asks about the units; LurkerKit's `hasPrefix` wants the prefix to end on a cluster
     *    boundary;
     *  - a space wearing a mark is still a separator there and takes the mark with it; here the
     *    space separates and the mark starts the next token. A space fused onto a prepending
     *    mark before it is no separator at all there, and is one here;
     *  - the `+`/`-` that opens a `/mode` flag run or a `/dcc` option, and the first-character
     *    tests this calls on (`ChannelName.isChannelTarget`, `DccChat.isTarget`, the quotes in
     *    `IgnoreArgs.tokenize`), read a marked character as itself here and as something else
     *    there.
     * Checked against the Swift over some 80,000 generated lines covering every command; every
     * difference was one of these three.
     */
    fun parse(
        input: String,
        networkId: Int?,
        target: String,
        ignores: IgnoreSet? = IgnoreSet.empty,
        relayBots: RelayBotSet? = RelayBotSet.empty,
        modeSpec: ModeSpec? = null,
        hasBuffer: (String) -> Boolean = { false },
        now: Instant = Instant.now(),
        formatted: (Instant) -> String,
    ): ParsedInput {
        // The composer trims before it hands text over, but be total about it anyway.
        val raw = input

        if (raw.startsWith("//")) {
            // A `//`-escaped literal only has somewhere to go in a real buffer; in the system
            // buffer it's non-command input like any other, so nudge rather than swallow it.
            return if (networkId == null) ParsedInput.NotCommand else ParsedInput.Message(chatBody(raw.substring(1)))
        }
        if (!raw.startsWith("/")) {
            return if (networkId == null) ParsedInput.NotCommand else ParsedInput.Message(chatBody(raw))
        }

        // Split the verb off the rest. `rest` is the whitespace-collapsed token list (the
        // web's `[cmd, ...rest] = line.slice(1).split(/\s+/)`); `argLine` is everything after
        // the verb, edge-trimmed but with interior spacing preserved (the web's `argLine`).
        val body = raw.substring(1)
        // Port note: the verb is measured as typed, before it is lowercased. LurkerKit drops
        // `verb.count` Characters, and lowercasing never changes that count; it can change the
        // UTF-16 length (`İ` lowercases to two units), which is what is dropped here.
        val typed = body.takeWhile { !it.isSwiftWhitespace() }
        val verb = typed.lowercase()
        // Newlines too, like the web's `trim()`: `/me⏎waves` (a multi-line paste, a
        // shift-return) would otherwise send the newline at the front of the action.
        val argLine = body.substring(typed.length).trimmingWhitespacesAndNewlines()
        val rest = if (argLine.isEmpty()) emptyList() else argLine.splitOnSwiftWhitespace()

        return ParsedInput.Command(
            resolve(
                verb = verb, fullBody = body, argLine = argLine, rest = rest,
                networkId = networkId, target = target, ignores = ignores, relayBots = relayBots,
                modeSpec = modeSpec, hasBuffer = hasBuffer, now = now, formatted = formatted,
            )
        )
    }

    // MARK: - Dispatch

    private fun resolve(
        verb: String,
        fullBody: String,
        argLine: String,
        rest: List<String>,
        networkId: Int?,
        target: String,
        ignores: IgnoreSet?,
        relayBots: RelayBotSet?,
        modeSpec: ModeSpec?,
        hasBuffer: (String) -> Boolean,
        now: Instant,
        formatted: (Instant) -> String,
    ): List<CommandEffect> {
        // A lone `/` (or `/ `) has no verb — nudge rather than fall through to the raw
        // default, which would put an empty line on the wire.
        if (verb.isEmpty()) {
            return listOf(CommandEffect.Info("Type a command after the slash — /commands lists what you can run."))
        }

        // Network-agnostic block: these run whether or not a network is active, so the
        // system buffer can issue them.
        when (verb) {
            "commands" ->
                return listOf(CommandEffect.Info(CommandRegistry.helpText()))
            "away", "back" -> {
                // Empty message clears away. The network it's typed on goes with it, and from the
                // system buffer there's none, which the server reads as every network — so `-one`
                // there is refused rather than quietly reaching them all.
                val (all, message) = awayFlag(argLine)
                if (all == false && networkId == null) {
                    return listOf(CommandEffect.Info("/$verb -one: there's no network here. Run it in a network's buffer."))
                }
                return if (verb == "away") {
                    listOf(CommandEffect.Away(message = message, all = all))
                } else {
                    listOf(CommandEffect.Back(all = all))
                }
            }
            // Ignore rules are global by default, so both verbs run without a network — the system
            // buffer can list them and write them. Only `-network` needs a connection, and that's
            // checked where it's read.
            "ignore" ->
                return resolveIgnore(
                    argLine = argLine, networkId = networkId, ignores = ignores, now = now, formatted = formatted,
                )
            "unignore" ->
                return resolveUnignore(
                    argLine = argLine, networkId = networkId, ignores = ignores, now = now, formatted = formatted,
                )
            else -> {}
        }

        // Network gate: everything below needs a channel or DM. In the system buffer, say so
        // rather than dropping the line.
        //
        // Bound rather than merely tested, so the one case below that needs the id — `/relay`,
        // whose marks are per-(network, nick) — takes it from the gate instead of restating it or
        // asserting it non-null. Nothing else past this point reads it: the wire effects carry a
        // target and let the executor supply the network.
        if (networkId == null) {
            // The connection verbs (lurker-ios#152) act on a network rather than on a
            // conversation, and the server buffer is as good a place to type them as a channel —
            // so their gate says so, instead of sending someone with an offline network to a
            // channel they can't join yet.
            if (listOf("connect", "disconnect", "quit", "reconnect").contains(verb)) {
                return listOf(
                    CommandEffect.Info(
                        "/$verb needs a network — open one of its buffers first, or use Settings → Networks."
                    )
                )
            }
            return listOf(CommandEffect.Info("/$verb needs an active network — switch to a channel or DM first."))
        }

        when (verb) {
            // Messaging
            "me" ->
                return if (argLine.isEmpty()) {
                    emptyList()
                } else {
                    listOf(CommandEffect.Action(target = target, text = chatBody(argLine)))
                }
            "react" -> {
                val value = argLine.trimmingWhitespaces()
                if (value.isEmpty()) return listOf(CommandEffect.Info("usage: /react <emoji|text> — e.g. /react 👍"))
                // The web turns `:tada:` into 🎉 from its emoji table. This client has no table, and
                // sending the name would react with the literal text, so it refuses instead. Only the
                // closed form: `:D` and `:P` are reactions people type on purpose.
                if (isShortcode(value)) {
                    return listOf(
                        CommandEffect.Info(
                            "/react: emoji names like $value aren't supported here — use the emoji itself, e.g. /react 👍"
                        )
                    )
                }
                if (!Reactions.isValidValue(value)) {
                    return listOf(
                        CommandEffect.Info("a reaction can be at most ${Reactions.maxGraphemes} characters")
                    )
                }
                return listOf(CommandEffect.React(value = value))
            }
            "slap" -> {
                val who = rest.firstOrNull() ?: return listOf(CommandEffect.Info("usage: /slap <nick>"))
                return listOf(
                    CommandEffect.Action(target = target, text = "slaps $who around a bit with a large trout")
                )
            }
            "shrug" -> {
                // A message, not an action: `/shrug no idea` says "no idea ¯\_(ツ)_/¯", as on the web.
                if (!ChannelName.isChannelTarget(target) && !isNickTarget(target)) {
                    return listOf(CommandEffect.Info("usage: /shrug [text] — run inside a channel or DM"))
                }
                val shrugText = if (argLine.isEmpty()) shrug else "$argLine $shrug"
                return listOf(CommandEffect.Send(target = target, text = chatBody(shrugText)))
            }
            "msg", "query" -> {
                val who = rest.firstOrNull() ?: return listOf(CommandEffect.Info("usage: /msg <nick> [message]"))
                val bodyText = rest.drop(1).joinToString(" ")
                val effects = mutableListOf<CommandEffect>()
                if (bodyText.isNotEmpty()) effects.add(CommandEffect.Send(target = who, text = chatBody(bodyText)))
                effects.add(CommandEffect.Activate(target = who))
                return effects
            }
            "notice" -> {
                val who = rest.firstOrNull() ?: return listOf(CommandEffect.Info("usage: /notice <target> <text>"))
                // Slice the body past the target so interior spacing survives (mirrors /topic),
                // rather than re-joining the whitespace-split tokens.
                val bodyText = body(who, argLine)
                if (bodyText.isEmpty()) return listOf(CommandEffect.Info("usage: /notice <target> <text>"))
                return listOf(CommandEffect.Notice(target = who, text = chatBody(bodyText)))
            }
            "ctcp" -> {
                if (rest.size < 2) return listOf(CommandEffect.Info("usage: /ctcp <target> <type> [args]"))
                val ctcpArgs = rest.drop(2).joinToString(" ")
                return listOf(CommandEffect.Ctcp(target = rest[0], type = rest[1].uppercase(), args = ctcpArgs))
            }
            "ping" -> {
                // A bare /ping in a DM pings the peer.
                val who = rest.firstOrNull() ?: bufferPeer(target)
                if (who.isEmpty()) return listOf(CommandEffect.Info("usage: /ping <nick>"))
                return listOf(CommandEffect.Ctcp(target = who, type = "PING", args = ""))
            }

            // Channels & buffers
            "join", "j" -> {
                // A bare `/join` is a no-op, like the web (it just keeps the buffer you're in).
                val first = rest.firstOrNull() ?: return emptyList()
                val key = if (rest.size > 1) rest[1] else null
                return listOf(CommandEffect.Join(channel = ChannelName.ensurePrefix(first), key = key))
            }
            "part", "leave", "p" -> {
                // `/part [reason]` leaves the current channel; `/part <#chan> [reason]` leaves a
                // named one. A leading channel word marks a channel (see `leadsWithChannel`), anything
                // else is a parting reason for the current channel — so `/part heading out` and
                // `/part +brb` say goodbye here rather than parting a channel "heading" or "+brb".
                val partChannel: String?
                val partReason: String
                val first = rest.firstOrNull()
                if (first != null && leadsWithChannel(first, hasBuffer)) {
                    partChannel = first
                    partReason = body(first, argLine)
                } else {
                    partChannel = if (ChannelName.isChannelTarget(target)) target else null
                    partReason = argLine
                }
                if (partChannel == null) {
                    return listOf(CommandEffect.Info("usage: /part [#chan] [reason] — no channel context"))
                }
                return listOf(
                    CommandEffect.Part(channel = partChannel, reason = if (partReason.isEmpty()) null else partReason)
                )
            }
            "cycle", "hop" -> {
                // Part and rejoin the CURRENT channel; the whole arg line is an optional part
                // reason (not a channel). Both legs use the structured verbs so the persisted
                // `joined` flag flips false and back, keeping reconnect auto-join intact.
                if (!ChannelName.isChannelTarget(target)) {
                    return listOf(CommandEffect.Info("usage: /cycle [reason] — run inside a channel"))
                }
                return listOf(
                    CommandEffect.Part(channel = target, reason = if (argLine.isEmpty()) null else argLine),
                    CommandEffect.Join(channel = target, key = null),
                )
            }
            "close" ->
                return listOf(CommandEffect.Close(target = target))
            "clear" -> {
                // `/clear`            — hide everything up to now, behind an undoable marker;
                // `/clear off|undo`   — drop the marker so the hidden messages come back.
                //
                // Anything else is read as a plain clear rather than refused. `/clear` takes no
                // other argument, and the alternative — an error for `/clear all`, which is what
                // a user reaching for "clear everything" would type — would refuse the thing they
                // asked for on the grounds that they were too specific about it.
                val arg = argLine.trimmingWhitespacesAndNewlines().lowercase()
                return listOf(CommandEffect.Clear(target = target, undo = arg == "off" || arg == "undo"))
            }
            "topic" -> {
                // `/topic` reads the current channel's topic; `/topic text` sets it; a leading
                // channel word retargets (see `leadsWithChannel`, which keeps `/topic !!! down !!!` a
                // topic). Interior spacing of the body is preserved by slicing.
                val channel: String
                val bodyText: String
                val first = rest.firstOrNull()
                if (first != null && leadsWithChannel(first, hasBuffer)) {
                    channel = first
                    bodyText = body(first, argLine)
                } else {
                    if (!ChannelName.isChannelTarget(target)) {
                        return listOf(CommandEffect.Info("usage: /topic [#chan] [text] — no channel context"))
                    }
                    channel = target
                    bodyText = argLine
                }
                val line = if (bodyText.isEmpty()) "TOPIC $channel" else "TOPIC $channel :$bodyText"
                return listOf(CommandEffect.Raw(line = line))
            }
            "nick" -> {
                val newNick = rest.firstOrNull() ?: return listOf(CommandEffect.Info("usage: /nick <newnick>"))
                return listOf(CommandEffect.Raw(line = "NICK $newNick"))
            }
            "whois" -> {
                // A bare `/whois` in a DM whoises the peer; in a channel it needs a nick.
                val who = rest.firstOrNull() ?: bufferPeer(target)
                if (who.isEmpty()) return listOf(CommandEffect.Info("usage: /whois <nick>"))
                return listOf(CommandEffect.ShowProfile(nick = who))
            }
            "invite" -> {
                // `/invite <nick> [#chan]`, or channel-first as /kick takes it: `/invite #chan <nick>`.
                // The channel defaults to the current buffer, but only if that's a channel — an
                // /invite from a DM with no explicit channel would otherwise aim at the peer nick. A
                // second word that isn't a channel is ignored rather than invited to.
                val who: String?
                val channel: String?
                val first = rest.firstOrNull()
                if (first != null && ChannelName.isChannelTarget(first)) {
                    channel = first
                    who = if (rest.size > 1) rest[1] else null
                } else {
                    who = first
                    channel = if (rest.size > 1 && ChannelName.isChannelTarget(rest[1])) {
                        rest[1]
                    } else if (ChannelName.isChannelTarget(target)) {
                        target
                    } else {
                        null
                    }
                }
                if (who == null) return listOf(CommandEffect.Info("usage: /invite <nick> [#channel]"))
                if (channel == null) {
                    return listOf(CommandEffect.Info("usage: /invite <nick> [#channel] — no channel context"))
                }
                // Wire order is nick first, whichever way round it was typed.
                return listOf(CommandEffect.Raw(line = "INVITE $who $channel"))
            }

            // Moderation
            "kick" -> {
                // `/kick <nick> [reason]` in a channel, or `/kick <#chan> <nick> [reason]` anywhere.
                val channel: String?
                val who: String?
                val reason: String
                val first = rest.firstOrNull()
                if (first != null && ChannelName.isChannelTarget(first)) {
                    channel = first
                    who = if (rest.size > 1) rest[1] else null
                    reason = rest.drop(2).joinToString(" ")
                } else {
                    channel = if (ChannelName.isChannelTarget(target)) target else null
                    who = rest.firstOrNull()
                    reason = rest.drop(1).joinToString(" ")
                }
                if (channel == null) {
                    return listOf(CommandEffect.Info("usage: /kick [#chan] <nick> [reason] — no channel context"))
                }
                if (who == null) return listOf(CommandEffect.Info("usage: /kick [#chan] <nick> [reason]"))
                val trailer = if (reason.isEmpty()) "" else " :$reason"
                return listOf(CommandEffect.Raw(line = "KICK $channel $who$trailer"))
            }
            "mode" -> {
                // `/mode <flags>` applies to the current channel; `/mode <target> <flags…>` is
                // explicit. A leading `+`/`-` in a channel buffer is the flags-only form — unless it
                // names an open `+channel`, so `/mode +local +m` reaches +local (lurker#724).
                val first = rest.firstOrNull()
                    ?: return listOf(CommandEffect.Info("usage: /mode [target] <flags> [args]"))
                if ((first.startsWith("+") || first.startsWith("-")) && !leadsWithChannel(first, hasBuffer) &&
                    ChannelName.isChannelTarget(target)
                ) {
                    return listOf(CommandEffect.Raw(line = "MODE $target ${rest.joinToString(" ")}"))
                }
                return listOf(CommandEffect.Raw(line = "MODE $argLine"))
            }
            "op" -> return modeShortcut(verb, letter = 'o', adding = true, rest = rest, target = target, spec = modeSpec)
            "deop" -> return modeShortcut(verb, letter = 'o', adding = false, rest = rest, target = target, spec = modeSpec)
            "voice" -> return modeShortcut(verb, letter = 'v', adding = true, rest = rest, target = target, spec = modeSpec)
            "devoice" -> return modeShortcut(verb, letter = 'v', adding = false, rest = rest, target = target, spec = modeSpec)
            "halfop" -> return modeShortcut(verb, letter = 'h', adding = true, rest = rest, target = target, spec = modeSpec)
            "dehalfop" -> return modeShortcut(verb, letter = 'h', adding = false, rest = rest, target = target, spec = modeSpec)
            "ban" -> return modeShortcut(verb, letter = 'b', adding = true, rest = rest, target = target, spec = modeSpec)
            "unban" -> return modeShortcut(verb, letter = 'b', adding = false, rest = rest, target = target, spec = modeSpec)
            // `/quiet` speaks solanum's +q quiet LIST. On InspIRCd and Unreal +q is the owner rank,
            // so `/quiet bob` would make bob an owner — refused unless the network lists q among its
            // list modes. Before the vocabulary arrives there's nothing to check, so it goes through,
            // as on the web.
            "quiet", "unquiet" -> {
                if (noQuietList(modeSpec)) return listOf(CommandEffect.Info("this network has no +q quiet list"))
                return modeShortcut(verb, letter = 'q', adding = verb == "quiet", rest = rest, target = target, spec = modeSpec)
            }
            "kickban" -> {
                // Ban first, so they can't rejoin in the gap, then kick. A leading channel is optional.
                var channel: String? = if (ChannelName.isChannelTarget(target)) target else null
                var args = rest
                val first = args.firstOrNull()
                if (first != null && ChannelName.isChannelTarget(first)) {
                    channel = first
                    args = args.drop(1)
                }
                if (channel == null) {
                    return listOf(CommandEffect.Info("usage: /kickban [#chan] <nick> [reason] — no channel context"))
                }
                val who = args.firstOrNull()
                    ?: return listOf(CommandEffect.Info("usage: /kickban [#chan] <nick> [reason]"))
                val reason = args.drop(1).joinToString(" ")
                val trailer = if (reason.isEmpty()) "" else " :$reason"
                return listOf(
                    CommandEffect.Raw(line = "MODE $channel +b $who"),
                    CommandEffect.Raw(line = "KICK $channel $who$trailer"),
                )
            }

            // Server / services
            "raw", "quote" -> {
                if (argLine.isEmpty()) return listOf(CommandEffect.Info("usage: /raw <line>"))
                return listOf(CommandEffect.Raw(line = argLine))
            }
            "ns" -> {
                if (argLine.isEmpty()) return listOf(CommandEffect.Info("usage: /ns <message>"))
                return listOf(CommandEffect.Raw(line = "PRIVMSG NickServ :$argLine"))
            }
            "cs" -> {
                if (argLine.isEmpty()) return listOf(CommandEffect.Info("usage: /cs <message>"))
                return listOf(CommandEffect.Raw(line = "PRIVMSG ChanServ :$argLine"))
            }

            // Server queries — a raw line of the uppercased verb plus any argument, matching the
            // web. Declared in the registry (so they complete and appear in /commands), so they
            // route here explicitly rather than sliding through the unknown-command default.
            "motd", "version", "time", "lusers", "links", "map", "admin", "info",
            "names", "who", "whowas", "stats", "userhost", "ison", "help" -> {
                val line = if (argLine.isEmpty()) verb.uppercase() else "${verb.uppercase()} $argLine"
                return listOf(CommandEffect.Raw(line = line))
            }

            // DCC (lurker#270). Below the network gate: a chat rides one network's connection.
            "dcc" ->
                return resolveDcc(rest = rest)

            // App
            "relay" ->
                // Below the network gate above: a mark is per-(network, nick), so there is no
                // sensible answer to `/relay` in the system buffer.
                return resolveRelay(argLine = argLine, networkId = networkId, relayBots = relayBots)

            // Connection lifecycle (lurker-ios#152) — REST verbs on this buffer's network, never
            // raw lines (see `CommandEffect.Disconnect` for why a raw QUIT is the one thing /quit
            // must not be). Below the network gate: the system buffer has no connection to start
            // or stop.
            "connect" ->
                return listOf(CommandEffect.Connect)
            "disconnect", "quit" -> {
                // The whole argument line is the reason, interior spacing kept — it's a quit
                // message. Empty means "let the server pick its default". Line breaks fold to
                // spaces: the reason is the tail of one IRC line, and a pasted break would end it
                // early and put the rest on the wire as a command of its own.
                val reason = argLine.split(*newlines).filter { it.isNotEmpty() }.joinToString(" ")
                return listOf(CommandEffect.Disconnect(reason = if (reason.isEmpty()) null else reason))
            }
            "reconnect" ->
                return listOf(CommandEffect.Reconnect)
            "server", "network", "net" ->
                // Intercepted rather than rawed: `SERVER` is a server-to-server command, and the
                // thing people mean by it is a form on this client. The web's `/network` verbs are
                // the same form.
                return listOf(CommandEffect.Info("Networks are added and edited in Settings → Networks."))

            // The web's commands this client has no screen for. Each would otherwise go out raw and
            // come back as a 421 in the server log — or, for `/list`, as a LIST that only refreshes
            // the server's cache and shows nothing.
            "list" ->
                return listOf(
                    CommandEffect.Info("The channel list isn't in the app yet — /join #channel if you know its name.")
                )
            "set", "get", "theme" ->
                return listOf(CommandEffect.Info("/$verb is web-only — the app's own options are in Settings."))
            "highlight", "hilight", "unhighlight", "dehilight" ->
                return listOf(CommandEffect.Info("Highlight words are edited in the web client for now."))
            "retention", "jitsi", "talk", "e2e" ->
                return listOf(CommandEffect.Info("/$verb is web-only for now."))

            else ->
                // Anything unrecognized goes raw, exactly as the web's `default`. The original
                // casing is preserved: `line.slice(1)`.
                return listOf(CommandEffect.Raw(line = fullBody.trimmingWhitespaces()))
        }
    }

    /**
     * Swift's `Character.isNewline`, which is what `/quit` folds: LF, VT, FF, CR, NEL and the
     * line and paragraph separators. Port-only.
     *
     * Port note: CR-LF is one `Character` there and two units here. It folds to the same single
     * space either way, because the empty piece between the two is dropped.
     */
    private val newlines: CharArray = charArrayOf('\n', '\u000B', '\u000C', '\r', '\u0085', '\u2028', '\u2029')

    // MARK: - DCC (lurker#270)

    private const val dccUsage = "usage: /dcc chat [-passive] <nick> · /dcc close chat <nick>"

    /**
     * The words for DCC file transfers, which this app has no screen for: the web's transfer verbs
     * and their aliases, plus irssi's `send` and `resume`. A habit carried over from either gets an
     * answer rather than a usage line that pretends the verb doesn't exist.
     */
    private val dccTransferVerbs: Set<String> = setOf(
        "list", "ls", "accept", "ok", "yes", "get", "reject", "deny", "no", "cancel", "abort", "stop",
        "send", "resume",
    )

    /** What [awayFlag] reads off a line: the flag, and the line after it. */
    internal data class AwayFlag(val all: Boolean?, val rest: String)

    /**
     * The scope flag at the front of an `/away` or `/back` line (lurker#994): `-all` for every
     * network, `-one` for just this one, null without one. Only a leading, whole-word flag
     * counts — `/away back at -all hands` is a message, as is `-allnighter`. The web's
     * `parseAwayFlag` reads it the same way, except that its `\s` also counts U+FEFF: this
     * splits on `Character.isWhitespace` (`isSwiftWhitespace` here) like every other command
     * (see `IgnoreArgs.tokenize`).
     *
     * Port note: read by UTF-16 unit where LurkerKit reads by `Character`, so a space wearing
     * a combining mark is one separator there, dropped mark and all, and here the mark is left
     * behind: on the message when the space follows the flag, and in front of the flag (which
     * then isn't one) when it precedes it — the second case in `parse`'s Port note. Checked
     * against the Swift; every difference found was this one.
     */
    internal fun awayFlag(argLine: String): AwayFlag {
        val line = argLine.dropWhile { it.isSwiftWhitespace() }
        val word = line.takeWhile { !it.isSwiftWhitespace() }
        val all: Boolean = when (word.lowercase()) {
            "-all" -> true
            "-one" -> false
            else -> return AwayFlag(null, argLine)
        }
        return AwayFlag(all, line.drop(word.length).dropWhile { it.isSwiftWhitespace() })
    }

    /**
     * `/dcc` — the chat verbs, in irssi's syntax exactly, as the web has them:
     *
     *     DCC CHAT [-passive] <nick>      irssi dcc-chat.c:442
     *     DCC CLOSE <type> <nick>         irssi dcc.c:490
     *
     * ⚠ Type-first on close is the reason this is strict. The web once took `/dcc close <nick>`
     * as a shorthand, which read irssi's `/dcc close chat bob` as closing a chat with a peer
     * named "chat" — and left the real one open. Accepting only irssi's shape leaves nothing to
     * guess.
     *
     * ⚠ `-passive` is opt-in, never a fallback: WeeChat and HexDroid turn a passive offer into
     * a silent dial to port 0, so the server refuses an active offer it can't make rather than
     * quietly degrading to one.
     */
    private fun resolveDcc(rest: List<String>): List<CommandEffect> {
        val args = rest.drop(1)
        when (rest.firstOrNull()?.lowercase() ?: "") {
            "chat" -> {
                val flags = args.filter { it.startsWith("-") }
                val positional = args.filter { !it.startsWith("-") }
                val unknown = flags.firstOrNull { it.lowercase() != "-passive" }
                if (unknown != null) {
                    return listOf(
                        CommandEffect.Info("/dcc: unknown option \"$unknown\". usage: /dcc chat [-passive] <nick>")
                    )
                }
                // `/dcc chat close bob` isn't a command, and read literally it would OFFER a chat
                // to someone called "close". Answer the intent instead.
                if (positional.size > 1) {
                    return listOf(
                        CommandEffect.Info(
                            if (positional[0].lowercase() == "close") {
                                "To end a chat: /dcc close chat <nick>"
                            } else {
                                "usage: /dcc chat [-passive] <nick>"
                            }
                        )
                    )
                }
                val nick = dccPeer(positional.firstOrNull())
                    ?: return listOf(CommandEffect.Info("usage: /dcc chat [-passive] <nick>"))
                return listOf(CommandEffect.DccChat(nick = nick, passive = flags.isNotEmpty()))
            }
            "close" ->
                when (args.firstOrNull()?.lowercase() ?: "") {
                    "chat" -> {
                        val nick = (if (args.size == 2) dccPeer(args[1]) else null)
                            ?: return listOf(CommandEffect.Info("usage: /dcc close chat <nick>"))
                        return listOf(CommandEffect.DccCloseChat(nick = nick))
                    }
                    "send", "get" ->
                        return listOf(CommandEffect.Info("DCC file transfers aren't in the app yet."))
                    else ->
                        return listOf(CommandEffect.Info("usage: /dcc close chat <nick>"))
                }
            in dccTransferVerbs ->
                return listOf(CommandEffect.Info("DCC file transfers aren't in the app yet."))
            else ->
                return listOf(CommandEffect.Info(dccUsage))
        }
    }

    /**
     * The peer a DCC verb names, or null when the token can't be one.
     *
     * ⚠ A leading `=` is refused: that's a chat's BUFFER name, and `/dcc chat =bob` almost
     * certainly means bob — accepting it would offer a chat to someone literally called "=bob".
     * A channel is refused too, all four sigils: the offer is a CTCP to its target, so a channel
     * name would put it in front of everyone there.
     */
    private fun dccPeer(token: String?): String? {
        val peer = token?.trimmingWhitespaces()
        if (peer == null || peer.isEmpty() || DccChat.isTarget(peer) || ChannelName.isChannelTarget(peer)) {
            return null
        }
        return peer
    }

    // MARK: - Ignore rules (lurker-ios#86)

    /**
     * `/ignore` — with no arguments, the rule listing; otherwise a rule to store.
     *
     * Nothing is mutated locally: the effect asks, and the rule appears when the server's
     * `ignore-list-updated` lands. The receipt rides on the effect rather than being printed
     * here, so it's withheld when the verb never reached a socket.
     */
    private fun resolveIgnore(
        argLine: String,
        networkId: Int?,
        ignores: IgnoreSet?,
        now: Instant,
        formatted: (Instant) -> String,
    ): List<CommandEffect> {
        // `whitespacesAndNewlines`: the composer is multi-line and Return inserts a newline, so
        // `/ignore\n` reaches here with one still attached — and an argLine that is only a
        // newline would otherwise skip the listing and author a rule instead.
        val args = argLine.trimmingWhitespacesAndNewlines()
        if (args.isEmpty()) {
            // Only the *listing* needs the rules to have arrived. Authoring below doesn't, and
            // gating it would refuse a perfectly good `/ignore bob` during the connect burst.
            if (ignores == null) return listOf(CommandEffect.Info(unsynced))
            return listOf(CommandEffect.Info(listing(ignores.listing(networkId), now = now, formatted = formatted)))
        }

        val parsed: IgnoreArgs.Parsed = when (val result = IgnoreArgs.parse(args, now = now)) {
            is Result.Success -> result.value
            is Result.Failure -> return listOf(CommandEffect.Info("/ignore: ${result.error.message}"))
        }
        // Global (the default) works anywhere; `-network` names a connection the system buffer
        // doesn't have. Refusing beats quietly writing the global rule they didn't ask for.
        if (!(!parsed.scopeNetwork || networkId != null)) {
            return listOf(
                CommandEffect.Info("/ignore -network needs an active network — switch to a channel or DM.")
            )
        }
        val scope = if (parsed.scopeNetwork) networkId else null
        // `add-ignore` is an upsert, not an insert: the server matches an existing rule on
        // every dimension EXCEPT expiry and rewrites that row's `expires_at` in place
        // (`findIdenticalStmt`/`addRule`). So `/ignore -time 1h bob` followed by `/ignore bob`
        // doesn't make a second rule — it makes the hour-long mute permanent, and vice versa.
        // Saying "added" for that is how someone loses a timed rule without being told.
        // Without the rules, an add and an upsert are indistinguishable — so the receipt
        // claims neither rather than guessing "added" in the one window this command is
        // careful about everywhere else. Authoring itself stays allowed here: the rule is
        // fine, it's only our ability to describe what it did to the list that's missing.
        val verb: String
        val listed = ignores?.listing(networkId)
        if (listed != null) {
            verb = if (listed.any { it.scope == scope && sameRule(it.rule, parsed.rule) }) {
                "ignore updated"
            } else {
                "ignore added"
            }
        } else {
            verb = "ignore sent"
        }
        return listOf(
            CommandEffect.AddIgnore(
                scope = scope,
                rule = parsed.rule,
                receipt = "$verb: ${parsed.rule.summary(global = scope == null, now = now, formatted = formatted)}",
            )
        )
    }

    /**
     * Whether two rules are the same one as far as the server's dedupe is concerned — every
     * dimension but the id and the expiry, which is exactly what `findIdenticalStmt` compares
     * and exactly what makes a re-issued `/ignore` change a rule's lifetime instead of adding
     * a rule.
     */
    private fun sameRule(lhs: IgnoreRule, rhs: IgnoreRule): Boolean =
        lhs.mask == rhs.mask &&
            (lhs.channels ?: emptyList<String>()) == (rhs.channels ?: emptyList<String>()) &&
            (lhs.pattern ?: "") == (rhs.pattern ?: "") &&
            lhs.patternKind == rhs.patternKind &&
            lhs.levels == rhs.levels &&
            lhs.isExcept == rhs.isExcept

    /**
     * `/unignore <index|mask>` — a number addresses a rule by its position in the last
     * listing, anything else is a mask to clear.
     *
     * The two remove differently on purpose, matching the web: by-index is exact (it resolves
     * to the rule's id and its bucket), while by-mask clears every rule carrying that mask,
     * which is what makes the common `/ignore bob` → `/unignore bob` round trip work without
     * anyone having to read a listing first.
     */
    private fun resolveUnignore(
        argLine: String,
        networkId: Int?,
        ignores: IgnoreSet?,
        now: Instant,
        formatted: (Instant) -> String,
    ): List<CommandEffect> {
        // Run through the same tokenizer `/ignore` used to create the mask, so a mask is
        // removable in the spelling that made it: `/ignore "bob smith"` stores `bob smith`, and
        // comparing the raw arg would have matched only the unquoted form. Trimming is
        // `whitespacesAndNewlines` for the multi-line composer (see `resolveIgnore`).
        val arg = IgnoreArgs.tokenize(argLine).firstOrNull()
            ?.trimmingWhitespacesAndNewlines() ?: ""
        if (arg.isEmpty()) {
            return listOf(CommandEffect.Info("usage: /unignore <index|mask>  (index from /ignore)"))
        }
        // Every answer below is a claim about which rules exist — "no ignore #3", "no ignore
        // with mask bob". Made against a set that hasn't arrived yet, each of them is a
        // confident denial of a rule the account really has.
        if (ignores == null) return listOf(CommandEffect.Info(unsynced))
        val listed = ignores.listing(networkId)

        // ASCII digits only — the web's `/^\d+$/`. A plain integer parse alone would accept `+5`
        // and a non-ASCII digit, either of which would silently address a different rule.
        if (arg.all { it in '0'..'9' }) {
            // An index too large for an Int is out of range by definition, so it reads as one
            // past the end rather than becoming a different number.
            //
            // Port note: an `Int` is 32 bits here and 64 in LurkerKit, so "too large" starts
            // sooner. Nothing rides on where: a listing never holds two billion rules, so either
            // side's too-large index is simply out of range, and the lines below quote what was
            // typed (`arg`), not the number it parsed to.
            val index = arg.toIntOrNull() ?: 0
            if (index >= 1 && index <= listed.size) {
                val item = listed[index - 1]
                val summary = item.rule.summary(global = item.scope == null, now = now, formatted = formatted)
                return listOf(
                    CommandEffect.RemoveIgnore(
                        scope = item.scope,
                        id = item.rule.id,
                        mask = null,
                        receipt = "removed ignore #$index: $summary",
                    )
                )
            }
            // Not an index that exists — but numeric masks are real (bots, `*!*@1234`), and
            // the web's parser stops here, leaving a rule you can create and never name.
            // Falling through to mask matching is a deliberate divergence: an in-range index
            // still wins, so nothing that worked before changes meaning.
            if (!listed.any { matchesMask(it, arg) }) {
                return listOf(CommandEffect.Info("/unignore: no ignore #$arg (see /ignore)"))
            }
        }

        // A rule that applies to anyone is stored with no mask at all (`*` normalizes to null
        // on both sides), so there is no string for the server's `mask = ?` delete to match —
        // it can only go by number. Said plainly, because the listing prints those rules as
        // `*` and typing what you were shown is the obvious next move.
        if (arg == "*") {
            return listOf(
                CommandEffect.Info(
                    "/unignore: a rule that applies to anyone has no mask to match — remove it by number (see /ignore)."
                )
            )
        }

        // Case-insensitive exact match of the stored mask, not a glob.
        val matches = listed.filter { matchesMask(it, arg) }
        if (matches.isEmpty()) {
            return listOf(CommandEffect.Info("/unignore: no ignore with mask \"$arg\" (see /ignore)"))
        }
        // Scoped to the issuing network, not to the matches': the server's by-mask delete
        // spans the globals plus that one network, which is exactly the set just counted.
        return listOf(
            CommandEffect.RemoveIgnore(
                scope = networkId,
                id = null,
                mask = arg,
                receipt = "removed ${matches.size} ignore${if (matches.size > 1) "s" else ""} matching \"$arg\".",
            )
        )
    }

    /**
     * Whether a listed rule's mask is the one the user typed, folded **the way the server's
     * delete folds it**: SQLite's `COLLATE NOCASE`, which is ASCII-only and byte-exact
     * otherwise.
     *
     * Deliberately not a case-insensitive compare (`equals(ignoreCase = true)`, or on iOS
     * `caseInsensitiveCompare`), which is the obvious spelling and folds far wider — on iOS it
     * treats a decomposed `café` as equal to the composed one, and `STRASSE` as equal to
     * `straße`. Those all count a match here that the `DELETE` will not make, so the user is
     * told a rule was removed and it is still there on the next `/ignore`. This number is a
     * claim about what the server did, so it has to be answered in the server's terms.
     */
    private fun matchesMask(item: ScopedIgnoreRule, arg: String): Boolean {
        val mask = item.rule.mask ?: return false
        return asciiLowered(mask).contentEquals(asciiLowered(arg))
    }

    /**
     * A mask folded the way SQLite folds it: **per byte**, `A`–`Z` only.
     *
     * Bytes, not characters, which is what makes this agree rather than merely look like it
     * does. On iOS a grapheme cluster like `A` + combining acute is not ASCII as a `Character`,
     * so folding by character leaves its `A` alone while `NOCASE` — which walks bytes — lowers
     * it. That's the *inverse* of the divergence this function exists to prevent: the client
     * would report "no ignore with that mask" for a rule the `DELETE` would have removed.
     *
     * Comparing the byte arrays also sidesteps Swift's canonical `==`, under which a
     * decomposed `cafe\u{301}` equals a composed `café` and to SQLite does not. (A Kotlin `==`
     * compares code units and has no such trap; the bytes are kept because they are the
     * server's terms.)
     */
    private fun asciiLowered(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        for (index in bytes.indices) {
            val byte = bytes[index]
            if (byte >= 0x41 && byte <= 0x5A) bytes[index] = (byte + 0x20).toByte()
        }
        return bytes
    }

    /**
     * The `/ignore` listing as one block — one local line, not one per rule, so a long list
     * arrives as a single message row rather than as N (the same shape `/commands` takes).
     */
    private fun listing(ignores: List<ScopedIgnoreRule>, now: Instant, formatted: (Instant) -> String): String {
        val head = if (ignores.isEmpty()) {
            listOf("ignore list is empty.")
        } else {
            listOf("ignore list (${ignores.size}):") + ignores.mapIndexed { index, item ->
                "  ${index + 1}. ${item.rule.summary(global = item.scope == null, now = now, formatted = formatted)}"
            }
        }
        return (head + listOf(grammar)).joinToString("\n")
    }

    /**
     * What to say when the account's rules haven't reached this device yet — the connect
     * burst hasn't finished, or the socket is down. Distinct from "you have no rules", which
     * is what an empty set would otherwise be read as.
     */
    private const val unsynced =
        "Your ignore rules haven't arrived yet — try again once you're connected."

    // MARK: - Relay bots (lurker#277)

    /**
     * `/relay` — with no arguments, the marks on this network; otherwise a mark to set or clear.
     *
     * Nothing is written locally, exactly as with `/ignore`: the effect asks, and the mark
     * appears when the server's `relay-bot-updated` lands. The receipt rides on the effect so
     * it's withheld if the verb never reached a socket.
     */
    private fun resolveRelay(
        argLine: String,
        networkId: Int,
        relayBots: RelayBotSet?,
    ): List<CommandEffect> {
        when (val parsed = RelayArgs.parse(argLine)) {
            is RelayArgs.Parsed.Failure ->
                return listOf(CommandEffect.Info("/relay: ${parsed.message}"))
            RelayArgs.Parsed.List -> {
                // Only the listing needs the marks to have arrived — it's a claim about what exists,
                // and made against a set still in flight it's a confident "you have none" for an
                // account that may have several. Marking below doesn't need them and isn't gated.
                if (relayBots == null) {
                    return listOf(
                        CommandEffect.Info("Your relay bots haven't arrived yet — try again once you're connected.")
                    )
                }
                return listOf(CommandEffect.Info(relayListing(relayBots.listing(networkId))))
            }
            is RelayArgs.Parsed.Add -> {
                val nick = parsed.nick
                val pattern = parsed.pattern
                // ⚠ A custom pattern that won't compile has to be refused HERE, at the only moment
                // anyone is looking. `RelayEnvelope.templates` deliberately doesn't fall back to
                // the built-ins for one — the user asked for a specific shape and inventing a speaker
                // by some other rule would be worse — so the mark would be stored, listed by
                // `/relay list`, and silently re-attribute nothing, forever, with a receipt that said
                // it worked. The server won't catch it either: it stores the string without reading it.
                //
                // Forgetting the braces is the whole of how this happens (`/relay add bot [Discord]
                // <nick> message`), so the refusal names them.
                if (pattern.isNotEmpty() && RelayEnvelope.compile(pattern) == null) {
                    return listOf(
                        CommandEffect.Info(
                            "/relay: that pattern can't be used. It needs {nick} and {message} — {source} " +
                                "is optional — e.g. /relay add $nick [{source}] <{nick}> {message}. " +
                                "Leave it off entirely to use the built-in formats."
                        )
                    )
                }
                // "marked" either way, not "updated": unlike `add-ignore` — whose upsert can silently
                // convert a timed rule into a permanent one, which is why that receipt is careful —
                // re-marking a bot with a new pattern has exactly one outcome, and it's this one.
                val suffix = if (pattern.isEmpty()) "" else " (pattern: $pattern)"
                return listOf(
                    CommandEffect.SetRelayBot(
                        networkId = networkId, nick = nick, marked = true, pattern = pattern,
                        receipt = "marked $nick as a relay bot$suffix.",
                    )
                )
            }
            is RelayArgs.Parsed.Remove ->
                return listOf(
                    CommandEffect.SetRelayBot(
                        networkId = networkId, nick = parsed.nick, marked = false, pattern = "",
                        receipt = "unmarked ${parsed.nick} as a relay bot.",
                    )
                )
        }
    }

    /**
     * The `/relay` listing as one block, like `/ignore`'s — one message row rather than N.
     *
     * The empty case carries the way *out* of it. A user typing `/relay` into a channel where a
     * bridge is talking is asking "how do I fix this", and "none" alone answers a different
     * question than the one they have.
     */
    private fun relayListing(bots: List<RelayBot>): String {
        if (bots.isEmpty()) {
            return "No relay bots marked on this network. /relay add <nick>"
        }
        val rows = bots.map { "  ${it.nick}${if (it.pattern.isEmpty()) "" else "  — ${it.pattern}"}" }
        return (listOf("relay bots (${bots.size}):") + rows).joinToString("\n")
    }

    /**
     * The flag and level vocabulary, printed under every listing.
     *
     * This is the only place it's reachable. `/commands` builds its usage line from the
     * positional `ArgSpec`s, so it can only say `/ignore [mask] [levels]` — and the grammar
     * is irssi's, which nobody guesses: without this, `-network`, the one flag that scopes a
     * rule to a single connection, cannot be discovered from inside the app. The web spends
     * four cheatsheet lines on the same problem.
     */
    private const val grammar =
        "  usage: /ignore [flags] [nick|mask|#chan] [LEVELS…] — no arguments lists\n" +
            "  flags: -network (this network only) -except -regexp -full -pattern <text> -time <dur>\n" +
            "  levels: PUBLIC MSGS NOTICES ACTIONS JOINS PARTS QUITS NICKS KICKS MODES TOPICS " +
            "NOHIGHLIGHT NOUNREAD NONOTIFY, or ALL -PUBLIC to subtract"

    // MARK: - Helpers

    /** `¯\_(ツ)_/¯`, as the web's `/shrug` says it. */
    private const val shrug = "¯\\_(ツ)_/¯"

    /**
     * Whether a command's leading word names a channel, for the commands whose first argument
     * may instead be free text: `/part [reason]`, `/topic [text]`, `/mode`. The web's
     * `isChannelArgAmbiguous`.
     *
     * ⚠ `#` always does: a sentence effectively never starts with one. The other three sigils do
     * only when this network has a buffer by that name, because `+` and `!` absolutely start
     * sentences — `/part +brb` would part a channel "+brb", `/topic !!! down !!!` would set the
     * topic of a channel "!!!". Commands whose first argument is never text (`/kick`, `/invite`,
     * the mode shortcuts) ask `ChannelName.isChannelTarget` instead.
     */
    private fun leadsWithChannel(token: String, hasBuffer: (String) -> Boolean): Boolean {
        if (!ChannelName.isChannelTarget(token)) return false
        return token.startsWith("#") || hasBuffer(token)
    }

    /**
     * A whole `:name:` in the gemoji character set — what the web's `reactionFromInput` would
     * look up. Closed only: `:D` is an emoticon, not a name.
     */
    private fun isShortcode(text: String): Boolean {
        if (text.length <= 2 || !text.startsWith(":") || !text.endsWith(":")) return false
        return text.substring(1, text.length - 1).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_+-" }
    }

    /** Whether the network is known to have no +q quiet list — an unknown spec isn't. */
    private fun noQuietList(spec: ModeSpec?): Boolean =
        spec != null && !spec.list.contains("q")

    /**
     * How many param-taking changes one MODE line may carry before the network's 005 says: the
     * web's `DEFAULT_MAX_MODES`, and RFC 2812's floor.
     */
    private const val defaultMaxModes = 3

    /**
     * The mode-shortcut family (`/op`, `/ban`, …): one mode letter repeated once per target,
     * against a leading channel arg (any sigil) or the current channel buffer. `/op a b` →
     * `MODE #chan +oo a b`, split into as many lines as the network's MODES allows — a server
     * drops the changes past its limit without a word. Refuses outside a channel, rather than
     * aiming a channel mode at a DM peer.
     */
    private fun modeShortcut(
        verb: String,
        letter: Char,
        adding: Boolean,
        rest: List<String>,
        target: String,
        spec: ModeSpec?,
    ): List<CommandEffect> {
        var channel: String? = if (ChannelName.isChannelTarget(target)) target else null
        var args = rest
        val first = args.firstOrNull()
        if (first != null && ChannelName.isChannelTarget(first)) {
            channel = first
            args = args.drop(1)
        }
        if (channel == null) {
            return listOf(CommandEffect.Info("usage: /$verb [#chan] <nick>… — no channel context"))
        }
        if (args.isEmpty()) {
            return listOf(CommandEffect.Info("usage: /$verb [#chan] <nick>…"))
        }
        val sign = if (adding) "+" else "-"
        // A known spec with no MODES is no limit; an unknown spec is the default, as the web.
        val perLine = maxOf(if (spec != null) spec.maxModes ?: args.size else defaultMaxModes, 1)
        return args.chunked(perLine).map { batch ->
            val letters = letter.toString().repeat(batch.size)
            CommandEffect.Raw(line = "MODE $channel $sign$letters ${batch.joinToString(" ")}")
        }
    }

    /**
     * The body of a command after its first token, interior spacing preserved — the web's
     * `argLine.slice(first.length).trim()`. `argLine` begins with `first`. Newlines are
     * trimmed too, as `trim()` does: `/topic #chan⏎new topic` mustn't start with one.
     *
     * Port note: LurkerKit drops `first.count` `Character`s from `argLine` whatever it begins
     * with. Where it does begin with `first` — the case the line above describes — that is
     * `first`'s UTF-16 length, and it is cut as such. Should it ever not, the cut is counted the
     * way LurkerKit counts it — by grapheme cluster, see `characterBoundaries` — because counting
     * units there could cut a CR-LF in half or leave half a surrogate pair on the wire. (A line
     * break after the verb was the one way in, `/notice⏎bob hi`, until lurker-ios#197 trimmed
     * newlines off `argLine` too.)
     */
    private fun body(first: String, argLine: String): String {
        if (argLine.startsWith(first)) return argLine.substring(first.length).trimmingWhitespacesAndNewlines()
        val count = characterBoundaries(first).size
        val cut = characterBoundaries(argLine).getOrNull(count - 1) ?: argLine.length
        return argLine.substring(cut).trimmingWhitespacesAndNewlines()
    }

    /** Where each of `text`'s Swift `Character`s ends — `support.graphemeBoundaries`. */
    private fun characterBoundaries(text: String): List<Int> = graphemeBoundaries(text)

    /** A DM/user target: has a network, isn't a channel, isn't a `:server:`/`:system:` pseudo. */
    private fun isNickTarget(target: String): Boolean =
        !ChannelName.isChannelTarget(target) && !target.startsWith(":")

    /**
     * The person a bare `/whois` or `/ping` means in this buffer, or "" for none: a DM's peer,
     * and a DCC chat's too.
     *
     * ⚠⚠ Peeled, never the buffer name. `=bob` isn't a nick, and both verbs put their argument
     * on the IRC wire — `/ping` as a CTCP, which no server-side `=` guard covers — so the raw
     * target here was a `PRIVMSG =bob` waiting to happen.
     */
    private fun bufferPeer(target: String): String =
        if (isNickTarget(target)) DccChat.peer(target) else ""
}
