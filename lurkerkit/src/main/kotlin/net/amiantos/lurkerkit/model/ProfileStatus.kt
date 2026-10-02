// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * What a user profile says about whether the person is *there* (lurker-ios#12).
 *
 * Four questions, resolved together because they are not the same question and answering them
 * separately at the call site is what shipped a blank profile on the web (lurker#818):
 *
 * - the **dot**: are they online, away, offline, or do we not know;
 * - the **status line**: does the body need to say something about the lookup itself;
 * - **Send DM**: is there anyone for a DM to reach;
 * - the **away reason**, when there is one.
 *
 * Pure, and in the kit rather than the screen, because the rules below are the whole of the
 * feature's subtlety and a view controller is the worst place to test them.
 */
data class ProfileStatus(
    val presence: FriendPresence,
    /**
     * The away reason, or null.
     *
     * ⚠ Only ever from the WHOIS reply, which means it exists only when the whois ran *while*
     * they were away. The live `peer-presence` event carries one too, but `FrameParser`
     * flattens that blob to the state alone, so it never reaches the store. The consequence is
     * worth knowing rather than chasing: the dot can say "away" while the reason is blank, and
     * that is honest — being away with no reason is still being away.
     */
    val awayMessage: String?,
    val statusLine: StatusLine?,
    /** Whether Send DM should be offered at all. */
    val canSendDirectMessage: Boolean,
) {

    /**
     * What the body says about the lookup, when it has anything to say. Null means the profile
     * speaks for itself and the line would only be noise.
     */
    enum class StatusLine {
        /** The lookup answered, and the answer was that nobody is using that nick. */
        NotFound,

        /** We have nothing yet and something is expected. */
        Waiting,
    }

    companion object {
        /**
         * Resolve all four from what the store and the reply know.
         *
         * - `peer`: `ChatState.presence(networkId:nick:)` — MONITOR's answer, already folded with
         *   this device's own connectivity.
         * - `whois`: the cached reply, if any. A `not_found` is a reply.
         * - `isLookingUp`: `ChatState.isWhoisPending(networkId:nick:)`.
         * - `isSelf`: whether this is the account's own nick on that network.
         */
        fun resolve(
            peer: FriendPresence,
            whois: WhoisResult?,
            isLookingUp: Boolean,
            isSelf: Boolean,
        ): ProfileStatus {
            val isNotFound = whois?.isNotFound ?: false
            val away = whois?.away

            // ⚠⚠ `isPeerOffline` and "offline" as Send DM means it are DIFFERENT QUESTIONS, and
            // conflating them is lurker#818 in both directions. This one — "MONITOR says they are
            // not on the network" — is the one the status line below must use.
            val peerIsOffline = peer == FriendPresence.Offline

            // The dot. MONITOR wins where it has an opinion; the reply settles it where MONITOR
            // has none.
            //
            // ⚠⚠ Every MONITOR branch is tested before the reply's `away` — including `Online`,
            // which is the one that is easy to get wrong and the reason this isn't a
            // straight port. A whois reply is a snapshot from whenever it was asked, and iOS
            // keeps no live away *message*: `FrameParser` flattens the `peer-presence` blob to the
            // state alone, so a peer who came back is `Online` here while `whois.away` still
            // holds the reason they gave an hour ago. Testing the reply first pins the dot to
            // "Away — lunch" for somebody MONITOR has already told us is back.
            //
            // ⚠ The `isNotFound` branch matters too: without it a nick nobody is using reads
            // "unknown", which is the one status we can actually rule out.
            val presence: FriendPresence = run {
                if (peerIsOffline) return@run FriendPresence.Offline
                if (peer == FriendPresence.Away) return@run FriendPresence.Away
                if (peer == FriendPresence.Online) return@run FriendPresence.Online
                // No MONITOR opinion — fall back to what the reply said.
                if (away?.isEmpty() == false) return@run FriendPresence.Away
                if (isNotFound) return@run FriendPresence.Offline
                if (whois != null) FriendPresence.Online else FriendPresence.Unknown
            }

            // The status line.
            //
            // ⚠⚠ The last test reads `peerIsOffline`, NOT "offline for Send DM purposes". The
            // latter folds a not-found in with MONITOR's verdict, which is right for hiding Send DM
            // and wrong here: it sends a miss we are re-checking down the quiet path and leaves the
            // body blank — the original lurker#818 bug. The two only look like one question.
            //
            // ⚠⚠ And a cached miss is demoted back to `Waiting` while a refresh is out. Without
            // that, reopening a profile seconds after that nick connected asserts they aren't on
            // the network for a whole round trip.
            val statusLine: StatusLine? = run {
                if (isNotFound && !isLookingUp) return@run StatusLine.NotFound
                if (hasDetails(whois)) return@run null
                if (isNotFound) return@run StatusLine.Waiting
                // MONITOR already told the dot they're offline; a spinner under it would be
                // claiming we're unsure when we aren't.
                if (peerIsOffline) null else StatusLine.Waiting
            }

            return ProfileStatus(
                presence = presence,
                // Only alongside an away dot. Carrying it while MONITOR says they're back would
                // put a stale "lunch" under an "Online" heading — the reason is only ever as
                // fresh as the reply it came in.
                awayMessage = if (presence == FriendPresence.Away && away?.isEmpty() == false) away else null,
                statusLine = statusLine,
                // A DM to yourself is meaningless, and one to somebody who isn't there bounces —
                // whether MONITOR says so or the lookup did. THIS is where the two verdicts are
                // rightly folded together.
                canSendDirectMessage = !isSelf && !peerIsOffline && !isNotFound,
            )
        }

        /**
         * Whether the reply carries anything a profile could actually draw.
         *
         * Deliberately not "is there a reply": a `not_found` is a reply and has nothing in it, so
         * treating its presence as details is what would suppress the status line that has to
         * explain it.
         */
        fun hasDetails(whois: WhoisResult?): Boolean {
            if (whois == null) return false
            return whois.realName != null ||
                whois.hostmask != null ||
                whois.actualHostname != null ||
                whois.actualIP != null ||
                whois.account != null ||
                whois.server != null ||
                whois.idleSeconds != null ||
                whois.signedOn != null ||
                whois.channels.isNotEmpty()
        }
    }
}
