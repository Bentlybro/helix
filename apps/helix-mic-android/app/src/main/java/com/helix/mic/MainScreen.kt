package com.helix.mic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────
//  TOP BAR
// ─────────────────────────────────────────────────────────────

@Composable
fun TopBar(
    isRecording: Boolean,
    elapsedSeconds: Long,
    onSettingsClick: () -> Unit,
    onSessionsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "HELIX MIC",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextSecondary,
            letterSpacing = 3.sp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isRecording) {
                Text(
                    text = formatDuration(elapsedSeconds),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.RecordingRed
                )
            }
            Text(
                "SESSIONS",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = HelixTheme.TextMuted,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onSessionsClick).padding(4.dp)
            )
            Text(
                "SETTINGS",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = HelixTheme.TextMuted,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onSettingsClick).padding(4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  STATUS LINE (connected)
// ─────────────────────────────────────────────────────────────

@Composable
fun StatusLine(
    batteryPercent: Int?,
    batteryMillivolts: Int?,
    isRecording: Boolean,
    elapsedSeconds: Long,
    isWsConnected: Boolean,
    backendLabel: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "CONNECTED",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = HelixTheme.Connected,
            letterSpacing = 2.sp
        )
        val batLabel = when {
            batteryPercent != null && batteryMillivolts != null && batteryMillivolts > 0 ->
                "BAT ${batteryPercent}%  ${"%.2f".format(batteryMillivolts / 1000f)}V"
            batteryPercent != null -> "BAT ${batteryPercent}%"
            else -> "BAT --"
        }
        val isLowBattery = batteryPercent != null && batteryPercent < 20
        val isCriticalBattery = batteryPercent != null && batteryPercent < 10
        Text(
            batLabel,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = when {
                isCriticalBattery -> HelixTheme.RecordingRed
                isLowBattery -> HelixTheme.TextPrimary
                else -> HelixTheme.TextMuted
            }
        )
        if (backendLabel != null) {
            Text(
                backendLabel,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted,
                letterSpacing = 1.sp
            )
        }
        if (isRecording) {
            Text(
                "REC ${formatDuration(elapsedSeconds)}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.RecordingRed,
                letterSpacing = 1.sp
            )
        }
        if (isWsConnected && !isRecording) {
            Text(
                "LIVE",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted,
                letterSpacing = 2.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  LIVE TRANSCRIPT
// ─────────────────────────────────────────────────────────────

@Composable
fun LiveTranscriptView(
    segments: List<TranscriptSegment>,
    partialText: String,
    error: String?,
    isRecording: Boolean,
    isConnected: Boolean,
    sessionStartMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val itemCount = segments.size + (if (partialText.isNotEmpty()) 1 else 0)

    LaunchedEffect(segments.size, partialText) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (segments.isEmpty() && partialText.isEmpty() && error == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRecording) {
                        if (isConnected) "LISTENING" else "CONNECTING"
                    } else "TAP RECORD TO BEGIN",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 3.sp
                )
                if (isRecording && !isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = HelixTheme.TextMuted,
                        strokeWidth = 1.dp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(segments) { segment ->
                    TranscriptLine(segment, sessionStartMs = sessionStartMs)
                }

                if (partialText.isNotEmpty()) {
                    item {
                        Text(
                            text = partialText,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                            color = HelixTheme.TextMuted,
                            lineHeight = 22.sp
                        )
                    }
                }

                if (error != null) {
                    item {
                        Text(
                            error,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HelixTheme.RecordingRed,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TranscriptLine(segment: TranscriptSegment, sessionStartMs: Long = 0L) {
    val timeStr = remember(segment.timestamp, sessionStartMs) {
        if (sessionStartMs > 0) {
            val elapsed = ((segment.timestamp - sessionStartMs) / 1000).coerceAtLeast(0)
            val h = elapsed / 3600
            val m = (elapsed % 3600) / 60
            val s = elapsed % 60
            if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        } else {
            SimpleDateFormat("mm:ss", Locale.getDefault()).format(Date(segment.timestamp))
        }
    }
    var showActions by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    if (showActions) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showActions = false },
            containerColor = HelixTheme.Background,
            shape = HelixTheme.SharpShape,
            title = { Text("SEGMENT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextPrimary, letterSpacing = 2.sp) },
            text = {
                Text(
                    segment.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                Row {
                    Text(
                        "COPY", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextPrimary, letterSpacing = 2.sp,
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(segment.text))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            showActions = false
                        }.padding(8.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        "SHARE", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextPrimary, letterSpacing = 2.sp,
                        modifier = Modifier.clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, segment.text)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share segment"))
                            showActions = false
                        }.padding(8.dp)
                    )
                }
            },
            dismissButton = {
                Text(
                    "CANCEL", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted, letterSpacing = 2.sp,
                    modifier = Modifier.clickable { showActions = false }.padding(8.dp)
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { showActions = true }
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[${timeStr}]",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
            if (segment.speaker != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = segment.speaker,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    color = HelixTheme.TextSecondary
                )
            }
            if (segment.inferenceMs > 0L) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${"%.1f".format(segment.inferenceMs / 1000.0)}s",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = segment.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = HelixTheme.TextPrimary,
            lineHeight = 22.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
