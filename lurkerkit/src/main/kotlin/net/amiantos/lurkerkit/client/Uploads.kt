// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import net.amiantos.lurkerkit.support.isInWhitespaces
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * The upload contract, client-side. `POST /api/uploads` is multipart/form-data with a
 * `progressToken` field and an `image` file field (that field name is the server's, and
 * covers every content class — image, video, text — not just images). The server re-encodes
 * images, scrubs video metadata, and answers with the public URL of the stored object.
 */
object Uploads {
    /**
     * What to compress a file to when the server hasn't told us its cap yet.
     *
     * A guess about *our* deployment, and nothing more. app.lurker.chat is fronted by
     * Cloudflare, whose request-body limit is 100 MB on every non-Enterprise plan; a larger
     * body is rejected at the edge with a connection reset the iOS client sees as
     * `cannotParseResponse`, long before the server's own limit is consulted (confirmed: a
     * 139 MB screen recording died exactly this way). 90 MiB sits comfortably under it.
     *
     * ⚠⚠ Not a cap, and never a substitute for one. It used to be the only number the iOS
     * client had, which cost accuracy in both directions (lurker-ios#149): a self-hosted
     * instance with no CDN in front of it has a 200 MB ceiling and had 150 MB clips transcoded
     * down to 90 for nothing, while an instance behind a tighter proxy got a video compressed
     * to 90 and then a 413 — the whole transcode spent to fail. The server has advertised the
     * real number since lurker#627; this is only what stands in for the window before the
     * snapshot lands, and for a server too old to send one.
     */
    const val fallbackMaxBytes: Long = 90L * 1024 * 1024

    /**
     * The size to compress a file to: what the server advertised, or the fallback until it
     * has said.
     *
     * ⚠⚠ `advertised` is a **file** cap, not a request-body limit. The multipart envelope is
     * already subtracted server-side (`ENVELOPE_HEADROOM_BYTES`, 64 KiB), so a file at
     * exactly this size still fits once the boundaries and fields are added. Budgeting for
     * the envelope again here would be the same over-compression this replaced, just smaller.
     *
     * ⚠ Advisory, not a contract. It is resolved for the account's *default* uploader, so a
     * per-upload override or an operator change mid-session is still settled by the 413 —
     * which is why `TooLarge` handling stays exactly as it was. This only stops us guessing
     * wrong before we start.
     */
    fun compressionTarget(advertised: Long?): Long = advertised ?: fallbackMaxBytes
}

/**
 * What the server returns on a successful upload. Mirrors the JSON the web client reads:
 * the `url` is what gets pasted into the composer, `mime` is derived from the magic bytes
 * (trust it over any client guess), and `thumbnailUrl` is present only when the server
 * hosted the thumbnail remotely.
 */
data class UploadResponse(
    val id: Int,
    val url: String,
    val mime: String?,
    val canDelete: Boolean,
    val thumbnailUrl: String?,
)

/**
 * Everything that can go wrong turning a picked file into a pasted URL. Each case carries a
 * user-facing sentence so the presenter never has to interpret an error code.
 */
sealed class UploadError : Exception(null, null, false, false) {
    /** No live session — the token was dropped between picking and uploading. */
    data object NotSignedIn : UploadError()

    /** The session token was rejected (401). The client also bounces to sign-in. */
    data object Unauthorized : UploadError()

    /**
     * The server refused the file for exceeding its size cap (413) — the video was already
     * compressed as far as we go, or an image somehow arrived over the ceiling.
     */
    data object TooLarge : UploadError()

    /**
     * Compression ran but couldn't get the video under the cap (a very long/high-motion 4K
     * clip). Distinct from `TooLarge` because it happened on-device, before any request.
     */
    data object CannotCompressEnough : UploadError()

    /** The transcode itself failed (unsupported codec, corrupt source, cancelled export). */
    data class CompressionFailed(val why: String) : UploadError()

    /** A non-2xx response carrying the server's own `error` string. */
    data class Server(val msg: String) : UploadError()

