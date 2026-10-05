// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.DraftEntry
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.ReactionChange
import net.amiantos.lurkerkit.model.RelayBot
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.TypingActivity
import java.time.Instant

/**
 * A parsed, typed update from the server — REST or WS — for the store to fold in.
 * Parsing the raw JSON into these here keeps the JSON layer an implementation detail
 * the store and UI never see.
 *
 * Port note: three cases share a name with the model type they carry — [AwayState],
 * [WhoisResult] and [ModeSpec] — and inside this interface the bare name is the case. The model
 * type is written out in full there (PORTING.md, "a case named like a builtin").
 *
 * Port note: where LurkerKit's case has an unlabelled associated value, the property takes the
 * name `LurkerStore` binds it to when it matches the case (`case .networks(let networks)`).
 *
 * Port note: a message id, a byte count and anything derived from one is a `Long` here
 * (PORTING.md, Types) — `lastReadId`, `clearedBeforeId`, `messageId`, `messageIds`, the keys of
 * `reactions`, and `UploadLimits.maxUploadBytes`.
 */
internal sealed interface ServerFrame {
    /** REST `GET /api/networks`: the roster (names live here, not in the snapshot). */
    data class Networks(val networks: List<Network>) : ServerFrame

    /**
     * WS `snapshot`: live per-network state + joined channels and their members.
     *
     * **Not authoritative for which buffers exist.** Its per-network `channels` is empty for
     * every network without a live connection, and is read before auto-rejoin JOINs land even
     * for one that has it. DMs and `:server:` logs aren't in it at all — they arrive as
     * separate `backlog` frames afterwards. Use `backlogComplete` to know the burst is done.
     *
     * `globalIgnores` is the un-scoped half of the ignore rules (lurker #350) — rules that
     * apply on every network, so they belong to no network blob. Spelled out as its own
     * value rather than hidden in a default because a snapshot that dropped it would leave
     * the most common kind of rule silently inert until the next time one was edited.
     *
     * `uploadLimits` are the account's advertised upload cap (lurker#627) and static-image
     * dimension (lurker#872), refreshed on every reconnect. **Each null is "the server didn't
     * say", not "no limit"** — an instance older than the field is a normal condition, and
     * reading its silence as a number would be the guess these replaced. See
     * `Uploads.compressionTarget(advertised)` and `ImageShrink`.
     *
     * They belong to the account rather than to any network, so they ride the frame the way
     * `globalIgnores` does — grouped as one class, because a third and fourth positional
     * value is where a parameter list stops being readable.
     *
     * `cursor` is the global max message id, sent on a fresh connect only (§4.3). The shell
     * backlogs that follow carry no rows, so it is the only thing that moves the resume
     * cursor past the server logs; null on a resume, which already has one.
     */
    data class Snapshot(
        val networks: List<NetworkSnapshot>,
        val globalIgnores: List<IgnoreRule>,
        val uploadLimits: UploadLimits,
        val cursor: Long? = null,
    ) : ServerFrame

    /**
     * WS `backlog-complete`: the terminal frame of a snapshot burst (lurker #635).
     *
     * The only frame that says "that's all of it". The server sends it last on every
     * snapshot — connect, in-band resync, fresh-network re-emit — and deliberately *not* at
     * all if the burst throws partway, since a truncated snapshot has proved nothing about
     * the buffers it never reached. So it means "the roster you have is the whole roster",
     * which is exactly what an empty list needs before it can claim to be empty.
     */
    data object BacklogComplete : ServerFrame

