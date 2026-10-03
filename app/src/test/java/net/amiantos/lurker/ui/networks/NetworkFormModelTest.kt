// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkProxy
import net.amiantos.lurkerkit.model.ProxyDraft
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.SecretEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

/** The network form's rules — lurker-ios's `NetworkFormViewController`, section for section. */
class NetworkFormModelTest {

    private fun config(
        tls: Boolean = true,
        hasPassword: Boolean = false,
        hasSaslPassword: Boolean = false,
        certificate: ClientCertificate? = null,
        proxy: NetworkProxy? = null,
    ) = NetworkConfig(
        id = 4,
        name = "Libera",
        host = "irc.libera.chat",
        port = 6697,
        tls = tls,
        nick = "me",
        hasPassword = hasPassword,
        hasSaslPassword = hasSaslPassword,
        clientCertificate = certificate,
        proxy = proxy,
    )

    private fun sections(
        existing: NetworkConfig? = null,
        draft: NetworkDraft = NetworkDraft(),
        error: String? = null,
        certificate: ClientCertificate? = existing?.clientCertificate,
        certificateError: String? = null,
    ) = NetworkFormModel.sections(existing, draft, error, certificate, certificateError)

    private fun List<FormSection>.section(id: FormSectionId) = first { it.id == id }

    // MARK: - Sections

    @Test
    fun addingHasEverySectionIncludingChannelsInIosOrder() {
        val sections = sections()
        assertEquals(
            listOf(
                FormSectionId.Connection, FormSectionId.You, FormSectionId.Authentication, FormSectionId.Certificate,
                FormSectionId.Channels, FormSectionId.Proxy, FormSectionId.Advanced,
            ),
            sections.map { it.id },
        )
        assertEquals(listOf("Connection", "You", "Authentication", "Client Certificate", "Channels", "Proxy", "Advanced"), sections.map { it.header })
        assertEquals(listOf(FormRow.Name, FormRow.Host, FormRow.Port, FormRow.Tls), sections.section(FormSectionId.Connection).rows)
        assertEquals(listOf(FormRow.Nick, FormRow.Realname), sections.section(FormSectionId.You).rows)
        assertEquals(
            listOf(FormRow.ConnectCommands, FormRow.Autoconnect, FormRow.VerifyCertificate),
            sections.section(FormSectionId.Advanced).rows,
        )
        assertEquals("Joined automatically when you connect. Separate several with commas.", sections.section(FormSectionId.Channels).footer)
        assertEquals("SASL logs you in during connection. Some networks require it.", sections.section(FormSectionId.Authentication).footer)
        assertNull(sections.section(FormSectionId.Advanced).footer)
    }

    @Test
    fun editingDropsChannelsAndSaysWhenChangesApply() {
        val sections = sections(existing = config())
        assertTrue(sections.none { it.id == FormSectionId.Channels })
        assertEquals(
            "Changes apply the next time this network connects. Reconnect it to apply them now.",
            sections.section(FormSectionId.Advanced).footer,
        )
    }

    @Test
    fun aRefusalLeadsTheFirstSection() {
        assertEquals(FormRow.Error, sections(error = "A nickname is required.").section(FormSectionId.Connection).rows.first())
    }

    @Test
    fun clearRowsExistOnlyWhereThereIsASavedSecret() {
        assertEquals(
            listOf(FormRow.SaslAccount, FormRow.SaslPassword, FormRow.ServerPassword),
            sections().section(FormSectionId.Authentication).rows,
        )
        assertEquals(
            listOf(FormRow.SaslAccount, FormRow.SaslPassword, FormRow.ClearSaslPassword, FormRow.ServerPassword, FormRow.ClearServerPassword),
            sections(existing = config(hasPassword = true, hasSaslPassword = true)).section(FormSectionId.Authentication).rows,
        )
    }

    @Test
    fun proxyDetailsShowOnlyWhileItsOn() {
        assertEquals(listOf(FormRow.ProxyEnabled), sections().section(FormSectionId.Proxy).rows)
        assertNull(sections().section(FormSectionId.Proxy).footer)
        val on = sections(draft = NetworkDraft(proxy = ProxyDraft(enabled = true))).section(FormSectionId.Proxy)
        assertEquals(
            listOf(FormRow.ProxyEnabled, FormRow.ProxyType, FormRow.ProxyHost, FormRow.ProxyPort, FormRow.ProxyUsername, FormRow.ProxyPassword),
            on.rows,
        )
        assertEquals("For Tor, use 127.0.0.1 port 9050. The proxy looks up the server's address, so .onion works.", on.footer)
        val saved = NetworkProxy(enabled = true, type = ProxyType.Socks5, host = "127.0.0.1", port = 9050, hasPassword = true)
        val existing = config(proxy = saved)
        assertEquals(
            FormRow.ClearProxyPassword,
            sections(existing = existing, draft = NetworkDraft(editing = existing)).section(FormSectionId.Proxy).rows.last(),
        )
    }

