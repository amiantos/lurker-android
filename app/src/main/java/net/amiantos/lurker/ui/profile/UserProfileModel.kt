// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.profile

import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.ProfileStatus
import net.amiantos.lurkerkit.model.WhoisResult
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import java.time.Instant

/**
 * Every input the profile draws from, and nothing else — lurker-ios's `removeDuplicates` block,
 * made a value. A profile open over a busy channel would otherwise rebuild on every arriving message.
 *
 * Projected rather than carried: the note for THIS nick rather than the whole `NickNoteSet`, and a
 * yes/no rather than the `RelayBotSet`, so comparing two of these is comparing a handful of small
 * values. iOS compares the two sets by identity for the same reason; here the projection is what
 * keeps it cheap.
 *
 * Our own nick is here because it decides `isSelf`, which decides whether Send Message is offered at
 * all — a `/nick` while this is open would otherwise leave the row inviting you to DM yourself.
 */
data class ProfileInputs(
    val whois: WhoisResult?,
    val isLookingUp: Boolean,
    val presence: FriendPresence,
    val note: NickNote?,
    val isRelay: Boolean,
    val selfNick: String,
) {
    companion object {
        fun of(state: ChatState, networkId: Int, nick: String): ProfileInputs =
            ProfileInputs(
                whois = state.whoisResult(networkId = networkId, nick = nick),
                isLookingUp = state.isWhoisPending(networkId = networkId, nick = nick),
                // `presence`, not `rowPresence`: the profile keeps the stricter reading, which says
                // OFFLINE while our own socket is down — `ProfileStatus` falls back to a cached whois
                // whenever this says unknown, and that reply outlives the socket.
                presence = state.presence(networkId = networkId, nick = nick),
                note = state.nickNotes.note(networkId = networkId, nick = nick),
                isRelay = state.relayBots.isRelay(networkId = networkId, nick = nick),
                selfNick = state.networks[networkId]?.nick ?: "",
            )
    }
}

/** Which glyph a flag row wears — iOS's SF Symbol names, as roles. */
enum class ProfileFlagIcon { Registered, Operator, HelpOp, Bot, Relay }

/** One row of the profile. */
sealed interface ProfileRow {
    data class Status(val line: ProfileStatus.StatusLine, val text: String) : ProfileRow

    /**
     * A labelled fact. [copyable] rows put their value on the clipboard when tapped — a hostmask is
     * the one thing here people actually retype elsewhere.
     */
    data class Detail(val title: String, val value: String, val copyable: Boolean = false) : ProfileRow

    /** A standing fact about the account rather than a value: registered, bot, operator. */
    data class Flag(val title: String, val icon: ProfileFlagIcon) : ProfileRow

    data class Channel(val entry: WhoisResult.ChannelEntry) : ProfileRow {
        val title: String get() = entry.prefix + entry.name
    }

    data class Note(val text: String) : ProfileRow

    data class EditNote(val hasNote: Boolean) : ProfileRow {
        val title: String get() = if (hasNote) "Edit Note" else "Add Note"
    }

    data object SendMessage : ProfileRow

    data object Refresh : ProfileRow
}

data class ProfileSection(val header: String? = null, val footer: String? = null, val rows: List<ProfileRow>)

/**
 * Who someone is (lurker-ios#12) — `UserProfileViewController`'s sections and copy, pure.
 *
 * The four questions about whether the person is *there* are the kit's (`ProfileStatus`); this is
 * which rows that answer, and the whois reply, become.
 */
object UserProfileModel {
    /** Said once, under the note, rather than left for someone to discover. */
    const val NOTE_FOOTER = "Only you can see this. It syncs to your other devices."

    fun status(inputs: ProfileInputs, nick: String): ProfileStatus {
        val self = inputs.selfNick
        return ProfileStatus.resolve(
            peer = inputs.presence,
            whois = inputs.whois,
            isLookingUp = inputs.isLookingUp,
            isSelf = self.isNotEmpty() && self.lowercase() == nick.lowercase(),
        )
    }