    /**
     * WS `backlog`: a buffer, plus its history when `hydrated`. A shell arrives
     * unhydrated with no events (the "fetch on open" marker).
     *
     * `append` distinguishes a `?since=` resume slice that carries only the gap
     * (`reset:false` → append it) from a full/latest backlog or an oversized-gap reset
     * (`reset:true` or no `reset` field → replace wholesale). Getting this wrong
     * silently wipes pre-gap history the moment resume (lurker-ios#4) starts sending `?since`.
     *
     * `speakers` is null when the frame didn't carry the field at all, which the wire treats as
     * different from an empty list: a shell deliberately omits it (`wsHub.ts`'s
     * `buildBufferShell`) precisely so a client that *replaces* on this field can't be made to
     * wipe a map it already holds. This client merges either way (`ChatState.seedSpeakers`), so
     * both spellings are a no-op here — the nullability mirrors the wire rather than branching
     * behavior, and it's the honest shape for a field a server may simply not send.
     */
    data class Backlog(
        val buffer: Buffer,
        val messages: List<Message>,
        val hydrated: Boolean,
        val append: Boolean,
        val speakers: List<Speaker>?,
    ) : ServerFrame

    /** WS `irc`: one live event, its fields spread flat on the frame. */
    data class Live(val networkId: Int?, val target: String, val message: Message) : ServerFrame

    /**
     * WS `history`: a paginated slice for an already-open buffer (distinct from
     * `backlog`, which is connect-time / open-buffer hydration). `mode` decides how the
     * store splices it in — `before` prepends, `after` appends, `latest`/`around` replace.
     * `events` is always oldest-first.
     *
     * Every history reply carries `speakers` (see the `backlog` note for why it's nullable
     * anyway). This is the *primary* way the map loads: a fresh connect ships shells, so a
     * buffer's first real speaker list arrives with the `latest` fetch its first open makes.
     */
    data class History(
        val networkId: Int?,
        val target: String,
        val events: List<Message>,
        val mode: HistoryMode,
        val hasMoreOlder: Boolean,
        val hasMoreNewer: Boolean,
        val speakers: List<Speaker>?,
    ) : ServerFrame

    /**
     * WS `buffer-cleared`: the `/clear` marker moved (lurker-ios#121). Sent to the device that
     * asked **and** fanned out to the account's others — the marker is server-side per-user
     * state, so a clear on the desktop has to take effect on the phone.
     *
     * `clearedBeforeId == 0` with a null `clearedAt` is the UNDO ("Show earlier messages" /
     * `/clear off`), not a malformed frame: it is how the server says the marker is gone.
     */
    data class BufferCleared(
        val networkId: Int?,
        val target: String,
        val clearedBeforeId: Long,
        val clearedAt: Instant?,
    ) : ServerFrame

    /**
     * A `channel-topic` event: RPL_TOPIC on join, i.e. "here's the topic" rather than
     * "someone changed it". Ephemeral and silent — it carries no id and prints no line,
     * which is why it's lifted out of `irc` into its own frame instead of arriving as a
     * `Message` nothing would render. A topic *change* is a `topic` event and stays a
     * message, because it's also something the channel said.
     *
     * `meta` is the 333 setter and time, when the frame stated them — null leaves the held pair
     * alone, a value replaces both (either half may itself be null: a 333 can carry the time
     * alone).
     */
    data class ChannelTopic(
        val networkId: Int?,
        val target: String,
        val topic: String?,
        val meta: TopicMeta? = null,
    ) : ServerFrame

    /**
     * A `channel-modes` event (§7.2): the channel's whole mode state — every set letter, the
     * values of set param modes, and its creation time. ⚠ Never the key; `k` is a letter only.
     * Ephemeral and silent, like `channelTopic`.
     */
    data class ChannelModes(
        val networkId: Int?,
        val target: String,
        val modes: String,
        val params: Map<String, String>,
        val createdAt: Instant?,
    ) : ServerFrame

    /**
     * Live `mode-spec` (§5.1): the network's channel-mode vocabulary. Arrives once the
     * registration burst ends (the snapshot says null until then) and again whenever a later
     * 005 changes it.
     */
    data class ModeSpec(
        val networkId: Int,
        val spec: net.amiantos.lurkerkit.model.ModeSpec?,
    ) : ServerFrame

