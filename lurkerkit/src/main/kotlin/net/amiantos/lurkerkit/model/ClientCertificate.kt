// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.unicodeRegex
import java.time.Instant

/**
 * The TLS client certificate a network presents on its handshake (CertFP, lurker#459), as the
 * server describes it.
 *
 * Never the PEM. The private key leaves the server through one route, and only when asked
 * (`exportCertificate`); nothing on screen needs the certificate itself.
 *
 * The description's fingerprints aren't read either. Nothing shows one, matching the web:
 * `/msg NickServ CERT ADD` with no argument takes it from the live connection.
 */
sealed interface ClientCertificate {
    /**
     * `expires` is null when the server's date didn't parse, or its reply didn't describe the
     * certificate at all. Either way the certificate is there.
     */
    data class Usable(val expires: Instant?) : ClientCertificate

    /**
     * ⚠⚠ A certificate IS attached and doesn't parse. Not "no certificate": the server refuses
     * to dial while it's there, so reading it as null would show a network with no certificate
     * that won't connect because of one. Removing it is the only thing that helps.
     */
    data object Unusable : ClientCertificate
}

/** Where a certificate comes from: minted by the server, or a pair brought from another client. */
sealed interface CertificateSource {
    data object Generate : CertificateSource
    data class Imported(val cert: String, val key: String) : CertificateSource
}

/** The outcome of attaching or removing a certificate. */
sealed interface CertificateResult {
    /** The network's certificate as the server now describes it — null once removed. */
    data class Updated(val certificate: ClientCertificate?) : CertificateResult

    /**
     * The server's own wording where it gave any. It names what's wrong with an imported pair
     * ("that private key doesn't match that certificate"), which this client can't check.
     */
    data class Failure(val message: String) : CertificateResult
}

/** A certificate export, or why there isn't one. */
sealed interface CertificateExport {
    /**
     * Key then certificate in one PEM file — the `client.pem` HexChat and WeeChat keep, and
     * what Import reads back.
     */
    data class Pem(val pem: String) : CertificateExport
    data class Failure(val message: String) : CertificateExport
}

/**
 * Pulling a certificate and its private key out of picked files (lurker#459). A port of the
 * web's `shared/clientCertPem.ts`.
 *
 * Text only. Whether the pair actually works is the server's call — it splits and validates
 * again on arrival — so this exists to name a file missing half the pair on the spot rather
 * than after a round trip.
 */
object ClientCertificatePEM {

    sealed interface Reading {
        data class Ready(val source: CertificateSource) : Reading
        data class Refused(val message: String) : Reading
    }

    /** What [parts] returns: a named tuple in LurkerKit. "" for either half that isn't there. */
    data class Parts(val cert: String, val key: String)

    private val certificateBlock = unicodeRegex(
        "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----",
    )

    /**
     * The label varies (`PRIVATE KEY`, `RSA PRIVATE KEY`, `EC PRIVATE KEY`). The backreference
     * keeps BEGIN and END agreeing, so a truncated file can't match across two different blocks.
     */
    private val keyBlock = unicodeRegex(
        "-----BEGIN ([A-Z ]*)PRIVATE KEY-----[\\s\\S]*?-----END \\1PRIVATE KEY-----",
    )

    /**
     * The first certificate and the first private key in some PEM text, "" for either that
     * isn't there. First on purpose: a bundle carrying a chain presents the leaf, which is the
     * certificate whose fingerprint services hold.
     */
    fun parts(pem: String): Parts = Parts(first(certificateBlock, pem), first(keyBlock, pem))

    /**
     * Picked files, joined, read as an import.
     *
     * A missing half is named rather than reported as a bad file: the pair often lives in two
     * files (`cert.pem`, `key.pem`), and picking only one of them is the likeliest mistake.
     */
    fun reading(text: String): Reading {
        val (cert, key) = parts(text)
        return when (cert.isEmpty()) {
            false -> when (key.isEmpty()) {
                false -> Reading.Ready(CertificateSource.Imported(cert = cert, key = key))
                true -> Reading.Refused(
                    "That file has no private key in it. Pick the .pem holding both, or both files at once.",
                )
            }
            true -> when (key.isEmpty()) {
                true -> Reading.Refused("That file doesn't hold a certificate or a private key.")
                false -> Reading.Refused(
                    "That file has no certificate in it. Pick the .pem holding both, or both files at once.",
                )
            }
        }
    }

    private fun first(regex: Regex, text: String): String = regex.find(text)?.value ?: ""
}