    /**
     * The profile, top to bottom.
     *
     * @param canOpenBuffers whether there is somewhere for Send Message and the channel rows to go.
     *   ⚠ Without one they are dead taps — they'd ask the server for the buffer and then go nowhere —
     *   so the rows that need it are only offered when there is.
     * @param dateTime how a moment reads (medium date, short time) — the app's, which can see the
     *   device's 24-hour setting.
     */
    fun sections(
        inputs: ProfileInputs,
        nick: String,
        canOpenBuffers: Boolean,
        dateTime: (Instant) -> String,
    ): List<ProfileSection> {
        val status = status(inputs, nick)
        val built = mutableListOf<ProfileSection>()
        status.statusLine?.let { line -> built += ProfileSection(rows = listOf(ProfileRow.Status(line, statusText(line, nick)))) }
        val details = details(status, inputs.whois, dateTime)
        if (details.isNotEmpty()) built += ProfileSection(rows = details)
        val flags = flags(inputs.whois, inputs.isRelay)
        if (flags.isNotEmpty()) built += ProfileSection(rows = flags)
        // Channels are a navigation offer, so they need somewhere to navigate — same rule as Send
        // Message below.
        val channels = if (canOpenBuffers) inputs.whois?.channels.orEmpty() else emptyList()
        if (channels.isNotEmpty()) built += ProfileSection(header = "Channels", rows = channels.map { ProfileRow.Channel(it) })
        built += noteSection(inputs.note, dateTime)
        val actions = buildList {
            if (status.canSendDirectMessage && canOpenBuffers) add(ProfileRow.SendMessage)
            add(ProfileRow.Refresh)
        }
        built += ProfileSection(rows = actions)
        return built
    }

    /**
     * The lookup's outcome, in words — what the page's one status place announces when a lookup lands
     * while the reader waits (#20): "alice isn't on this network.", or the Status row's own value
     * under the nick ("alice, Away — lunch"). Null while a lookup is out (a wait isn't news — the
     * line says "Looking up alice…" on focus, quietly) and when nothing is known to say.
     *
     * ⚠ Null for ANY lookup in flight, not just the status line's `Waiting`: with a cached reply on
     * screen `ProfileStatus.resolve` drops the line while a refresh is out (the details speak for
     * themselves), and reading that as "settled" would hold the old outcome through a reopen or a
     * Refresh — so a refresh answering the same thing would never be read out again. Going null for
     * the wait is what makes every answer a change. The cached details still draw.
     */
    fun lookupOutcome(inputs: ProfileInputs, nick: String): String? {
        if (inputs.isLookingUp) return null
        val status = status(inputs, nick)
        return when (status.statusLine) {
            ProfileStatus.StatusLine.Waiting -> null
            ProfileStatus.StatusLine.NotFound -> statusText(ProfileStatus.StatusLine.NotFound, nick)
            null -> if (status.presence == FriendPresence.Unknown) null else "$nick, ${statusValue(status)}"
        }
    }

    /** The Status row's value: the presence, and the away reason beside an away dot — "Away — lunch". */
    fun statusValue(status: ProfileStatus): String {
        val title = presenceTitle(status.presence)
        return status.awayMessage?.let { "$title — $it" } ?: title
    }

    fun statusText(line: ProfileStatus.StatusLine, nick: String): String =
        when (line) {
            ProfileStatus.StatusLine.NotFound -> "$nick isn't on this network."
            ProfileStatus.StatusLine.Waiting -> "Looking up $nick…"
        }

    /**
     * The facts, status first. Status is a labelled row rather than a coloured dot: the bar already
     * says whose profile this is, and as a row it says its state in words, which is what a colour was
     * only standing in for.
     */
    fun details(status: ProfileStatus, whois: WhoisResult?, dateTime: (Instant) -> String): List<ProfileRow> {
        val rows = mutableListOf<ProfileRow>()
        fun add(title: String, value: String?, copyable: Boolean = false) {
            if (value.isNullOrEmpty()) return
            rows += ProfileRow.Detail(title, value, copyable)
        }
        // ⚠ Omitted rather than shown as "Unknown" while we're still asking. The status LINE above
        // already says "Looking up alice…", and a row asserting we don't know, under a line saying
        // we're finding out, is the same fact told twice.
        if (status.presence != FriendPresence.Unknown) add("Status", statusValue(status))
        if (whois == null) return rows
        add("Real name", whois.realName)
        add("Hostmask", whois.hostmask, copyable = true)
        // Only opers and the account itself are told this, so it's absent far more often than
        // present — a row that appears rather than one that says "unknown".
        add("Connected from", listOfNotNull(whois.actualHostname, whois.actualIP).joinToString(" "))
        // ⚠ Only ever "secure", never "not secure": RPL_WHOISSECURE is sent only when the connection
        // IS one, and plenty of servers never send it — an absent flag is silence, not a denial.
        if (whois.isSecure) add("Connection", "Secure (TLS)")
        add("Account", whois.account)
        // ⚠ Gated on `server`, not `serverInfo`: the two are independent on the wire, and the
        // description alone would render " (Example Network)", leading space and all.
        whois.server?.let { name -> add("Server", whois.serverInfo?.let { "$name ($it)" } ?: name) }
        add("Idle", whois.idleSeconds?.let(::idle))
        add("Signed on", whois.signedOn?.let(dateTime))
        return rows
    }

