// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.SecretEdit
import java.io.InputStream
import java.time.Instant

/** One row of the network form — lurker-ios's `NetworkFormViewController.Row`, case for case. */
enum class FormRow {
    /**
     * Why the last save was refused, when there was one. A row of its own, in the refusal's colour,
     * so it can never read like the grey guidance footers further down. Always in the list, drawing
     * nothing until there's a refusal, so TalkBack can read one out when it lands.
     */
    Error,
    Name, Host, Port, Tls,
    Nick, Realname,
    SaslAccount, SaslPassword, ClearSaslPassword,
    ServerPassword, ClearServerPassword,

    /** What the certificate is: when it expires, that it's waiting on the create, or that it can't be read. */
    CertificateStatus,

    /** Why the last certificate action didn't work. The [Error] row's look, in this section: it's about these rows. */
    CertificateError,
    GenerateCertificate, ImportCertificate,
    ExportCertificate, RemoveCertificate,

    /** Drop a certificate waiting on the create. */
    UndoCertificate,
    DefaultChannel,
    ProxyEnabled, ProxyType, ProxyHost, ProxyPort, ProxyUsername, ProxyPassword, ClearProxyPassword,
    ConnectCommands, Autoconnect, VerifyCertificate,
}

enum class FormSectionId { Connection, You, Authentication, Certificate, Channels, Proxy, Advanced }

data class FormSection(val id: FormSectionId, val header: String?, val footer: String?, val rows: List<FormRow>)

/** The certificate status row's content — see [NetworkFormModel.certificateStatus]. */
sealed interface CertificateStatus {
    /** "Expires" over a date to come, "Expired" once it has gone by. The date is formatted by the screen. */
    data class Expiry(val label: String, val date: Instant) : CertificateStatus

    /** A usable certificate whose date the server didn't give: "Certificate · Attached". */
    data object Attached : CertificateStatus

    /** One sentence, in the refusal colour when [isProblem]. */
    data class Sentence(val text: String, val isProblem: Boolean = false) : CertificateStatus
}

/**
 * The network form's rules (lurker-ios#11) — `NetworkFormViewController`'s sections, copy and
 * guards, pure so they can be pinned in JVM tests. The screen is `NetworkFormPage`.
 *
 * One form for adding and editing, because they are the same form: the differences are a title, a
 * save verb, the create-only rows, and whether the secret rows offer to clear something. Two copies
 * of every field would drift.
 *
 * **The certificate rows don't wait for Save** (lurker#459). Editing, they write through the
 * server's certificate routes when tapped: a certificate isn't a column the PATCH sets, it's a pair
 * the server validates on a route of its own. Adding, there is no network to write to yet, so the
 * choice waits in the draft and rides the create request.
 */
object NetworkFormModel {

    fun title(isEditing: Boolean): String = if (isEditing) "Edit Network" else "Add Network"

    fun saveTitle(isEditing: Boolean): String = if (isEditing) "Save" else "Add"

    /**
     * The form, section by section.
     *
     * @param existing the network being edited, or null when adding. Also the source of the `has_…`
     *   flags — the only way to know a secret exists, since its value is never sent to us.
     * @param certificate the network's certificate as the server last described it — not
     *   `existing`'s, which is the row as it was when the form opened (see `NetworkFormState`).
     */
    fun sections(
        existing: NetworkConfig?,
        draft: NetworkDraft,
        error: String?,
        certificate: ClientCertificate?,
        certificateError: String?,
    ): List<FormSection> {
        val isEditing = existing != null
        val authRows = mutableListOf(FormRow.SaslAccount, FormRow.SaslPassword)
        // The clear rows exist only where there is something to clear. Adding a network has no
        // saved secret by definition, and offering to remove one that isn't there is a control that
        // can only be a no-op.
        if (existing?.hasSaslPassword == true) authRows.add(FormRow.ClearSaslPassword)
        authRows.add(FormRow.ServerPassword)
        if (existing?.hasPassword == true) authRows.add(FormRow.ClearServerPassword)

        val sections = mutableListOf(
            FormSection(
                id = FormSectionId.Connection,
                header = "Connection",
                footer = null,
                // ⚠ The refusal row leads whether or not there is a refusal: it draws nothing without
                // one, and a row that's already there is what TalkBack can announce a refusal in
                // (`AnnouncedSlot`) — an inserted one is never read out.
                rows = listOf(FormRow.Error, FormRow.Name, FormRow.Host, FormRow.Port, FormRow.Tls),
            ),
            FormSection(FormSectionId.You, header = "You", footer = null, rows = listOf(FormRow.Nick, FormRow.Realname)),
            FormSection(
                id = FormSectionId.Authentication,
                header = "Authentication",
                footer = "SASL logs you in during connection. Some networks require it.",
                rows = authRows,
            ),
            certificateSection(existing, draft, certificate, certificateError),
        )
        if (!isEditing) {
            sections.add(
                FormSection(
                    id = FormSectionId.Channels,
                    header = "Channels",
                    // Said plainly because it's the difference between landing in a conversation
                    // and landing in an empty server log — which is what a new user sees if this is
                    // blank, with no idea that a channel is the thing they're missing.
                    footer = "Joined automatically when you connect. Separate several with commas.",
                    rows = listOf(FormRow.DefaultChannel),
                ),
            )
        }
        sections.add(proxySection(existing, draft))
        sections.add(
            FormSection(
                id = FormSectionId.Advanced,
                header = "Advanced",
                // The web says the same thing, and it's the question anyone editing a host or a nick
                // is about to have.
                footer = if (isEditing) {
                    "Changes apply the next time this network connects. Reconnect it to apply them now."
                } else {
                    null
                },
                rows = listOf(FormRow.ConnectCommands, FormRow.Autoconnect, FormRow.VerifyCertificate),
            ),
        )
        return sections
    }

