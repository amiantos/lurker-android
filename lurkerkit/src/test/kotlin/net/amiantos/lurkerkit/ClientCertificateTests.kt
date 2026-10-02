// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificatePEM
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

    private fun draft(): NetworkDraft =
        NetworkDraft(name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "me")

    // MARK: - Reading a row

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

    // Waiting on FrameParser (reading a row, and the `row(certificate:)` helper):
    // testACertificateReadsItsExpiry, testAnUnreadableCertificateIsNotNoCertificate,
    // testNullAndAbsentBothMeanNoCertificate
}