    /**
     * What the network *asserts* about them, in its own words — "is an IRC Operator" says more than a
     * chip reading "Operator", and the numeric's trailing text is exactly what the field holds.
     */
    fun flags(whois: WhoisResult?, isRelay: Boolean): List<ProfileRow> {
        val rows = mutableListOf<ProfileRow>()
        if (whois != null) {
            whois.registeredNick?.let { rows += ProfileRow.Flag(it, ProfileFlagIcon.Registered) }
            whois.isOperator?.let { rows += ProfileRow.Flag(it, ProfileFlagIcon.Operator) }
            whois.helpop?.let { rows += ProfileRow.Flag(it, ProfileFlagIcon.HelpOp) }
            whois.bot?.let { rows += ProfileRow.Flag(it, ProfileFlagIcon.Bot) }
        }
        // A local mark rather than a whois fact, so it shows even with no reply in — including for a
        // bot that is currently offline, which is exactly when you'd come looking.
        if (isRelay) rows += ProfileRow.Flag("Marked as a relay bot", ProfileFlagIcon.Relay)
        return rows
    }

    fun noteSection(note: NickNote?, dateTime: (Instant) -> String): ProfileSection {
        val rows = mutableListOf<ProfileRow>()
        if (note != null && note.note.isNotEmpty()) rows += ProfileRow.Note(note.note)
        rows += ProfileRow.EditNote(hasNote = note != null)
        return ProfileSection(
            header = "Your note",
            // A note is the account's, not the channel's, and it follows them to the browser.
            footer = note?.updatedAt?.let { "Updated ${dateTime(it)}" } ?: NOTE_FOOTER,
            rows = rows,
        )
    }

    /** Capitalised, for the Status row's value — iOS's `FriendPresence.title`. */
    fun presenceTitle(presence: FriendPresence): String =
        when (presence) {
            FriendPresence.Online -> "Online"
            FriendPresence.Away -> "Away"
            FriendPresence.Offline -> "Offline"
            FriendPresence.Unknown -> "Unknown"
        }

    /**
     * Idle time — iOS's `DateComponentsFormatter`, abbreviated and capped at two units, so a long idle
     * reads "3d 4h" instead of counting seconds. Zero is "active right now", which reads better than
     * "0s".
     *
     * Two units from the largest one present, the second left off when it's zero ("1d", not
     * "1d 0h") — the formatter drops zero-valued units in its labelled styles.
     */
    fun idle(seconds: Long): String {
        if (seconds <= 0) return "Active now"
        val parts = listOf(
            seconds / 86_400 to "d",
            seconds % 86_400 / 3_600 to "h",
            seconds % 3_600 / 60 to "m",
            seconds % 60 to "s",
        )
        val first = parts.indexOfFirst { it.first > 0 }
        return parts.drop(first).take(2).filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }
    }
}

/**
 * The nick note editor's one rule worth testing: whether there is anything to delete. Clearing has its
 * own row rather than being "save an empty field", because an empty field is also what you see a
 * moment after tapping Add Note, and a Save that sometimes deletes is a button whose meaning depends on
 * state the user can't see.
 */
object NickNoteModel {
    const val PLACEHOLDER = "Anything worth remembering."
    const val DELETE_TITLE = "Delete this note?"

    fun fieldLabel(nick: String): String = "Note about $nick"

    fun deleteMessage(nick: String): String = "Your note about $nick will be removed from all your devices."

    fun offersDelete(original: String): Boolean = original.isNotEmpty()

    /** iOS's words for a send that has no socket to go down. */
    const val NOT_CONNECTED = "Not connected — try again when you're back online"

    /**
     * Why Save or Delete can't go out now, or null when it can. The note travels over our own socket,
     * and the kit's `setNickNote` only knows whether there was one, not whether it still works — so
     * the editor asks first, and stays open with what was typed rather than closing on a write that
     * went nowhere. Only the socket
     * matters: a note is the account's, not the IRC network's, so a network that's down is no reason.
     */
    fun sendRefusal(connection: SocketStatus, reachable: Boolean): String? =
        if (reachable && connection == SocketStatus.Connected) null else NOT_CONNECTED
}
