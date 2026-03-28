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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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

// ══════════════════════════════════════════════════════════════
// HELIX MIC — Wireframe / Schematic UI Theme
// ══════════════════════════════════════════════════════════════

object HelixTheme {
    // Backgrounds
    val Background = Color(0xFF0A0A0A)
    val SurfaceDark = Color(0xFF0D1117)
    val SurfaceCard = Color(0xFF111827)

    // Borders
    val BorderDefault = Color(0xFF1E293B)
    val BorderActive = Color(0xFF2A3441)

    // Accent
    val Cyan = Color(0xFF00D4AA)
    val CyanMuted = Color(0xFF4ECDC4)

    // Text
    val TextPrimary = Color(0xFFE2E8F0)
    val TextSecondary = Color(0xFF6B7280)
    val TextMuted = Color(0xFF3B4252)

    // Status
    val Danger = Color(0xFFFF3B30)
    val Warning = Color(0xFFFFAA00)
    val Success = Color(0xFF00D4AA)

    // Shapes
    val CardShape = RoundedCornerShape(4.dp)
    val ButtonShape = RoundedCornerShape(4.dp)
    val InputShape = RoundedCornerShape(4.dp)
}

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

enum class Screen { MAIN, SETTINGS, SESSIONS, SESSION_DETAIL, VOICE_ENROLL }

// ── Markdown stripping utility ──