    /**
     * A `names` event: the channel's full member list, replacing whatever we hold. The
     * server sends it on our own join and re-broadcasts it whenever it re-learns the
     * list wholesale (a prefix-mode change, a WHO ident/host backfill, an away flip via
     * away-notify). Ephemeral and silent, like `channelTopic` — state, not a line.
     *
     * `pending` is the server's `membersPending` (§9.1): it hasn't heard this channel's NAMES
     * since it last connected or attached, so `members` is only who it has learned of so far.
     */
    data class ChannelMembers(
        val networkId: Int?,
        val target: String,
        val members: List<Member>,
        val pending: Boolean = false,
    ) : ServerFrame

    /**
     * A `member-update` event: one member's current snapshot, patched onto the list in
     * place. The server's incremental alternative to re-broadcasting `names` for a
     * one-nick edit (a chghost, an account change). Also ephemeral and silent.
     */
    data class MemberUpdate(val networkId: Int?, val target: String, val member: Member) : ServerFrame

    /**
     * A `channel-joined` event: **we** are in this channel (lurker `CLIENT_PROTOCOL.md` §9.1).
     * A join request is only intent, so this is the one thing that says a join landed — ours,
     * a reconnect's rejoin, or the channel a 470 forward actually put us in. It materializes
     * the buffer when we hold no row, and marks the row `joined` either way.
     *
     * Lifted out of `irc` like `channelTopic`: no id, nothing to render. As a live event it
     * minted a row with `joined` false and never set it on a row we already held — so once a
     * reconnect re-sent a network's buffers as parted, nothing marked them joined again.
     */
    data class ChannelJoined(val networkId: Int?, val target: String) : ServerFrame

    /**
     * A `channel-parted` event: we left, were kicked, were forwarded away (470), or lost the
     * IRC connection, which parts every joined channel at once (lurker#915). Resolve, never
     * materialize: mark the row parted, keep its history, drop its members — and with no row,
     * do nothing, which is what a forward's part for a name we never had needs.
     */
    data class ChannelParted(val networkId: Int?, val target: String) : ServerFrame

    /**
     * A `join-error` event: the server refused a join — invite-only, banned, a bad key, full, too
     * many channels, or it wants a registered nick (lurker-ios#57). Aimed at the channel it
     * refused, which we're not in, so the store records nothing and creates no row (a row made
     * for it read joined, lurker-ios#168). The view model tells the user when this device asked.
     * `reason` is ready to show: "This channel is invite-only."
     */
    data class JoinError(val networkId: Int?, val target: String, val reason: String) : ServerFrame

    /**
     * An `own-nick` event: *our* nick on this network changed — by `/nick`, or because
     * services renamed us. Network-scoped and target-less, like `peer-presence`.
     *
     * Silent by design: the visible line is the ordinary `nick` event fanned out to each
     * shared channel, and this frame is only the state behind it. Without it `Network.nick`
     * is whatever the last connect snapshot said, and everything that asks "is this me?"
     * — the `.smart` tier's own-churn exemption (lurker-ios#63), the member list's self
     * marking, nick-completion's exclusion of ourselves — answers with a nick we no longer have.
     */
    data class OwnNick(val networkId: Int, val nick: String) : ServerFrame

    /**
     * A `state` event: this network's connection moved (`connecting` → `connected`, a drop
     * to `disconnected`, a `reconnecting` backoff).
     *
     * The server publishes one on every transition, including a re-assertion of the state
     * it was already in, precisely so a client that attached late is never wrong. Nothing
     * else carries this: the connect `snapshot` states each network's connection once, and
     * without this frame that value stands for the whole session — every status light
     * frozen at whatever was true when the socket opened.
     *
     * `nick` is null except on the connect transition, where the server sends it alongside.
     * Null means "unchanged", never "empty" — blanking `Network.nick` on a disconnect would
     * break every "is this me?" test the way a missing `own-nick` does.
     */
    data class NetworkState(val networkId: Int, val state: ConnectionState, val nick: String?) : ServerFrame

