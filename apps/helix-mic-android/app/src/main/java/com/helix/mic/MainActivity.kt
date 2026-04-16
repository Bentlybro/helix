package com.helix.mic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
        sessionManager = SessionManager(sharedPrefs, applicationContext)
        enableEdgeToEdge()

        // Check battery optimization — prompt user to disable for reliable BLE background
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) { /* Some ROMs don't support this */ }
        }

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
    val batteryMillivolts by bleManager.batteryMillivolts.collectAsState()
    val isReceivingAudio by bleManager.isReceivingAudio.collectAsState()
    val packetCount by bleManager.packetCount.collectAsState()

    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    var apiKey by remember { mutableStateOf(sharedPrefs.getString("openai_api_key", "") ?: "") }
    var backendType by remember {
        mutableStateOf(
            if (sharedPrefs.contains("backend_type")) {
                BackendType.fromKey(sharedPrefs.getString("backend_type", null))
            } else if (sharedPrefs.getBoolean("use_local_server", false)) {
                BackendType.LOCAL_SERVER
            } else {
                BackendType.OPENAI_REALTIME
            }
        )
    }
    var serverUrl by remember { mutableStateOf(sharedPrefs.getString("server_url", "ws://192.168.0.35:8770/ws/stream") ?: "ws://192.168.0.35:8770/ws/stream") }
    var onDeviceModel by remember {
        mutableStateOf(WhisperModel.fromId(sharedPrefs.getString("whisper_model", WhisperModel.BASE_EN.id)))
    }

    var isRecording by remember { mutableStateOf(false) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var audioRecorder by remember { mutableStateOf<SessionAudioRecorder?>(null) }
    var sessionStartTime by remember { mutableStateOf(0L) }
    var summary by remember { mutableStateOf<String?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }
    var sessionSaved by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }

    val context = LocalContext.current
    val voiceProfileManager = remember { VoiceProfileManager(context) }
    var isVoiceEnrolled by remember { mutableStateOf(voiceProfileManager.isEnrolled) }

    val whisperModelManager = remember { WhisperModelManager(context) }

    val openAiService = remember(apiKey) {
        if (apiKey.isNotBlank()) {
            TranscriptionService(apiKey).also { it.voiceProfileManager = voiceProfileManager }
        } else null
    }
    val localServerClient = remember(serverUrl) { LocalServerClient(serverUrl) }
    val whisperClient = remember(onDeviceModel) {
        WhisperCppClient(context, onDeviceModel, whisperModelManager)
    }

    DisposableEffect(Unit) {
        onDispose {
            openAiService?.destroy()
            localServerClient.destroy()
            whisperClient.destroy()
        }
    }

    val activeBackend: TranscriptionBackend? = when (backendType) {
        BackendType.OPENAI_REALTIME -> openAiService
        BackendType.LOCAL_SERVER -> localServerClient
        BackendType.ON_DEVICE -> whisperClient
    }

    val emptySegmentsFlow = remember { MutableStateFlow<List<TranscriptSegment>>(emptyList()) }
    val emptyPartialFlow = remember { MutableStateFlow("") }
    val emptyConnFlow = remember { MutableStateFlow(false) }
    val emptyErrFlow = remember { MutableStateFlow<String?>(null) }

    val segments by (activeBackend?.segments ?: emptySegmentsFlow).collectAsState()
    val partialText by (activeBackend?.partialText ?: emptyPartialFlow).collectAsState()
    val isConnected by (activeBackend?.isConnected ?: emptyConnFlow).collectAsState()
    val transcriptionError by (activeBackend?.error ?: emptyErrFlow).collectAsState()

    val scope = rememberCoroutineScope()

    // Listen for STOP broadcast from foreground service notification
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == HelixForegroundService.ACTION_STOP && isRecording) {
                    isRecording = false
                    activeBackend?.stop()
                    val savedFile = audioRecorder?.close()
                    audioRecorder = null
                    // Auto-save session
                    currentSessionId?.let { id ->
                        sessionManager.stopSession(id, segments.toList(), null, hasAudio = savedFile != null, backendType = backendType.toKey())
                        sessionSaved = true
                    }
                    val fullText = segments.joinToString(" ") { it.text }
                    if (fullText.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val result = SummaryClient(apiKey).summarize(fullText)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (result.success) {
                                    summary = result.summary
                                    currentSessionId?.let { id ->
                                        sessionManager.updateSessionSummary(id, result.summary ?: "")
                                    }
                                }
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

    LaunchedEffect(backendType, activeBackend, isRecording) {
        if (isRecording && activeBackend != null) {
            bleManager.audioFrames.collect { frame ->
                activeBackend.feedAudio(frame)
                audioRecorder?.write(frame)
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
                        backendType = backendType,
                        onBackendTypeChange = {
                            backendType = it
                            sharedPrefs.edit().putString("backend_type", it.toKey()).apply()
                        },
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it; sharedPrefs.edit().putString("server_url", it).apply() },
                        onDeviceModel = onDeviceModel,
                        onOnDeviceModelChange = {
                            onDeviceModel = it
                            sharedPrefs.edit().putString("whisper_model", it.id).apply()
                        },
                        modelManager = whisperModelManager,
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
                    selectedSession?.let {
                        SessionDetailScreen(
                            session = it,
                            sessionManager = sessionManager,
                            onBack = { currentScreen = Screen.SESSIONS },
                            onDeleted = { selectedSession = null; currentScreen = Screen.SESSIONS }
                        )
                    } ?: run { currentScreen = Screen.SESSIONS }
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
                                batteryMillivolts = batteryMillivolts,
                                isRecording = isRecording,
                                elapsedSeconds = elapsedSeconds,
                                isWsConnected = isConnected,
                                backendLabel = backendType.toLabel()
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

                            // Auto-saved indicator + Dismiss button
                            if (!isRecording && segments.isNotEmpty() && sessionSaved) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "AUTO-SAVED",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = HelixTheme.TextSecondary,
                                        letterSpacing = 2.sp
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            currentSessionId?.let { id ->
                                                sessionManager.deleteSession(id)
                                                sessionSaved = false
                                                Toast.makeText(context, "Session dismissed", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = HelixTheme.SharpShape,
                                        border = BorderStroke(0.5.dp, HelixTheme.BorderDefault),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.TextMuted)
                                    ) {
                                        Text(
                                            "DISMISS",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Light,
                                            letterSpacing = 2.sp
                                        )
                                    }
                                }
                            }

                            // Bottom: Record button
                            Spacer(modifier = Modifier.height(12.dp))
                            RecordButton(
                                isRecording = isRecording,
                                canRecord = when (backendType) {
                                    BackendType.OPENAI_REALTIME -> apiKey.isNotBlank()
                                    BackendType.LOCAL_SERVER -> true
                                    BackendType.ON_DEVICE -> whisperModelManager.isReady(onDeviceModel)
                                },
                                pulseScale = pulseScale,
                                packetCount = packetCount,
                                onRecord = {
                                    when (backendType) {
                                        BackendType.OPENAI_REALTIME -> if (apiKey.isBlank()) {
                                            Toast.makeText(context, "Set API key or choose a different backend", Toast.LENGTH_SHORT).show()
                                            return@RecordButton
                                        }
                                        BackendType.ON_DEVICE -> if (!whisperModelManager.isReady(onDeviceModel)) {
                                            Toast.makeText(context, "Download the Whisper model in Settings", Toast.LENGTH_SHORT).show()
                                            return@RecordButton
                                        }
                                        BackendType.LOCAL_SERVER -> Unit
                                    }
                                    if (!isRecording) {
                                        summary = null; sessionSaved = false
                                        activeBackend?.start()
                                        bleManager.resetPacketCount()
                                        val newId = sessionManager.startSession()
                                        currentSessionId = newId
                                        audioRecorder = SessionAudioRecorder(context, newId)
                                        sessionStartTime = System.currentTimeMillis()
                                        isRecording = true
                                    } else {
                                        isRecording = false
                                        activeBackend?.stop()
                                        val savedFile = audioRecorder?.close()
                                        audioRecorder = null
                                        // Auto-save session immediately
                                        currentSessionId?.let { id ->
                                            sessionManager.stopSession(id, segments.toList(), null, hasAudio = savedFile != null, backendType = backendType.toKey())
                                            sessionSaved = true
                                        }
                                        val fullText = segments.joinToString(" ") { it.text }
                                        if (fullText.isNotBlank()) {
                                            isSummarizing = true
                                            scope.launch(Dispatchers.IO) {
                                                val result = SummaryClient(apiKey).summarize(fullText)
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    isSummarizing = false
                                                    if (result.success) {
                                                        summary = result.summary
                                                        // Update saved session with summary
                                                        currentSessionId?.let { id ->
                                                            sessionManager.updateSessionSummary(id, result.summary ?: "")
                                                        }
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
                                        HelixForegroundService.stop(context)
                                        activeBackend?.stop()
                                        audioRecorder?.close()
                                        audioRecorder = null
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
        Text(
            batLabel,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = HelixTheme.TextMuted
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
    var sessions by remember { mutableStateOf(sessionManager.loadSessions()) }
    val context = LocalContext.current

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
                        sessionManager.deleteAllSessions()
                        sessions = emptyList()
                        Toast.makeText(context, "All sessions deleted", Toast.LENGTH_SHORT).show()
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
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
                                    val time = SimpleDateFormat("mm:ss", Locale.getDefault()).format(Date(seg.timestamp))
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
        } else {
            SessionStatsView(session = session, modifier = Modifier.weight(1f))
        }

        // Delete button at bottom
        HairlineDivider()
        OutlinedButton(
            onClick = {
                sessionManager.deleteSession(session.id)
                Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                onDeleted()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .height(40.dp),
            shape = HelixTheme.SharpShape,
            border = BorderStroke(0.5.dp, HelixTheme.RecordingRed.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HelixTheme.RecordingRed)
        ) {
            Text(
                "DELETE SESSION",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                letterSpacing = 3.sp
            )
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
    backendType: BackendType,
    onBackendTypeChange: (BackendType) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onDeviceModel: WhisperModel,
    onOnDeviceModelChange: (WhisperModel) -> Unit,
    modelManager: WhisperModelManager,
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

    LaunchedEffect(serverUrl, backendType) {
        if (backendType == BackendType.LOCAL_SERVER) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val name = voiceProfileManager.checkServerEnrollment(serverUrl)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    serverEnrolledName = name; serverCheckDone = true; onEnrollmentChanged(name != null)
                }
            }
        } else { serverCheckDone = true }
    }

    val downloadState by modelManager.state.collectAsState()
    LaunchedEffect(onDeviceModel) { modelManager.refreshState(onDeviceModel) }

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

                BackendOption(
                    label = "OPENAI REALTIME",
                    subtitle = "Cloud. Uses API key above.",
                    selected = backendType == BackendType.OPENAI_REALTIME,
                    onClick = { onBackendTypeChange(BackendType.OPENAI_REALTIME) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                BackendOption(
                    label = "LOCAL SERVER",
                    subtitle = "Self-hosted STT on LAN.",
                    selected = backendType == BackendType.LOCAL_SERVER,
                    onClick = { onBackendTypeChange(BackendType.LOCAL_SERVER) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                BackendOption(
                    label = "ON DEVICE",
                    subtitle = "whisper.cpp + VAD. Fully offline.",
                    selected = backendType == BackendType.ON_DEVICE,
                    onClick = { onBackendTypeChange(BackendType.ON_DEVICE) }
                )

                if (backendType == BackendType.LOCAL_SERVER) {
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

                if (backendType == BackendType.ON_DEVICE) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "MODEL",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = HelixTheme.TextMuted, letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    WhisperModel.entries.forEach { m ->
                        BackendOption(
                            label = m.label,
                            subtitle = when (m) {
                                WhisperModel.TINY_EN -> "Fastest. Lowest accuracy."
                                WhisperModel.BASE_EN -> "Balanced. Good default."
                                WhisperModel.SMALL_EN -> "Most accurate. Slower."
                            },
                            selected = onDeviceModel == m,
                            onClick = { onOnDeviceModelChange(m) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    ModelDownloadControls(
                        model = onDeviceModel,
                        state = downloadState,
                        onDownload = {
                            scope.launch(Dispatchers.IO) {
                                modelManager.ensureDownloaded(onDeviceModel)
                            }
                        },
                        onCancel = { modelManager.cancel() },
                        onDelete = { modelManager.delete(onDeviceModel) }
                    )
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
                val useLocalServer = backendType == BackendType.LOCAL_SERVER

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
