package com.helix.mic

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────
//  SESSIONS LIST
// ─────────────────────────────────────────────────────────────

@Composable
fun SessionsListScreen(
    sessionManager: SessionManager,
    onBack: () -> Unit,
    onSessionClick: (Session) -> Unit
) {
    var sessions by remember { mutableStateOf(sessionManager.loadSessions()) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showDeleteAllDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            containerColor = HelixTheme.Background,
            shape = HelixTheme.SharpShape,
            title = {
                Text(
                    "DELETE ALL SESSIONS?",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextPrimary,
                    letterSpacing = 2.sp
                )
            },
            text = {
                Text(
                    "This will permanently delete all ${sessions.size} saved sessions, transcripts, and audio recordings. This cannot be undone.",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary
                )
            },
            confirmButton = {
                Text(
                    "DELETE ALL",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.RecordingRed,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable {
                        sessionManager.deleteAllSessions()
                        sessions = emptyList()
                        showDeleteAllDialog = false
                        Toast.makeText(context, "All sessions deleted", Toast.LENGTH_SHORT).show()
                    }.padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "CANCEL",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable { showDeleteAllDialog = false }.padding(8.dp)
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header with session count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "<",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp)
            )
            Text(
                "SESSIONS",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = HelixTheme.TextSecondary,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "(${sessions.size})",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
            Spacer(modifier = Modifier.weight(1f))
            if (sessions.isNotEmpty()) {
                Text(
                    "DELETE ALL",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = HelixTheme.RecordingRed,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable {
                        showDeleteAllDialog = true
                    }.padding(4.dp)
                )
            }
        }
        HairlineDivider()

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "NO SESSIONS",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 3.sp
                )
            }
        } else {
            // Group sessions by relative date bucket
            val grouped = remember(sessions) { groupSessionsByDate(sessions) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                grouped.forEach { (bucket, sessionsInBucket) ->
                    item(key = "header-$bucket") {
                        Text(
                            bucket,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light,
                            color = HelixTheme.TextMuted,
                            letterSpacing = 3.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                        )
                        HairlineDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(sessionsInBucket, key = { it.id }) { session ->
                        SwipeToDeleteRow(
                            onDelete = {
                                sessionManager.deleteSession(session.id)
                                sessions = sessionManager.loadSessions()
                                Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            SessionRow(session = session, onClick = { onSessionClick(session) })
                        }
                        Spacer(modifier = Modifier.height(1.dp))
                        HairlineDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/** Group sessions into relative date buckets (TODAY / YESTERDAY / specific dates). */
private fun groupSessionsByDate(sessions: List<Session>): Map<String, List<Session>> {
    val now = Calendar.getInstance()
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterday = today - 24L * 60 * 60 * 1000
    val thisWeek = today - 7L * 24 * 60 * 60 * 1000
    val thisYear = Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

    val dayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    val monthDayFmt = SimpleDateFormat("MMM d", Locale.getDefault())
    val fullFmt = SimpleDateFormat("MMM d yyyy", Locale.getDefault())

    return sessions.groupBy { session ->
        val t = session.startTime
        when {
            t >= today -> "TODAY"
            t >= yesterday -> "YESTERDAY"
            t >= thisWeek -> dayFmt.format(Date(t)).uppercase()
            t >= thisYear -> monthDayFmt.format(Date(t)).uppercase()
            else -> fullFmt.format(Date(t)).uppercase()
        }
    }
}

@Composable
fun SessionRow(session: Session, onClick: () -> Unit) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        formatDuration((session.endTime - session.startTime) / 1000)
    }
    val preview = session.transcript.firstOrNull()?.text?.take(80) ?: "No transcript"
    val summaryPreview = session.summary?.let { stripMarkdown(it).take(100) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dateStr,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
            Text(
                durationStr,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
        }
        if (session.title != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                session.title,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                color = HelixTheme.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            preview,
            fontSize = 14.sp,
            color = if (session.title != null) HelixTheme.TextSecondary else HelixTheme.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp
        )
        if (summaryPreview != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                summaryPreview,
                fontSize = 12.sp,
                color = HelixTheme.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  SESSION DETAIL
// ─────────────────────────────────────────────────────────────

@Composable
fun SessionDetailScreen(
    session: Session,
    sessionManager: SessionManager,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        formatDuration((session.endTime - session.startTime) / 1000)
    }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var isEditing by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(session.title ?: "") }

    var activeTab by remember { mutableStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = HelixTheme.Background,
            shape = HelixTheme.SharpShape,
            title = {
                Text("DELETE SESSION?", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextPrimary, letterSpacing = 2.sp)
            },
            text = {
                Text(
                    "This will delete the transcript${if (session.hasAudio) " and audio recording" else ""}. This cannot be undone.",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextSecondary
                )
            },
            confirmButton = {
                Text(
                    "DELETE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.RecordingRed, letterSpacing = 2.sp,
                    modifier = Modifier.clickable {
                        sessionManager.deleteSession(session.id)
                        showDeleteDialog = false
                        Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                        onDeleted()
                    }.padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "CANCEL", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted, letterSpacing = 2.sp,
                    modifier = Modifier.clickable { showDeleteDialog = false }.padding(8.dp)
                )
            }
        )
    }
    val audioPlayer = remember { SessionAudioPlayer() }
    val isPlaying by audioPlayer.isPlaying.collectAsState()
    val progressMs by audioPlayer.progressMs.collectAsState()
    DisposableEffect(Unit) { onDispose { audioPlayer.release() } }
    val audioFile = remember(session.id) {
        if (session.hasAudio) SessionAudioRecorder.fileFor(context, session.id) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header with date, duration, and edit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "<",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                if (session.title != null) {
                    Text(
                        session.title,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                        color = HelixTheme.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    dateStr,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    durationStr,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted
                )
            }
            Text(
                "EDIT",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = HelixTheme.TextSecondary,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable { isEditing = true }.padding(4.dp)
            )
        }

        HairlineDivider()

        // Audio playback strip
        if (audioFile != null && audioFile.exists()) {
            val totalMs = remember(audioFile.length()) {
                (audioFile.length() * 1000L) / (16000L * 2L)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        if (isPlaying) audioPlayer.stop() else audioPlayer.play(audioFile)
                    },
                    shape = HelixTheme.SharpShape,
                    border = BorderStroke(0.5.dp, HelixTheme.TextPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                ) {
                    Text(
                        if (isPlaying) "STOP" else "PLAY",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AUDIO  ${formatDuration(totalMs / 1000)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val frac = if (totalMs > 0) (progressMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(HelixTheme.Hairline)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac)
                                .height(1.dp)
                                .background(HelixTheme.TextPrimary)
                        )
                    }
                }
            }
            HairlineDivider()
        }

        // Edit title dialog
        if (isEditing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HelixTheme.SurfaceSubtle)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    "SESSION TITLE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Untitled session", color = HelixTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    singleLine = true,
                    shape = HelixTheme.SharpShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HelixTheme.BorderDefault,
                        unfocusedBorderColor = HelixTheme.BorderDefault,
                        focusedTextColor = HelixTheme.TextPrimary,
                        unfocusedTextColor = HelixTheme.TextPrimary,
                        cursorColor = HelixTheme.TextPrimary
                    ),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = HelixTheme.TextPrimary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val newTitle = editTitle.ifBlank { null }
                            sessionManager.updateSessionTitle(session.id, newTitle)
                            isEditing = false
                            Toast.makeText(context, "Title updated", Toast.LENGTH_SHORT).show()
                            // Navigate back to refresh
                            onBack()
                        },
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                    ) {
                        Text("SAVE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                    }
                    OutlinedButton(
                        onClick = { isEditing = false; editTitle = session.title ?: "" },
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted)
                    ) {
                        Text("CANCEL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                    }
                }
            }
            HairlineDivider()
        }

        // Tab selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "TRANSCRIPT",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = if (activeTab == 0) HelixTheme.TextPrimary else HelixTheme.TextMuted,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable { activeTab = 0 }
            )
            Text(
                "STATS",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = if (activeTab == 1) HelixTheme.TextPrimary else HelixTheme.TextMuted,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable { activeTab = 1 }
            )
        }
        HairlineDivider()

        if (activeTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (session.summary != null) {
                    item {
                        SummarySection(summary = session.summary, modifier = Modifier.padding(bottom = 4.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(stripMarkdown(session.summary)))
                                Toast.makeText(context, "Summary copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(32.dp),
                            shape = HelixTheme.SharpShape,
                            border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("COPY SUMMARY", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val fullTranscript = session.transcript.joinToString("\n") { seg ->
                                    val elapsed = ((seg.timestamp - session.startTime) / 1000).coerceAtLeast(0)
                                    val h = elapsed / 3600
                                    val m = (elapsed % 3600) / 60
                                    val s = elapsed % 60
                                    val time = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
                                    val speaker = if (seg.speaker != null) " [${seg.speaker}]" else ""
                                    "[$time]$speaker ${seg.text}"
                                }
                                clipboardManager.setText(AnnotatedString(fullTranscript))
                                Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(32.dp),
                            shape = HelixTheme.SharpShape,
                            border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("COPY TRANSCRIPT", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HairlineDivider()
                }

                items(session.transcript) { segment ->
                    TranscriptLine(segment, sessionStartMs = session.startTime)
                }

                if (session.transcript.isEmpty()) {
                    item {
                        Text(
                            "No transcript recorded",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HelixTheme.TextMuted
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        } else {
            SessionStatsView(session = session, modifier = Modifier.weight(1f))
        }

        // Delete button at bottom
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { SessionExporter.shareSession(context, session) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = HelixTheme.SharpShape,
                border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
            ) {
                Text("EXPORT", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
            }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = HelixTheme.SharpShape,
                border = BorderStroke(0.5.dp, HelixTheme.RecordingRed.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.RecordingRed)
            ) {
                Text("DELETE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  SESSION STATS
// ─────────────────────────────────────────────────────────────

@Composable
fun SessionStatsView(session: Session, modifier: Modifier = Modifier) {
    val segments = session.transcript
    val timedSegments = segments.filter { it.inferenceMs > 0L }

    val totalSegments = segments.size
    val totalAudioMs = segments.sumOf { it.durationMs }
    val sessionDurationMs = session.endTime - session.startTime
    val totalWords = segments.sumOf { it.text.split("\\s+".toRegex()).size }
    val avgInferenceMs = if (timedSegments.isNotEmpty()) timedSegments.map { it.inferenceMs }.average() else 0.0
    val minInferenceMs = timedSegments.minOfOrNull { it.inferenceMs } ?: 0L
    val maxInferenceMs = timedSegments.maxOfOrNull { it.inferenceMs } ?: 0L
    val wpm = if (sessionDurationMs > 0) (totalWords * 60_000.0 / sessionDurationMs) else 0.0
    val totalInferenceMs = timedSegments.sumOf { it.inferenceMs }
    val totalTimedAudioMs = timedSegments.sumOf { it.durationMs }
    val rtf = if (totalTimedAudioMs > 0) totalInferenceMs.toDouble() / totalTimedAudioMs else 0.0

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (session.backendType != null) {
            item { StatRow("BACKEND", session.backendType.uppercase()) }
        }
        item { StatRow("SEGMENTS", "$totalSegments") }
        item { StatRow("WORDS", "$totalWords") }
        item { StatRow("SESSION", formatDuration(sessionDurationMs / 1000)) }
        item { StatRow("AUDIO", formatDuration(totalAudioMs / 1000)) }
        item { StatRow("WORDS / MIN", "%.1f".format(wpm)) }

        if (timedSegments.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "INFERENCE",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 3.sp
                )
            }
            item { StatRow("AVG", "${"%.0f".format(avgInferenceMs)} ms") }
            item { StatRow("MIN", "$minInferenceMs ms") }
            item { StatRow("MAX", "$maxInferenceMs ms") }
            item { StatRow("REAL-TIME FACTOR", "%.2f".format(rtf)) }
            item { StatRow("TIMED", "${timedSegments.size} / $totalSegments") }
        } else {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "NO INFERENCE TIMING DATA",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}
