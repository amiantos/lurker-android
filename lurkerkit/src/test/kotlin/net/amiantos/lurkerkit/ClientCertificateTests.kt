// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.ClientCertificatePEM
import net.amiantos.lurkerkit.model.ISOTime
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CertFP (lurker#459) below the form: how a network row describes its certificate, the
 * certificate a create carries, and pulling a pair out of picked files.
 */
class ClientCertificateTests {

    private companion object {
        const val cert = "-----BEGIN CERTIFICATE-----\nMIIC\n-----END CERTIFICATE-----"
        const val key = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"
        const val rsaKey = "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----"
    }

    private fun row(certificate: String): NetworkConfig? =
        FrameParser.parseNetworkReply(
            """{"network":{"id":1,"name":"n","host":"h","tls":true,"nick":"me","client_cert":$certificate}}""",
        )

    private fun draft(): NetworkDraft =
        NetworkDraft(name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "me")

    // MARK: - Reading a row

    @Test
    fun testACertificateReadsItsExpiry() {
        val config = row(
            certificate = """
            {"sha256":"b2","sha1":"a1","sha512":"c5","subject":"CN=me",
             "validFrom":"2026-09-11T00:00:00.000Z","validTo":"2027-09-11T00:00:00.000Z"}
            """.trimIndent(),
        )
        val expires = ISOTime.parse("2027-09-11T00:00:00.000Z")
        assertNotNull(expires)
        assertEquals(ClientCertificate.Usable(expires = expires), config?.clientCertificate)
    }

    @Test
    fun testAnUnreadableCertificateIsNotNoCertificate() {
        // ⚠⚠ The server says `{unusable: true}` for a pair that won't parse, which archive import
        // can produce without anyone pasting anything. It refuses to dial while that's attached,
        // so reading it as "no certificate" would offer Generate on a network whose problem is the
        // certificate it already has.
        assertEquals(ClientCertificate.Unusable, row(certificate = """{"unusable":true}""")?.clientCertificate)
    }

    @Test
    fun testNullAndAbsentBothMeanNoCertificate() {
        assertNull(row(certificate = "null")?.clientCertificate)
        val absent = FrameParser.parseNetworkReply("""{"network":{"id":1,"name":"n","host":"h"}}""")
        assertNotNull(absent)
        assertNull(absent.clientCertificate)
    }

    // MARK: - The create body

    @Test
    fun testAGeneratedCertificateRidesTheCreate() {
        // Attached before the first dial, which is the connect the user registers it from.
        val d = draft().copy(certificate = CertificateSource.Generate)
        val body = d.jsonBody(creating = true)
        assertEquals(JsonPrimitive(true), body["generate_client_cert"])
        assertNull(body["client_cert"])
        assertNull(body["client_key"])
    }

    @Test
    fun testAnImportedPairRidesTheCreate() {
        val d = draft().copy(certificate = CertificateSource.Imported(cert = cert, key = key))
        val body = d.jsonBody(creating = true)
        assertEquals(JsonPrimitive(cert), body["client_cert"])
        assertEquals(JsonPrimitive(key), body["client_key"])
        // The server refuses a body carrying both.
        assertNull(body["generate_client_cert"])
    }

    @Test
    fun testAnEditNeverCarriesACertificate() {
        // Once the network exists the certificate has routes of its own, and PATCH ignores these.
        val d = draft().copy(certificate = CertificateSource.Imported(cert = cert, key = key))
        val body = d.jsonBody(creating = false)
        for (key in listOf("generate_client_cert", "client_cert", "client_key")) assertNull(body[key], key)
    }

    @Test
    fun testNoCertificateSendsNoCertificateKeys() {
        val body = draft().jsonBody(creating = true)
        for (key in listOf("generate_client_cert", "client_cert", "client_key")) assertNull(body[key], key)
    }

    @Test
    fun testACertificateNeedsTLS() {
        var d = draft().copy(certificate = CertificateSource.Generate, tls = false)
        assertNotNull(d.validationError)
        d = d.copy(tls = true)
        assertNull(d.validationError)
        // TLS off with no certificate is still an ordinary network.
        d = d.copy(certificate = null, tls = false)
        assertNull(d.validationError)
    }

    // MARK: - Picked files (ported from shared/clientCertPem.test.ts)

    @Test
    fun testAClientPemReadsInEitherOrder() {
        for (pem in listOf("$key\n$cert\n", "$cert\n$key\n")) {
            assertEquals(
                ClientCertificatePEM.Reading.Ready(CertificateSource.Imported(cert = cert, key = key)),
                ClientCertificatePEM.reading(pem),
            )
        }
    }

    @Test
    fun testTheKeyLabelsOtherToolsWriteAreRead() {
        assertEquals(rsaKey, ClientCertificatePEM.parts("$cert\n$rsaKey").key)
    }

    @Test
    fun testTheLeafIsTakenOutOfAChain() {
        // A bundle with a chain presents the leaf, whose fingerprint services hold, so the first
        // certificate is the one.
        val issuer = "-----BEGIN CERTIFICATE-----\nISSUER\n-----END CERTIFICATE-----"
        assertEquals(cert, ClientCertificatePEM.parts("$cert\n$issuer\n$key").cert)
    }

    @Test
    fun testAKeyDoesNotMatchAcrossDifferentLabels() {
        // A truncated file must not pair a BEGIN with some later, unrelated END.
        assertEquals("", ClientCertificatePEM.parts("$cert\n-----BEGIN RSA PRIVATE KEY-----\nMIIE\n").key)
        assertEquals(
            "",
            ClientCertificatePEM.parts("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----").key,
        )
    }

    @Test
    fun testTwoPickedFilesMakeOnePair() {
        // cert.pem and key.pem picked together, joined the way the form joins them.
        assertEquals(
            ClientCertificatePEM.Reading.Ready(CertificateSource.Imported(cert = cert, key = key)),
            ClientCertificatePEM.reading(listOf(cert, key).joinToString("\n")),
        )
    }

    @Test
    fun testAMissingHalfIsNamed() {
        val noKey = ClientCertificatePEM.reading(cert)
        val noCert = ClientCertificatePEM.reading(key)
        val neither = ClientCertificatePEM.reading("not a certificate")
        if (noKey !is ClientCertificatePEM.Reading.Refused ||
            noCert !is ClientCertificatePEM.Reading.Refused ||
            neither !is ClientCertificatePEM.Reading.Refused
        ) {
            fail("expected every incomplete file to be refused")
        }
        assertTrue(noKey.message.contains("no private key"), noKey.message)
        assertTrue(noCert.message.contains("no certificate"), noCert.message)
        assertTrue(neither.message.contains("doesn't hold"), neither.message)
    }
}