    /** A transport-level failure (offline, TLS, timeout) — the I/O exception's description. */
    data class Transport(val why: String) : UploadError()

    /**
     * The sentence shown to the user. Written to be actionable where we can be, honest
     * where we can't.
     */
    val userMessage: String
        get() = when (this) {
            NotSignedIn ->
                "You're not signed in."
            Unauthorized ->
                "Your session ended. Sign in again to upload."
            TooLarge ->
                "The server rejected this file for being too large."
            CannotCompressEnough ->
                "This video is too large to upload, even after compression."
            is CompressionFailed ->
                "Couldn't process this video: $why"
            is Server ->
                msg
            is Transport ->
                "Upload failed: $why"
        }

    /**
     * Whether this condemns a whole batch, or only the file that hit it.
     *
     * The split is *is this about the file, or about the pipe?* A file the server refused —
     * too large, undecodable — says nothing about the next one, and stopping there would
     * strand four good uploads behind one bad photo. A dead session or a broken transport
     * will fail every remaining file identically, and each one costs another bounce to
     * sign-in or another 300-second timeout before it says so.
     *
     * `Server` sits on the continue side, but only provisionally — see
     * `UploadBatch.shouldStop(failures)`. The server answering means the pipe works, yet the
     * refusal may still be about the *instance* rather than the file: lurker maps every
     * uploader-driver failure onto one status (a stale provider credential, a provider that
     * is down), so "this file was rejected" and "nothing will upload today" arrive here
     * looking identical. What tells them apart is repetition, which needs the batch's
     * history rather than one error.
     */
    val stopsABatch: Boolean
        get() = when (this) {
            NotSignedIn, Unauthorized, is Transport ->
                true
            TooLarge, CannotCompressEnough, is CompressionFailed, is Server ->
                false
        }
}

/**
 * What to tell the user after a multi-file upload. Pure, so the wording of a half-failed
 * batch is decided somewhere it can be tested rather than inside a view controller.
 */
object UploadBatch {
    /**
     * Whether to abandon the rest of a batch, given everything that has failed so far.
     *
     * Two ways to earn it. The first is an error that is plainly about the connection rather
     * than the file (`UploadError.stopsABatch`). The second is **the same message twice in a
     * row**, which is how an instance-level refusal announces itself when it can't say so
     * directly: a stale provider credential comes back as a per-file rejection, but it comes
     * back as the *identical* per-file rejection every time. Ten videos on cellular, each
     * compressed and pushed in full before being told the same thing, is most of a gigabyte
     * spent learning what the second file already said.
     *
     * Waiting for the repeat rather than guessing from the first is the point: one file
     * genuinely can be too big, or the wrong type, with the next nine perfectly fine.
     */
    fun shouldStop(failures: List<UploadError>): Boolean {
        val last = failures.lastOrNull() ?: return false
        if (last.stopsABatch) return true
        if (failures.size < 2) return false
        return failures[failures.size - 2].userMessage == last.userMessage
    }

