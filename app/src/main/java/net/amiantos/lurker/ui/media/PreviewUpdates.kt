// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurkerkit.client.LinkPreviewStore

/**
 * The kit's one `LinkPreviewStore.onUpdate`, handed to every screen that wants it.
 *
 * The store takes a single callback; a conversation is rebuilt per buffer, and during a phone's pane
 * transition two can be composed at once — whichever set the callback last would silently orphan the
 * other. So `LurkerApp` installs this once and screens [listen].
 *
 * ⚠ What arrives is WHICH URLs moved, and a listener's job is to ask whether any of them are on its
 * screen — the store is shared by every buffer, and most of what resolves belongs to another one.
 *
 * A process-wide object, as lurker-ios's `PreviewImageLoader.shared` is: it outlives every screen, and
 * threading it through the activity would buy nothing. Main thread only, as the store is.
 */
object PreviewUpdates {
    private val listeners = mutableListOf<(Set<String>) -> Unit>()

    /** Take over [store]'s callback. Once, from `LurkerApp`. */
    fun install(store: LinkPreviewStore) {
        store.onUpdate = ::publish
    }

    /** Hear every batch of moved URLs until the returned function is called. */
    fun listen(listener: (Set<String>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    /** Over a copy: a listener may stop listening from inside its own call. */
    fun publish(urls: Set<String>) {
        for (listener in listeners.toList()) listener(urls)
    }
}
