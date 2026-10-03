// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import net.amiantos.lurkerkit.model.AttributedBody
import net.amiantos.lurkerkit.support.TextRange

/**
 * A message body while it's being assembled: its text, and everything laid over the text, as spans
 * that follow the characters when some are taken out. What `MessageText` builds a line in before it
 * becomes an [AnnotatedString]; the stand-in for the `NSMutableAttributedString` lurker-ios's
 * `MessageRenderer` builds in.
 *
 * Its own type because Compose's `AnnotatedString.Builder` can only append, and a body has to lose
 * characters after it's styled: `PreviewText.stripHiddenUrls` takes the `<…>` off a bracketed link
 * (on every message) and the address a link preview's picture stands in for. That
 * function is the kit's, and it asks for an [AttributedBody]: this is the app's one.
 *
 * ⚠ The contract the kit spells out on [AttributedBody] — the styling of the text that survives a
 * deletion moves with it — is what [deleteCharacters] keeps, for every kind of span. The renderer's
 * own bookkeeping beside it (which ranges were mIRC-coloured, which were spoilers) is plain offsets
 * that do NOT move, which is why the kit's deletions run last (see `MessageText.body`).
 */
internal class StyledBody : AttributedBody {
    private val text = StringBuilder()
    private val marks = ArrayList<Mark>()

    private class Mark(var start: Int, var end: Int, val kind: Kind)

    /** What a span carries. */
    private sealed interface Kind {
        class Style(val style: SpanStyle) : Kind

        /** A link the reader can open — see `MessageText.body` for where links are found. */
        class Url(val url: String) : Kind

        /**
         * A spoiler box, by its ordinal within the message — iOS's `.spoiler`. On the run whether
         * it is hidden or revealed: it is what makes the tap target exist in both states, so a
         * reveal can be taken back.
         */
        class Spoiler(val ordinal: Int) : Kind

        /**
         * Present only while a spoiler is still hidden, carrying what to SAY instead of the text
         * underneath — iOS's `.spoilerHidden`. See `MessageText.spoken`.
         */
        class Hidden(val announcement: String) : Kind

        /**
         * Text that paints something even where it is only whitespace — a background, an
         * underline, a strike. The kit's `ink`: the end-trim must not eat it (see
         * [AttributedBody.ink]).
         */
        data object Ink : Kind
    }

    val length: Int get() = text.length

    override val string: String get() = text.toString()

    /** Append [s] in [style] (or unstyled), and say where it landed. */
    fun append(s: String, style: SpanStyle? = null): TextRange {
        val start = text.length
        text.append(s)
        val range = TextRange(start, text.length)
        if (style != null && !range.isEmpty) marks.add(Mark(range.start, range.end, Kind.Style(style)))
        return range
    }

    /** Append another body whole, its spans shifted to where it lands. */
    fun append(other: StyledBody) {
        val offset = text.length
        text.append(other.text)
        for (mark in other.marks) marks.add(Mark(mark.start + offset, mark.end + offset, mark.kind))
    }

    /** Lay [style] over [range]. A later style wins over an earlier one where both set a field. */
    fun style(range: TextRange, style: SpanStyle) = add(range, Kind.Style(style))

    fun link(range: TextRange, url: String) = add(range, Kind.Url(url))

    fun spoiler(range: TextRange, ordinal: Int) = add(range, Kind.Spoiler(ordinal))

    fun hidden(range: TextRange, announcement: String) = add(range, Kind.Hidden(announcement))

    fun ink(range: TextRange) = add(range, Kind.Ink)

    private fun add(range: TextRange, kind: Kind) {
        if (!range.isEmpty) marks.add(Mark(range.start, range.end, kind))
    }

    override fun deleteCharacters(range: TextRange) {
        val removed = range.length
        if (removed == 0) return
        text.delete(range.start, range.end)
        // A boundary before the cut stays, one after it moves back by the cut, and one inside it
        // lands where the cut began — so a span that was wholly inside vanishes, and one that
        // straddled an edge keeps whatever of it survived.
        fun moved(position: Int): Int = when {
            position <= range.start -> position
            position >= range.end -> position - removed
            else -> range.start
        }
        val iterator = marks.iterator()
        while (iterator.hasNext()) {
            val mark = iterator.next()
            mark.start = moved(mark.start)
            mark.end = moved(mark.end)
            if (mark.start >= mark.end) iterator.remove()
        }
    }

    override fun ink(index: Int): Boolean = marks.any { it.kind === Kind.Ink && index >= it.start && index < it.end }

    override fun inkRanges(): List<TextRange> =
        marks.filter { it.kind === Kind.Ink }.map { TextRange(it.start, it.end) }

    /**
     * The finished line.
     *
     * Links become `LinkAnnotation.Url`s in [linkStyles], which `Text` opens through the platform's
     * `UriHandler`. A spoiler becomes a string annotation (what `spoken` and the TalkBack actions
     * read) and, when [onToggleSpoiler] is given, a `LinkAnnotation.Clickable` that toggles it —
     * styled by nothing, so a box stays exactly the colours it was sent in. No callback (a screen
     * with nothing to toggle) leaves a spoiler hidden and untappable, which is iOS's rule for a
     * line that can't be revealed.
     *
     * [paragraph] covers the whole line: the indent `MessageText.spaced` sets.
     */
    fun toAnnotatedString(
        paragraph: ParagraphStyle?,
        linkStyles: TextLinkStyles?,
        onToggleSpoiler: ((Int) -> Unit)?,
    ): AnnotatedString = buildAnnotatedString {
        append(text.toString())
        for (mark in marks) {
            when (val kind = mark.kind) {
                is Kind.Style -> addStyle(kind.style, mark.start, mark.end)
                is Kind.Url -> addLink(LinkAnnotation.Url(kind.url, linkStyles), mark.start, mark.end)
                is Kind.Spoiler -> {
                    addStringAnnotation(MessageText.SPOILER_TAG, kind.ordinal.toString(), mark.start, mark.end)
                    if (onToggleSpoiler != null) {
                        val ordinal = kind.ordinal
                        addLink(
                            LinkAnnotation.Clickable("${MessageText.SPOILER_TAG}:$ordinal") { onToggleSpoiler(ordinal) },
                            mark.start,
                            mark.end,
                        )
                    }
                }
                is Kind.Hidden -> addStringAnnotation(MessageText.HIDDEN_TAG, kind.announcement, mark.start, mark.end)
                Kind.Ink -> Unit
            }
        }
        if (paragraph != null && length > 0) addStyle(paragraph, 0, length)
    }
}
