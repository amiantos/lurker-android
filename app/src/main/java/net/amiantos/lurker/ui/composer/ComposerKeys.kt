// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

/**
 * What the composer does with a key (lurker-android#63, #64), with no Compose in it so the rules
 * can be tested: the field's `onPreviewKeyEvent` maps the event down to plain values, asks
 * [onKey], and does what it says.
 *
 *  - **Enter** (or the numpad's) sends from a hardware keyboard, whatever the "Enter to send"
 *    setting says and whatever other modifier is held, as on the web. Held down, it sends once: a
 *    repeat would send whatever came back into the field after it (a refused line, restored).
 *  - **Shift+Enter** inserts a newline, by the composer's own hand. ⚠ Not left to the field: Compose's
 *    key mapping has no command for a SHIFTED Enter (`commonKeyMapping` maps `Key.Enter` to `NEW_LINE`
 *    only unmodified) and its typed-character path drops the control character, so passed on it
 *    would type nothing — and with plain Enter sending, a hardware keyboard would have no newline.
 *  - An Enter that comes as a key event from the ON-SCREEN keyboard (some send a real
 *    `KEYCODE_ENTER` rather than text or an editor action) follows the setting: it sends when
 *    "Enter to send" is on and is left to the field — a newline — when it's off.
 *  - **Tab** completes in place (`TabCompletion`), **Shift+Tab** backwards. Always taken, even when
 *    nothing matches, so it never moves focus or types a tab character. From either keyboard: the
 *    on-screen kind is only told apart for Enter, the one key with a setting.
 *  - **Escape** cancels a pending reply, and only then.
 *
 * ⚠ An ON-SCREEN key during an IME composition is the IME's (Enter commits the word), and is left
 * alone. A HARDWARE key isn't gated on the composing region at all: Android hands a physical key to
 * the IME before the app (ViewRootImpl's pre-IME → IME → post-IME stages; `onPreviewKeyEvent` runs
 * post-IME), so a CJK IME that is composing consumes Enter itself and the composer never sees it. A
 * hardware key that does arrive has been passed on by the IME — and Gboard keeps a composing region
 * on the word being typed even in Latin, so gating on it would make Enter a newline mid-word.
 *
 * Holds one piece of state: which keys it took the KeyDown of, so their KeyUp is taken too. A KeyUp
 * let through for a key the composer already acted on would reach whatever handles the release —
 * focus traversal, a clickable — as half a key press.
 */
internal class ComposerKeys {

    /** A key the composer cares about. The numpad's Enter is [Enter]. */
    enum class Key { Enter, Tab, Escape, Other }

    enum class Action {
        /** Not ours — let the field (or the IME, or focus) have it. */
        Pass,

        /** Send, through the Send button's own path and checks: an unsendable draft does nothing. */
        Send,

        /** `TabCompletion`, forward. */
        Complete,

        /** `TabCompletion`, backward. */
        CompleteBackward,

        /** Insert a newline at the caret (Shift+Enter). */
        Newline,

        CancelReply,

        /** The release of a key whose press was ours. */
        Swallow,
    }

    /** One key event, as plain values. */
    data class Event(
        val key: Key,
        /** KeyDown; false is KeyUp. */
        val down: Boolean,
        /** Shift. Any other modifier changes nothing for Enter: Ctrl+Enter sends, as on the web. */
        val shift: Boolean = false,
        /**
         * Ctrl, Alt or Meta. Tab with one is someone else's — Ctrl+Tab switches things on ChromeOS and
         * DeX — so it's passed on rather than completing into the draft.
         */
        val otherModifier: Boolean = false,
        /** From a physical keyboard — never the on-screen one, even when it sends real key events. */
        val hardware: Boolean = true,
        val composing: Boolean = false,
        /** The device's "Enter to send" (`UiPreferences.composerEnterSends`). */
        val enterSends: Boolean = false,
        val replyPending: Boolean = false,
    )

    private val taken = mutableSetOf<Key>()

    fun onKey(event: Event): Action {
        if (!event.down) return if (taken.remove(event.key)) Action.Swallow else Action.Pass
        // A held Enter repeats its KeyDown; it already acted on the first. Tab's repeats still cycle.
        if (event.key == Key.Enter && event.key in taken) return Action.Swallow
        val action = decide(event)
        if (action != Action.Pass) taken.add(event.key)
        return action
    }

    private fun decide(event: Event): Action {
        if (event.composing && !event.hardware) {
            // The IME sent it as a key: Enter and the rest are its own business mid-word. Tab is
            // swallowed rather than passed, or the multi-line field would type a tab character.
            return if (event.key == Key.Tab) Action.Swallow else Action.Pass
        }
        return when (event.key) {
            Key.Enter -> when {
                event.shift -> Action.Newline
                event.hardware || event.enterSends -> Action.Send
                else -> Action.Pass
            }
            Key.Tab -> when {
                event.otherModifier -> Action.Pass
                event.shift -> Action.CompleteBackward
                else -> Action.Complete
            }
            Key.Escape -> if (event.replyPending) Action.CancelReply else Action.Pass
            Key.Other -> Action.Pass
        }
    }
}