    /**
     * WS `read-state`: server-authoritative read counts for a buffer, broadcast to all of
     * the user's devices (after a mark-read, or any countable event). The client mirrors
     * these onto the buffer — it never derives unread/highlight counts locally.
     */
    data class ReadState(
        val networkId: Int?,
        val target: String,
        val lastReadId: Long,
        val unread: Int,
        val highlights: Int,
    ) : ServerFrame

    /**
     * WS `favorites-changed`: the user's buffer favorites — the Friends/Contacts
     * successor — as ONE global ordered list spanning networks. Sent in the connect
     * burst and re-sent wholesale on every favorite/unfavorite/reorder/merge, so the
     * store simply replaces what it held.
     */
    data class FavoritesChanged(val favorites: List<FavoriteEntry>) : ServerFrame

    /**
     * WS `bookmark-updated`: a message was saved or unsaved, on any of the account's
     * devices — including this one. The server echoes to every socket rather than
     * answering the sender alone, so this is the single source of truth for the toggle
     * and nothing needs to guess optimistically.
     *
     * Note there is no bookmark *snapshot* to pair with it: a save the client never
     * witnessed arrives on the `bookmarked` flag of the message row itself. So a
     * `saved: false` for an id the store never knew about is normal, not a lost update —
     * it just means that line isn't loaded here.
     */
    data class BookmarkUpdated(val messageId: Long, val saved: Boolean) : ServerFrame

    /**
     * WS `draft-snapshot` (connect burst, frame 2): every composer draft the account has
     * saved. Authoritative for the buffers it lists and the ones it doesn't — except where this
     * device has an edit the server hasn't heard yet (`DraftSync`).
     */
    data class DraftSnapshot(val entries: List<DraftEntry>) : ServerFrame

    /**
     * WS `draft-updated`: another device (or a rename/merge) changed one buffer's draft.
     * Never echoed to the socket that wrote it.
     */
    data class DraftUpdated(val entry: DraftEntry) : ServerFrame

    /**
     * WS `reaction`: an IRCv3 reaction was added or (with `remove`) taken back on a stored
     * line, by anyone, ours included — the network's echo is the only thing that ever lights a
     * reaction up here (lurker-ios#183). Patches a line we may not hold; never reopens a buffer.
     */
    data class Reaction(val change: ReactionChange) : ServerFrame

    /**
     * WS `reactions-sync`: the answer to `sync-reactions`. Every id in `messageIds` is
     * authoritative — one absent from `reactions` has none standing now.
     */
    data class ReactionsSync(
        val messageIds: List<Long>,
        val reactions: Map<Long, List<MessageReaction>>,
    ) : ServerFrame

    /**
     * Live `react-support` (§7.2): whether reactions can go out on this network changed — the
     * burst ended (CLIENTTAGDENY rides a 005 after the snapshot), or a later 005 moved it.
     */
    data class ReactSupport(val networkId: Int, val canReact: Boolean) : ServerFrame

    /**
     * WS `upload-progress`: how far along the server is with an upload *this* device is
     * running (lurker-ios#47), correlated by the `progressToken` we put in the multipart body.
     *
     * **The one frame that never reaches the store** — it is an answer to something this device
     * started, not state the account owns. It also fans out to every socket the user has open —
     * two devices uploading at once would otherwise drive each other's readouts — so
     * `LurkerClient` matches the token against its in-flight uploads and drops anything it
     * didn't ask for.
     *
     * ⚠ It used to be one of two. `search-result` was correlated the same way until search
     * became a REST read (lurker-ios#123), which is why the interception in `LurkerClient` now
     * has a single case in it.
     */
    data class UploadProgress(val token: String, val progress: UploadServerProgress) : ServerFrame

