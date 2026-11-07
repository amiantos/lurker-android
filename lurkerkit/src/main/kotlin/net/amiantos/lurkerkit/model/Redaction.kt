// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * A deleted line's mark (IRCv3 `draft/message-redaction`, lurker#1013 / #1277): the server NULLed
 * its text and stamped who sent the REDACT, when, and the reason if one came. The web's
 * `shared/redaction.ts`. Absent on every line that wasn't deleted, like `bookmarked`.
 */
data class Redaction(
    /** The nick that sent the REDACT: the author, or an op who removed it. */
    val by: String,
    /** The REDACT's optional trailing reason, as given. Never a default one. */
    val reason: String? = null,
    /** When, as server-time (or arrival) — ISO 8601, like a row's `time`. */
    val time: String? = null,
) {
    /**
     * The placeholder a deleted line shows in place of its text: "message deleted", naming who did
     * it when it wasn't the author, and the reason when one came. The web's `redactionLabel`.
     *
     * Case-insensitive on the nick — the deleter is named the way the REDACT came, which can
     * differ in case from the line's stored nick.
     */
    fun label(authorNick: String?): String {
        val own = authorNick?.let { NickCompletion.sameNick(it, by) } ?: false
        var label = if (own || by.isEmpty()) Redactions.PLACEHOLDER else "message deleted by $by"
        if (!reason.isNullOrEmpty()) label += ": $reason"
        return label
    }
}

/**
 * A live `message-redacted` frame (§7.1): the line `messageId` was deleted. Patch the row where
 * it's held; never a reason to reopen a closed buffer. LurkerKit's `MessageRedacted`, named beside
 * `ReactionChange` here because the frame that carries it takes that name (`ServerFrame`).
 *
 * Port note: `messageId` is a message id, so it is a `Long` (PORTING.md, Types).
 */
data class RedactionChange(
    val networkId: Int,
    val target: String,
    val messageId: Long,
    val by: String,
    val reason: String? = null,
    /** It was your line. */
    val isSelf: Boolean = false,
    val time: String? = null,
) {
    val redaction: Redaction get() = Redaction(by = by, reason = reason, time = time)
}

/**
 * The rules about deleted lines that don't belong to any one screen — the web's `redaction.ts`
 * and the delete half of `useMessageActions`.
 */
object Redactions {

    /** What a deleted line says where its words were, and what a reply's quote of one says. */
    const val PLACEHOLDER = "message deleted"

    /**
     * The lines as the screen draws them: every line the store knows was deleted — by the row it
     * came in on, or by a frame since — shows as deleted, its words gone. A display transform like
     * relay re-attribution: the stored row is never patched (see `ChatState.redactions`).
     *
     * After relay re-attribution, as the web orders it: a bridged line keeps the speaker its
     * envelope named, and the placeholder stands where their words were.
     */
    fun presenting(messages: List<Message>, redactions: Map<Long, Redaction>): List<Message> {
        if (redactions.isEmpty() && messages.none { it.redaction != null }) return messages
        return messages.map { line ->
            val redaction = (if (line.id != 0L) redactions[line.id] else null) ?: line.redaction ?: return@map line
            line.deleted(redaction)
        }
    }

    /**
     * Whether Delete is offered on this line (lurker#1013): a line a REDACT could name — a msgid,
     * in a channel or DM, not already deleted — on a network that negotiated the cap
     * (`canRedact`), and one of ours, or anyone's in a channel where we hold a prefix mode
     * ([moderating]), which is who ergo lets delete. The network has the last word (a FAIL comes
     * back as an error line); this only keeps the action off lines where it could do nothing.
     *
     * Not `Replies.replyable`: that rule refuses an E2E line because a reply TAG would be
     * cleartext about it, and a REDACT carries no content (its reason stays off the wire on an
     * E2E channel — the server's `redact`). Your own encrypted line can go like any other.
     */
    fun deletable(message: Message, target: String, support: TagSupport, moderating: Boolean): Boolean {
        if (message.id == 0L || message.msgid.isNullOrEmpty() || message.redaction != null) return false
        if (!message.type.isSpeech || !Reactions.isConversation(target) || !support.canRedact) return false
        if (message.isSelf) return true
        return moderating && ChannelName.isChannelTarget(target)
    }
}
