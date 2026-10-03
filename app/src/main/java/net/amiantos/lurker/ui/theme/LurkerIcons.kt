// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few glyphs the app draws, as vectors.
 *
 * material3 stopped depending on `material-icons-core` (1.4), so there is no `Icons.Default` in
 * this build, and `material-icons-extended` is a multi-megabyte dependency for a handful of
 * shapes. The path data is Material's own (`edit`, `more_vert`, `add`, `close`, `arrow_back`,
 * `keyboard`, `visibility`, `visibility_off`, `search`, `warning`, `keyboard_arrow_up`,
 * `keyboard_arrow_down`, `language`, `chevron_right`, `check`, `arrow_drop_down`, `remove`,
 * `arrow_upward`, `cancel`, `info_outline`, `group`, `account_circle`, `content_copy`, `refresh`,
 * `chat_bubble_outline`, `tune`, `list`, `help_outline`, `more_horiz`, `check_circle_outline`,
 * `security`, `memory`, `wifi_tethering` — Apache-2.0), so a
 * glyph here is the one every Android user already reads. Tinted by the `Icon` that draws it.
 */
object LurkerIcons {
    /** A draft waiting in a buffer's composer — the web's pencil (lurker-ios#188). */
    val Pencil: ImageVector by lazy {
        icon(
            "Pencil",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25z" +
                "M20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0" +
                "l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )
    }

    /** An overflow menu — "More". */
    val MoreVert: ImageVector by lazy {
        icon(
            "MoreVert",
            "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z" +
                "M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
                "M12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }

    /** "Add" — the buffer list's "+" (joins and networks) and the networks screen's. */
    val Add: ImageVector by lazy { icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }

    /** Close a full-screen dialog from its root page (Material's full-screen dialog pattern). */
    val Close: ImageVector by lazy {
        icon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        )
    }

    /**
     * Back: from a conversation to the buffer list on a phone, and from a page pushed inside a
     * full-screen dialog. Mirrored right-to-left, as the platform's own back arrow is.
     */
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true)
    }

    /**
     * The typing line's lead-in (lurker-ios#61) — iOS's `keyboard` symbol, drawn inline as the
     * line's first glyph. See `MessageText.renderCompactTyping`.
     */
    val Keyboard: ImageVector by lazy {
        icon(
            "Keyboard",
            "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z" +
                "M11,8h2v2h-2zM11,11h2v2h-2zM8,8h2v2H8zM8,11h2v2H8zM7,13H5v-2h2v2zM7,10H5V8h2v2z" +
                "M16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z",
        )
    }

