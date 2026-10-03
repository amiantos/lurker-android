// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurkerkit.commands.IgnoreArgs
import net.amiantos.lurkerkit.model.LinkActionContext
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageAction
import net.amiantos.lurkerkit.model.MessageActionContext
import net.amiantos.lurkerkit.model.MessageActionKey
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.model.MessageActions
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.support.Result
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.net.URI
import java.net.URISyntaxException

/**
 * What the actions sheet is about. A press that lands on a link is about the link — the line around
 * it isn't what you were pointing at — which is the same split Discord makes. lurker-ios's
 * `MessageActionsViewController.Subject`.
 */
sealed interface ActionSubject {
    /**
     * The line as the list SHOWS it (a relayed line as the person inside it), plus the facts about
     * where it lives that the line itself doesn't carry — read at the press, and carried to the run
     * rather than re-read there (see [MessageActionsModel.run]).
     */
    data class Line(val message: Message, val scope: MessageActionScope) : ActionSubject

    /** A link's `href`, as the body's link annotation resolved it. */
    data class Link(val url: String) : ActionSubject
}

/**
 * Which row the sheet drew: one of the kit's actions, or Ignore — the web's fourth action, which the
 * kit leaves to the screen because it needs rule authoring (`MessageActions`' note).
 */
sealed interface ActionKey {
    data class Kit(val key: MessageActionKey) : ActionKey

    /** "Ignore alice…": the choices dialog, then the kit's `/ignore` builder. */
    data object Ignore : ActionKey
}

/** The glyph a row wears — the kit's SF Symbol names, mapped onto the app's own icons. */
enum class ActionGlyph { Reply, React, Copy, Bookmark, Bookmarked, Profile, OpenLink, Share, Ignore }

/** One row of the sheet: what it does, what it says, and its glyph. */
data class ActionRow(val key: ActionKey, val title: String, val glyph: ActionGlyph)

/** The sheet's header: who or what it's about, then the line itself — so it can't act on the wrong one unseen. */
data class ActionHeader(val title: String, val detail: String?)

/**
 * The per-message actions sheet, decided without drawing it (lurker-ios#60, lurker-android#37).
 *
 * The kit's `MessageActions` says which actions a line offers, in what order and with what words —
 * neither this nor the sheet second-guesses it, so a second message-list style can't drift from this
 * one. What's added here is only what the kit leaves to the screen: Ignore, and the header.
 */
object MessageActionsModel {

    /** The rows for [subject], in menu order — empty when it offers nothing (no sheet then, as iOS). */
    fun rows(subject: ActionSubject): List<ActionRow> =
        when (subject) {
            is ActionSubject.Link -> MessageActions.build(subject.url).map(::kitRow)
            is ActionSubject.Line -> {
                val kit = MessageActions.build(subject.message, scope = subject.scope).map(::kitRow)
                val ignore = ignoreSubject(subject.message, subject.scope)
                if (ignore == null) kit else kit + ActionRow(ActionKey.Ignore, "Ignore $ignore…", ActionGlyph.Ignore)
            }
        }

    private fun kitRow(action: MessageAction): ActionRow =
        ActionRow(ActionKey.Kit(action.key), action.title, glyph(action.symbol))

    /**
     * The kit's SF Symbol name → the app's glyph. By name rather than by key, because Bookmark's
     * symbol is what says whether the line is saved (`bookmark` vs `bookmark.fill`).
     */
    fun glyph(symbol: String): ActionGlyph =
        when (symbol) {
            "arrowshape.turn.up.left" -> ActionGlyph.Reply
            "face.smiling" -> ActionGlyph.React
            "doc.on.doc" -> ActionGlyph.Copy
            "bookmark" -> ActionGlyph.Bookmark
            "bookmark.fill" -> ActionGlyph.Bookmarked
            "person.crop.circle" -> ActionGlyph.Profile
            "safari" -> ActionGlyph.OpenLink
            "square.and.arrow.up" -> ActionGlyph.Share
            // A symbol the kit grows later still draws something rather than nothing.
            else -> ActionGlyph.Copy
        }

    /**
     * Title over detail. For a line: the nick names who you're acting on; the body confirms which of
     * their lines it was — stripped of mIRC codes, deliberately unlike Copy Text, because the header's
     * only job is identification and has to match what's on screen. A re-attributed relay line names
     * its bridge ("alice via relaybot") — this sheet is the whole of that provenance on a phone. For a
     * link: the host, which stays legible where a long URL truncates to nothing useful.
     */
    fun header(subject: ActionSubject): ActionHeader =
        when (subject) {
            is ActionSubject.Line -> {
                val message = subject.message
                val nick = message.nick
                val speaker = if (nick != null && nick.isNotEmpty()) nick else "Message"
                val bot = message.relayBot
                ActionHeader(
                    title = if (bot != null) "$speaker via $bot" else speaker,
                    detail = message.text?.let(IRCFormatting::strip),
                )
            }
            is ActionSubject.Link -> ActionHeader(title = host(subject.url) ?: "Link", detail = subject.url)
        }

