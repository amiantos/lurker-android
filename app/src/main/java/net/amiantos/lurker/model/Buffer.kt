// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.model

/**
 * A conversation surface: a channel, a DM, a per-network server buffer, or the
 * app-scoped system buffer. There is no buffer id on the wire — a buffer is
 * identified by (networkId, target). `networkId` is null ONLY for the system
 * buffer.
 *
 * Per-buffer counts (`unread`/`highlights`/`lastReadId`) are server-authoritative:
 * they ship in `backlog` and `read-state` frames and the client never derives
 * them locally (full read-state handling is #8).
 */
data class Buffer(
    val networkId: Int?,
    val target: String,
    val kind: BufferKind,
    val unread: Int = 0,
    val highlights: Int = 0,
    val lastReadId: Long = 0,
    val joined: Boolean = false,
    /**
     * False until the server has actually read this buffer's history. On a fresh
     * connect channel/DM buffers arrive as SHELLS (`events: []`); their history is
     * not read until the client sends `open-buffer`. Full hydration is #6.
     */
    val hydrated: Boolean = false,
) {
    val key: BufferKey get() = BufferKey(networkId, target)
}

/** Stable identity for a buffer, plus its string form for use as a map key. */
data class BufferKey(val networkId: Int?, val target: String) {
    val id: String get() = "${networkId ?: "sys"}::$target"
}

enum class BufferKind {
    Channel,
    Dm,
    Server,
    System;

    companion object {
        /** Classify a target the way the server does (isDmTarget / SYSTEM_TARGET). */
        fun of(networkId: Int?, target: String): BufferKind =
            when {
                networkId == null || target == ":system:" -> System
                target.startsWith(":server:") -> Server
                target.startsWith("#") || target.startsWith("&") -> Channel
                else -> Dm
            }
    }
}