    /**
     * WS `buffer-closed`: the user closed this buffer — from *any* of their devices.
     * Drop it from the model entirely: closed is absent (lurker `CLIENT_PROTOCOL.md` §9.1).
     *
     * This is the only buffer-lifecycle frame that needs a handler, and it's the only one
     * that travels alone. `buffer-opened` and `buffer-reopened` both arrive alongside
     * something that already materializes the buffer — an `open-buffer` reply ships a
     * `backlog` with it, and a reopen is caused by an event that arrives as a normal `irc`
     * frame. Closing has no such companion, which is exactly why its absence was invisible:
     * opening a buffer on the web propagated here fine, closing one silently didn't, and the
     * stale row sat in the list until the app was relaunched.
     *
     * Dropping rather than flagging is deliberate and matches the web client: the server
     * keeps the buffer's history, so a reopen restores it in full and there is nothing local
     * worth preserving. `Buffer.state` has no `closed` case for the same reason.
     */
    data class BufferClosed(val networkId: Int?, val target: String) : ServerFrame

    /**
     * A buffer kept its identity and changed names (protocol §9.7) — today, a
     * DM following its peer's /nick. Like `bufferClosed`, this travels alone:
     * nothing else re-materializes the buffer under its new name, so an
     * unhandled rename strands the old row AND phantoms a new one when the
     * next live event arrives. `bufferId` is the surviving buffer's stable id
     * (it did not change); `merged` means a stale buffer already held `to` and
     * was absorbed — `mergedFromBufferId` names it, and the survivor's history
     * interleaves server-side (wipe and re-hydrate, never guess).
     */
    data class BufferRenamed(
        val networkId: Int?,
        val from: String,
        val to: String,
        val bufferId: Int?,
        val merged: Boolean,
        val mergedFromBufferId: Int?,
    ) : ServerFrame

    /**
     * WS `ignore-list-updated`: one scope's ignore rules, re-sent whole (lurker #301).
     *
     * `networkId` null means the global bucket, a number means that network's own — the
     * server fans one or the other, never both, and the client replaces the matching bucket.
     * Note this is the opposite of the null convention buffer frames use, where null means the
     * app-scoped system buffer: an ignore scope of "no network" is *every* network.
     *
     * Fanned to every one of the account's devices, which is the point — rules are authored
     * on the web (`/ignore`, the settings pane) and this client has no editor, so this frame
     * is the whole of how a rule reaches the phone mid-session. It also carries the expiry
     * sweeper's deletions, so a `-time` rule stops applying here without a reconnect.
     */
    data class IgnoreListUpdated(val networkId: Int?, val rules: List<IgnoreRule>) : ServerFrame

    /**
     * WS `relay-bot-updated`: one nick was marked or unmarked as a relay/bridge bot
     * (lurker#277).
     *
     * One nick per frame, not a network's whole list — so the store patches rather than replaces
     * (see `RelayBotSet.applying`). `marked` false is the clear, and `pattern` is the custom
     * envelope template, empty for the built-in formats.
     *
     * Fanned to every one of the account's devices, including the one that asked, which is why
     * nothing here is applied optimistically: this frame is the mark, and a mark made in a
     * browser reaches the phone by exactly this route.
     *
     * Unlike `ignoreListUpdated`, `networkId` is a plain number: a relay mark is always about one
     * connection, so there is no global bucket for a null to mean.
     */
    data class RelayBotUpdated(
        val networkId: Int,
        val nick: String,
        val marked: Boolean,
        val pattern: String,
    ) : ServerFrame

    /**
     * WS `nick-note-updated`: the note about one nick was written or cleared (lurker-ios#12).
     *
     * Same shape and same rules as `relayBotUpdated` above — one nick per frame, fanned to
     * every device including the one that asked, so nothing is applied optimistically. An
     * empty `note` is the clear; that's the server's own encoding (`setNickNote.ts` deletes
     * the row for an empty string and echoes `note: ''`), not a convention invented here.
     */
    data class NickNoteUpdated(
        val networkId: Int,
        val nick: String,
        val note: String,
        val updatedAt: Instant?,
    ) : ServerFrame