    /** A URL's host, or null when it has none (`mailto:`) or doesn't parse. */
    fun host(url: String): String? =
        try {
            URI(url).host?.takeIf { it.isNotEmpty() }
        } catch (_: URISyntaxException) {
            null
        }

    // MARK: - Ignore

    /**
     * Who Ignore would act on, or null when the line offers no Ignore. The web's rule — someone
     * else's line with a nick — narrowed to speech on a network:
     *
     *  - **Speech**: the web's whole action surface is gated on speech, and a server line's
     *    nick-shaped field is the server itself, not a person.
     *  - **A network**: a system-buffer line has no IRC subject at all.
     *  - **Not your own**: ignoring yourself is pointless, and the server never filters your lines.
     *
     * ⚠ The subject is `MessageActions.profileSubject`, not the nick on screen — on a relayed line
     * that's the bridge. The person shown has no IRC presence; the bot is the thing a rule can match,
     * and the kit's rule for "anything that addresses the network about a line" is to ask about it.
     */
    fun ignoreSubject(message: Message, scope: MessageActionScope): String? {
        if (!message.type.isSpeech || message.isSelf || scope.networkId == null) return null
        return MessageActions.profileSubject(message)
    }

    /**
     * The mask the Ignore dialog opens on — the web's `IgnoreModal` default: the sender's identity
     * (`*!user@host`, IRCCloud's convention) so the rule survives a nick change, else the nick with
     * wildcards when the line carries no usable hostmask. Always a full `!`/`@` mask, so a nick that
     * happens to spell a level token (`Quit`) or start like a flag can't be read as one.
     */
    fun defaultIgnoreMask(message: Message, subject: String): String {
        // On a relayed line the userhost is the bot's, which is the subject too — so it still fits.
        val identity = message.userHostMask
        return if (identity != null) "*!$identity" else "$subject!*@*"
    }

    /**
     * The `/ignore` line for [mask] — the kit's own builder (`CommandParser` → `IgnoreArgs`) parses
     * it into the rule, scopes it, writes it and prints its receipt, exactly as a typed `/ignore`
     * does; nothing here knows the rule's shape. Null when the mask wouldn't come through as the mask
     * alone (blank, two words, a channel or a level token) — the dialog then won't offer to send it.
     *
     * ⚠⚠ Global unless [thisNetwork] (lurker#350): `-network` is what scopes it.
     */
    fun ignoreCommand(mask: String, thisNetwork: Boolean): String? {
        val trimmed = mask.trimmingWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return null
        val args = if (thisNetwork) "-network $trimmed" else trimmed
        val parsed = IgnoreArgs.parse(args)
        if (parsed !is Result.Success) return null
        val rule = parsed.value.rule
        // Exactly the mask, nothing else: not split, not eaten as a level or a channel, not quoted.
        if (rule.mask != trimmed || rule.channels != null || rule.levels != listOf("ALL")) return null
        return "/ignore $args"
    }

    /** "Messages matching … will be hidden on every network." — the web dialog's preview line. */
    fun ignorePreview(mask: String, thisNetwork: Boolean): String {
        val shown = mask.trimmingWhitespacesAndNewlines().ifEmpty { "∅" }
        return "Messages matching $shown will be hidden ${if (thisNetwork) "on this network" else "on every network"}."
    }

    // MARK: - Run

    /**
     * Where a line's actions go. The kit's `MessageActionContext` plus the one it doesn't carry.
     */
    class LineEffects(
        val reply: (Message) -> Unit,
        val copy: (String) -> Unit,
        val setBookmark: (Long, Boolean) -> Unit,
        val showProfile: (String) -> Unit,
        val react: (Message) -> Unit,
        val ignore: (Message, subject: String) -> Unit,
    )

    /**
     * Perform [key] on [subject]. A line's kit keys run through `MessageActions.run` with the scope
     * the sheet was BUILT with — deliberately not a fresh reading: it's what titled the row, so a
     * `bookmark-updated` echo landing between the press and the tap can't invert "Save Message" into
     * an unsave. Ignore is re-gated the same way the kit gates its own.
     */
    fun run(
        key: ActionKey,
        subject: ActionSubject,
        line: LineEffects,
        open: (String) -> Unit,
        copyLink: (String) -> Unit,
        share: (String) -> Unit,
    ) {
        when (subject) {
            is ActionSubject.Link -> if (key is ActionKey.Kit) {
                MessageActions.run(
                    key.key,
                    url = subject.url,
                    context = LinkActionContext(open = open, copy = copyLink, share = share),
                )
            }
            is ActionSubject.Line -> when (key) {
                is ActionKey.Kit -> MessageActions.run(
                    key.key,
                    message = subject.message,
                    scope = subject.scope,
                    context = MessageActionContext(
                        reply = line.reply,
                        copy = line.copy,
                        setBookmark = line.setBookmark,
                        showProfile = line.showProfile,
                        react = line.react,
                    ),
                )
                ActionKey.Ignore -> ignoreSubject(subject.message, subject.scope)?.let { line.ignore(subject.message, it) }
            }
        }
    }
}
