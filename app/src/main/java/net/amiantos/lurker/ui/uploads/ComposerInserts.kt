// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.model.BufferKey

/**
 * Where text from outside a composer lands in one — a finished upload's link, the uploads browser's
 * Add to Message, a share's text. lurker-ios's `ChatViewController.activeChat()` plus
 * `composer.insert(_:atCaret:)`: iOS finds the chat on screen by walking the window; here the
 * conversation registers its composer while it's mounted, and the app-long pieces (the upload run, the
 * share inbox) ask this.
 *
 * Main-thread only, like the composers it holds.
 *
 * **A stack, not a slot.** A conversation pane animates in over the one leaving, so for a moment two
 * are mounted; the newest is the one on screen, and the old one's unmount mustn't clear it.
 */
class ComposerInserts(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** A composer's way in: put [text] in the field — at the caret when `atCaret`, else at the end. */
    fun interface Insert {
        fun insert(text: String, atCaret: Boolean)
    }

    private class Mounted(val key: BufferKey, val insert: Insert)

    private val mounted = mutableListOf<Mounted>()

    /** Text waiting for one buffer's composer to appear, oldest first, and when it was asked for. */
    private class Pending(val texts: MutableList<String>, val since: Long)

    /** By `BufferKey.id`. */
    private val pending = mutableMapOf<String, Pending>()

    /** The buffer whose composer is on screen, or null when none is (a phone on the buffer list). */
    val activeKey: BufferKey? get() = mounted.lastOrNull()?.key

    /**
     * A composer is on screen. Anything held for its buffer goes in now, at the caret (it's what the
     * reader asked for on the way here). Returns the handle [unmount] takes.
     *
     * ⚠ And anything held for ANY OTHER buffer is dropped: the reader went somewhere else before that
     * composer appeared, so the text is no longer on its way anywhere — left held, it would turn up in
     * that buffer whenever it was next opened, hours later, from nowhere the reader remembers. Held
     * text also lapses after [PATIENCE_MS] (the reader backed out to the list, where no composer mounts).
     */
    fun mount(key: BufferKey, insert: Insert): Any {
        val entry = Mounted(key, insert)
        mounted.add(entry)
        val held = pending.remove(key.id)
        pending.clear()
        if (held != null && clock() - held.since <= PATIENCE_MS) held.texts.forEach { insert.insert(it, atCaret = true) }
        return entry
    }

    fun unmount(handle: Any) {
        mounted.remove(handle)
    }

    /**
     * Into the composer on screen NOW — an upload's link lands where the reader's cursor is, not in the
     * composer the upload started from (what the web client does too). False when there is none.
     */
    fun insertIntoActive(text: String, atCaret: Boolean): Boolean {
        val active = mounted.lastOrNull() ?: return false
        active.insert.insert(text, atCaret)
        return true
    }

    /**
     * Into [key]'s composer: now if it's the one on screen, else as soon as it is — the caller is opening
     * that buffer (Add to Message from a dialog over it, a share sent to it).
     */
    fun insert(key: BufferKey, text: String) {
        val active = mounted.lastOrNull()
        if (active != null && active.key.id == key.id) {
            active.insert.insert(text, atCaret = true)
        } else {
            pending.getOrPut(key.id) { Pending(mutableListOf(), clock()) }.texts.add(text)
        }
    }

    /** Sign-out: text held for the previous account's buffers must not land in the next one's. */
    fun clear() {
        pending.clear()
    }

    companion object {
        /** How long text waits for its buffer's composer — a navigation and a frame, with room to spare. */
        const val PATIENCE_MS = 10_000L
    }
}
