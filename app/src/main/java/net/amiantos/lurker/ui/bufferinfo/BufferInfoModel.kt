// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import net.amiantos.lurker.ui.list.label
import net.amiantos.lurker.ui.networks.NetworksListModel
import net.amiantos.lurker.ui.networks.title
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelAccess
import net.amiantos.lurkerkit.model.ChannelModeForm
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkRow
import net.amiantos.lurkerkit.model.SearchQuery
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.model.channelAccess
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import java.time.Instant

/**
 * Everything the buffer info page draws, and nothing else. iOS dedupes on the built sections rather
 * than a hand-kept list of inputs, because its list drifted the moment it was written (it missed the
 * network's name); here the inputs ARE what the sections are built from, one field each, so the two
 * can't disagree.
 *
 * Read live out of state rather than off the buffer the page was opened for: that one is a value,
 * frozen at the moment of the tap, so its topic would be too.
 */
data class BufferInfoInputs(
    val kind: BufferKind,
    /** The live row, or the opening one while the store doesn't hold it. */
    val topic: String?,
    /** For the title: a server log is named for its network, which can arrive late (lurker-ios#136). */
    val networkName: String?,
    /**
     * Visible members, not every member: this count sits one tap from the nicklist, and the two
     * disagreeing about how many people are in the channel reads as a bug in whichever the reader
     * looks at second.
     */
    val memberCount: Int,
    val modes: ChannelModeState?,
    val access: ChannelAccess,
    /** The network's connection, for a server log. Null when the store doesn't hold the network. */
    val connection: NetworkRow?,
    /** A DCC chat's session (`ChatState.dccChatSession`): null while it can't be known. */
    val dccLive: Boolean?,
    /**
     * The `in:`/`on:` prefix that scopes a search to this buffer (`SearchQuery.scope`), or null where
     * there's no meaningful scope — the row simply isn't offered then. The `on:` half needs the
     * network's name, which only the roster has.
     */
    val searchScope: String? = null,
) {
    companion object {
        fun of(state: ChatState, opened: Buffer): BufferInfoInputs {
            val key = opened.key
            val live = state.buffers[key.id] ?: opened
            val networkId = key.networkId
            val network = networkId?.let { state.networks[it] }
            return BufferInfoInputs(
                kind = opened.kind,
                topic = live.topic,
                networkName = network?.name,
                // Counted only where it's drawn: a busy channel's filter isn't free.
                memberCount = if (opened.kind == BufferKind.Channel) state.visibleMembers(key).size else 0,
                modes = state.channelModes[key.id],
                access = state.channelAccess(key),
                connection = network?.let { NetworkRow(connection = it.state, isBlocked = it.blocked) },
                dccLive = if (opened.kind == BufferKind.Dcc) state.dccChatSession(key) else null,
                searchScope = SearchQuery.scope(live, network?.name),
            )
        }
    }
}

/**
 * The slice of `ChatState` the info page is built from, narrowed so that most frames can be turned away
 * cheaply before [BufferInfoInputs] is built — lurker-ios's `removeDuplicates`, made a type, as
 * `BufferListInputs` is.
 *
 * ⚠ The member list and the ignore set compare by IDENTITY ([same]): building the inputs counts the
 * visible members, which runs the ignore rules over every member of the channel, and a busy channel's
 * state moves on every line. The store replaces a member list only when membership changes, and the
 * ignore set only when a rule does, so identity is exactly "something the count reads moved". The
 * buffer is narrowed to the two fields the page reads (topic, joined) — its unread counts move on every
 * message in it, and nothing here draws them.
 */
internal class BufferInfoSource private constructor(
    private val key: BufferKey,
    private val connection: SocketStatus,
    private val snapshotSinceOpen: Boolean,
    private val present: Boolean,
    private val topic: String?,
    private val joined: Boolean,
    private val network: Network?,
    private val members: List<Member>?,
    private val modes: ChannelModeState?,
    private val ignores: IgnoreSet,
    private val dccChats: List<String>?,
) {
    /** The page's inputs, from exactly what was compared — the kit's own helpers over a narrowed state. */
    fun inputs(opened: Buffer): BufferInfoInputs {
        val networkId = key.networkId
        val narrowed = ChatState(
            connection = connection,
            snapshotSinceOpen = snapshotSinceOpen,
            networks = network?.let { mapOf(it.id to it) }.orEmpty(),
            buffers = if (present) mapOf(key.id to opened.copy(topic = topic, joined = joined)) else emptyMap(),
            members = members?.let { mapOf(key.id to it) }.orEmpty(),
            channelModes = modes?.let { mapOf(key.id to it) }.orEmpty(),
            ignores = ignores,
            dccChats = if (networkId != null && dccChats != null) mapOf(networkId to dccChats) else emptyMap(),
        )
        return BufferInfoInputs.of(narrowed, opened)
    }

    companion object {
        fun of(state: ChatState, key: BufferKey): BufferInfoSource {
            val live = state.buffers[key.id]
            val networkId = key.networkId
            return BufferInfoSource(
                key = key,
                connection = state.connection,
                snapshotSinceOpen = state.snapshotSinceOpen,
                present = live != null,
                topic = live?.topic,
                joined = live?.joined == true,
                network = networkId?.let { state.networks[it] },
                members = state.members[key.id],
                modes = state.channelModes[key.id],
                ignores = state.ignores,
                dccChats = networkId?.let { state.dccChats[it] },
            )
        }

        fun same(old: BufferInfoSource, new: BufferInfoSource): Boolean =
            old.members === new.members &&
                old.ignores === new.ignores &&
                old.connection == new.connection &&
                old.snapshotSinceOpen == new.snapshotSinceOpen &&
                old.present == new.present &&
                old.topic == new.topic &&
                old.joined == new.joined &&
                old.network == new.network &&
                old.modes == new.modes &&
                old.dccChats == new.dccChats
    }
}