    // MARK: - Certificates

    @Test
    fun noCertificateOffersGenerateAndImport() {
        val section = sections().section(FormSectionId.Certificate)
        assertEquals(listOf(FormRow.GenerateCertificate, FormRow.ImportCertificate), section.rows)
        assertNull(section.footer)
    }

    @Test
    fun aCertificateNeedsTls() {
        assertEquals("Client certificates need TLS.", sections(draft = NetworkDraft(tls = false)).section(FormSectionId.Certificate).footer)
    }

    @Test
    fun theCertificateRoutesCheckTheSavedRowSoTlsMustBeSavedFirst() {
        val existing = config(tls = false)
        val section = sections(existing = existing, draft = NetworkDraft(editing = existing).copy(tls = true)).section(FormSectionId.Certificate)
        assertEquals("Save with TLS on first, then add a certificate.", section.footer)
        assertFalse(NetworkFormModel.canAddCertificate(NetworkDraft(tls = true), existing, certificateBusy = false, saving = false))
    }

    @Test
    fun aUsableCertificateCanBeExportedOrRemovedAndAnUnreadableOneOnlyRemoved() {
        val usable = config(certificate = ClientCertificate.Usable(expires = null))
        assertEquals(
            listOf(FormRow.CertificateStatus, FormRow.ExportCertificate, FormRow.RemoveCertificate),
            sections(existing = usable).section(FormSectionId.Certificate).rows,
        )
        val unusable = config(certificate = ClientCertificate.Unusable)
        assertEquals(
            listOf(FormRow.CertificateStatus, FormRow.RemoveCertificate),
            sections(existing = unusable).section(FormSectionId.Certificate).rows,
        )
        assertEquals(
            "Remove the certificate before turning TLS off.",
            sections(existing = usable, draft = NetworkDraft(editing = usable).copy(tls = false)).section(FormSectionId.Certificate).footer,
        )
    }

    @Test
    fun aStagedCertificateCanBeUndone() {
        val section = sections(draft = NetworkDraft(certificate = CertificateSource.Generate)).section(FormSectionId.Certificate)
        assertEquals(listOf(FormRow.CertificateStatus, FormRow.UndoCertificate), section.rows)
    }

    @Test
    fun aCertificateErrorLeadsItsSection() {
        assertEquals(FormRow.CertificateError, sections(certificateError = "Nope.").section(FormSectionId.Certificate).rows.first())
    }

    @Test
    fun addingACertificateWaitsOutBusyAndSaving() {
        assertTrue(NetworkFormModel.canAddCertificate(NetworkDraft(), null, certificateBusy = false, saving = false))
        assertFalse(NetworkFormModel.canAddCertificate(NetworkDraft(), null, certificateBusy = true, saving = false))
        assertFalse(NetworkFormModel.canAddCertificate(NetworkDraft(), null, certificateBusy = false, saving = true))
        assertFalse(NetworkFormModel.canAddCertificate(NetworkDraft(tls = false), null, certificateBusy = false, saving = false))
    }

    @Test
    fun certificateStatusWords() {
        val now = Instant.parse("2026-10-03T12:00:00Z")
        val later = Instant.parse("2027-01-01T00:00:00Z")
        val earlier = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(CertificateStatus.Expiry("Expires", later), NetworkFormModel.certificateStatus(ClientCertificate.Usable(later), null, now))
        // Past tense once the date has gone by.
        assertEquals(CertificateStatus.Expiry("Expired", earlier), NetworkFormModel.certificateStatus(ClientCertificate.Usable(earlier), null, now))
        assertEquals(CertificateStatus.Attached, NetworkFormModel.certificateStatus(ClientCertificate.Usable(null), null, now))
        assertEquals(
            CertificateStatus.Sentence("This certificate can't be read, and the network won't connect while it's attached.", isProblem = true),
            NetworkFormModel.certificateStatus(ClientCertificate.Unusable, null, now),
        )
        assertEquals(
            CertificateStatus.Sentence("A certificate will be created with this network."),
            NetworkFormModel.certificateStatus(null, CertificateSource.Generate, now),
        )
        assertEquals(
            CertificateStatus.Sentence("Your certificate will be attached to this network."),
            NetworkFormModel.certificateStatus(null, CertificateSource.Imported("c", "k"), now),
        )
        assertNull(NetworkFormModel.certificateStatus(null, null, now))
    }

    @Test
    fun onlyAReadableCertificateGetsTheExportAdvice() {
        assertNull(NetworkFormModel.removeCertificateMessage(ClientCertificate.Unusable))
        assertEquals(
            "Anywhere you've registered it won't recognize a new one. Export it first to keep a copy.",
            NetworkFormModel.removeCertificateMessage(ClientCertificate.Usable(null)),
        )
        assertEquals("lurker-4-client.pem", NetworkFormModel.exportFileName(4))
    }