    /**
     * The sentence for a batch that didn't fully succeed, or null when there is nothing worth
     * interrupting for.
     *
     * - `picked`: everything the user selected, including files that never staged.
     * - `uploaded`: how many produced a URL.
     * - `failures`: the per-file errors actually hit. Shorter than `picked - uploaded` when a
     *   `stopsABatch` error cut the run short — the files never attempted have no error of
     *   their own, and are accounted for by the count rather than invented reasons.
     * - `unreadable`: picked files that couldn't even be staged off the picker.
     * - `unreadableReason`: the first such failure's own words ("No space left on device"),
     *   which is the difference between a user who can fix it and one who can only shrug.
     * - `cancelled`: the user stopped it. The *stopping* is silent by design — they know, and
     *   an alert confirming what someone just asked for is a dialog to dismiss, not
     *   information. What already went wrong before they stopped is still news, though, so a
     *   cancel suppresses the counts and not the errors.
     */
    fun summary(
        picked: Int,
        uploaded: Int,
        failures: List<UploadError>,
        unreadable: Int,
        unreadableReason: String? = null,
        cancelled: Boolean,
    ): String? {
        if (failures.isEmpty() && unreadable == 0) return null

        // What the files that failed have to say for themselves, whether or not the run was
        // cancelled — a cancel excuses the files not attempted, not the ones that broke.
        val unreadableClause: String? = run {
            if (unreadable <= 0) return@run null
            val count = if (unreadable == 1) "1 couldn't be read" else "$unreadable couldn't be read"
            unreadableReason?.let { "$count: $it" } ?: "$count."
        }
        val failureClause: String? = failures.firstOrNull()?.let { first ->
            if (failures.size == 1) {
                first.userMessage
            } else {
                "${failures.size} failed — first error: ${first.userMessage}"
            }
        }

        if (cancelled) {
            // No "Uploaded N of M": that shortfall is the user's own doing, and counting it
            // back at them reads as an accusation.
            return listOfNotNull(unreadableClause, failureClause).joinToString(separator = " ")
        }

        // A single file that failed on its own reads as a plain error, exactly as it did
        // before batches existed. "Uploaded 0 of 1" is a statistic where a sentence will do.
        if (picked == 1 && unreadable == 0) {
            val only = failures.firstOrNull()
            if (only != null) return only.userMessage
        }
        if (picked == 1 && unreadable == 1) {
            return unreadableReason ?: "That file couldn't be read."
        }

        return (listOf("Uploaded $uploaded of $picked.") + listOfNotNull(unreadableClause, failureClause))
            .joinToString(separator = " ")
    }
}

/**
 * One `upload-progress` frame: the server narrating the half of an upload this device
 * cannot see (lurker-ios#47; server-side lurker#545).
 *
 * The HTTP client's count of body bytes sent measures device→server and nothing else. When it
 * reads 100% the file has merely *arrived* at the cell — and then the two slowest phases run:
 * the sharp/scrub pipeline, and the server→provider send, which on a home uplink is by far the
 * longest. Only the server knows about those, so only the server can narrate them.
 */
data class UploadServerProgress(
    val phase: Phase,
    /** 0…100 for the provider send, or null when there is no number to give. */
    val percent: Int?,
    /**
     * The resolved uploader's human label ("Catbox", "Local disk"), so the readout can name
     * where the file is going. Null when the server didn't say.
     */
    val destination: String?,
) {
    enum class Phase(val rawValue: String) {
        /**
         * The server's pipeline (image re-encode / media scrub). A one-shot native call with
         * no seam to count, so it never carries a percentage.
         */
        Processing("processing"),

        /**
         * The send to the provider. Carries a percentage only when the driver can report
         * bytes — `local` renames a temp file, and there is no wire to count.
         */
        Sending("sending");

        companion object {
            fun fromRawValue(raw: String): Phase? = entries.firstOrNull { it.rawValue == raw }
        }
    }
}

/**
 * The legs of an upload, folded into the one thing the readout renders.
 *
 * **Tier 1 is local and unconditional; tier 2 only refines it.** The moment the device leg
 * finishes, this advances to `Processing` on its own — without waiting for any frame. That
 * alone kills the "Uploading… 100%" dead air even against an old server or a dropped socket,
 * and the server's frames then sharpen the label rather than enable it. Progress is a
 * courtesy, never a precondition: the HTTP response is still what completes the upload.
 *
 * Port note: immutable, because it is shown in UI state (PORTING.md, "Structs that mutate",
 * case 2). LurkerKit's two `mutating func apply(…)` keep their names here and return the
 * updated copy: `progress = progress.apply(deviceFraction = 0.5)`. The primary constructor is
 * private, as the setters are in LurkerKit; a fresh `UploadProgress()` is the only way in, and
 * `apply` the only way to change one.
 */