/** End the live session, or offer a new one when there's none — a dead chat can't be resumed, only replaced. */
enum class DccChatAction { Start, End }

/** One row of the buffer info page. */
sealed interface InfoRow {
    /** The topic, or "No topic set." in the secondary colour. */
    data class Topic(val text: String, val muted: Boolean) : InfoRow

    /** The channel's topic and modes (lurker-ios#187); the value is the set letters, `+nt`. */
    data class ChannelSettings(val modes: String?) : InfoRow

    /** One of the channel's list modes — bans, exceptions, invite exceptions, quiets. */
    data class ModeList(val letter: String, val name: String) : InfoRow

    data class Members(val count: Int) : InfoRow

    data object Whois : InfoRow

    /**
     * Search pre-scoped to this buffer; [scope] is the `in:`/`on:` prefix the search field starts with.
     * Searching *this* buffer is a fact about it, so it lives here rather than in the bar's views.
     */
    data class Search(val scope: String) : InfoRow

    /** A placeholder that says it is one — see [BufferInfoModel.notifications]. */
    data class NotifyPlaceholder(val title: String) : InfoRow

    /** The network's connection as a status line, with the networks screen's light. */
    data class Connection(val label: String, val light: StatusLight) : InfoRow

    /** One verb that changes it — only the non-destructive ones (`NetworkRow.connectionActions`). */
    data class NetworkVerb(val action: NetworkAction) : InfoRow {
        val title: String get() = action.title
    }

    /** A DCC chat's session as a status line. */
    data class DccStatus(val label: String, val light: StatusLight) : InfoRow

    data class DccVerb(val action: DccChatAction) : InfoRow {
        val title: String get() = if (action == DccChatAction.End) "End Chat" else "Start New Chat"
    }
}

data class InfoSection(val header: String? = null, val footer: String? = null, val rows: List<InfoRow>)

/**
 * What a buffer *is*, rather than what's been said in it — lurker-ios's `BufferInfoViewController`,
 * pure. A channel gets its topic, its settings and lists, a count of who's here, and how it notifies;
 * a DM gets the person and how it notifies; a server log gets the connection behind it and the verbs
 * that change it (lurker-ios#152); a DCC chat gets its session and the verb that ends or restarts it,
 * then the person (lurker#270); the system buffer gets a sentence saying there's nothing here.
 *
 * The info button means the same thing on every buffer: "about this one". That's why a DM lands here
 * and not straight in a whois — whois is about a *person*, and a person is one of the things a DM is
 * about, not the whole of it.
 *
 * "Search This Conversation" (`InfoRow.Search`) rides the members/whois section of every buffer that
 * has a scope to search by.
 */
object BufferInfoModel {
    /**
     * Shown instead of an empty page: the system buffer, which has nothing to configure — and, for the
     * moment before the store holds its network, a server log. The info button opens this from every
     * buffer, so the honest answer has to be a sentence rather than a blank page.
     */
    const val EMPTY = "This buffer has no settings."

    fun title(opened: Buffer, inputs: BufferInfoInputs): String = opened.displayName(networkName = inputs.networkName)

