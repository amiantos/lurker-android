// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme

/**
 * The in-flight upload readout above the composer — lurker-ios's `UploadStatusView`: a capsule that
 * names the phase ("Compressing…", "2/4 · Uploading… 42%") with a spinner, and offers ✕.
 *
 * In the bar's own column rather than floating over the log as iOS's glass capsule does: the bar grows
 * by its height while a run is under way, and the newest line stays above it instead of under it.
 */
@Composable
fun UploadStatusView(readout: UploadReadout?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    // The last readout is held through the exit, so the capsule slides away showing what it last said
    // rather than going blank first.
    val held = remember { arrayOfNulls<UploadReadout>(1) }
    if (readout != null) held[0] = readout
    AnimatedVisibility(
        visible = readout != null,
        modifier = modifier,
        // Rises just from below its spot, as iOS's slides up from over the composer.
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
    ) {
        held[0]?.let { ReadoutCapsule(it, onCancel) }
    }
}

@Composable
private fun ReadoutCapsule(readout: UploadReadout, onCancel: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .wrapContentWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                .padding(start = 16.dp, end = if (readout.showsCancel) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = muted, strokeWidth = 2.dp)
            Text(
                readout.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = LurkerTheme.colors.fg,
                maxLines = 1,
                // The MIDDLE, not the tail: a long provider name ("Sending to Some Long Name… 42%") at a
                // large font scale can outgrow the capsule, and a tail cut would eat the percentage —
                // the one part that changes and the part being watched.
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 10.dp)
                    // Read when focused, NOT a live region — as iOS's label isn't one. The percentage moves
                    // every few hundred milliseconds, and a live region would queue "Uploading… 41%",
                    // "42%"… for as long as the upload runs, over whatever the reader is doing (#20).
                    .clearAndSetSemantics { contentDescription = readout.accessibility },
            )
            if (readout.showsCancel) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(role = Role.Button, onClick = onCancel)
                        .clearAndSetSemantics {
                            contentDescription = "Cancel upload"
                            role = Role.Button
                            onClick { onCancel(); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LurkerIcons.Close, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * The paperclip, and the menu of its two sources — iOS's action sheet of Photo Library / Files /
 * Cancel; Android's dropdown, where dismissing is the cancel. Off while a run is under way.
 */
@Composable
fun AttachButton(attachments: Attachments, size: Dp) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = !attachments.busy
    val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LurkerTheme.colors.fgMuted.copy(alpha = 0.5f)
    Box {
        Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .clickable(enabled = enabled, role = Role.Button) { expanded = true }
                .clearAndSetSemantics {
                    contentDescription = "Attach"
                    role = Role.Button
                    if (enabled) onClick { expanded = true; true } else disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(LurkerIcons.AttachFile, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Photo Library") },
                leadingIcon = { Icon(LurkerIcons.PhotoLibrary, contentDescription = null) },
                onClick = {
                    expanded = false
                    attachments.pickPhotos()
                },
            )
            DropdownMenuItem(
                text = { Text("Files") },
                leadingIcon = { Icon(LurkerIcons.InsertDriveFile, contentDescription = null) },
                onClick = {
                    expanded = false
                    attachments.pickFiles()
                },
            )
        }
    }
}

// MARK: - Previews

@Composable
private fun ReadoutPreview(dark: Boolean, readout: UploadReadout) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(LurkerTheme.colors.bg).padding(16.dp)) {
            ReadoutCapsule(readout, onCancel = {})
        }
    }
}

private val previewUploading = UploadReadout(UploadPhase.Uploading(0.42), UploadBatchPosition(2, 4))
private val previewSending = UploadReadout(UploadPhase.Sending(0.7, "Some Long Provider Name"), null)
private val previewStopping = UploadReadout(UploadPhase.Stopping, null, stopping = true)

@Preview(name = "Readout, uploading 2 of 4 — light", widthDp = 360)
@Composable
private fun UploadingLight() = ReadoutPreview(dark = false, readout = previewUploading)

@Preview(name = "Readout, uploading 2 of 4 — dark", widthDp = 360)
@Composable
private fun UploadingDark() = ReadoutPreview(dark = true, readout = previewUploading)

@Preview(name = "Readout, sending to a provider — light", widthDp = 360)
@Composable
private fun SendingLight() = ReadoutPreview(dark = false, readout = previewSending)

@Preview(name = "Readout, sending to a provider — dark", widthDp = 360)
@Composable
private fun SendingDark() = ReadoutPreview(dark = true, readout = previewSending)

@Preview(name = "Readout, stopping — light", widthDp = 360)
@Composable
private fun StoppingLight() = ReadoutPreview(dark = false, readout = previewStopping)

@Preview(name = "Readout, stopping — dark", widthDp = 360)
@Composable
private fun StoppingDark() = ReadoutPreview(dark = true, readout = previewStopping)