@ConsistentCopyVisibility
data class UploadProgress private constructor(
    val stage: Stage,
    /** 0…1 for the device leg. Only meaningful while `stage == Stage.Uploading`. */
    val deviceFraction: Double,
    /** 0…1 for the provider send, or null when the phase can't be counted. */
    val sentFraction: Double?,
    /**
     * Sticky once the server names it: a later frame that omits the destination must not
     * blank a label that is already on screen.
     */
    val destination: String?,
    /**
     * Whether the server has narrated anything yet. Once it has, its account of where the
     * upload is beats anything the device leg can say — see `apply(deviceFraction)`.
     */
    private val heardFromServer: Boolean,
) {
    enum class Stage {
        /** device → server, the only leg the HTTP client can see. */
        Uploading,

        /** The server's pipeline. Indeterminate by nature. */
        Processing,

        /** server → provider. The long one. */
        Sending,
    }

    constructor() : this(
        stage = Stage.Uploading,
        deviceFraction = 0.0,
        sentFraction = null,
        destination = null,
        heardFromServer = false,
    )

    /**
     * A body-bytes-sent tick. Advances to `Processing` at 100% by itself — see the type's
     * note on tier 1 — and is otherwise ignored once a later stage has begun, because those
     * callbacks hop threads to reach the main thread and a straggler landing after the
     * server's first frame would undo the very thing this exists to fix.
     *
     * **The one exception is a fraction that goes backwards before the server has said
     * anything.** That isn't a straggler — device ticks are generated in order by the one
     * writer of the request body and enqueued in order, so they can only reorder against the
     * *WS*, never against each other. It means the HTTP client restarted the request body (an
     * HTTP/2 `GOAWAY` retry, a redirect): the bytes-sent count resets and the whole file goes
     * again. Latching would sit on "Processing…" through that entire second transmission — up
     * to the 300 s timeout on a large video — and no server frame could correct it, because
     * the server hasn't received the file yet. So re-enter, and report the leg that is
     * genuinely running.
     */
    fun apply(deviceFraction: Double): UploadProgress {
        // Port note: Swift's generic `min`/`max` turn a NaN into 1 here; `kotlin.math`'s keep
        // it NaN. Nothing produces one — the fraction is a byte count over a positive total.
        val clamped = max(0.0, min(1.0, deviceFraction))
        if (stage != Stage.Uploading) {
            if (heardFromServer || !(clamped < this.deviceFraction)) return this
        }
        return copy(
            deviceFraction = clamped,
            stage = if (clamped >= 1) Stage.Processing else Stage.Uploading,
        )
    }

    /**
     * A server frame. Monotonic in the same direction and for the same reason: a delayed
     * `processing` arriving after `sending` has begun would rewind a real percentage to an
     * indeterminate label. WS ordering makes that unlikely, not impossible, and a visibly
     * jumping readout is more expensive than the guard.
     */
    fun apply(server: UploadServerProgress): UploadProgress {
        var next = copy(heardFromServer = true)
        val destination = server.destination
        if (destination != null && destination.isNotEmpty()) {
            next = next.copy(destination = destination)
        }
        if (stage == Stage.Sending && server.phase == UploadServerProgress.Phase.Processing) return next
        return when (server.phase) {
            UploadServerProgress.Phase.Processing ->
                next.copy(stage = Stage.Processing, sentFraction = null)
            UploadServerProgress.Phase.Sending ->
                next.copy(
                    stage = Stage.Sending,
                    // A percentage only where one was actually reported. An assumed 0% would freeze
                    // at "Sending to Local disk… 0%" for a driver that never counts a byte — the same
                    // species of lie as the 100% this whole feature exists to kill.
                    sentFraction = server.percent?.let { max(0, min(100, it)).toDouble() / 100 },
                )
        }
    }
}

/**
 * Assembles a `multipart/form-data` body **to a file on disk**, streaming the source in
 * chunks so a 200 MB video is never held in memory. The whole feature turns on this: the
 * server's own upload route went to disk-backed multer for exactly this reason (a 200 MB
 * upload used to cost ~1 GB of RSS), and buffering the body here would just move that cost
 * to the phone, which has far less headroom.
 */
internal object MultipartBody {
    /**
     * Port note: `fileURL` is a `java.io.File`. Both of LurkerKit's file `URL`s (this one and
     * `assemble`'s source) are paths on local disk, which PORTING.md's `URL` row — `HttpUrl`
     * for http(s) — does not cover.
     */
    data class Assembled(
        val fileURL: File,
        val contentType: String,
    )