    // MARK: - Saving

    @Test
    fun saveRefusesWhatTheDraftRefusesFirst() {
        assertEquals("Give this network a name.", NetworkFormModel.saveProblem(NetworkDraft(), null))
    }

    @Test
    fun saveRefusesTlsOffUnderAnAttachedCertificate() {
        val draft = NetworkDraft(name = "L", host = "h", nick = "n", tls = false)
        assertEquals("Remove the certificate before turning TLS off.", NetworkFormModel.saveProblem(draft, ClientCertificate.Usable(null)))
        assertNull(NetworkFormModel.saveProblem(draft, null))
        assertNull(NetworkFormModel.saveProblem(draft.copy(tls = true), ClientCertificate.Usable(null)))
    }

    @Test
    fun titlesFollowTheMode() {
        assertEquals("Add Network", NetworkFormModel.title(isEditing = false))
        assertEquals("Edit Network", NetworkFormModel.title(isEditing = true))
        assertEquals("Add", NetworkFormModel.saveTitle(isEditing = false))
        assertEquals("Save", NetworkFormModel.saveTitle(isEditing = true))
    }

    // MARK: - Secrets

    @Test
    fun aBlankPasswordFieldSaysWhatItWillDo() {
        assertEquals("Will be removed", NetworkFormModel.secretPlaceholder(SecretEdit.Cleared, saved = true))
        assertEquals("Saved — type to replace", NetworkFormModel.secretPlaceholder(SecretEdit.Unchanged, saved = true))
        assertEquals("Optional", NetworkFormModel.secretPlaceholder(SecretEdit.Unchanged, saved = false))
    }

    @Test
    fun typingSupersedesAnArmedClearAndDeletingBackLeavesItAlone() {
        assertEquals(SecretEdit.Set("hunter2"), NetworkFormModel.secretEdit("hunter2"))
        // Blank never means "remove it".
        assertEquals(SecretEdit.Unchanged, NetworkFormModel.secretEdit(""))
        assertEquals("hunter2", NetworkFormModel.secretText(SecretEdit.Set("hunter2")))
        assertEquals("", NetworkFormModel.secretText(SecretEdit.Cleared))
    }

    @Test
    fun theClearRowArmsAndDisarms() {
        assertEquals(SecretEdit.Cleared, NetworkFormModel.toggledClear(SecretEdit.Unchanged))
        assertEquals(SecretEdit.Cleared, NetworkFormModel.toggledClear(SecretEdit.Set("x")))
        assertEquals(SecretEdit.Unchanged, NetworkFormModel.toggledClear(SecretEdit.Cleared))
        assertEquals("Remove Saved SASL Password", NetworkFormModel.clearRowTitle(isArmed = false, what = "SASL Password"))
        assertEquals("Keep Saved Proxy Password", NetworkFormModel.clearRowTitle(isArmed = true, what = "Proxy Password"))
    }

    // MARK: - Fields

    @Test
    fun aZeroPortIsAnEmptyFieldAndAnEmptyFieldIsZero() {
        assertEquals("", NetworkFormModel.portText(0))
        assertEquals("6697", NetworkFormModel.portText(6697))
        assertEquals(0, NetworkFormModel.parsePort(""))
        assertEquals(0, NetworkFormModel.parsePort("abc"))
        assertEquals(6697, NetworkFormModel.parsePort("6697"))
        assertEquals("Port must be between 1 and 65535.", NetworkDraft(name = "n", host = "h", nick = "k", port = NetworkFormModel.parsePort("")).validationError)
    }

    @Test
    fun proxyTypeWordsAndTheSaslHint() {
        assertEquals("SOCKS5", NetworkFormModel.proxyTypeTitle(ProxyType.Socks5))
        assertEquals("HTTP CONNECT", NetworkFormModel.proxyTypeTitle(ProxyType.Http))
        assertEquals("Optional", NetworkFormModel.saslAccountPlaceholder(NetworkDraft()))
        assertEquals("alice", NetworkFormModel.saslAccountPlaceholder(NetworkDraft(nick = "alice")))
    }

    // MARK: - Picked files

    @Test
    fun aPickedFileIsReadWhole() {
        val pem = "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n"
        assertEquals(pem, NetworkFormModel.readPickedFile(ByteArrayInputStream(pem.toByteArray())))
    }

    @Test
    fun aFileOverTheLimitReadsAsNothingWithoutBeingReadWhole() {
        val reads = intArrayOf(0)
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                reads[0] += 1
                return 'a'.code
            }
        }
        assertEquals("", NetworkFormModel.readPickedFile(endless, limit = 64))
        // Bounded by what's read: one past the limit, then it stops.
        assertEquals(65, reads[0])
        assertEquals("a".repeat(64), NetworkFormModel.readPickedFile(ByteArrayInputStream("a".repeat(64).toByteArray()), limit = 64))
    }
}
