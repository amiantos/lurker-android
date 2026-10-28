// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The message list's diagnostics trace (lurker-ios#137, lurker-ios#246): what the position machine
 * decided and the layout it decided on, kept in memory while a hidden switch is on, for a tester to
 * hand over through the share sheet.
 *
 * Why it ships rather than staying in debug builds: the bugs it exists for — the list not following
 * a burst from one person, the unread banner missing — are rare and were never caught by reading
 * the code. The trace has to already be running when it happens, on the device it happens on.
 *
 * Off by default and costs nothing off: callers check [isEnabled] before building a line. Bounded
 * at a few thousand lines — about a megabyte of text at most; lines carry a buffer's ordinal label
 * and a timestamp — never a key (a channel or a DM peer's name) and never message text.
 */
object ScrollTrace {
    private const val CAPACITY = 4000

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val lines = ArrayDeque<String>()
    /** Ordinals in place of buffer keys: a key names a channel or a DM peer, and the file is meant to be handed over. Per process. */
    private val labels = HashMap<String, String>()
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** The saved switch, at startup: no "on" line, nothing to clear. */
    fun restore(on: Boolean) {
        isEnabled = on
    }

    @Synchronized
    fun setEnabled(on: Boolean, build: String) {
        isEnabled = on
        if (on) {
            note("trace", "on $build")
        } else {
            // The lines go; the labels stay. A conversation on screen remembers its label for the
            // process, and a fresh map would hand the same ordinal to the next buffer opened.
            lines.clear()
        }
    }

    /** The label a buffer is written under — see [labels]. */
    @Synchronized
    fun label(keyId: String): String = labels.getOrPut(keyId) { "b${labels.size + 1}" }

    /** One line, stamped with a buffer's label (never its key or its content). */
    @Synchronized
    fun note(source: String, line: String) {
        if (!isEnabled) return
        // Trimmed by halves rather than one line at a time: a scroll's ticks arrive on the main thread.
        if (lines.size >= CAPACITY) repeat(CAPACITY / 2) { lines.removeFirst() }
        lines.addLast("${LocalTime.now().format(formatter)} [$source] $line")
    }

    /** Everything held, newest last, with a header saying which build wrote it. */
    @Synchronized
    fun export(build: String): String = (listOf("Lurker scroll trace — $build", "") + lines).joinToString("\n") + "\n"

    /**
     * The trace as a text file on the share sheet — Messages, Mail, Files, whatever the tester has. A
     * file rather than a string, so it arrives as an attachment and not a wall of text (and never
     * trips the transaction size limit a long trace would as an extra).
     */
    suspend fun share(context: Context, build: String) {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            File(directory, "lurker-scroll-trace.txt").also { it.writeText(export(build)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share scroll trace"))
    }
}
