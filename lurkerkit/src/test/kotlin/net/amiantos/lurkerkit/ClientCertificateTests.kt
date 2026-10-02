// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificatePEM
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // Waiting on FrameParser and NetworkConfig (reading a row): testACertificateReadsItsExpiry,
    // testAnUnreadableCertificateIsNotNoCertificate, testNullAndAbsentBothMeanNoCertificate
    //
    // Waiting on NetworkDraft (the create body): testAGeneratedCertificateRidesTheCreate,
    // testAnImportedPairRidesTheCreate, testAnEditNeverCarriesACertificate,
    // testNoCertificateSendsNoCertificateKeys, testACertificateNeedsTLS
}
