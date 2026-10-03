// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import net.amiantos.lurkerkit.client.LinkPreviewStore

/**
 * The kit's `LinkPreviewStore.onUpdate`, turned into state a row can read: a version per URL, bumped
 * whenever that URL's preview state moves.
 *
 * ⚠⚠ Per URL, and that is the point. The store says WHICH addresses moved, and it is shared by every
 * buffer — most of what resolves during a connect burst belongs to some other screen. A row reads the
 * versions of its own URLs ([version]) while composing, so a batch landing recomposes exactly the rows
 * that mention something in it, wherever they are in the list, and nothing else. (One state object per
 * URL rather than a snapshot map: a map's reads are tracked as a whole, so any write would recompose
 * every row that had read it.)
 *
 * A process-wide object, as lurker-ios's `PreviewImageLoader.shared` is — it outlives every screen —
 * installed once by `LurkerApp`. Main thread only, as the store is.
 */
object PreviewUpdates {
    private val versions = HashMap<String, MutableIntState>()

    /** Take over [store]'s callback. Once, from `LurkerApp`. */
    fun install(store: LinkPreviewStore) {
        store.onUpdate = ::publish
    }

    /**
     * The version of [url]'s preview state. Read during composition, it subscribes the reader to that
     * URL alone. Starts at 0 for a URL nothing has moved yet — whoever reads it then plans from the
     * store's current answer, and hears about the next one.
     */
    fun version(url: String): Int = versions.getOrPut(url) { mutableIntStateOf(0) }.intValue

    /** [urls] moved: bump the ones somebody is reading. A URL nobody has read has nobody to tell. */
    fun publish(urls: Set<String>) {
        for (url in urls) versions[url]?.let { it.intValue += 1 }
    }

    /**
     * Forget every version. On sign-out, with the store's own reset: the next account's rows start
     * from nothing, and the URLs the last one read are its reading history.
     */
    fun reset() {
        versions.clear()
    }
}
