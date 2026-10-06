// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

/**
 * Whether a picture plays: the question a link preview asks before it badges an image as an
 * animation and plays it in the viewer.
 *
 * ⚠ More images than one is not the answer. ImageIO counts every image a container holds, and
 * most containers that hold several are stills: a JPEG carrying an HDR gain map (Pixel and
 * Samsung Ultra HDR, iPhone HDR exports) or a stereo MPO, a multi-page TIFF, an ICO's sizes, an
 * HEIC collection. Played as frames, an Ultra HDR photo flickered between itself and its
 * greyscale gain map (sweep L13). So only the formats that animate count, by the type ImageIO
 * reads: a GIF, an APNG and an HEIC sequence were checked against ImageIO (a three-image HEIC
 * reads as `public.heic`, an HEIC sequence as `public.heics`); WebP and AVIF sequences are by
 * their registered types.
 *
 * Not the upload's rule (`ImageShrink`), which asks a different question — what the server's
 * decoder will leave unresized — and answers it differently.
 *
 * Port note: MIME types where LurkerKit has uniform type identifiers (PORTING.md), as `ImageShrink`
 * does: `com.compuserve.gif` → `image/gif`, `public.png` → `image/png`, `org.webmproject.webp` →
 * `image/webp`, `public.heics` → `image/heic-sequence`. LurkerKit's `public.avis` has no MIME of its
 * own — a still AVIF and an AVIF sequence are both `image/avif` — and allowing `image/avif` would let
 * a gain-map AVIF play, which is the bug, so it is left out (ledger).
 *
 * Nothing on Android asks. Its previews decode through `ImageDecoder`, which answers the question
 * itself: it returns an `AnimatedImageDrawable` only for a picture that animates, and an Ultra HDR
 * JPEG decodes as a still with its gain map attached (`PreviewImageLoader`). Ported for parity.
 */
object ImageAnimation {
    /** The MIME types that can hold an animation. */
    internal val animatableTypes: Set<String> = setOf(
        "image/gif",
        "image/png", // APNG
        "image/webp",
        "image/heic-sequence",
    )

    /** Whether an image read as [frameCount] images of MIME type [typeIdentifier] plays. */
    fun plays(frameCount: Int, typeIdentifier: String?): Boolean {
        if (frameCount <= 1 || typeIdentifier == null) return false
        return typeIdentifier in animatableTypes
    }
}
