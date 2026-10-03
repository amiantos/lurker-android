// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import net.amiantos.lurkerkit.model.Message

/**
 * Where a long press on a row landed (lurker-ios#60) — iOS's `MessageBodyHosting` questions, asked
 * of the row because it's the only thing that knows where its body and chips sit.
 */
sealed interface RowPress {
    /** The line itself: its actions sheet. */
    data class Line(val message: Message) : RowPress

    /** A link in the body: the link's actions — the line around it isn't what the finger was on. */
    data class Link(val url: String) : RowPress

    /** The reaction chips: who gave what, the one thing a tap on a chip can't show (it toggles). */
    data class Reactions(val message: Message) : RowPress
}

/**
 * A long press anywhere in the element, offered at the press's position. [onLongPress] says whether
 * it took it: when it did, the rest of that touch is swallowed, so the link, chip or quote under the
 * finger doesn't also take it as a tap on release; when it didn't (nothing there to act on — the
 * typing line, a summary with no link), the touch is let go untouched.
 *
 * Watched on the Initial pass, ahead of the children: a link and a chip are clickable, and a
 * clickable consumes its down, so a long press asked for after them would never see a press that
 * started on one — and a press on a link is exactly the one iOS answers with the link's actions. The
 * press is let go the moment the finger moves past the touch slop (the list is being scrolled),
 * lifts early (it was a tap), or a second finger lands.
 *
 * A plain detector rather than `combinedClickable`: that would make the whole row a click target
 * (a ripple over every line, and a TalkBack "double-tap to activate" that does nothing), and its long
 * press would race the chips' own.
 */
@Composable
internal fun Modifier.longPressAnywhere(onLongPress: (Offset) -> Boolean): Modifier {
    val current by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var letGo = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (!letGo) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    letGo = change == null || !change.pressed || event.changes.size > 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
            }
            if (letGo || !current(down.position)) return@awaitEachGesture
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/**
 * Where a row's parts were placed, so a press can be resolved to one of them — kept outside state:
 * it's read only when a press lands, and recording it must not recompose the row.
 */
internal class PressTargets {
    /** The element the press was measured in — the row's outer box. */
    var row: LayoutCoordinates? = null

    /** The body text, and how it was laid out. */
    var body: LayoutCoordinates? = null
    var bodyLayout: TextLayoutResult? = null

    /** The chip row's slot — its padding included, so a chip's target is bigger than its drawing. */
    var chips: LayoutCoordinates? = null

    /**
     * Each attachment's box — a mosaic tile, a media box, a card — by the address it stands for. An
     * attachment re-planned away leaves composition and its coordinates detach, so a stale entry is
     * simply never matched.
     */
    val attachments = HashMap<String, LayoutCoordinates>()

    /**
     * What a press at [position] (in [row]'s space) is about: the chips, then an attachment, then a
     * link, then the line.
     *
     * A press on a picture or a card is about ITS address, as a press on a link in the text is — and
     * for a picture that took its address out of the text it's the only way to that address's Copy,
     * Open and Share. A link is looked for before the line is required — a consolidated run of topic
     * changes has no single message, and a URL in one is still a URL you can act on (iOS learned that
     * the hard way).
     */
    fun resolve(position: Offset, message: Message?): RowPress? {
        val row = row?.takeIf { it.isAttached }
        if (row != null && message != null) {
            val chips = chips?.takeIf { it.isAttached }
            if (chips != null && contains(chips, chips.localPositionOf(row, position))) return RowPress.Reactions(message)
        }
        if (row != null) {
            for ((url, box) in attachments) {
                if (box.isAttached && contains(box, box.localPositionOf(row, position))) return RowPress.Link(url)
            }
            linkAt(row, position)?.let { return RowPress.Link(it) }
        }
        return message?.let { RowPress.Line(it) }
    }

    private fun linkAt(row: LayoutCoordinates, position: Offset): String? {
        val body = body?.takeIf { it.isAttached } ?: return null
        val layout = bodyLayout ?: return null
        val point = body.localPositionOf(row, position)
        if (!contains(body, point)) return null
        // `getOffsetForPosition` answers with the NEAREST caret wherever the finger is, so the glyph
        // has to actually be under it — a press in the blank end of a line isn't a press on the link
        // that ends it.
        val text = layout.layoutInput.text
        val offset = layout.getOffsetForPosition(point)
        for (index in listOf(offset, offset - 1)) {
            if (index !in text.indices || !layout.getBoundingBox(index).contains(point)) continue
            val url = text.getLinkAnnotations(index, index + 1).firstOrNull { it.item is LinkAnnotation.Url } ?: return null
            return (url.item as LinkAnnotation.Url).url
        }
        return null
    }

    private fun contains(coordinates: LayoutCoordinates, point: Offset): Boolean =
        point.x >= 0f && point.y >= 0f && point.x <= coordinates.size.width && point.y <= coordinates.size.height
}