    /**
     * A `whois_result` ephemeral (rides `irc`, `type:"whois_result"`) — a WHOIS reply
     * (lurker-ios#12).
     *
     * ⚠⚠ It carries **no target**, so it has to be recognised above `parseIrc`'s target guard
     * or it is dropped. It is about a person on a network, not about a conversation: the
     * screen that asked is the one waiting for it, and the server buffer separately gets the
     * raw numerics through the ordinary `raw` path.
     *
     * A reply whose `error` is `not_found` IS an answer — see `WhoisResult.isNotFound` for
     * where that signal actually comes from, which is not where it looks like it comes from.
     */
    data class WhoisResult(
        val networkId: Int,
        val whois: net.amiantos.lurkerkit.model.WhoisResult,
    ) : ServerFrame

    /**
     * A `peer-presence` ephemeral (rides `irc`, `type:"peer-presence"`, network-scoped via a
     * `:server:<id>` target): a watched nick changed state. `state` is null when the server
     * reports no known state, which the store reads as `unknown`.
     */
    data class PeerPresence(val networkId: Int, val nick: String, val state: PresenceState?) : ServerFrame

    /**
     * An `away-state` ephemeral (rides `irc`, `type:"away-state"`, network-scoped via a
     * `:server:<id>` target): *your own* away state changed — from this device, another one,
     * or the server's own idle auto-away.
     *
     * `away` is null when the account has no away on record. The server sends that literally
     * (`away: null` when there's no `since`), so it's a value to store rather than a frame to
     * drop: it is how "cleared" arrives.
     */
    data class AwayState(
        val networkId: Int,
        val away: net.amiantos.lurkerkit.model.AwayState?,
    ) : ServerFrame

    /**
     * A `dcc-chat-offer` ephemeral (lurker#270): `nick` wants to open a DCC chat with us.
     * Nothing is dialled until we accept. Network-scoped via a `:server:<id>` carrier target;
     * the peer rides in `from`, not `target`.
     */
    data class DccChatOffer(val networkId: Int, val nick: String, val passive: Boolean) : ServerFrame

    /**
     * A `dcc-chat-offer-closed` ephemeral: `nick`'s offer is gone — accepted, declined,
     * expired or torn down. Whatever was asking about it should stop.
     */
    data class DccChatOfferClosed(val networkId: Int, val nick: String) : ServerFrame

    /**
     * A `dcc-chat-state` ephemeral: a session with `nick` opened (`live`) or ended. The live set
     * also rides every snapshot, which is what a fresh connect reads.
     */
    data class DccChatState(val networkId: Int, val nick: String, val live: Boolean) : ServerFrame

    /**
     * An `invite` ephemeral naming us: `from` invited us to `channel`. Network-scoped via a
     * `:server:<id>` carrier, like the DCC offer. Nothing is stored; the system buffer's line
     * is the record, and this is only the moment to offer a Join.
     */
    data class Invited(val networkId: Int, val channel: String, val from: String) : ServerFrame

    /**
     * WS `pins-changed`: this network's pinned buffers, in the user's order.
     *
     * Authoritative and wholesale — the server re-sends the whole list on every pin, unpin
     * and reorder — so the store replaces rather than patches. Sent per network, so it says
     * nothing about the others.
     */
    data class PinsChanged(val networkId: Int, val pinned: List<String>) : ServerFrame

