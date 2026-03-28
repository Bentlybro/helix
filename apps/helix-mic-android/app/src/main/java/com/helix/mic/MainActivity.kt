package com.helix.mic

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: HelixMicBleManager
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sessionManager: SessionManager
    private var transcriptionService: TranscriptionService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = HelixMicBleManager(this)
        sharedPrefs = getSharedPreferences("helix_mic_prefs", Context.MODE_PRIVATE)
        sessionManager = SessionManager(sharedPrefs)
        enableEdgeToEdge()
        setContent { HelixMicApp(bleManager, sharedPrefs, sessionManager) }
    }

    override fun onDestroy() {
        super.onDestroy()
        transcriptionService?.destroy()
        bleManager.disconnect()
    }
}

enum class Screen { MAIN, SETTINGS, SESSIONS, SESSION_DETAIL }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HelixMicApp(
    bleManager: HelixMicBleManager,
    sharedPrefs: SharedPreferences,
    sessionManager: SessionManager
) {
    val connectionState by bleManager.connectionState.collectAsState()
    val batteryPercent by bleManager.batteryPercent.collectAsState()
    val isReceivingAudio by bleManager.isReceivingAudio.collectAsState()
    val packetCount by bleManager.packetCount.collectAsState()

    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    var apiKey by remember { mutableStateOf(sharedPrefs.getString("openai_api_key", "") ?: "") }

    // Session state
    var isRecording by remember { mutableStateOf(false) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var sessionStartTime by remember { mutableStateOf(0L) }
    var summary by remember { mutableStateOf<String?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }
    var sessionSaved by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }

    val context = LocalContext.current

    // Voice profile manager
    val voiceProfileManager = remember { VoiceProfileManager(context) }
    var isVoiceEnrolled by remember { mutableStateOf(voiceProfileManager.isEnrolled) }

    // TranscriptionService — now uses Realtime WebSocket
    val transcriptionService = remember(apiKey) {
        if (apiKey.isNotBlank()) {
            TranscriptionService(apiKey).also {
                it.voiceProfileManager = voiceProfileManager
            }
        } else null
    }

    val segments by (transcriptionService?.segments ?: MutableStateFlow(emptyList())).collectAsState()
    val partialText by (transcriptionService?.partialText ?: MutableStateFlow("")).collectAsState()
    val isConnected by (transcriptionService?.isConnected ?: MutableStateFlow(false)).collectAsState()
    val transcriptionError by (transcriptionService?.error ?: MutableStateFlow<String?>(null)).collectAsState()

    val scope = rememberCoroutineScope()

    // Collect BLE audio frames and feed to TranscriptionService
    LaunchedEffect(transcriptionService, isRecording) {
        if (transcriptionService != null && isRecording) {
            bleManager.audioFrames.collect { frame ->
                transcriptionService.feedAudio(frame)
            }
        }
    }

    val blePermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    )

    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Session timer
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(isRecording, sessionStartTime) {
        if (isRecording && sessionStartTime > 0) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF1E1E1E),
            primary = Color(0xFFFF4444),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFAAAAAA),
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (currentScreen) {
                Screen.SETTINGS -> {
                    SettingsScreen(
                        apiKey = apiKey,
                        onApiKeyChange = { newKey ->
                            apiKey = newKey
                            sharedPrefs.edit().putString("openai_api_key", newKey).apply()
                        },
                        voiceProfileManager = voiceProfileManager,
                        isVoiceEnrolled = isVoiceEnrolled,
                        onEnrollmentChanged = { isVoiceEnrolled = it },
                        onBack = { currentScreen = Screen.MAIN }
                    )
                }
                Screen.SESSIONS -> {
                    SessionsListScreen(
                        sessionManager = sessionManager,
                        onBack = { currentScreen = Screen.MAIN },
                        onSessionClick = { session ->
                            selectedSession = session
                            currentScreen = Screen.SESSION_DETAIL
                        }
                    )
                }
                Screen.SESSION_DETAIL -> {
                    selectedSession?.let { session ->
                        SessionDetailScreen(
                            session = session,
                            onBack = { currentScreen = Screen.SESSIONS }
                        )
                    } ?: run { currentScreen = Screen.SESSIONS }
                }
                Screen.MAIN -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        // ── Top Bar ──
                        TopStatusBar(
                            connectionState = connectionState,
                            batteryPercent = batteryPercent,
                            isRecording = isRecording,
                            elapsedSeconds = elapsedSeconds,
                            isWsConnected = isConnected,
                            onSettingsClick = { currentScreen = Screen.SETTINGS },
                            onSessionsClick = { currentScreen = Screen.SESSIONS }
                        )

                        // ── Connection button (when disconnected) ──
                        if (connectionState != ConnectionState.CONNECTED) {
                            Spacer(modifier = Modifier.weight(1f))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when (connectionState) {
                                        ConnectionState.DISCONNECTED -> "No device connected"
                                        ConnectionState.SCANNING -> "Scanning for Helix Mic..."
                                        ConnectionState.CONNECTING -> "Connecting..."
                                        else -> ""
                                    },
                                    fontSize = 16.sp,
                                    color = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                if (!blePermissions.allPermissionsGranted) {
                                    Button(
                                        onClick = { blePermissions.launchMultiplePermissionRequest() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFFF4444)
                                        )
                                    ) { Text("Grant Bluetooth Permissions", color = Color.White) }
                                } else {
                                    Button(
                                        onClick = {
                                            if (connectionState == ConnectionState.DISCONNECTED) {
                                                bleManager.startScan()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = connectionState == ConnectionState.DISCONNECTED,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFFF4444)
                                        )
                                    ) {
                                        Text(
                                            when (connectionState) {
                                                ConnectionState.SCANNING -> "Scanning..."
                                                ConnectionState.CONNECTING -> "Connecting..."
                                                else -> "Connect to Helix Mic"
                                            },
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            // ── Connected: Main content ──

                            // Live transcript area
                            LiveTranscriptView(
                                segments = segments,
                                partialText = partialText,
                                error = transcriptionError,
                                isRecording = isRecording,
                                isConnected = isConnected,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )

                            // Summary card (shown after stopping)
                            if (!isRecording && summary != null) {
                                SummaryCard(
                                    summary = summary!!,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }

                            if (isSummarizing) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color(0xFFFF4444),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Generating summary...", fontSize = 13.sp, color = Color.Gray)
                                }
                            }

                            // Save button (after session ends with transcript)
                            if (!isRecording && segments.isNotEmpty() && !sessionSaved) {
                                Button(
                                    onClick = {
                                        val sessionId = currentSessionId
                                        if (sessionId != null) {
                                            sessionManager.stopSession(sessionId, segments.toList(), summary)
                                            sessionSaved = true
                                            Toast.makeText(context, "Session saved", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2A2A2A)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("💾 Save Session", color = Color.White, fontSize = 14.sp)
                                }
                            }

                            if (!isRecording && sessionSaved) {
                                Text(
                                    "✓ Session saved",
                                    fontSize = 13.sp,
                                    color = Color(0xFF00FF88),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }

                            // ── Bottom Bar ──
                            BottomControlBar(
                                isRecording = isRecording,
                                canRecord = apiKey.isNotBlank(),
                                pulseScale = pulseScale,
                                packetCount = packetCount,
                                onRecord = {
                                    if (apiKey.isBlank()) {
                                        Toast.makeText(context, "Set API key in settings first", Toast.LENGTH_SHORT).show()
                                        return@BottomControlBar
                                    }
                                    if (!isRecording) {
                                        // Start recording
                                        summary = null
                                        sessionSaved = false
                                        transcriptionService?.start()
                                        bleManager.resetPacketCount()
                                        currentSessionId = sessionManager.startSession()
                                        sessionStartTime = System.currentTimeMillis()
                                        isRecording = true
                                    } else {
                                        // Stop recording
                                        isRecording = false
                                        transcriptionService?.stop()
                                        val finalSegments = segments.toList()

                                        // Generate summary
                                        val fullText = finalSegments.joinToString(" ") { it.text }
                                        if (fullText.isNotBlank()) {
                                            isSummarizing = true
                                            scope.launch(Dispatchers.IO) {
                                                val client = SummaryClient(apiKey)
                                                val result = client.summarize(fullText)
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    isSummarizing = false
                                                    if (result.success) {
                                                        summary = result.summary
                                                    } else {
                                                        Toast.makeText(context, "Summary failed: ${result.error}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onDisconnect = {
                                    if (isRecording) {
                                        isRecording = false
                                        transcriptionService?.stop()
                                    }
                                    bleManager.disconnect()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}

// ── Top Status Bar ──

@Composable
fun TopStatusBar(
    connectionState: ConnectionState,
    batteryPercent: Int?,
    isRecording: Boolean,
    elapsedSeconds: Long,
    isWsConnected: Boolean,
    onSettingsClick: () -> Unit,
    onSessionsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: connection status
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(connectionState)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (connectionState) {
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.SCANNING -> "Scanning..."
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.CONNECTED -> if (isWsConnected) "Live" else "Connected"
                },
                fontSize = 13.sp,
                color = Color.Gray
            )
            if (batteryPercent != null) {
                Spacer(modifier = Modifier.width(12.dp))
                BatteryIndicator(batteryPercent)
            }
        }

        // Right: timer + sessions + settings
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isRecording) {
                Text(
                    text = formatDuration(elapsedSeconds),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFF4444)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onSessionsClick) {
                Text("Sessions", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = onSettingsClick) {
                Text("Settings", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

// ── Live Transcript View ──

@Composable
fun LiveTranscriptView(
    segments: List<TranscriptSegment>,
    partialText: String,
    error: String?,
    isRecording: Boolean,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val itemCount = segments.size + (if (partialText.isNotEmpty()) 1 else 0)

    // Auto-scroll to bottom when new segments or partial text changes
    LaunchedEffect(segments.size, partialText) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (segments.isEmpty() && partialText.isEmpty() && error == null) {
                // Empty state
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording) {
                            if (isConnected) "Listening..." else "Connecting to OpenAI..."
                        } else "Tap record to start",
                        fontSize = 16.sp,
                        color = Color(0xFF444444)
                    )
                    if (isRecording && !isConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFFFF4444),
                            strokeWidth = 2.dp
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(segments) { segment ->
                        TranscriptSegmentCard(segment)
                    }

                    // Partial text (current utterance in progress)
                    if (partialText.isNotEmpty()) {
                        item {
                            Text(
                                text = partialText,
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                                color = Color(0xFF888888),
                                lineHeight = 22.sp
                            )
                        }
                    }

                    if (error != null) {
                        item {
                            Text(
                                "⚠ $error",
                                fontSize = 13.sp,
                                color = Color(0xFFFF6666),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TranscriptSegmentCard(segment: TranscriptSegment) {
    val timeStr = remember(segment.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(segment.timestamp))
    }
    val speakerColor = remember(segment.speaker) {
        when (segment.speaker) {
            "You" -> Color(0xFF00CC88)
            null -> Color(0xFF666666)
            else -> Color(0xFF6699FF)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeStr,
                fontSize = 11.sp,
                color = Color(0xFF666666)
            )
            if (segment.speaker != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = segment.speaker,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = speakerColor
                )
            }
        }
        Text(
            text = segment.text,
            fontSize = 15.sp,
            color = Color.White,
            lineHeight = 22.sp
        )
    }
}

// ── Summary Card ──

@Composable
fun SummaryCard(summary: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1212))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Summary",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF4444)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                fontSize = 14.sp,
                color = Color(0xFFCCCCCC),
                lineHeight = 20.sp
            )
        }
    }
}

// ── Sessions List Screen ──

@Composable
fun SessionsListScreen(
    sessionManager: SessionManager,
    onBack: () -> Unit,
    onSessionClick: (Session) -> Unit
) {
    val sessions = remember { sessionManager.loadSessions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 24.sp, color = Color.White)
            }
            Text(
                "Sessions",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No saved sessions", fontSize = 16.sp, color = Color(0xFF444444))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    SessionListItem(session = session, onClick = { onSessionClick(session) })
                }
            }
        }
    }
}

@Composable
fun SessionListItem(session: Session, onClick: () -> Unit) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        formatDuration((session.endTime - session.startTime) / 1000)
    }
    val firstLine = session.transcript.firstOrNull()?.text?.take(80) ?: "No transcript"
    val summaryPreview = session.summary?.take(100)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(dateStr, fontSize = 13.sp, color = Color(0xFFFF4444), fontWeight = FontWeight.Medium)
                Text(durationStr, fontSize = 12.sp, color = Color(0xFF666666))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                firstLine,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (summaryPreview != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    summaryPreview + if ((session.summary?.length ?: 0) > 100) "..." else "",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Session Detail Screen ──

@Composable
fun SessionDetailScreen(session: Session, onBack: () -> Unit) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        formatDuration((session.endTime - session.startTime) / 1000)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 24.sp, color = Color.White)
            }
            Column {
                Text(dateStr, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Duration: $durationStr", fontSize = 13.sp, color = Color.Gray)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Summary
            if (session.summary != null) {
                item {
                    SummaryCard(
                        summary = session.summary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Transcript header
            item {
                Text(
                    "Transcript",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(session.transcript) { segment ->
                TranscriptSegmentCard(segment)
            }

            if (session.transcript.isEmpty()) {
                item {
                    Text("No transcript recorded", fontSize = 14.sp, color = Color(0xFF444444))
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ── Bottom Control Bar ──

@Composable
fun BottomControlBar(
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Packet count when recording
        if (isRecording) {
            Text(
                "$packetCount packets",
                fontSize = 11.sp,
                color = Color(0xFF555555)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Disconnect button
            TextButton(onClick = onDisconnect) {
                Text("Disconnect", fontSize = 13.sp, color = Color(0xFF666666))
            }

            // Big record/stop button
            Button(
                onClick = onRecord,
                modifier = Modifier
                    .size(72.dp)
                    .then(
                        if (isRecording) Modifier.scale(pulseScale) else Modifier
                    ),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFCC0000) else Color(0xFFFF4444),
                    disabledContainerColor = Color(0xFF333333)
                ),
                enabled = canRecord,
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isRecording) {
                    // Stop icon (square)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                    )
                } else {
                    // Record icon (circle)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }

            // Spacer to balance layout
            TextButton(onClick = {}, enabled = false) {
                Text("", fontSize = 13.sp)
            }
        }
    }
}

// ── Shared Components ──

@Composable
fun BatteryIndicator(percent: Int) {
    val color = when {
        percent > 50 -> Color(0xFF00FF88)
        percent > 20 -> Color(0xFFFFAA00)
        else -> Color(0xFFFF4444)
    }
    Text("BAT $percent%", fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
}

@Composable
fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFF555555)
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> Color(0xFFFFAA00)
        ConnectionState.CONNECTED -> Color(0xFF00FF88)
    }
    Box(modifier = Modifier
        .size(10.dp)
        .clip(CircleShape)
        .background(color))
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    voiceProfileManager: VoiceProfileManager,
    isVoiceEnrolled: Boolean,
    onEnrollmentChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var isEnrolling by remember { mutableStateOf(false) }
    var enrollProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Audio recording permission
    val audioPermission = rememberMultiplePermissionsState(
        permissions = listOf(android.Manifest.permission.RECORD_AUDIO)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .statusBarsPadding()
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 24.sp, color = Color.White)
            }
            Text(
                "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("OpenAI API Key", fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...", color = Color(0xFF444444)) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show", fontSize = 12.sp, color = Color.Gray)
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFF4444),
                unfocusedBorderColor = Color(0xFF333333),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Your API key is stored locally and used for real-time transcription and GPT-4o-mini summaries.",
            fontSize = 12.sp,
            color = Color(0xFF666666)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Voice Profile Section ──
        Text("Voice Profile", fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Enroll your voice so the app can label transcript segments as \"You\" vs other speakers.",
            fontSize = 12.sp,
            color = Color(0xFF666666)
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Status
        Text(
            text = if (isVoiceEnrolled) "✓ Voice enrolled" else "Not enrolled",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isVoiceEnrolled) Color(0xFF00CC88) else Color(0xFF888888)
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isEnrolling) {
            // Recording progress
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Recording your voice...",
                    fontSize = 14.sp,
                    color = Color(0xFFFF4444)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { enrollProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Color(0xFFFF4444),
                    trackColor = Color(0xFF333333),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Speak naturally for 10 seconds",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!audioPermission.allPermissionsGranted) {
                            audioPermission.launchMultiplePermissionRequest()
                            return@Button
                        }
                        isEnrolling = true
                        enrollProgress = 0f
                        scope.launch(Dispatchers.IO) {
                            try {
                                val pcmData = recordEnrollmentAudio(
                                    durationMs = 10_000,
                                    onProgress = { progress ->
                                        enrollProgress = progress
                                    }
                                )
                                voiceProfileManager.enroll(pcmData)
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    isEnrolling = false
                                    onEnrollmentChanged(voiceProfileManager.isEnrolled)
                                    if (voiceProfileManager.isEnrolled) {
                                        Toast.makeText(context, "Voice enrolled!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Enrollment failed — try speaking louder", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    isEnrolling = false
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF4444)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isVoiceEnrolled) "Re-enroll Voice" else "Enroll Voice",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                if (isVoiceEnrolled) {
                    OutlinedButton(
                        onClick = {
                            voiceProfileManager.clearProfile()
                            onEnrollmentChanged(false)
                            Toast.makeText(context, "Voice profile cleared", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF888888)
                        )
                    ) {
                        Text("Clear Profile", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * Record audio from the device microphone for enrollment.
 * Returns raw PCM 16kHz 16-bit mono LE.
 */
@Suppress("MissingPermission")
fun recordEnrollmentAudio(
    durationMs: Long = 10_000,
    onProgress: (Float) -> Unit = {}
): ByteArray {
    val sampleRate = 16000
    val bufferSize = android.media.AudioRecord.getMinBufferSize(
        sampleRate,
        android.media.AudioFormat.CHANNEL_IN_MONO,
        android.media.AudioFormat.ENCODING_PCM_16BIT
    )
    val recorder = android.media.AudioRecord(
        android.media.MediaRecorder.AudioSource.MIC,
        sampleRate,
        android.media.AudioFormat.CHANNEL_IN_MONO,
        android.media.AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )

    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(bufferSize)
    val startTime = System.currentTimeMillis()

    recorder.startRecording()
    try {
        while (System.currentTimeMillis() - startTime < durationMs) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                output.write(buffer, 0, read)
            }
            val elapsed = System.currentTimeMillis() - startTime
            onProgress((elapsed.toFloat() / durationMs).coerceIn(0f, 1f))
        }
    } finally {
        recorder.stop()
        recorder.release()
    }

    return output.toByteArray()
}

fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
