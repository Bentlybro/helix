package com.helix.mic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: HelixMicBleManager
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sessionManager: SessionManager
    private var transcriptionService: TranscriptionService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = HelixMicBleManager(this)
        sharedPrefs = getSharedPreferences("helix_mic_prefs", Context.MODE_PRIVATE)
        val database = AppDatabase.getInstance(applicationContext)
        sessionManager = SessionManager(database, applicationContext, legacyPrefs = sharedPrefs)
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
    val lastDisconnectReason by bleManager.lastDisconnectReason.collectAsState()

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

    // Webhook integration
    var webhookUrl by remember { mutableStateOf(sharedPrefs.getString("webhook_url", "") ?: "") }
    var webhookLive by remember { mutableStateOf(sharedPrefs.getBoolean("webhook_live_segments", false)) }
    var webhookSummary by remember { mutableStateOf(sharedPrefs.getBoolean("webhook_session_summary", false)) }
    val webhookClient = remember { WebhookClient() }
    var transcriptionLanguage by remember { mutableStateOf(sharedPrefs.getString("transcription_language", "en") ?: "en") }

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

    val openAiService = remember(apiKey, transcriptionLanguage) {
        if (apiKey.isNotBlank()) {
            TranscriptionService(apiKey, transcriptionLanguage).also { it.voiceProfileManager = voiceProfileManager }
        } else null
    }
    val localServerClient = remember(serverUrl) { LocalServerClient(serverUrl) }
    val whisperClient = remember(onDeviceModel, transcriptionLanguage) {
        WhisperCppClient(context, onDeviceModel, whisperModelManager, transcriptionLanguage)
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

    // Webhook: POST each new segment as it arrives
    LaunchedEffect(segments.size) {
        if (webhookLive && webhookUrl.isNotBlank() && isRecording && segments.isNotEmpty()) {
            webhookClient.postSegment(webhookUrl, segments.last(), currentSessionId)
        }
    }

    // Auto-checkpoint session every 30s during recording so a crash doesn't
    // lose the transcript. Uses the full session state for resilience.
    LaunchedEffect(isRecording, currentSessionId) {
        if (isRecording && currentSessionId != null) {
            while (isRecording) {
                kotlinx.coroutines.delay(30_000)
                val id = currentSessionId ?: break
                val segs = segments.toList()
                if (segs.isNotEmpty()) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        try {
                            sessionManager.checkpointSession(
                                id, segs,
                                hasAudio = audioRecorder != null,
                                backendType = backendType.toKey()
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("HelixMic", "Checkpoint failed", e)
                        }
                    }
                }
            }
        }
    }

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
                                        if (webhookSummary && webhookUrl.isNotBlank() && result.summary != null) {
                                            webhookClient.postSummary(webhookUrl, result.summary, id, segments.toList(), System.currentTimeMillis() - sessionStartTime)
                                        }
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
        if (connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.SCANNING ||
            connectionState == ConnectionState.CONNECTING) {
            // Keep service alive during reconnect attempts so Android doesn't
            // kill the app when screen is off.
            HelixForegroundService.start(context)
        } else if (connectionState == ConnectionState.DISCONNECTED && !isRecording) {
            // Only stop the service when fully disconnected AND not recording.
            // If recording, keep it alive so the session can resume on reconnect.
            HelixForegroundService.stop(context)
        }
    }

    // Update service notification state when recording changes
    LaunchedEffect(isRecording) {
        if (connectionState == ConnectionState.CONNECTED) {
            HelixForegroundService.setRecording(context, isRecording)
        }
    }

    // Low battery warning — toast once when crossing thresholds (30% and 10%)
    var lastBatteryWarning by remember { mutableStateOf(100) }
    LaunchedEffect(batteryPercent) {
        val pct = batteryPercent ?: return@LaunchedEffect
        if (pct < 10 && lastBatteryWarning >= 10) {
            Toast.makeText(context, "Helix battery critical: ${pct}%", Toast.LENGTH_LONG).show()
            lastBatteryWarning = pct
        } else if (pct < 30 && lastBatteryWarning >= 30) {
            Toast.makeText(context, "Helix battery low: ${pct}%", Toast.LENGTH_SHORT).show()
            lastBatteryWarning = pct
        } else if (pct >= 30) {
            lastBatteryWarning = 100 // Reset when charged back up
        }
    }

    // Track paused time so the timer shows actual recording duration,
    // not wall-clock time (pauses during disconnect).
    var pausedMs by remember { mutableStateOf(0L) }
    var pauseStartMs by remember { mutableStateOf(0L) }
    val isPaused = isRecording && connectionState != ConnectionState.CONNECTED

    LaunchedEffect(isPaused) {
        if (isPaused) {
            pauseStartMs = System.currentTimeMillis()
        } else if (pauseStartMs > 0) {
            pausedMs += System.currentTimeMillis() - pauseStartMs
            pauseStartMs = 0
        }
    }

    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(isRecording, sessionStartTime) {
        if (isRecording && sessionStartTime > 0) {
            while (true) {
                if (!isPaused) {
                    elapsedSeconds = (System.currentTimeMillis() - sessionStartTime - pausedMs) / 1000
                }
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
                        webhookUrl = webhookUrl,
                        onWebhookUrlChange = { webhookUrl = it; sharedPrefs.edit().putString("webhook_url", it).apply() },
                        webhookLive = webhookLive,
                        onWebhookLiveChange = { webhookLive = it; sharedPrefs.edit().putBoolean("webhook_live_segments", it).apply() },
                        webhookSummary = webhookSummary,
                        onWebhookSummaryChange = { webhookSummary = it; sharedPrefs.edit().putBoolean("webhook_session_summary", it).apply() },
                        transcriptionLanguage = transcriptionLanguage,
                        onLanguageChange = { transcriptionLanguage = it; sharedPrefs.edit().putString("transcription_language", it).apply() },
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

                                // Show last disconnect reason if available
                                if (lastDisconnectReason != null && connectionState != ConnectionState.CONNECTED) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        lastDisconnectReason ?: "",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = HelixTheme.TextMuted,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 32.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                                sessionStartMs = sessionStartTime,
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
                                                        currentSessionId?.let { id ->
                                                            sessionManager.updateSessionSummary(id, result.summary ?: "")
                                                            if (webhookSummary && webhookUrl.isNotBlank() && result.summary != null) {
                                                                webhookClient.postSummary(webhookUrl, result.summary, id, segments.toList(), System.currentTimeMillis() - sessionStartTime)
                                                            }
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
