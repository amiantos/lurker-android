// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

/**
 * What `GET /api/config` says about the instance: its feature flags, and the protocol it
 * speaks.
 */
internal data class InstanceConfig(
    val features: InstanceFeatures = InstanceFeatures(),
    /** The protocol the server speaks. Null when it didn't say. */
    val protocolVersion: Int? = null,
    /** The oldest client protocol the server still serves. Null when it didn't say. */
    val minProtocolVersion: Int? = null,
) {
    /** Whether this build can talk to the server, going by what the server advertised (lurker-ios#17). */
    val incompatibility: Incompatibility?
        get() = ProtocolVersion.incompatibility(serverVersion = protocolVersion, serverMinimum = minProtocolVersion)
}