    /**
     * @param actionError the latest refusal of a connection or DCC verb, pinned under its section.
     * @param dateTime medium date, short time — the topic setter's line.
     * @param longDate a long date alone — the channel's creation.
     */
    fun sections(
        inputs: BufferInfoInputs,
        actionError: String?,
        dateTime: (Instant) -> String,
        longDate: (Instant) -> String,
    ): List<InfoSection> =
        when (inputs.kind) {
            BufferKind.Channel -> {
                val held = inputs.modes
                val access = inputs.access
                // Which lists exist is the network's to say — `q` is a list on solanum and an owner
                // elsewhere — so none are offered until its vocabulary arrives, and none out of the
                // channel, where asking draws a 442.
                val spec = access.spec
                val lists = if (access.joined && spec != null) {
                    ChannelModeForm.lists(spec).map { InfoRow.ModeList(it.letter, it.name) }
                } else {
                    emptyList()
                }
                val topic = inputs.topic
                val hasTopic = !topic.isNullOrEmpty()
                listOf(
                    // Nothing under no topic: there's no one to credit.
                    InfoSection(
                        header = "Topic",
                        footer = if (hasTopic) held?.topicSetterLine(dateTime) else null,
                        rows = listOf(InfoRow.Topic(if (hasTopic) topic!! else "No topic set.", muted = !hasTopic)),
                    ),
                    InfoSection(
                        footer = held?.createdAt?.let { "Created ${longDate(it)}" },
                        rows = listOf(InfoRow.ChannelSettings(held?.modes?.takeIf { it.isNotEmpty() }?.let { "+$it" })) + lists,
                    ),
                    InfoSection(rows = listOf(InfoRow.Members(inputs.memberCount)) + searchRows(inputs)),
                    notifications,
                )
            }
            BufferKind.Dm -> listOf(InfoSection(rows = listOf(InfoRow.Whois) + searchRows(inputs)), notifications)
            BufferKind.Dcc -> {
                // The session first: whether a line typed here will arrive is the thing about this
                // buffer most worth knowing, and its verbs live nowhere else a thumb can reach.
                //
                // No verb until the session is known: during a reconnect the list is the last
                // session's, and End on a chat that has already ended — or Start on one that hasn't —
                // is a request made on a guess.
                val live = inputs.dccLive
                val status = when (live) {
                    true -> InfoRow.DccStatus("Connected", StatusLight.Good)
                    false -> InfoRow.DccStatus("Not connected", StatusLight.Bad)
                    null -> InfoRow.DccStatus("Checking…", StatusLight.Warn)
                }
                val verb = live?.let { InfoRow.DccVerb(if (it) DccChatAction.End else DccChatAction.Start) }
                listOf(
                    InfoSection(header = "DCC Chat", footer = actionError, rows = listOfNotNull(status, verb)),
                    // The Whois row is the peer's — the profile peels the `=` off.
                    InfoSection(rows = listOf(InfoRow.Whois) + searchRows(inputs)),
                    notifications,
                )
            }
            // A server log has no topic or members; the one thing it is *about* is the connection
            // behind it, so this is where that connection is managed (lurker-ios#152) — the networks
            // screen's verbs, minus Delete, which belongs with the roster.
            BufferKind.Server -> listOfNotNull(connectionSection(inputs.connection, actionError))
            // Nothing here is a setting: the app's own buffer has no topic, no members, no connection
            // of its own, and nothing to notify about.
            BufferKind.System -> emptyList()
        }

    /** The search row, where the buffer has a scope to search by. */
    private fun searchRows(inputs: BufferInfoInputs): List<InfoRow> = listOfNotNull(inputs.searchScope?.let(InfoRow::Search))

    /**
     * The connection behind a server log, and what can be done to it now. Null when the store has no
     * row for the network — a transient — and the page says [EMPTY] rather than offering Connect for a
     * network it can't describe.
     *
     * The refusal takes the footer, as it takes the subtitle on the networks screen: what the server
     * just said beats a standing note. Blocked gets that screen's sentence whether or not anything is
     * offered — a connected-but-blocked network still needs to say why Reconnect is missing.
     */
    fun connectionSection(row: NetworkRow?, actionError: String?): InfoSection? {
        row ?: return null
        val footer = actionError ?: if (row.isBlocked) NetworksListModel.BLOCKED_EXPLANATION else null
        return InfoSection(
            header = "Connection",
            footer = footer,
            rows = listOf(InfoRow.Connection(row.connection.label, row.light)) + row.connectionActions.map { InfoRow.NetworkVerb(it) },
        )
    }

    /**
     * Placeholders, and they say so. The per-channel flag they'll drive (`notify_always`) exists
     * server-side and rides the snapshot, but the client doesn't parse it yet — so there's no honest
     * state to render, and a switch that silently does nothing is worse than one that admits it.
     */
    val notifications = InfoSection(
        header = "Notifications",
        footer = "Not wired up yet — these don't change anything.",
        rows = listOf(InfoRow.NotifyPlaceholder("Notify me about every message")),
    )

    /**
     * Whether a refusal on screen is still about the connection (or DCC session) it was sent against.
     * Once that moves — the network's own retry succeeding under a failed Disconnect, say — the footer
     * would sit under rows that contradict it, with no way to clear it short of another verb.
     */
    data class Subject(val connection: NetworkRow?, val dccLive: Boolean?) {
        companion object {
            fun of(inputs: BufferInfoInputs) = Subject(inputs.connection, inputs.dccLive)
        }
    }

    /** The key a buffer's page is about, for the DCC verbs: the peer, without the chat's `=`. */
    fun dccPeer(key: BufferKey): String = DccChat.peer(key.target)
}
