package com.helix.mic

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: HelixMicBleManager
    private lateinit var sharedPrefs: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = HelixMicBleManager(this)
        sharedPrefs = getSharedPreferences("helix_mic_prefs", Context.MODE_PRIVATE)
        enableEdgeToEdge()
        setContent { HelixMicApp(bleManager, sharedPrefs) }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HelixMicApp(bleManager: HelixMicBleManager, sharedPrefs: SharedPreferences) {
    val connectionState by bleManager.connectionState.collectAsState()
    val deviceAddress by bleManager.deviceAddress.collectAsState()
    val batteryPercent by bleManager.batteryPercent.collectAsState()
    val isReceivingAudio by bleManager.isReceivingAudio.collectAsState()
    val audioBuffer by bleManager.audioBuffer.collectAsState()
    val packetCount by bleManager.packetCount.collectAsState()
    val totalAudioBytes by bleManager.totalAudioBytesFlow.collectAsState()
    
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(sharedPrefs.getString("openai_api_key", "") ?: "") }
    var transcript by remember { mutableStateOf("") }
    var isTranscribing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var transcriptionError by remember { mutableStateOf<String?>(null) }
    
    val scope = rememberCoroutineScope()
    
    val blePermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    )
    
    // Pulse animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    // Calculate audio duration (16kHz, 16-bit mono = 32000 bytes/sec)
    val audioDurationSec = totalAudioBytes / 32000f
    
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF1A1A1A),
            primary = Color(0xFFFF4444),
            onBackground = Color.White,
            onSurface = Color.White,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (showSettings) {
                SettingsScreen(
                    apiKey = apiKey,
                    onApiKeyChange = { newKey ->
                        apiKey = newKey
                        sharedPrefs.edit().putString("openai_api_key", newKey).apply()
                    },
                    onBack = { showSettings = false }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .statusBarsPadding()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Helix Mic",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // Recording indicator
                            if (isReceivingAudio) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .scale(scale)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF4444))
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (batteryPercent != null) {
                                BatteryIndicator(batteryPercent!!)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            StatusDot(connectionState)
                            Spacer(modifier = Modifier.width(8.dp))
                            // Settings button
                            IconButton(onClick = { showSettings = true }) {
                                Text("⚙", fontSize = 20.sp, color = Color.Gray)
                            }
                        }
                    }
                    
                    Text(
                        text = when (connectionState) {
                            ConnectionState.DISCONNECTED -> "Not connected"
                            ConnectionState.SCANNING -> "Scanning..."
                            ConnectionState.CONNECTING -> "Connecting..."
                            ConnectionState.CONNECTED -> deviceAddress ?: "Connected"
                        },
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Audio status
                    if (connectionState == ConnectionState.CONNECTED) {
                        AudioStatusCard(
                            isReceivingAudio = isReceivingAudio,
                            packetCount = packetCount,
                            audioDurationSec = audioDurationSec
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    
                    // Transcript area
                    TranscriptArea(
                        transcript = transcript,
                        error = transcriptionError,
                        isTranscribing = isTranscribing
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Bottom buttons
                    if (connectionState == ConnectionState.CONNECTED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Clear button
                            OutlinedButton(
                                onClick = {
                                    bleManager.clearAudioBuffer()
                                    transcript = ""
                                    transcriptionError = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.Gray
                                )
                            ) {
                                Text("Clear", color = Color.Gray)
                            }
                            
                            // Play button
                            if (totalAudioBytes > 0) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                isPlaying = true
                                            }
                                            try {
                                                val pcmData = bleManager.getAudioBufferForTranscription()
                                                if (pcmData.isNotEmpty()) {
                                                    val minBuf = AudioTrack.getMinBufferSize(
                                                        16000,
                                                        AudioFormat.CHANNEL_OUT_MONO,
                                                        AudioFormat.ENCODING_PCM_16BIT
                                                    )
                                                    val bufSize = maxOf(minBuf, 4096)
                                                    val audioTrack = AudioTrack(
                                                        AudioAttributes.Builder()
                                                            .setUsage(AudioAttributes.USAGE_MEDIA)
                                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                            .build(),
                                                        AudioFormat.Builder()
                                                            .setSampleRate(16000)
                                                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                                            .build(),
                                                        bufSize,
                                                        AudioTrack.MODE_STREAM,
                                                        android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                                                    )
                                                    audioTrack.play()
                                                    // Stream in chunks to avoid blocking
                                                    var offset = 0
                                                    while (offset < pcmData.size) {
                                                        val chunkSize = minOf(bufSize, pcmData.size - offset)
                                                        audioTrack.write(pcmData, offset, chunkSize)
                                                        offset += chunkSize
                                                    }
                                                    // Wait for playback to finish
                                                    val durationMs = (pcmData.size / 32000f * 1000).toLong()
                                                    kotlinx.coroutines.delay(durationMs + 200)
                                                    audioTrack.stop()
                                                    audioTrack.release()
                                                }
                                            } finally {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    isPlaying = false
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isPlaying,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF00FF88)
                                    )
                                ) {
                                    Text(
                                        if (isPlaying) "Playing..." else "▶ Play",
                                        color = Color(0xFF00FF88)
                                    )
                                }
                            }
                            
                            // Transcribe button
                            Button(
                                onClick = {
                                    if (apiKey.isBlank()) {
                                        transcriptionError = "Set API key in settings first"
                                    } else {
                                        scope.launch {
                                            isTranscribing = true
                                            transcriptionError = null
                                            val client = WhisperClient(apiKey)
                                            val result = client.transcribe(bleManager.getAudioBufferForTranscription())
                                            isTranscribing = false
                                            when (result.state) {
                                                WhisperClient.TranscriptionState.SUCCESS -> {
                                                    transcript += result.text
                                                }
                                                WhisperClient.TranscriptionState.ERROR -> {
                                                    transcriptionError = result.error
                                                }
                                                else -> {}
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isTranscribing && totalAudioBytes > 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    if (isTranscribing) "Transcribing..." else "Transcribe",
                                    color = Color.White
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Connect/Disconnect
                    if (!blePermissions.allPermissionsGranted) {
                        Button(
                            onClick = { blePermissions.launchMultiplePermissionRequest() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Grant Bluetooth Permissions") }
                    } else {
                        Button(
                            onClick = {
                                if (connectionState == ConnectionState.DISCONNECTED) {
                                    bleManager.startScan()
                                } else {
                                    bleManager.disconnect()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (connectionState == ConnectionState.DISCONNECTED)
                                    MaterialTheme.colorScheme.primary else Color(0xFF333333)
                            )
                        ) {
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.DISCONNECTED -> "Connect to Helix Mic"
                                    ConnectionState.SCANNING -> "Scanning..."
                                    ConnectionState.CONNECTING -> "Connecting..."
                                    ConnectionState.CONNECTED -> "Disconnect"
                                },
                                color = if (connectionState == ConnectionState.DISCONNECTED)
                                    Color.White else Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.navigationBarsPadding())
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun AudioStatusCard(
    isReceivingAudio: Boolean,
    packetCount: Int,
    audioDurationSec: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isReceivingAudio) "Listening..." else "Ready",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isReceivingAudio) Color(0xFFFF4444) else Color(0xFF888888)
                )
                Text(
                    text = "$packetCount packets received",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.1fs".format(audioDurationSec),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF4444)
                )
                Text(
                    text = "buffered",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun TranscriptArea(
    transcript: String,
    error: String?,
    isTranscribing: Boolean
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(transcript) {
        if (transcript.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(Int.MAX_VALUE)
            }
        }
    }
    
    Column {
        Text(
            "Transcript",
            fontSize = 13.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(4.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isTranscribing) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFFFF4444),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Transcribing...",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                } else if (transcript.isEmpty() && error == null) {
                    Text(
                        "Transcript will appear here...",
                        fontSize = 14.sp,
                        color = Color(0xFF444444),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        if (error != null) {
                            item {
                                Text(
                                    "Error: $error",
                                    fontSize = 14.sp,
                                    color = Color(0xFFFF4444)
                                )
                            }
                        }
                        if (transcript.isNotEmpty()) {
                            item {
                                Text(
                                    transcript,
                                    fontSize = 16.sp,
                                    color = Color.White,
                                    lineHeight = 24.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onBack: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    
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
        
        Text(
            "OpenAI API Key",
            fontSize = 14.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("sk-...", color = Color(0xFF444444))
            },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(
                        if (showKey) "Hide" else "Show",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
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
            "Your API key is stored locally on this device and is only used to communicate with OpenAI's Whisper API.",
            fontSize = 12.sp,
            color = Color(0xFF666666)
        )
    }
}

@Composable
fun BatteryIndicator(percent: Int) {
    val color = when {
        percent > 50 -> Color(0xFF00FF88)
        percent > 20 -> Color(0xFFFFAA00)
        else -> Color(0xFFFF4444)
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$percent%", fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFF555555)
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> Color(0xFFFFAA00)
        ConnectionState.CONNECTED -> Color(0xFF00FF88)
    }
    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
}
