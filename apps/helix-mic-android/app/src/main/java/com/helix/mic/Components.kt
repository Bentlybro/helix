package com.helix.mic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────
//  SHARED COMPONENTS
// ─────────────────────────────────────────────────────────────

@Composable
fun BackendOption(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, if (selected) HelixTheme.TextPrimary else HelixTheme.Hairline)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .border(
                    0.5.dp,
                    if (selected) HelixTheme.TextPrimary else HelixTheme.BorderDefault
                )
                .background(if (selected) HelixTheme.TextPrimary else Color.Transparent)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = if (selected) HelixTheme.TextPrimary else HelixTheme.TextSecondary,
                letterSpacing = 2.sp
            )
            Text(
                subtitle,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
        }
    }
}

@Composable
fun ModelDownloadControls(
    model: WhisperModel,
    state: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column {
        when (state) {
            is DownloadState.Ready -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "MODEL READY",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary,
                        letterSpacing = 2.sp
                    )
                    OutlinedButton(
                        onClick = onDelete,
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted)
                    ) {
                        Text("DELETE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    }
                }
            }
            is DownloadState.Downloading -> {
                Column {
                    Text(
                        "DOWNLOADING  ${state.percent}%",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(HelixTheme.Hairline)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(state.percent / 100f)
                                .height(2.dp)
                                .background(HelixTheme.TextPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted)
                    ) {
                        Text("CANCEL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    }
                }
            }
            is DownloadState.Failed -> {
                Column {
                    Text(
                        "DOWNLOAD FAILED",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.RecordingRed,
                        letterSpacing = 2.sp
                    )
                    Text(state.message, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                    ) {
                        Text("RETRY", fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    }
                }
            }
            DownloadState.Idle -> {
                OutlinedButton(
                    onClick = onDownload,
                    shape = HelixTheme.SharpShape,
                    border = BorderStroke(0.5.dp, HelixTheme.TextPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                ) {
                    Text(
                        "DOWNLOAD  ${model.approxMb} MB",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
    }
}

@Composable
fun RecordButton(
    isRecording: Boolean,
    canRecord: Boolean,
    pulseScale: Float,
    packetCount: Int,
    onRecord: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isRecording) {
            Text(
                "${packetCount}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // The button itself
        Box(
            modifier = Modifier
                .size(64.dp)
                .then(if (isRecording) Modifier.scale(pulseScale) else Modifier)
                .clip(CircleShape)
                .then(
                    if (isRecording) {
                        Modifier.background(HelixTheme.RecordingRed, CircleShape)
                    } else {
                        Modifier
                            .border(
                                0.5.dp,
                                if (canRecord) HelixTheme.TextPrimary else HelixTheme.TextMuted,
                                CircleShape
                            )
                            .background(Color.Transparent, CircleShape)
                    }
                )
                .clickable(enabled = canRecord, onClick = onRecord),
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                // Square stop icon
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(HelixTheme.Background, RoundedCornerShape(1.dp))
                )
            }
            // When not recording, the circle outline IS the icon
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            if (isRecording) "STOP" else "REC",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = if (isRecording) HelixTheme.RecordingRed else HelixTheme.TextMuted,
            letterSpacing = 3.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "DISCONNECT",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextMuted,
            letterSpacing = 2.sp,
            modifier = Modifier.clickable(onClick = onDisconnect).padding(8.dp)
        )
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HelixTheme.TextSecondary,
            letterSpacing = 1.sp
        )
        Text(
            value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HelixTheme.TextPrimary
        )
    }
}

@Composable
fun ToggleRow(label: String, subtitle: String, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextSecondary, letterSpacing = 2.sp)
            Text(subtitle, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            if (enabled) "ON" else "OFF",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) HelixTheme.Connected else HelixTheme.TextMuted,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun SummarySection(summary: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "SUMMARY",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextMuted,
            letterSpacing = 3.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        HairlineDivider()
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stripMarkdown(summary),
            fontSize = 13.sp,
            color = HelixTheme.TextSecondary,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val threshold = -200f

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Delete background
        if (offsetX < -20f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
                    .background(HelixTheme.RecordingRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    "DELETE",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.RecordingRed,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .offset(x = (offsetX / 3).dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < threshold) {
                                onDelete()
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceAtMost(0f)
                        }
                    )
                }
        ) {
            content()
        }
    }
}

fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

fun stripMarkdown(text: String): String {
    var result = text
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    result = result.replace(Regex("__(.+?)__"), "$1")
    result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
    result = result.replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "$1")
    result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    result = result.replace(Regex("^[\\-\\*]\\s+", RegexOption.MULTILINE), "  ")
    return result.trim()
}