    /**
     * CertFP (lurker#459). No fingerprints on screen and no explanation — the rows are the verbs,
     * which is where the web settled after several rounds.
     */
    fun certificateSection(
        existing: NetworkConfig?,
        draft: NetworkDraft,
        certificate: ClientCertificate?,
        certificateError: String?,
    ): FormSection {
        // Always first, refusal or not — see the Connection section's Error row.
        val rows = mutableListOf(FormRow.CertificateError)
        var footer: String? = null
        if (certificate != null) {
            when (certificate) {
                is ClientCertificate.Usable ->
                    rows += listOf(FormRow.CertificateStatus, FormRow.ExportCertificate, FormRow.RemoveCertificate)
                ClientCertificate.Unusable -> rows += listOf(FormRow.CertificateStatus, FormRow.RemoveCertificate)
            }
            // Save refuses TLS off while a certificate is attached, and so does the server. The
            // footer says it before either has to.
            if (!draft.tls) footer = "Remove the certificate before turning TLS off."
        } else if (draft.certificate != null) {
            rows += listOf(FormRow.CertificateStatus, FormRow.UndoCertificate)
            if (!draft.tls) footer = "Client certificates need TLS."
        } else {
            rows += listOf(FormRow.GenerateCertificate, FormRow.ImportCertificate)
            if (!draft.tls) {
                footer = "Client certificates need TLS."
            } else if (existing?.tls == false) {
                // ⚠ The certificate routes check the SAVED row, not this form, so TLS switched on
                // here and not yet saved would still be refused.
                footer = "Save with TLS on first, then add a certificate."
            }
        }
        return FormSection(FormSectionId.Certificate, header = "Client Certificate", footer = footer, rows = rows)
    }

    /** The proxy (lurker#303). Its details show only while it's switched on — see `NetworkDraft.applyProxy`. */
    fun proxySection(existing: NetworkConfig?, draft: NetworkDraft): FormSection {
        if (!draft.proxy.enabled) {
            return FormSection(FormSectionId.Proxy, header = "Proxy", footer = null, rows = listOf(FormRow.ProxyEnabled))
        }
        val rows = mutableListOf(
            FormRow.ProxyEnabled, FormRow.ProxyType, FormRow.ProxyHost, FormRow.ProxyPort,
            FormRow.ProxyUsername, FormRow.ProxyPassword,
        )
        if (existing?.proxy?.hasPassword == true) rows.add(FormRow.ClearProxyPassword)
        return FormSection(
            id = FormSectionId.Proxy,
            header = "Proxy",
            // Tor is why most people turn this on. The name lookup is worth saying because it's what
            // makes `.onion` work.
            footer = "For Tor, use 127.0.0.1 port 9050. The proxy looks up the server's address, so .onion works.",
            rows = rows,
        )
    }

    /** Whether Generate and Import can be used right now. The section's footer says why when they can't. */
    fun canAddCertificate(draft: NetworkDraft, existing: NetworkConfig?, certificateBusy: Boolean, saving: Boolean): Boolean =
        draft.tls && existing?.tls != false && !certificateBusy && !saving

    /**
     * Why Save can't go yet, or null. The draft's own check first; then the one the draft can't
     * catch — an attached certificate is the form's state, not the draft's. The server refuses it
     * too; this says so without the round trip.
     */
    fun saveProblem(draft: NetworkDraft, certificate: ClientCertificate?): String? =
        draft.validationError ?: if (certificate != null && !draft.tls) "Remove the certificate before turning TLS off." else null