    /**
     * A `typing` ephemeral (rides `irc`, `type:"typing"`): a peer's `+typing` tag, scoped to
     * the channel or DM they're composing in. `activity` is null for `done` and for anything
     * unrecognized, which the store reads as "stop showing them".
     *
     * Our *own* typing never arrives here: under `echo-message` the server sees its own
     * TAGMSG reflect back and drops it, case-folded, before publishing
     * (`ircConnection.ts:2621`). So the client deliberately does not re-check for self —
     * that verdict has an owner, and duplicating it would just be a second place to get the
     * case-folding wrong.
     */
    data class Typing(
        val networkId: Int?,
        val target: String,
        val nick: String,
        val activity: TypingActivity?,
        val userhost: String?,
    ) : ServerFrame

    /**
     * REST `GET /api/settings/bootstrap`: the registry plus the user's *stored* values.
     * Defaults are not merged in — see `Settings.effective`.
     */
    data class SettingsBootstrap(
        val registry: Map<String, SettingOption>,
        val values: Map<String, SettingValue>,
    ) : ServerFrame

    /**
     * WS `settings`: the keys that just changed, fanned out to every device (including the
     * echo of this client's own `PATCH`). A patch, never a full set.
     *
     * ⚠⚠ `uploadLimits` ride this frame **only when a limit was actually touched** — the
     * server recomputes and re-sends both when `uploads.image.max_upload_mb` or
     * `uploads.image.max_dimension` is among the changes, and omits them otherwise. So null
     * here means "unchanged", NOT "no limit", and the store must patch each conditionally
     * rather than assign it. Overwriting with null would drop the advertised numbers on every
     * unrelated settings change the user made.
     */
    data class SettingsChanged(val changes: Map<String, SettingValue>, val uploadLimits: UploadLimits) : ServerFrame

    /**
     * The `{values}` a REST reply carries (`PATCH /api/settings`) — the user's complete
     * stored set, which REPLACES what we hold rather than merging into it. See
     * `Settings.replaceValues`: a key set back to its default is dropped server-side, so it
     * comes back as an absence that a merge would never notice.
     */
    data class SettingsValues(val values: Map<String, SettingValue>) : ServerFrame

    /** WS `send-result`: ack for a send/action/notice, keyed by the client's clientId. */
    data class SendResult(val clientId: String?, val ok: Boolean, val error: String?) : ServerFrame

    /** WS `error`. */
    data class ServerError(val text: String) : ServerFrame

    /**
     * A 401 mid-session (REST or the WS upgrade): the token expired or was revoked
     * from another device. The owner drops to sign-in rather than a dead-end (lurker-ios#3).
     */
    data object Unauthorized : ServerFrame

    /**
     * The WS upgrade was refused with 426: the server no longer serves the protocol version
     * this build announced (lurker-ios#17). The owner stops reconnecting rather than retrying
     * forever.
     */
    data class Incompatible(val incompatibility: Incompatibility) : ServerFrame

    /** Socket opened. Reconnect/resume is lurker-ios#4. */
    data object SocketOpen : ServerFrame

    /** Socket closed or failed. */
    data class SocketClosed(val reason: String?, val code: Int?) : ServerFrame

    /** A frame we parse but the 1.0 foundation doesn't act on yet. */
    data object Ignored : ServerFrame
}

/**
 * The four `history` request modes. `before`/`after` page older/newer, `around` jumps
 * to a message, `latest` returns to the live tail. See the server's `history` verb.
 */
enum class HistoryMode(val rawValue: String) {
    Before("before"),
    After("after"),
    Around("around"),
    Latest("latest");

