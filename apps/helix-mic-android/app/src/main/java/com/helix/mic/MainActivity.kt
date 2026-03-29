package com.helix.mic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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

// ─────────────────────────────────────────────────────────────
//  HELIX MIC — Technical Illustration UI
//  Monochrome. Restrained. Purposeful.
// ─────────────────────────────────────────────────────────────

object HelixTheme {
    // Backgrounds
    val Background = Color(0xFF0A0A0A)
    val SurfaceSubtle = Color(0xFF111111)

    // Borders & Lines
    val Hairline = Color(0xFF222222)
    val BorderDefault = Color(0xFF333333)

    // Text hierarchy
    val TextPrimary = Color(0xFFE8E8E8)
    val TextSecondary = Color(0xFF888888)
    val TextMuted = Color(0xFF444444)

    // Accent — recording only
    val RecordingRed = Color(0xFFCC3333)

    // Connected indicator — just slightly brighter than borders
    val Connected = Color(0xFF888888)

    // Shapes — sharp, technical
    val SharpShape = RoundedCornerShape(0.dp)
    val SlightShape = RoundedCornerShape(2.dp)
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

    var isRecording by remember { mutableStateOf(false) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var sessionStartTime by remember { mutableStateOf(0L) }
    var summary by remember { mutableStateOf<String?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }
    var sessionSaved by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }

    val context = LocalContext.current
    val voiceProfileManager = remember { VoiceProfileManager(context) }
    var isVoiceEnrolled by remember { mutableStateOf(voiceProfileManager.isEnrolled) }

    val openAiService = remember(apiKey) {
        if (apiKey.isNotBlank()) {
            TranscriptionService(apiKey).also { it.voiceProfileManager = voiceProfileManager }
        } else null
    }
    val localServerClient = remember(serverUrl) { LocalServerClient(serverUrl) }

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