    /**
     * What an empty password field means right now — the one thing a blank masked field cannot say
     * for itself. Blank never means "remove it" (see `SecretEdit`).
     */
    fun secretPlaceholder(edit: SecretEdit, saved: Boolean): String =
        when {
            edit == SecretEdit.Cleared -> "Will be removed"
            saved -> "Saved — type to replace"
            else -> "Optional"
        }

    /** What a password field's text shows: what's been typed, or nothing. */
    fun secretText(edit: SecretEdit): String = (edit as? SecretEdit.Set)?.value ?: ""

    /**
     * What typing into a password field asks for. Typing supersedes an armed clear: the user is
     * replacing the password now, not removing it, and leaving the clear armed would throw the new
     * value away on save. Deleting back to empty leaves it alone.
     */
    fun secretEdit(typed: String): SecretEdit = if (typed.isEmpty()) SecretEdit.Unchanged else SecretEdit.Set(typed)

    /** A clear row's tap: arm the removal, or take it back. Nothing is removed until Save. */
    fun toggledClear(edit: SecretEdit): SecretEdit = if (edit == SecretEdit.Cleared) SecretEdit.Unchanged else SecretEdit.Cleared

    /**
     * A clear row's label. It states what tapping does, and flips once armed so the row is also the
     * way back.
     */
    fun clearRowTitle(isArmed: Boolean, what: String): String = if (isArmed) "Keep Saved $what" else "Remove Saved $what"

    /**
     * A port field's text. Zero renders as empty, not as a literal "0" the user has to delete: a
     * cleared field parses to 0, and after a failed save the form would otherwise hand back a field
     * they have to clear again before retyping.
     */
    fun portText(port: Int): String = if (port == 0) "" else port.toString()

    /**
     * A port field's value. A cleared (or unreadable) field is 0, not the old value: an empty port is
     * a port the user is in the middle of retyping, and `validationError` refuses 0 if they stop
     * there. Silently keeping the previous number would save something they can't see on screen.
     */
    fun parsePort(text: String): Int = text.toIntOrNull() ?: 0

    fun proxyTypeTitle(type: ProxyType): String =
        when (type) {
            ProxyType.Socks5 -> "SOCKS5"
            ProxyType.Http -> "HTTP CONNECT"
        }

    /** The SASL account field's hint: the nick it will default to, when there is one. */
    fun saslAccountPlaceholder(draft: NetworkDraft): String = draft.nick.ifEmpty { "Optional" }

    /**
     * The certificate status row, or null when there's nothing to describe (no status row is built
     * without a certificate).
     */
    fun certificateStatus(certificate: ClientCertificate?, pending: CertificateSource?, now: Instant): CertificateStatus? =
        when (certificate) {
            is ClientCertificate.Usable -> {
                val expires = certificate.expires
                if (expires == null) {
                    CertificateStatus.Attached
                } else {
                    // Past tense once the date has gone by, rather than "Expires" over a date in the past.
                    CertificateStatus.Expiry(label = if (expires < now) "Expired" else "Expires", date = expires)
                }
            }
            ClientCertificate.Unusable -> CertificateStatus.Sentence(
                "This certificate can't be read, and the network won't connect while it's attached.",
                isProblem = true,
            )
            null -> when (pending) {
                CertificateSource.Generate -> CertificateStatus.Sentence("A certificate will be created with this network.")
                is CertificateSource.Imported -> CertificateStatus.Sentence("Your certificate will be attached to this network.")
                null -> null
            }
        }

    /**
     * The remove confirmation's message. Only a readable certificate can be exported, so only that
     * one gets the advice.
     */
    fun removeCertificateMessage(certificate: ClientCertificate?): String? =
        if (certificate == ClientCertificate.Unusable) {
            null
        } else {
            "Anywhere you've registered it won't recognize a new one. Export it first to keep a copy."
        }

    /** The export's file name: the server's own, so it matches what the web downloads. */
    fun exportFileName(networkId: Int): String = "lurker-$networkId-client.pem"

    /**
     * How much of a picked file is read. A PEM pair is a few kilobytes and the picker offers every
     * file, so a video picked by mistake would otherwise be read whole into memory to be told it
     * isn't a certificate.
     */
    const val PICKED_FILE_LIMIT = 1 shl 20

    /**
     * A picked file's text, or "" for one too big to be a certificate — which then gets the message
     * for a file holding no certificate, the one a user can act on.
     *
     * ⚠ Bounded by what's READ, not by a reported size, which a document provider may not give.
     */
    fun readPickedFile(input: InputStream, limit: Int = PICKED_FILE_LIMIT): String {
        val buffer = ByteArray(limit + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total > limit) return ""
        return String(buffer, 0, total, Charsets.UTF_8)
    }
}
