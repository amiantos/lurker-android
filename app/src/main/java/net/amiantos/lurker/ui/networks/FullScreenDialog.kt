// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme

/**
 * A full-screen dialog — where iOS presents a large sheet (the networks list, Add Network, Join
 * Channel), Material's full-screen dialog pattern.
 *
 * Edge to edge on a phone. On a wide window (600dp and up) the content is capped at 560dp and
 * centred, the window's own dim behind it serving as the scrim — a sheet a tablet's width would be a
 * form stretched past reading. Taps outside it are deliberately inert: a stray tap beside a
 * half-typed form must not throw it away.
 *
 * Back is the dialog's: a page that wants it first (a pushed page popping) installs its own
 * `BackHandler`, which registers after the dialog's and so runs first.
 */
@Composable
internal fun FullScreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // Said, not left to the content happening to fill the window: a stray tap beside the
            // centred sheet must never throw away a half-typed form.
            dismissOnClickOutside = false,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE
            SystemBarIcons(darkIcons = !wide && !LurkerTheme.colors.isDark)
            if (wide) {
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxHeight(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.background,
                    ) { content() }
                }
            } else {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }
}

/**
 * The dialog window's status and navigation bar icons. It's a window of its own, edge to edge, so it
 * doesn't inherit the activity's: left alone, a light theme's page would sit under white icons. Dark
 * icons over a light page; light ones over a dark page, or over the dim around a centred dialog.
 */
@Composable
private fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

/** The window width from which a full-screen dialog becomes a centred, capped one. */
private val WIDE = 600.dp

/** How a dialog page leaves: closing the whole dialog from its root, or back to the page under it. */
internal enum class PageExit { Close, Back }

/**
 * One page of a full-screen dialog: a top app bar with the way out on the left (✕ on a root, ← on a
 * pushed page), the title, and the confirming verb as a text button on the right; then the content,
 * handed the padding that clears the bar, the system bars and the keyboard.
 *
 * ⚠ The content's insets include the IME. The dialog's window doesn't resize for the keyboard
 * (`decorFitsSystemWindows = false` asks for `ADJUST_NOTHING`), so a form that didn't pad for it
 * would put its lower fields under the keyboard with no way to scroll them out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialogPage(
    title: String,
    exit: PageExit,
    onExit: () -> Unit,
    confirmTitle: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        when (exit) {
                            PageExit.Close -> Icon(LurkerIcons.Close, contentDescription = "Close")
                            PageExit.Back -> Icon(LurkerIcons.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    actions()
                    if (confirmTitle != null) {
                        TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmTitle) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding -> content(padding) }
}