    /**
     * A filename can't carry a bare `"`/CR/LF into the `Content-Disposition` header without
     * breaking it (or, worse, injecting a header). Strip control chars and quotes; the
     * server re-derives the real extension from the magic bytes anyway, so this value is
     * only ever the display name.
     *
     * Port note: "control chars" is LurkerKit's `CharacterSet.controlCharacters`, which is
     * Unicode categories Cc **and Cf** — so a zero-width joiner, a BOM or a bidi override goes
     * too, not just C0/C1. Stepped by code point, as the Swift steps by Unicode scalar.
     *
     * The trim is `.whitespaces` — spaces and tabs, not newlines.
     */
    fun sanitizeFilename(name: String): String {
        val cleaned = StringBuilder()
        var index = 0
        while (index < name.length) {
            val scalar = name.codePointAt(index)
            index += Character.charCount(scalar)
            val category = Character.getType(scalar)
            val isControl = category == Character.CONTROL.toInt() || category == Character.FORMAT.toInt()
            if (scalar != '"'.code && !isControl) cleaned.appendCodePoint(scalar)
        }
        val result = cleaned.toString().trim { it.isInWhitespaces() }
        return if (result.isEmpty()) "upload" else result
    }

    /**
     * Field order is load-bearing: `progressToken` **before** `image`, because the server
     * parses multipart fields in stream order and reads the token before the (huge) file
     * body streams past — a token appended behind the file wouldn't exist yet when the
     * route wires up progress. This mirrors the web client's `FormData` ordering.
     *
     * Port note: throws `java.io.IOException` where the Swift `throws`. The body is written
     * under `java.io.tmpdir` — on Android that is the app's cache directory. The UUIDs are
     * uppercased because `UUID().uuidString` is, so a boundary reads the same in a server log
     * whichever client sent it.
     */
    fun assemble(
        token: String,
        fileURL: File,
        filename: String,
        mime: String,
    ): Assembled {
        val boundary = "LurkerBoundary-${UUID.randomUUID().toString().uppercase()}"
        val outURL = File(
            System.getProperty("java.io.tmpdir"),
            "lurker-upload-${UUID.randomUUID().toString().uppercase()}.multipart",
        )

        try {
            assembleInto(outURL, boundary, token, fileURL, filename, mime)
        } catch (failure: Throwable) {
            // Port note: not in LurkerKit, where the half-written body is left for iOS to purge
            // with the rest of its temporary directory. Android only trims its cache under
            // quota pressure, and the likeliest failure here is a full disk — the one condition
            // in which leaving up to a whole video behind per attempt makes the next one worse.
            outURL.delete()
            throw failure
        }

        return Assembled(fileURL = outURL, contentType = "multipart/form-data; boundary=$boundary")
    }

    private fun assembleInto(
        outURL: File,
        boundary: String,
        token: String,
        fileURL: File,
        filename: String,
        mime: String,
    ) {
        FileOutputStream(outURL).use { out ->
            fun write(string: String) {
                out.write(string.toByteArray(Charsets.UTF_8))
            }

            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"progressToken\"\r\n\r\n")
            write("$token\r\n")

            write("--$boundary\r\n")
            write(
                "Content-Disposition: form-data; name=\"image\"; filename=\"${sanitizeFilename(filename)}\"\r\n",
            )
            write("Content-Type: $mime\r\n\r\n")

            // Stream the source file in 1 MB chunks straight into the body file — never a full
            // `readBytes()`, which would defeat the whole disk-backed design.
            FileInputStream(fileURL).use { input ->
                val chunk = ByteArray(1 shl 20)
                while (true) {
                    val count = input.read(chunk)
                    if (count <= 0) break
                    out.write(chunk, 0, count)
                }
            }

            write("\r\n--$boundary--\r\n")
        }
    }
}

// UploadProgressDelegate: waits for the OkHttp client port (see LEDGER).