    companion object {
        fun fromRawValue(raw: String): HistoryMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The per-network live view from the WS `snapshot` (no `name` — see `.networks`). */
internal data class NetworkSnapshot(
    val id: Int,
    val state: ConnectionState,
    val nick: String,
    val channels: List<ChannelSnapshot>,
    /**
     * Watched-peer presence for this network, `lowercased nick → state`. The connect-time
     * seed the live `peer-presence` events then patch. Defaulted so the many existing
     * snapshot call sites that predate presence don't have to name it.
     */
    val peerPresence: Map<String, PresenceState> = emptyMap(),
    /**
     * This network's own ignore rules — the connect-time seed for the per-network bucket
     * that `ignore-list-updated` then replaces. Rules with no network scope ride the
     * snapshot frame itself (`globalIgnores`), not this.
     */
    val ignoredMasks: List<IgnoreRule> = emptyList(),
    /**
     * The nicks marked as relay/bridge bots on this network (lurker#277) — the connect-time seed
     * that live `relay-bot-updated` frames then patch. Defaulted, like the fields above it, so
     * the snapshot call sites that predate the feature don't have to name it.
     */
    val relayBots: List<RelayBot> = emptyList(),
    /**
     * This account's notes about nicks on this network (lurker-ios#12) — the connect-time seed
     * that live `nick-note-updated` frames then patch. Defaulted like its neighbours.
     */
    val nickNotes: List<NickNote> = emptyList(),
    /**
     * Your own away state on this network (lurker-ios#68) — the connect-time seed for what live
     * `away-state` events then replace. Null when the server reports none, which is the
     * normal case for a user who has never been away.
     */
    val away: net.amiantos.lurkerkit.model.AwayState? = null,
    /**
     * The targets this network's buffers are pinned to, in the user's order — the
     * connect-time seed for what live `pins-changed` frames then replace.
     *
     * Ordered, so it's a list and not a set: the order IS the payload. It is also a
     * superset of what can be shown, since a pin row survives its buffer being parted or
     * closed.
     */
    val pinned: List<String> = emptyList(),
    /**
     * Peers with a live DCC chat on this network (lurker#270) — listed for a disconnected
     * network too, because a chat's socket outlives the IRC link.
     */
    val dccChats: List<String> = emptyList(),
    /** Peers whose DCC chat offer to us still awaits an answer. */
    val dccChatOffers: List<String> = emptyList(),
    /**
     * Whether reactions (and reply tags) can be sent on this network (§5.1). False until the
     * registration burst ends, then kept current by `react-support`.
     */
    val canReact: Boolean = false,
    /**
     * The network's channel-mode vocabulary (§5.1). ⚠ Null until the registration burst ends —
     * "unknown", not the RFC defaults — then kept current by `mode-spec`.
     */
    val modeSpec: net.amiantos.lurkerkit.model.ModeSpec? = null,
)

internal data class ChannelSnapshot(
    val name: String,
    val topic: String?,
    val members: List<Member>,
    /** Modes, param values, creation time and the topic's setter — never the key. */
    val modeState: ChannelModeState = ChannelModeState(),
    /** The server's `membersPending` (§9.1) — see `ServerFrame.ChannelMembers`. */
    val membersPending: Boolean = false,
)

/** Who set a channel's topic and when — 333, or a live TOPIC. Either half may be null. */
data class TopicMeta(
    val setBy: String?,
    val setAt: Instant?,
)

/**
 * What an acked verb answered (§6): `send-result` with the verb's result as `data`. Read by
 * `LurkerClient.request`, never by the store.
 */
data class VerbReply(
    val ok: Boolean,
    /**
     * The refusal code — `not-connected`, `refused`, `no-reply`, … — or `no-answer` /
     * `connection-lost` when nothing came back.
     */
    val error: String?,
    /** For a `refused` list fetch: the IRC numeric and the server's sentence. */
    val numeric: String? = null,
    val text: String? = null,
    /** A list fetch's entries. */
    val entries: List<ModeListEntry>? = null,
) {
    companion object {
        /** Nothing came back before the wait ran out. */
        val noAnswer = VerbReply(ok = false, error = "no-answer")

        /**
         * The socket it went down ended first, taking the answer with it. Whether the change
         * reached IRC is unknown — only that this socket will never say.
         */
        val connectionLost = VerbReply(ok = false, error = "connection-lost")

        /** It never went out: no socket to write to. */
        val notSent = VerbReply(ok = false, error = "not-connected")
    }
}