fun stripMarkdown(text: String): String {
    var result = text
    // Bold: **text** or __text__
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    result = result.replace(Regex("__(.+?)__"), "$1")
    // Italic: *text* or _text_
    result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
    result = result.replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "$1")
    // Headers: # text
    result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    // Bullet points: - or *
    result = result.replace(Regex("^[\\-\\*]\\s+", RegexOption.MULTILINE), "  ")
    return result.trim()
}

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
    var useLocalServer by remember { mutableStateOf(sharedPrefs.getBoolean("use_local_server", false)) }
    var serverUrl by remember { mutableStateOf(sharedPrefs.getString("server_url", "ws://192.168.0.35:8770/ws/stream") ?: "ws://192.168.0.35:8770/ws/stream") }

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

    val openAiService = remember(apiKey) {
        if (apiKey.isNotBlank()) {
            TranscriptionService(apiKey).also {
                it.voiceProfileManager = voiceProfileManager
            }
        } else null
    }
    val localServerClient = remember(serverUrl) {
        LocalServerClient(serverUrl)
    }

    DisposableEffect(Unit) {
        onDispose {
            openAiService?.destroy()
            localServerClient.destroy()
        }
    }

    val segments by (if (useLocalServer) localServerClient.segments else (openAiService?.segments ?: MutableStateFlow(emptyList()))).collectAsState()
    val partialText by (if (useLocalServer) localServerClient.partialText else (openAiService?.partialText ?: MutableStateFlow(""))).collectAsState()
    val isConnected by (if (useLocalServer) localServerClient.isConnected else (openAiService?.isConnected ?: MutableStateFlow(false))).collectAsState()
    val transcriptionError by (if (useLocalServer) localServerClient.error else (openAiService?.error ?: MutableStateFlow<String?>(null))).collectAsState()

    val scope = rememberCoroutineScope()

    LaunchedEffect(useLocalServer, openAiService, isRecording) {
        if (isRecording) {
            bleManager.audioFrames.collect { frame ->
                if (useLocalServer) {
                    localServerClient.feedAudio(frame)
                } else {
                    openAiService?.feedAudio(frame)
                }
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
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
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
            background = HelixTheme.Background,
            surface = HelixTheme.SurfaceDark,
            surfaceVariant = HelixTheme.SurfaceCard,
            primary = HelixTheme.Cyan,
            onBackground = HelixTheme.TextPrimary,
            onSurface = HelixTheme.TextPrimary,
            onSurfaceVariant = HelixTheme.TextSecondary,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = HelixTheme.Background) {
            when (currentScreen) {
                Screen.SETTINGS -> {
                    SettingsScreen(
                        apiKey = apiKey,
                        onApiKeyChange = { newKey ->
                            apiKey = newKey
                            sharedPrefs.edit().putString("openai_api_key", newKey).apply()
                        },
                        useLocalServer = useLocalServer,
                        onUseLocalServerChange = { enabled ->
                            useLocalServer = enabled
                            sharedPrefs.edit().putBoolean("use_local_server", enabled).apply()
                        },
                        serverUrl = serverUrl,
                        onServerUrlChange = { url ->
                            serverUrl = url
                            sharedPrefs.edit().putString("server_url", url).apply()
                        },
                        voiceProfileManager = voiceProfileManager,
                        isVoiceEnrolled = isVoiceEnrolled,
                        onEnrollmentChanged = { isVoiceEnrolled = it },
                        isConnected = connectionState == ConnectionState.CONNECTED,
                        onStartEnrollment = { currentScreen = Screen.VOICE_ENROLL },
                        onBack = { currentScreen = Screen.MAIN }
                    )
                }
                Screen.VOICE_ENROLL -> {
                    VoiceEnrollmentScreen(
                        bleManager = bleManager,
                        voiceProfileManager = voiceProfileManager,
                        serverUrl = serverUrl,
                        isDeviceConnected = connectionState == ConnectionState.CONNECTED,
                        onComplete = { enrolled ->
                            isVoiceEnrolled = enrolled
                            currentScreen = Screen.SETTINGS
                        },
                        onBack = { currentScreen = Screen.SETTINGS }
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

                        // Thin divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(HelixTheme.BorderDefault)
                        )

                        // ── Connection button (when disconnected) ──
                        if (connectionState != ConnectionState.CONNECTED) {
                            Spacer(modifier = Modifier.weight(1f))

                            // Device status card
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Status card with thin border
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, HelixTheme.BorderDefault, HelixTheme.CardShape)
                                        .background(HelixTheme.SurfaceCard, HelixTheme.CardShape)
                                        .padding(20.dp)
                                ) {
                                    Column {
                                        // Section label
                                        Text(
                                            text = "-- DEVICE STATUS",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = HelixTheme.TextSecondary,
                                            letterSpacing = 2.sp
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Diamond status indicator
                                            StatusDiamond(
                                                color = when (connectionState) {
                                                    ConnectionState.DISCONNECTED -> HelixTheme.TextMuted
                                                    ConnectionState.SCANNING, ConnectionState.CONNECTING -> HelixTheme.Warning
                                                    ConnectionState.CONNECTED -> HelixTheme.Cyan
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = when (connectionState) {
                                                    ConnectionState.DISCONNECTED -> "DISCONNECTED"
                                                    ConnectionState.SCANNING -> "SCANNING..."
                                                    ConnectionState.CONNECTING -> "CONNECTING..."
                                                    ConnectionState.CONNECTED -> "CONNECTED"
                                                },
                                                fontSize = 14.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = HelixTheme.TextPrimary,
                                                letterSpacing = 1.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = when (connectionState) {
                                                ConnectionState.DISCONNECTED -> "No device in range"
                                                ConnectionState.SCANNING -> "Searching for Helix Mic..."
                                                ConnectionState.CONNECTING -> "Establishing link..."
                                                else -> ""
                                            },
                                            fontSize = 13.sp,
                                            color = HelixTheme.TextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                if (!blePermissions.allPermissionsGranted) {
                                    OutlinedButton(
                                        onClick = { blePermissions.launchMultiplePermissionRequest() },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        shape = HelixTheme.ButtonShape,
                                        border = BorderStroke(1.dp, HelixTheme.Cyan),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = HelixTheme.Cyan
                                        )
                                    ) {
                                        Text(
                                            "GRANT BLUETOOTH PERMISSIONS",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            if (connectionState == ConnectionState.DISCONNECTED) {
                                                bleManager.startScan()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        enabled = connectionState == ConnectionState.DISCONNECTED,
                                        shape = HelixTheme.ButtonShape,
                                        border = BorderStroke(1.dp, 
                                                if (connectionState == ConnectionState.DISCONNECTED) HelixTheme.Cyan
                                                else HelixTheme.BorderDefault
                                            ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = HelixTheme.Cyan,
                                            disabledContentColor = HelixTheme.TextMuted
                                        )
                                    ) {
                                        Text(
                                            when (connectionState) {
                                                ConnectionState.SCANNING -> "SCANNING..."
                                                ConnectionState.CONNECTING -> "CONNECTING..."
                                                else -> "CONNECT TO HELIX MIC"
                                            },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            // ── Connected: status bar ──
                            if (!isRecording) {
                                ConnectedStatusBar(
                                    batteryPercent = batteryPercent,
                                    isWsConnected = isConnected
                                )
                            } else {
                                RecordingStatusBar(
                                    batteryPercent = batteryPercent,
                                    elapsedSeconds = elapsedSeconds
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(HelixTheme.BorderDefault)
                            )

                            // Live transcript area
                            LiveTranscriptView(
                                segments = segments,
                                partialText = partialText,
                                error = transcriptionError,
                                isRecording = isRecording,
                                isConnected = isConnected,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )

                            // Summary card (shown after stopping)
                            if (!isRecording && summary != null) {
                                SummaryCard(
                                    summary = summary!!,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
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
                                        modifier = Modifier.size(14.dp),
                                        color = HelixTheme.Cyan,
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "GENERATING SUMMARY...",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = HelixTheme.TextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            // Save button (after session ends with transcript)
                            if (!isRecording && segments.isNotEmpty() && !sessionSaved) {
                                OutlinedButton(
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
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .height(44.dp),
                                    shape = HelixTheme.ButtonShape,
                                    border = BorderStroke(1.dp, HelixTheme.BorderActive),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = HelixTheme.TextPrimary
                                    )
                                ) {
                                    Text(
                                        "SAVE SESSION",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            if (!isRecording && sessionSaved) {
                                Text(
                                    "[v] Session saved",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = HelixTheme.Cyan,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }

                            // ── Bottom Bar ──
                            Spacer(modifier = Modifier.height(8.dp))
                            BottomControlBar(
                                isRecording = isRecording,
                                canRecord = useLocalServer || apiKey.isNotBlank(),
                                pulseScale = pulseScale,
                                packetCount = packetCount,
                                onRecord = {
                                    if (!useLocalServer && apiKey.isBlank()) {
                                        Toast.makeText(context, "Set API key or enable local server in settings", Toast.LENGTH_SHORT).show()
                                        return@BottomControlBar
                                    }
                                    if (!isRecording) {
                                        summary = null
                                        sessionSaved = false
                                        if (useLocalServer) {
                                            localServerClient.start()
                                        } else {
                                            openAiService?.start()
                                        }
                                        bleManager.resetPacketCount()
                                        currentSessionId = sessionManager.startSession()
                                        sessionStartTime = System.currentTimeMillis()
                                        isRecording = true
                                    } else {
                                        isRecording = false
                                        if (useLocalServer) {
                                            localServerClient.stop()
                                        } else {
                                            openAiService?.stop()
                                        }
                                        val finalSegments = segments.toList()
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
                                        if (useLocalServer) {
                                            localServerClient.stop()
                                        } else {
                                            openAiService?.stop()
                                        }
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

// ══════════════════════════════════════════════════════════════
// TOP STATUS BAR
// ══════════════════════════════════════════════════════════════

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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: App title
        Text(
            text = "HELIX MIC",
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = HelixTheme.TextPrimary,
            letterSpacing = 2.sp
        )

        // Right: nav buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isRecording) {
                Text(
                    text = formatDuration(elapsedSeconds),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = HelixTheme.Danger
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(
                onClick = onSessionsClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "SESSIONS",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary,
                    letterSpacing = 1.sp
                )
            }
            TextButton(
                onClick = onSettingsClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "SETTINGS",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// ── Connected status bar ──

@Composable
fun ConnectedStatusBar(batteryPercent: Int?, isWsConnected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HelixTheme.SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDiamond(color = HelixTheme.Cyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "CONNECTED",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.Cyan,
                letterSpacing = 1.sp
            )
        }
        if (batteryPercent != null) {
            Text(
                "BAT ${batteryPercent}%",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = when {
                    batteryPercent > 50 -> HelixTheme.Cyan
                    batteryPercent > 20 -> HelixTheme.Warning
                    else -> HelixTheme.Danger
                }
            )
        }
        if (isWsConnected) {
            Text(
                "LIVE",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.Cyan,
                letterSpacing = 1.sp
            )
        }
    }
}

// ── Recording status bar ──

@Composable
fun RecordingStatusBar(batteryPercent: Int?, elapsedSeconds: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HelixTheme.SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDiamond(color = HelixTheme.Cyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "CONNECTED",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.Cyan,
                letterSpacing = 1.sp
            )
        }
        if (batteryPercent != null) {
            Text(
                "BAT ${batteryPercent}%",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = when {
                    batteryPercent > 50 -> HelixTheme.Cyan
                    batteryPercent > 20 -> HelixTheme.Warning
                    else -> HelixTheme.Danger
                }
            )
        }
        // Recording indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(HelixTheme.Danger, RoundedCornerShape(1.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "REC ${formatDuration(elapsedSeconds)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.Danger,
                letterSpacing = 1.sp
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// LIVE TRANSCRIPT VIEW
// ══════════════════════════════════════════════════════════════

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

    LaunchedEffect(segments.size, partialText) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HelixTheme.BorderDefault, HelixTheme.CardShape)
            .background(HelixTheme.SurfaceCard, HelixTheme.CardShape)
    ) {
        // Section label at top
        Text(
            text = "-- TRANSCRIPT",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = HelixTheme.TextSecondary,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp)
        )

        if (segments.isEmpty() && partialText.isEmpty() && error == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRecording) {
                        if (isConnected) "Listening..." else "Connecting..."
                    } else "Tap record to begin",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted
                )
                if (isRecording && !isConnected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = HelixTheme.Cyan,
                        strokeWidth = 1.5.dp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(segments) { segment ->
                    TranscriptSegmentCard(segment)
                }

                if (partialText.isNotEmpty()) {
                    item {
                        Text(
                            text = "> $partialText",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontStyle = FontStyle.Italic,
                            color = HelixTheme.CyanMuted,
                            lineHeight = 20.sp
                        )
                    }
                }

                if (error != null) {
                    item {
                        Text(
                            "[!] $error",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HelixTheme.Danger,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
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
            "You" -> Color(0xFF00D4AA)
            null -> HelixTheme.TextSecondary
            else -> Color(0xFF6699FF)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "[$timeStr]",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextSecondary
            )
            if (segment.speaker != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${segment.speaker}:",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = speakerColor
                )
            }
        }
        Text(
            text = segment.text,
            fontSize = 14.sp,
            color = HelixTheme.TextPrimary,
            lineHeight = 20.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// SUMMARY CARD
// ══════════════════════════════════════════════════════════════

@Composable
fun SummaryCard(summary: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HelixTheme.BorderDefault, HelixTheme.CardShape)
            .background(HelixTheme.SurfaceCard, HelixTheme.CardShape)
            .padding(16.dp)
    ) {
        Column {
            Text(
                "-- SUMMARY",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = HelixTheme.Cyan,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stripMarkdown(summary),
                fontSize = 13.sp,
                color = HelixTheme.TextPrimary,
                lineHeight = 20.sp
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// SESSIONS LIST SCREEN
// ══════════════════════════════════════════════════════════════

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
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "<-",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "SESSIONS",
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = HelixTheme.TextPrimary,
                letterSpacing = 2.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HelixTheme.BorderDefault)
        )

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No saved sessions",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        "dur: " + formatDuration((session.endTime - session.startTime) / 1000)
    }
    val firstLine = session.transcript.firstOrNull()?.text?.take(80) ?: "No transcript"
    val summaryPreview = session.summary?.let { stripMarkdown(it).take(100) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, HelixTheme.BorderDefault, HelixTheme.CardShape)
            .background(HelixTheme.SurfaceCard, HelixTheme.CardShape)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    dateStr,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    durationStr,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "\"$firstLine\"",
                fontSize = 13.sp,
                color = HelixTheme.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (summaryPreview != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Summary: $summaryPreview" + if ((session.summary?.length ?: 0) > 100) "..." else "",
                    fontSize = 12.sp,
                    color = HelixTheme.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// SESSION DETAIL SCREEN
// ══════════════════════════════════════════════════════════════

@Composable
fun SessionDetailScreen(session: Session, onBack: () -> Unit) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        formatDuration((session.endTime - session.startTime) / 1000)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "<-",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    dateStr,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = HelixTheme.TextPrimary
                )
                Text(
                    "Duration: $durationStr",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HelixTheme.BorderDefault)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    "-- TRANSCRIPT",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = HelixTheme.TextSecondary,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(session.transcript) { segment ->
                TranscriptSegmentCard(segment)
            }

            if (session.transcript.isEmpty()) {
                item {
                    Text(
                        "No transcript recorded",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextMuted
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// BOTTOM CONTROL BAR
// ══════════════════════════════════════════════════════════════

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
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Packet count when recording
        if (isRecording) {
            Text(
                "${packetCount} packets",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Record/Stop button
        if (isRecording) {
            // STOP button — square, red border
            OutlinedButton(
                onClick = onRecord,
                modifier = Modifier
                    .size(72.dp)
                    .scale(pulseScale),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, HelixTheme.Danger),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = HelixTheme.Danger
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Square stop icon
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(HelixTheme.Danger, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "STOP",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.Danger,
                        letterSpacing = 1.sp
                    )
                }
            }
        } else {
            // RECORD button — circle outline, cyan
            OutlinedButton(
                onClick = onRecord,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                enabled = canRecord,
                border = BorderStroke(1.dp, 
                        if (canRecord) HelixTheme.Cyan else HelixTheme.TextMuted
                    ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = HelixTheme.Cyan,
                    disabledContentColor = HelixTheme.TextMuted
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Circle record icon
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(2.dp, if (canRecord) HelixTheme.Cyan else HelixTheme.TextMuted, CircleShape)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "REC",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Disconnect link
        TextButton(onClick = onDisconnect) {
            Text(
                "[disconnect]",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextSecondary
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ══════════════════════════════════════════════════════════════

@Composable
fun StatusDiamond(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .rotate(45f)
            .background(color, RoundedCornerShape(1.dp))
    )
}

@Composable
fun BatteryIndicator(percent: Int) {
    val color = when {
        percent > 50 -> HelixTheme.Cyan
        percent > 20 -> HelixTheme.Warning
        else -> HelixTheme.Danger
    }
    Text(
        "BAT $percent%",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

// ══════════════════════════════════════════════════════════════
// SETTINGS SCREEN
// ══════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    useLocalServer: Boolean,
    onUseLocalServerChange: (Boolean) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    voiceProfileManager: VoiceProfileManager,
    isVoiceEnrolled: Boolean,
    onEnrollmentChanged: (Boolean) -> Unit,
    isConnected: Boolean,
    onStartEnrollment: () -> Unit,
    onBack: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var serverEnrolledName by remember { mutableStateOf<String?>(null) }
    var serverCheckDone by remember { mutableStateOf(false) }

    LaunchedEffect(serverUrl, useLocalServer) {
        if (useLocalServer) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val name = voiceProfileManager.checkServerEnrollment(serverUrl)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    serverEnrolledName = name
                    serverCheckDone = true
                    onEnrollmentChanged(name != null)
                }
            }
        } else {
            serverCheckDone = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "<-",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "SETTINGS",
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = HelixTheme.TextPrimary,
                letterSpacing = 2.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HelixTheme.BorderDefault)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── API Key Section ──
            item {
                SectionLabel("OPENAI API KEY")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "sk-...",
                            color = HelixTheme.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(
                                if (showKey) "HIDE" else "SHOW",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = HelixTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                        }
                    },
                    singleLine = true,
                    shape = HelixTheme.InputShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HelixTheme.Cyan,
                        unfocusedBorderColor = HelixTheme.BorderDefault,
                        focusedTextColor = HelixTheme.TextPrimary,
                        unfocusedTextColor = HelixTheme.TextPrimary,
                        cursorColor = HelixTheme.Cyan
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Stored locally. Used for real-time transcription and GPT-4o-mini summaries.",
                    fontSize = 11.sp,
                    color = HelixTheme.TextSecondary,
                    lineHeight = 16.sp
                )
            }

            // ── Transcription Backend Section ──
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionLabel("TRANSCRIPTION BACKEND")
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Use Local Server",
                            fontSize = 14.sp,
                            color = HelixTheme.TextPrimary
                        )
                        Text(
                            if (useLocalServer) "Streaming to local Helix STT server" else "Using OpenAI Realtime API",
                            fontSize = 11.sp,
                            color = HelixTheme.TextSecondary
                        )
                    }
                    Switch(
                        checked = useLocalServer,
                        onCheckedChange = onUseLocalServerChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HelixTheme.Background,
                            checkedTrackColor = HelixTheme.Cyan,
                            uncheckedThumbColor = HelixTheme.TextSecondary,
                            uncheckedTrackColor = HelixTheme.BorderDefault
                        )
                    )
                }

                if (useLocalServer) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "SERVER URL",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "ws://192.168.0.35:8770/ws/stream",
                                color = HelixTheme.TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        shape = HelixTheme.InputShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HelixTheme.Cyan,
                            unfocusedBorderColor = HelixTheme.BorderDefault,
                            focusedTextColor = HelixTheme.TextPrimary,
                            unfocusedTextColor = HelixTheme.TextPrimary,
                            cursorColor = HelixTheme.Cyan
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Raw PCM audio sent directly -- no API key needed.",
                        fontSize = 11.sp,
                        color = HelixTheme.TextSecondary
                    )
                }
            }

            // ── Voice Profile Section ──
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionLabel("VOICE PROFILE")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Enroll your voice so the server can label transcript segments as your name vs other speakers.",
                    fontSize = 11.sp,
                    color = HelixTheme.TextSecondary,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                val displayName = serverEnrolledName ?: voiceProfileManager.enrolledName
                val effectiveEnrolled = if (serverCheckDone) (serverEnrolledName != null) else isVoiceEnrolled

                if (!serverCheckDone && useLocalServer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = HelixTheme.Cyan,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Checking server...",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HelixTheme.TextSecondary
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDiamond(
                            color = if (effectiveEnrolled) HelixTheme.Cyan else HelixTheme.TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (effectiveEnrolled && displayName != null) "Enrolled as \"$displayName\"" else "Not enrolled",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = if (effectiveEnrolled) HelixTheme.Cyan else HelixTheme.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!isConnected) {
                                Toast.makeText(context, "Connect Helix-Mic first", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            if (!useLocalServer) {
                                Toast.makeText(context, "Enable local server to use voice enrollment", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            onStartEnrollment()
                        },
                        shape = HelixTheme.ButtonShape,
                        border = BorderStroke(1.dp, HelixTheme.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = HelixTheme.Cyan
                        )
                    ) {
                        Text(
                            if (isVoiceEnrolled) "RE-ENROLL" else "SET UP VOICE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }

                    if (isVoiceEnrolled) {
                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    voiceProfileManager.clearProfile(serverUrl)
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        onEnrollmentChanged(false)
                                        Toast.makeText(context, "Voice profile cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = HelixTheme.ButtonShape,
                            border = BorderStroke(1.dp, HelixTheme.BorderActive),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = HelixTheme.TextSecondary
                            )
                        ) {
                            Text(
                                "CLEAR",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = "-- $text",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = HelixTheme.TextSecondary,
        letterSpacing = 2.sp
    )
}

// ══════════════════════════════════════════════════════════════
// VOICE ENROLLMENT SCREEN
// ══════════════════════════════════════════════════════════════

private val ENROLLMENT_PHRASES = listOf(
    "The quick brown fox jumps over the lazy dog",
    "I enjoy working on interesting projects every day",
    "Hello, my name is %s and this is my voice",
    "The weather today is quite nice for a walk outside",
    "Technology keeps getting better and more accessible"
)

private const val PHRASE_TIMEOUT_MS = 15000L

enum class EnrollStep { INTRO, LISTENING, MATCHED, PROCESSING, COMPLETE, ERROR }

private fun phrasesMatch(spoken: String, target: String): Boolean {
    val spokenWords = spoken.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
    val targetWords = target.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
    if (targetWords.isEmpty()) return false
    val matchCount = targetWords.count { it in spokenWords }
    return matchCount.toFloat() / targetWords.size >= 0.6f
}

@Composable
fun VoiceEnrollmentScreen(
    bleManager: HelixMicBleManager,
    voiceProfileManager: VoiceProfileManager,
    serverUrl: String,
    isDeviceConnected: Boolean,
    onComplete: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var step by remember { mutableStateOf(EnrollStep.INTRO) }
    var currentPhraseIndex by remember { mutableIntStateOf(0) }
    var speakerName by remember { mutableStateOf(voiceProfileManager.enrolledName ?: "You") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var heardText by remember { mutableStateOf("") }
    var timedOut by remember { mutableStateOf(false) }
    var listenKey by remember { mutableIntStateOf(0) }
    val pcmChunks = remember { mutableListOf<ByteArray>() }
    val scope = rememberCoroutineScope()

    val enrollWsClient = remember { LocalServerClient(serverUrl) }
    val enrollSegments by enrollWsClient.segments.collectAsState()
    val enrollPartial by enrollWsClient.partialText.collectAsState()
    val enrollConnected by enrollWsClient.isConnected.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            enrollWsClient.destroy()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "enrollPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val accumulatedText = remember(enrollSegments, enrollPartial) {
        val segText = enrollSegments.joinToString(" ") { it.text }
        if (enrollPartial.isNotBlank()) "$segText $enrollPartial".trim() else segText.trim()
    }

    LaunchedEffect(accumulatedText) {
        if (step == EnrollStep.LISTENING && !timedOut) {
            heardText = accumulatedText
        }
    }

    LaunchedEffect(accumulatedText, step, currentPhraseIndex) {
        if (step == EnrollStep.LISTENING && !timedOut && accumulatedText.isNotBlank()) {
            val targetPhrase = ENROLLMENT_PHRASES[currentPhraseIndex].replace("%s", speakerName)
            if (phrasesMatch(accumulatedText, targetPhrase)) {
                val chunk = voiceProfileManager.stopCollecting()
                pcmChunks.add(chunk)
                enrollWsClient.stop()
                step = EnrollStep.MATCHED
            }
        }
    }

    LaunchedEffect(step, listenKey) {
        if (step == EnrollStep.LISTENING) {
            bleManager.audioFrames.collect { frame ->
                if (step == EnrollStep.LISTENING) {
                    enrollWsClient.feedAudio(frame)
                }
            }
        }
    }

    LaunchedEffect(step, currentPhraseIndex, listenKey) {
        if (step == EnrollStep.LISTENING) {
            timedOut = false
            kotlinx.coroutines.delay(PHRASE_TIMEOUT_MS)
            if (step == EnrollStep.LISTENING) {
                voiceProfileManager.stopCollecting()
                enrollWsClient.stop()
                timedOut = true
            }
        }
    }

    LaunchedEffect(step, currentPhraseIndex) {
        if (step == EnrollStep.MATCHED) {
            kotlinx.coroutines.delay(800)
            if (currentPhraseIndex < ENROLLMENT_PHRASES.size - 1) {
                currentPhraseIndex++
                heardText = ""
                timedOut = false
                enrollWsClient.clear()
                enrollWsClient.start()
                voiceProfileManager.startCollecting(bleManager.audioFrames, scope)
                step = EnrollStep.LISTENING
            } else {
                step = EnrollStep.PROCESSING
            }
        }
    }

    LaunchedEffect(step) {
        if (step == EnrollStep.PROCESSING) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val totalSize = pcmChunks.sumOf { it.size }
                val combined = ByteArray(totalSize)
                var offset = 0
                for (chunk in pcmChunks) {
                    chunk.copyInto(combined, offset)
                    offset += chunk.size
                }

                val result = voiceProfileManager.enrollOnServer(combined, speakerName, serverUrl)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (result.success) {
                        step = EnrollStep.COMPLETE
                    } else {
                        errorMessage = result.error
                        step = EnrollStep.ERROR
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step == EnrollStep.INTRO || step == EnrollStep.COMPLETE || step == EnrollStep.ERROR || timedOut) {
                TextButton(
                    onClick = {
                        enrollWsClient.stop()
                        try { voiceProfileManager.stopCollecting() } catch (_: Exception) {}
                        onBack()
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "<-",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.Cyan
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            // Progress squares instead of dots
            if (step == EnrollStep.LISTENING || step == EnrollStep.MATCHED) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in ENROLLMENT_PHRASES.indices) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    when {
                                        i < currentPhraseIndex -> HelixTheme.Cyan
                                        i == currentPhraseIndex && step == EnrollStep.MATCHED -> HelixTheme.Cyan
                                        i == currentPhraseIndex -> HelixTheme.Danger
                                        else -> HelixTheme.BorderDefault
                                    },
                                    RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.weight(1f))

        when (step) {
            EnrollStep.INTRO -> {
                Text(
                    "VOICE ENROLLMENT",
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = HelixTheme.TextPrimary,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Make sure Helix-Mic is connected and close to your mouth.",
                    fontSize = 14.sp,
                    color = HelixTheme.TextSecondary,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "You'll read 5 short phrases aloud. Each one is verified before moving on.",
                    fontSize = 14.sp,
                    color = HelixTheme.TextSecondary,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    "YOUR NAME",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = speakerName,
                    onValueChange = { speakerName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "You",
                            color = HelixTheme.TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    singleLine = true,
                    shape = HelixTheme.InputShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HelixTheme.Cyan,
                        unfocusedBorderColor = HelixTheme.BorderDefault,
                        focusedTextColor = HelixTheme.TextPrimary,
                        unfocusedTextColor = HelixTheme.TextPrimary,
                        cursorColor = HelixTheme.Cyan
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                OutlinedButton(
                    onClick = {
                        if (speakerName.isBlank()) speakerName = "You"
                        pcmChunks.clear()
                        currentPhraseIndex = 0
                        heardText = ""
                        timedOut = false
                        enrollWsClient.clear()
                        enrollWsClient.start()
                        voiceProfileManager.startCollecting(bleManager.audioFrames, scope)
                        step = EnrollStep.LISTENING
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = isDeviceConnected,
                    shape = HelixTheme.ButtonShape,
                    border = BorderStroke(1.dp, 
                            if (isDeviceConnected) HelixTheme.Cyan else HelixTheme.TextMuted
                        ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HelixTheme.Cyan,
                        disabledContentColor = HelixTheme.TextMuted
                    )
                ) {
                    Text(
                        "START",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                if (!isDeviceConnected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Connect Helix-Mic to continue",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.Danger
                    )
                }
            }

            EnrollStep.LISTENING -> {
                if (timedOut) {
                    Text(
                        "TIMEOUT",
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = HelixTheme.Warning,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Phrase ${currentPhraseIndex + 1} of ${ENROLLMENT_PHRASES.size}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    val phrase = ENROLLMENT_PHRASES[currentPhraseIndex].replace("%s", speakerName)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, HelixTheme.BorderDefault, HelixTheme.CardShape)
                            .background(HelixTheme.SurfaceCard, HelixTheme.CardShape)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "\"$phrase\"",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = HelixTheme.TextPrimary,
                            lineHeight = 28.sp
                        )
                    }

                    if (heardText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Heard: $heardText",
                            fontSize = 13.sp,
                            color = HelixTheme.TextSecondary,
                            fontStyle = FontStyle.Italic
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedButton(
                        onClick = {
                            heardText = ""
                            timedOut = false
                            listenKey++
                            enrollWsClient.clear()
                            enrollWsClient.start()
                            voiceProfileManager.startCollecting(bleManager.audioFrames, scope)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = HelixTheme.ButtonShape,
                        border = BorderStroke(1.dp, HelixTheme.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = HelixTheme.Cyan
                        )
                    ) {
                        Text(
                            "TRY AGAIN",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    // Active listening
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    HelixTheme.Danger.copy(alpha = pulseAlpha),
                                    RoundedCornerShape(1.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "LISTENING...",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HelixTheme.Danger.copy(alpha = pulseAlpha),
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Phrase ${currentPhraseIndex + 1} of ${ENROLLMENT_PHRASES.size}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    val phrase = ENROLLMENT_PHRASES[currentPhraseIndex].replace("%s", speakerName)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, HelixTheme.BorderDefault, HelixTheme.CardShape)
                            .background(HelixTheme.SurfaceCard, HelixTheme.CardShape)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "\"$phrase\"",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = HelixTheme.TextPrimary,
                            lineHeight = 28.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (heardText.isNotBlank()) {
                        Text(
                            "Heard: $heardText",
                            fontSize = 13.sp,
                            color = HelixTheme.TextSecondary,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 20.sp
                        )
                    } else if (enrollConnected) {
                        Text(
                            "Say the phrase above...",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HelixTheme.TextMuted
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = HelixTheme.Cyan,
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Connecting to server...",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = HelixTheme.TextMuted
                            )
                        }
                    }
                }
            }

            EnrollStep.MATCHED -> {
                Text(
                    "[v]",
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "PHRASE ${currentPhraseIndex + 1} MATCHED",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan,
                    letterSpacing = 1.sp
                )
            }

            EnrollStep.PROCESSING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = HelixTheme.Cyan,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "PROCESSING VOICE PROFILE...",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextPrimary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This may take a moment",
                    fontSize = 12.sp,
                    color = HelixTheme.TextSecondary
                )
            }

            EnrollStep.COMPLETE -> {
                Text(
                    "[v]",
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.Cyan
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "VOICE PROFILE CREATED",
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = HelixTheme.Cyan,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Your voice will be recognized during transcription.",
                    fontSize = 14.sp,
                    color = HelixTheme.TextSecondary
                )

                Spacer(modifier = Modifier.height(48.dp))

                OutlinedButton(
                    onClick = { onComplete(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = HelixTheme.ButtonShape,
                    border = BorderStroke(1.dp, HelixTheme.Cyan),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HelixTheme.Cyan
                    )
                ) {
                    Text(
                        "DONE",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }

            EnrollStep.ERROR -> {
                Text(
                    "ENROLLMENT FAILED",
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = HelixTheme.Danger,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    errorMessage ?: "Unknown error",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary
                )

                Spacer(modifier = Modifier.height(48.dp))

                OutlinedButton(
                    onClick = {
                        pcmChunks.clear()
                        currentPhraseIndex = 0
                        errorMessage = null
                        heardText = ""
                        timedOut = false
                        step = EnrollStep.INTRO
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = HelixTheme.ButtonShape,
                    border = BorderStroke(1.dp, HelixTheme.Cyan),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HelixTheme.Cyan
                    )
                ) {
                    Text(
                        "TRY AGAIN",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
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