    /** A masked password field's reveal toggle, while the text is hidden. */
    val Visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
                "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
                "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    /** A masked password field's reveal toggle, while the text is shown. */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75" +
                " -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
                "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3" +
                " 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
                "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03" +
                " 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
                "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
        )
    }

    /** A search field's leading glyph — the network picker's. */
    val Search: ImageVector by lazy {
        icon(
            "Search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5" +
                " 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z" +
                "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }

    /** The unread banner's lead-in — iOS's `chevron.up`: unread messages are up there (U2b). */
    val KeyboardArrowUp: ImageVector by lazy {
        icon("KeyboardArrowUp", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z")
    }

    /** The jump-to-latest pill — iOS's `chevron.down`: back down to the newest message (U2b). */
    val KeyboardArrowDown: ImageVector by lazy {
        icon("KeyboardArrowDown", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    }

    /** A refusal pinned in a form — iOS's `exclamationmark.triangle.fill` beside the reason. */
    val Warning: ImageVector by lazy { icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z") }

    /** Settings' Networks row — iOS's `network` globe, as Material's `language`. */
    val Language: ImageVector by lazy {
        icon(
            "Language",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                "M18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56z" +
                "M12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96z" +
                "M4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2" +
                " 0,0.68 0.06,1.34 0.14,2L4.26,14z" +
                "M5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56z" +
                "M8.03,8L5.08,8c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8z" +
                "M12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96z" +
                "M14.34,14L9.66,14c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68" +
                "c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2z" +
                "M14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56z" +
                "M16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38" +
                "c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z",
        )
    }

    /** A row that goes somewhere — iOS's disclosure indicator. Mirrored right-to-left. */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z", autoMirror = true)
    }

    /** The choice in force in a pull-down — iOS's menu checkmark. */
    val Check: ImageVector by lazy { icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }

    /** A row whose value opens a menu of choices — Material's exposed dropdown arrow. */
    val ArrowDropDown: ImageVector by lazy { icon("ArrowDropDown", "M7,10l5,5 5,-5z") }

    /** A stepper's decrement — its increment is [Add]. */
    val Remove: ImageVector by lazy { icon("Remove", "M19,13H5v-2h14v2z") }

    /** The composer's send button — iOS's `arrow.up`. */
    val ArrowUpward: ImageVector by lazy {
        icon("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }

    /** Cancel the pending reply — iOS's `xmark.circle.fill`. */
    val Cancel: ImageVector by lazy {
        icon(
            "Cancel",
            "M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2z" +
                "M17,15.59L15.59,17 12,13.41 8.41,17 7,15.59 10.59,12 7,8.41 8.41,7 12,10.59 15.59,7 17,8.41 13.41,12 17,15.59z",
        )
    }

    // U5 — the conversation's bar, and the member list, profile and buffer info pages behind it.

    /** The conversation's info button — iOS's `info.circle` (U5). */
    val Info: ImageVector by lazy {
        icon(
            "Info",
            "M11,7h2v2h-2zM11,11h2v6h-2z" +
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
        )
    }

    /** The member list — the bar's members button and the info page's Members row (`person.2`). */
    val Group: ImageVector by lazy {
        icon(
            "Group",
            "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3z" +
                "M8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3z" +
                "M8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z" +
                "M16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
        )
    }

    /** A DM's Whois row — iOS's `person.crop.circle`. */
    val AccountCircle: ImageVector by lazy {
        icon(
            "AccountCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,5c1.66,0 3,1.34 3,3s-1.34,3 -3,3 -3,-1.34 -3,-3 1.34,-3 3,-3z" +
                "M12,19.2c-2.5,0 -4.71,-1.28 -6,-3.22 0.03,-1.99 4,-3.08 6,-3.08 1.99,0 5.97,1.09 6,3.08" +
                " -1.29,1.94 -3.5,3.22 -6,3.22z",
        )
    }

    /** A copyable value — the profile's hostmask (`doc.on.doc`). */
    val ContentCopy: ImageVector by lazy {
        icon(
            "ContentCopy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1z" +
                "M19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z" +
                "M19,21H8V7h11v14z",
        )
    }

    /** Ask again — the profile's Refresh (`arrow.clockwise`). */
    val Refresh: ImageVector by lazy {
        icon(
            "Refresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6" +
                "h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78" +
                "L13,11h7V4l-2.35,2.35z",
        )
    }

    /** The profile's Send Message (`bubble.left`). */
    val ChatBubble: ImageVector by lazy {
        icon(
            "ChatBubble",
            "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM20,16H6l-2,2V4h16v12z",
        )
    }

    /** The info page's Channel Settings row (`slider.horizontal.3`). */
    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7z" +
                "M21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
        )
    }

    /** A channel's list modes — bans and the rest (`list.bullet`). */
    val ListBullet: ImageVector by lazy {
        icon("ListBullet", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z")
    }

    /** The profile's "isn't on this network" line, and a help-op flag (`questionmark.circle`). */
    val HelpOutline: ImageVector by lazy {
        icon(
            "HelpOutline",
            "M11,18h2v-2h-2v2z" +
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z" +
                "M12,6c-2.21,0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5" +
                " 0,-2.21 -1.79,-4 -4,-4z",
        )
    }

    /** The profile's "Looking up…" line (`ellipsis.circle`). */
    val MoreHoriz: ImageVector by lazy {
        icon(
            "MoreHoriz",
            "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
                "M18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
                "M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }

    /** A whois flag: the nick is registered (`checkmark.seal`). */
    val CheckCircle: ImageVector by lazy {
        icon(
            "CheckCircle",
            "M16.59,7.58L10,14.17l-3.59,-3.58L5,12l5,5 8,-8z" +
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        )
    }

    /** A whois flag: an IRC operator (`shield.lefthalf.filled`). */
    val Shield: ImageVector by lazy {
        icon(
            "Shield",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z" +
                "M12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z",
        )
    }

    /** A whois flag: a bot (`gearshape.2`, as Material's `memory` chip). */
    val Memory: ImageVector by lazy {
        icon(
            "Memory",
            "M15,9H9v6h6V9zM13,13h-2v-2h2v2z" +
                "M21,11V9h-2V7c0,-1.1 -0.9,-2 -2,-2h-2V3h-2v2h-2V3H9v2H7c-1.1,0 -2,0.9 -2,2v2H3v2h2v2H3v2h2v2" +
                "c0,1.1 0.9,2 2,2h2v2h2v-2h2v2h2v-2h2c1.1,0 2,-0.9 2,-2v-2h2v-2h-2v-2h2zM17,17H7V7h10v10z",
        )
    }

    /** The profile's relay-bot mark (`antenna.radiowaves.left.and.right`). */
    val Antenna: ImageVector by lazy {
        icon(
            "Antenna",
            "M12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
                "M18,13c0,-3.31 -2.69,-6 -6,-6s-6,2.69 -6,6c0,2.22 1.21,4.15 3,5.19l1,-1.74c-1.19,-0.7 -2,-1.97 -2,-3.45" +
                " 0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.48 -0.81,2.75 -2,3.45l1,1.74c1.79,-1.04 3,-2.97 3,-5.19z" +
                "M12,3C6.48,3 2,7.48 2,13c0,3.7 2.01,6.92 4.99,8.65l1,-1.73C5.61,18.53 4,15.96 4,13c0,-4.42 3.58,-8 8,-8" +
                "s8,3.58 8,8c0,2.96 -1.61,5.53 -4,6.92l1,1.73c2.99,-1.73 5,-4.95 5,-8.65 0,-5.52 -4.48,-10 -10,-10z",
        )
    }

    private fun icon(name: String, path: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
