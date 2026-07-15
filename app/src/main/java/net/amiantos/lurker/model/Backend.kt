// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.model

/**
 * A Lurker backend the client can speak to. Self-hosted and hosted are the SAME
 * client differing only in base URL and where the token is minted — this is why
 * #3 builds one client with a configurable base URL + auth strategy rather than a
 * transport-adapter seam. Direct IRC is dropped permanently, so no second
 * transport will ever appear; if one does, extract the seam then, against a real
 * case.
 */
enum class Backend(
    val defaultUrl: String,
    /** Where a password is exchanged for a session token. */
    val loginPath: String,
    /** The JSON key the login body uses for the account identifier. */
    val identifierField: String,
    /** What to call that identifier in the UI. */
    val identifierLabel: String,
) {
    /** Mint at the cell: `POST /api/auth/login/token`, `{username, password}`. */
    SelfHosted(
        // 10.0.2.2 is the emulator's alias for the host's 127.0.0.1; 8010 is the
        // API/WS server (NOT the Vite client dev port).
        defaultUrl = "http://10.0.2.2:8010",
        loginPath = "/api/auth/login/token",
        identifierField = "username",
        identifierLabel = "Username",
    ),

    /** Mint at the control plane: `POST /_cp/auth/app/login`, `{email, password}`. */
    Hosted(
        defaultUrl = "https://app.lurker.chat",
        loginPath = "/_cp/auth/app/login",
        identifierField = "email",
        identifierLabel = "Email",
    ),
}