    // Listen for STOP broadcast from foreground service notification
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == HelixForegroundService.ACTION_STOP && isRecording) {
                    isRecording = false
                    if (useLocalServer) localServerClient.stop() else openAiService?.stop()
                    val fullText = segments.joinToString(" ") { it.text }
                    if (fullText.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val result = SummaryClient(apiKey).summarize(fullText)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (result.success) summary = result.summary
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(HelixForegroundService.ACTION_STOP),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(receiver, IntentFilter(HelixForegroundService.ACTION_STOP))
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(useLocalServer, openAiService, isRecording) {
        if (isRecording) {
            bleManager.audioFrames.collect { frame ->
                if (useLocalServer) localServerClient.feedAudio(frame)
                else openAiService?.feedAudio(frame)
            }
        }
    }

    val permissionsList = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val blePermissions = rememberMultiplePermissionsState(permissions = permissionsList)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseScale"
    )

    // Start foreground service on BLE connect, stop on disconnect
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            HelixForegroundService.start(context)
        } else if (connectionState == ConnectionState.DISCONNECTED) {
            HelixForegroundService.stop(context)
        }
    }

    // Update service notification state when recording changes
    LaunchedEffect(isRecording) {
        if (connectionState == ConnectionState.CONNECTED) {
            HelixForegroundService.setRecording(context, isRecording)
        }
    }

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
            surface = HelixTheme.Background,
            surfaceVariant = HelixTheme.SurfaceSubtle,
            primary = HelixTheme.TextPrimary,
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
                        onApiKeyChange = { apiKey = it; sharedPrefs.edit().putString("openai_api_key", it).apply() },
                        useLocalServer = useLocalServer,
                        onUseLocalServerChange = { useLocalServer = it; sharedPrefs.edit().putBoolean("use_local_server", it).apply() },
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it; sharedPrefs.edit().putString("server_url", it).apply() },
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
                        onComplete = { isVoiceEnrolled = it; currentScreen = Screen.SETTINGS },
                        onBack = { currentScreen = Screen.SETTINGS }
                    )
                }
                Screen.SESSIONS -> {
                    SessionsListScreen(
                        sessionManager = sessionManager,
                        onBack = { currentScreen = Screen.MAIN },
                        onSessionClick = { selectedSession = it; currentScreen = Screen.SESSION_DETAIL }
                    )
                }
                Screen.SESSION_DETAIL -> {
                    selectedSession?.let { SessionDetailScreen(session = it, onBack = { currentScreen = Screen.SESSIONS }) }
                        ?: run { currentScreen = Screen.SESSIONS }
                }
                Screen.MAIN -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        // Top bar
                        TopBar(
                            isRecording = isRecording,
                            elapsedSeconds = elapsedSeconds,
                            onSettingsClick = { currentScreen = Screen.SETTINGS },
                            onSessionsClick = { currentScreen = Screen.SESSIONS }
                        )

                        HairlineDivider()

                        if (connectionState != ConnectionState.CONNECTED) {
                            // Disconnected state — clean and minimal
                            Spacer(modifier = Modifier.weight(1f))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when (connectionState) {
                                        ConnectionState.DISCONNECTED -> "NO DEVICE"
                                        ConnectionState.SCANNING -> "SCANNING"
                                        ConnectionState.CONNECTING -> "CONNECTING"
                                        ConnectionState.CONNECTED -> "CONNECTED"
                                    },
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Light,
                                    color = HelixTheme.TextMuted,
                                    letterSpacing = 4.sp
                                )

                                if (connectionState == ConnectionState.SCANNING || connectionState == ConnectionState.CONNECTING) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = HelixTheme.TextSecondary,
                                        strokeWidth = 1.dp
                                    )
                                }

                                Spacer(modifier = Modifier.height(40.dp))

                                if (!blePermissions.allPermissionsGranted) {
                                    OutlinedButton(
                                        onClick = { blePermissions.launchMultiplePermissionRequest() },
                                        modifier = Modifier.padding(horizontal = 64.dp).fillMaxWidth().height(44.dp),
                                        shape = HelixTheme.SharpShape,
                                        border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                                    ) {
                                        Text(
                                            "GRANT PERMISSIONS",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Light,
                                            letterSpacing = 3.sp
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { if (connectionState == ConnectionState.DISCONNECTED) bleManager.startScan() },
                                        modifier = Modifier.padding(horizontal = 64.dp).fillMaxWidth().height(44.dp),
                                        enabled = connectionState == ConnectionState.DISCONNECTED,
                                        shape = HelixTheme.SharpShape,
                                        border = BorderStroke(
                                            0.5.dp,
                                            if (connectionState == ConnectionState.DISCONNECTED) HelixTheme.TextPrimary
                                            else HelixTheme.TextMuted
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = HelixTheme.TextPrimary,
                                            disabledContentColor = HelixTheme.TextMuted
                                        )
                                    ) {
                                        Text(
                                            when (connectionState) {
                                                ConnectionState.SCANNING -> "SCANNING"
                                                ConnectionState.CONNECTING -> "CONNECTING"
                                                else -> "CONNECT"
                                            },
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Light,
                                            letterSpacing = 3.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            // Connected — status line
                            StatusLine(
                                batteryPercent = batteryPercent,
                                isRecording = isRecording,
                                elapsedSeconds = elapsedSeconds,
                                isWsConnected = isConnected
                            )

                            HairlineDivider()

                            // Transcript area
                            LiveTranscriptView(
                                segments = segments,
                                partialText = partialText,
                                error = transcriptionError,
                                isRecording = isRecording,
                                isConnected = isConnected,
                                modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp)
                            )

                            // Summary
                            if (!isRecording && summary != null) {
                                SummarySection(
                                    summary = summary!!,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                            }

                            if (isSummarizing) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = HelixTheme.TextSecondary,
                                        strokeWidth = 1.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "GENERATING SUMMARY",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = HelixTheme.TextMuted,
                                        letterSpacing = 2.sp
                                    )
                                }
                            }

                            // Save button
                            if (!isRecording && segments.isNotEmpty() && !sessionSaved) {
                                OutlinedButton(
                                    onClick = {
                                        currentSessionId?.let { id ->
                                            sessionManager.stopSession(id, segments.toList(), summary)
                                            sessionSaved = true
                                            Toast.makeText(context, "Session saved", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).height(40.dp),
                                    shape = HelixTheme.SharpShape,
                                    border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextSecondary)
                                ) {
                                    Text(
                                        "SAVE SESSION",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Light,
                                        letterSpacing = 3.sp
                                    )
                                }
                            }

                            if (!isRecording && sessionSaved) {
                                Text(
                                    "SESSION SAVED",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = HelixTheme.TextSecondary,
                                    letterSpacing = 2.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                )
                            }

                            // Bottom: Record button
                            Spacer(modifier = Modifier.height(12.dp))
                            RecordButton(
                                isRecording = isRecording,
                                canRecord = useLocalServer || apiKey.isNotBlank(),
                                pulseScale = pulseScale,
                                packetCount = packetCount,
                                onRecord = {
                                    if (!useLocalServer && apiKey.isBlank()) {
                                        Toast.makeText(context, "Set API key or enable local server", Toast.LENGTH_SHORT).show()
                                        return@RecordButton
                                    }
                                    if (!isRecording) {
                                        summary = null; sessionSaved = false
                                        if (useLocalServer) localServerClient.start() else openAiService?.start()
                                        bleManager.resetPacketCount()
                                        currentSessionId = sessionManager.startSession()
                                        sessionStartTime = System.currentTimeMillis()
                                        isRecording = true
                                    } else {
                                        isRecording = false
                                        if (useLocalServer) localServerClient.stop() else openAiService?.stop()
                                        val fullText = segments.joinToString(" ") { it.text }
                                        if (fullText.isNotBlank()) {
                                            isSummarizing = true
                                            scope.launch(Dispatchers.IO) {
                                                val result = SummaryClient(apiKey).summarize(fullText)
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    isSummarizing = false
                                                    if (result.success) summary = result.summary
                                                    else Toast.makeText(context, "Summary failed: ${result.error}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                onDisconnect = {
                                    if (isRecording) {
                                        isRecording = false
                                        HelixForegroundService.stop(context)
                                        if (useLocalServer) localServerClient.stop() else openAiService?.stop()
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

// ─────────────────────────────────────────────────────────────
//  SHARED COMPONENTS
// ─────────────────────────────────────────────────────────────

@Composable
fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(HelixTheme.Hairline)
    )
}

@Composable
fun SectionHeader(text: String) {
    Column {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextSecondary,
            letterSpacing = 3.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        HairlineDivider()
    }
}

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
    isRecording: Boolean,
    elapsedSeconds: Long,
    isWsConnected: Boolean
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
        if (batteryPercent != null) {
            Text(
                "BAT ${batteryPercent}%",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HelixTheme.TextMuted
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
                    TranscriptLine(segment)
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

@Composable
fun TranscriptLine(segment: TranscriptSegment) {
    val timeStr = remember(segment.timestamp) {
        SimpleDateFormat("mm:ss", Locale.getDefault()).format(Date(segment.timestamp))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

// ─────────────────────────────────────────────────────────────
//  SUMMARY
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
//  RECORD BUTTON
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
//  SESSIONS LIST
// ─────────────────────────────────────────────────────────────

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
        ScreenHeader(title = "SESSIONS", onBack = onBack)
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                items(sessions) { session ->
                    SessionRow(session = session, onClick = { onSessionClick(session) })
                    Spacer(modifier = Modifier.height(1.dp))
                    HairlineDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            preview,
            fontSize = 14.sp,
            color = HelixTheme.TextPrimary,
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
fun SessionDetailScreen(session: Session, onBack: () -> Unit) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }
    val durationStr = remember(session.startTime, session.endTime) {
        formatDuration((session.endTime - session.startTime) / 1000)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header with date and duration
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
            Column {
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
        }

        HairlineDivider()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (session.summary != null) {
                item {
                    SummarySection(summary = session.summary, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            item {
                Text(
                    "TRANSCRIPT",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = HelixTheme.TextMuted,
                    letterSpacing = 3.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                HairlineDivider()
            }

            items(session.transcript) { segment ->
                TranscriptLine(segment)
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
    }
}

// ─────────────────────────────────────────────────────────────
//  SCREEN HEADER
// ─────────────────────────────────────────────────────────────

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
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
            title,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextSecondary,
            letterSpacing = 3.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  SETTINGS
// ─────────────────────────────────────────────────────────────

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
                    serverEnrolledName = name; serverCheckDone = true; onEnrollmentChanged(name != null)
                }
            }
        } else { serverCheckDone = true }
    }

    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        focusedTextColor = HelixTheme.TextPrimary,
        unfocusedTextColor = HelixTheme.TextPrimary,
        cursorColor = HelixTheme.TextPrimary
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        ScreenHeader(title = "SETTINGS", onBack = onBack)
        HairlineDivider()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // API Key
            item {
                SectionHeader("API KEY")
                Spacer(modifier = Modifier.height(12.dp))

                // Underline-only input
                Column {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("sk-...", color = HelixTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Text(
                                if (showKey) "HIDE" else "SHOW",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = HelixTheme.TextMuted,
                                letterSpacing = 1.sp,
                                modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                            )
                        },
                        singleLine = true,
                        shape = HelixTheme.SharpShape,
                        colors = inputColors,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = HelixTheme.TextPrimary)
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(HelixTheme.BorderDefault))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Stored locally. Used for transcription and summaries.",
                    fontSize = 11.sp, color = HelixTheme.TextMuted, lineHeight = 16.sp
                )
            }

            // Backend
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader("BACKEND")
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "LOCAL SERVER",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light,
                            color = HelixTheme.TextPrimary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            if (useLocalServer) "Streaming to local STT" else "OpenAI Realtime API",
                            fontSize = 11.sp, color = HelixTheme.TextMuted
                        )
                    }
                    Switch(
                        checked = useLocalServer,
                        onCheckedChange = onUseLocalServerChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HelixTheme.TextPrimary,
                            checkedTrackColor = HelixTheme.BorderDefault,
                            uncheckedThumbColor = HelixTheme.TextMuted,
                            uncheckedTrackColor = HelixTheme.Hairline,
                            checkedBorderColor = Color.Transparent,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }

                if (useLocalServer) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "SERVER URL",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextMuted, letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = onServerUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("ws://...", color = HelixTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                            singleLine = true,
                            shape = HelixTheme.SharpShape,
                            colors = inputColors,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HelixTheme.TextPrimary)
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(HelixTheme.BorderDefault))
                    }
                }
            }

            // Voice Profile
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader("VOICE PROFILE")
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Enroll your voice so the server can identify you in transcripts.",
                    fontSize = 11.sp, color = HelixTheme.TextMuted, lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                val displayName = serverEnrolledName ?: voiceProfileManager.enrolledName
                val effectiveEnrolled = if (serverCheckDone) (serverEnrolledName != null) else isVoiceEnrolled

                if (!serverCheckDone && useLocalServer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), color = HelixTheme.TextMuted, strokeWidth = 1.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking server", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted)
                    }
                } else {
                    Text(
                        text = if (effectiveEnrolled && displayName != null) "ENROLLED AS \"${displayName.uppercase()}\"" else "NOT ENROLLED",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        color = if (effectiveEnrolled) HelixTheme.TextSecondary else HelixTheme.TextMuted,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!isConnected) { Toast.makeText(context, "Connect device first", Toast.LENGTH_SHORT).show(); return@OutlinedButton }
                            if (!useLocalServer) { Toast.makeText(context, "Enable local server first", Toast.LENGTH_SHORT).show(); return@OutlinedButton }
                            onStartEnrollment()
                        },
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                    ) {
                        Text(
                            if (isVoiceEnrolled) "RE-ENROLL" else "SET UP",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light, letterSpacing = 2.sp
                        )
                    }

                    if (isVoiceEnrolled) {
                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    voiceProfileManager.clearProfile(serverUrl)
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        onEnrollmentChanged(false)
                                        Toast.makeText(context, "Profile cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = HelixTheme.SharpShape,
                            border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted)
                        ) {
                            Text("CLEAR", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  VOICE ENROLLMENT
// ─────────────────────────────────────────────────────────────

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

    DisposableEffect(Unit) { onDispose { enrollWsClient.destroy() } }

    val infiniteTransition = rememberInfiniteTransition(label = "enrollPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    val accumulatedText = remember(enrollSegments, enrollPartial) {
        val segText = enrollSegments.joinToString(" ") { it.text }
        if (enrollPartial.isNotBlank()) "$segText $enrollPartial".trim() else segText.trim()
    }

    LaunchedEffect(accumulatedText) {
        if (step == EnrollStep.LISTENING && !timedOut) heardText = accumulatedText
    }

    LaunchedEffect(accumulatedText, step, currentPhraseIndex) {
        if (step == EnrollStep.LISTENING && !timedOut && accumulatedText.isNotBlank()) {
            val targetPhrase = ENROLLMENT_PHRASES[currentPhraseIndex].replace("%s", speakerName)
            if (phrasesMatch(accumulatedText, targetPhrase)) {
                pcmChunks.add(voiceProfileManager.stopCollecting())
                enrollWsClient.stop()
                step = EnrollStep.MATCHED
            }
        }
    }

    LaunchedEffect(step, listenKey) {
        if (step == EnrollStep.LISTENING) {
            bleManager.audioFrames.collect { frame ->
                if (step == EnrollStep.LISTENING) enrollWsClient.feedAudio(frame)
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
                currentPhraseIndex++; heardText = ""; timedOut = false
                enrollWsClient.clear(); enrollWsClient.start()
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
                for (chunk in pcmChunks) { chunk.copyInto(combined, offset); offset += chunk.size }
                val result = voiceProfileManager.enrollOnServer(combined, speakerName, serverUrl)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (result.success) step = EnrollStep.COMPLETE
                    else { errorMessage = result.error; step = EnrollStep.ERROR }
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step == EnrollStep.INTRO || step == EnrollStep.COMPLETE || step == EnrollStep.ERROR || timedOut) {
                Text(
                    "<",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextSecondary,
                    modifier = Modifier.clickable {
                        enrollWsClient.stop()
                        try { voiceProfileManager.stopCollecting() } catch (_: Exception) {}
                        onBack()
                    }.padding(end = 16.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))

            // Progress indicators — thin squares
            if (step == EnrollStep.LISTENING || step == EnrollStep.MATCHED) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in ENROLLMENT_PHRASES.indices) {
                        Box(
                            modifier = Modifier.size(6.dp).background(
                                when {
                                    i < currentPhraseIndex -> HelixTheme.TextSecondary
                                    i == currentPhraseIndex && step == EnrollStep.MATCHED -> HelixTheme.TextSecondary
                                    i == currentPhraseIndex -> HelixTheme.TextPrimary
                                    else -> HelixTheme.Hairline
                                },
                                RoundedCornerShape(0.dp)
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
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = HelixTheme.TextSecondary,
                    letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Hold the mic close. You'll read 5 short phrases aloud.",
                    fontSize = 13.sp,
                    color = HelixTheme.TextMuted,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    "YOUR NAME",
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted, letterSpacing = 2.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    OutlinedTextField(
                        value = speakerName,
                        onValueChange = { speakerName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("You", color = HelixTheme.TextMuted, fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        shape = HelixTheme.SharpShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = HelixTheme.TextPrimary,
                            unfocusedTextColor = HelixTheme.TextPrimary,
                            cursorColor = HelixTheme.TextPrimary
                        ),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = HelixTheme.TextPrimary)
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(HelixTheme.BorderDefault))
                }

                Spacer(modifier = Modifier.height(40.dp))

                OutlinedButton(
                    onClick = {
                        if (speakerName.isBlank()) speakerName = "You"
                        pcmChunks.clear(); currentPhraseIndex = 0; heardText = ""; timedOut = false
                        enrollWsClient.clear(); enrollWsClient.start()
                        voiceProfileManager.startCollecting(bleManager.audioFrames, scope)
                        step = EnrollStep.LISTENING
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    enabled = isDeviceConnected,
                    shape = HelixTheme.SharpShape,
                    border = BorderStroke(0.5.dp, if (isDeviceConnected) HelixTheme.TextPrimary else HelixTheme.TextMuted),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HelixTheme.TextPrimary,
                        disabledContentColor = HelixTheme.TextMuted
                    )
                ) {
                    Text("START", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
                }

                if (!isDeviceConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Connect device first", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted)
                }
            }

            EnrollStep.LISTENING -> {
                if (timedOut) {
                    Text(
                        "TIMEOUT",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light, color = HelixTheme.TextSecondary, letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${currentPhraseIndex + 1} / ${ENROLLMENT_PHRASES.size}",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    val phrase = ENROLLMENT_PHRASES[currentPhraseIndex].replace("%s", speakerName)
                    Text(
                        "\"$phrase\"",
                        fontSize = 16.sp, color = HelixTheme.TextPrimary,
                        lineHeight = 26.sp, textAlign = TextAlign.Center
                    )

                    if (heardText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Heard: $heardText", fontSize = 12.sp, color = HelixTheme.TextMuted, fontStyle = FontStyle.Italic)
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedButton(
                        onClick = {
                            heardText = ""; timedOut = false; listenKey++
                            enrollWsClient.clear(); enrollWsClient.start()
                            voiceProfileManager.startCollecting(bleManager.audioFrames, scope)
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = HelixTheme.SharpShape,
                        border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                    ) {
                        Text("TRY AGAIN", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                    }
                } else {
                    Text(
                        "LISTENING",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextSecondary.copy(alpha = pulseAlpha),
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${currentPhraseIndex + 1} / ${ENROLLMENT_PHRASES.size}",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    val phrase = ENROLLMENT_PHRASES[currentPhraseIndex].replace("%s", speakerName)
                    Text(
                        "\"$phrase\"",
                        fontSize = 16.sp, color = HelixTheme.TextPrimary,
                        lineHeight = 26.sp, textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    if (heardText.isNotBlank()) {
                        Text(heardText, fontSize = 12.sp, color = HelixTheme.TextMuted, fontStyle = FontStyle.Italic, lineHeight = 18.sp)
                    } else if (enrollConnected) {
                        Text("Say the phrase above", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), color = HelixTheme.TextMuted, strokeWidth = 1.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted)
                        }
                    }
                }
            }

            EnrollStep.MATCHED -> {
                Text(
                    "MATCHED",
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light, color = HelixTheme.TextSecondary, letterSpacing = 3.sp
                )
            }

            EnrollStep.PROCESSING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = HelixTheme.TextSecondary,
                    strokeWidth = 1.dp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "PROCESSING",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted, letterSpacing = 3.sp
                )
            }

            EnrollStep.COMPLETE -> {
                Text(
                    "ENROLLED",
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light, color = HelixTheme.TextSecondary, letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Your voice will be identified in transcripts.",
                    fontSize = 12.sp, color = HelixTheme.TextMuted, textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(40.dp))
                OutlinedButton(
                    onClick = { onComplete(true) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = HelixTheme.SharpShape,
                    border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                ) {
                    Text("DONE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
                }
            }

            EnrollStep.ERROR -> {
                Text(
                    "ENROLLMENT FAILED",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light, color = HelixTheme.RecordingRed, letterSpacing = 3.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    errorMessage ?: "Unknown error",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted
                )
                Spacer(modifier = Modifier.height(40.dp))
                OutlinedButton(
                    onClick = {
                        pcmChunks.clear(); currentPhraseIndex = 0; errorMessage = null
                        heardText = ""; timedOut = false; step = EnrollStep.INTRO
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = HelixTheme.SharpShape,
                    border = BorderStroke(0.5.dp, HelixTheme.TextSecondary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextPrimary)
                ) {
                    Text("TRY AGAIN", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
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
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
