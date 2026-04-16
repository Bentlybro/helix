package com.helix.mic

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    webhookUrl: String,
    onWebhookUrlChange: (String) -> Unit,
    webhookLive: Boolean,
    onWebhookLiveChange: (Boolean) -> Unit,
    webhookSummary: Boolean,
    onWebhookSummaryChange: (Boolean) -> Unit,
    transcriptionLanguage: String,
    onLanguageChange: (String) -> Unit,
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

    var settingsTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        ScreenHeader(title = "SETTINGS", onBack = onBack)
        HairlineDivider()

        // Tab selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            listOf("GENERAL", "BACKEND", "INTEGRATIONS").forEachIndexed { i, label ->
                Text(
                    label,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    color = if (settingsTab == i) HelixTheme.TextPrimary else HelixTheme.TextMuted,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable { settingsTab = i }
                )
            }
        }
        HairlineDivider()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

        // ── TAB 0: GENERAL ──
        if (settingsTab == 0) {

            // Language
            item {
                SectionHeader("LANGUAGE")
                Spacer(modifier = Modifier.height(8.dp))
                val languages = listOf(
                    "en" to "ENGLISH",
                    "auto" to "AUTO-DETECT",
                    "es" to "SPANISH",
                    "fr" to "FRENCH",
                    "de" to "GERMAN",
                    "it" to "ITALIAN",
                    "pt" to "PORTUGUESE",
                    "nl" to "DUTCH",
                    "ja" to "JAPANESE",
                    "ko" to "KOREAN",
                    "zh" to "CHINESE",
                    "ar" to "ARABIC",
                    "ru" to "RUSSIAN",
                    "hi" to "HINDI",
                    "pl" to "POLISH",
                    "tr" to "TURKISH",
                    "sv" to "SWEDISH",
                    "uk" to "UKRAINIAN"
                )
                languages.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { (code, label) ->
                            val selected = transcriptionLanguage == code
                            Text(
                                label,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (selected) HelixTheme.TextPrimary else HelixTheme.TextMuted,
                                letterSpacing = 1.sp,
                                modifier = Modifier
                                    .border(0.5.dp, if (selected) HelixTheme.TextPrimary else HelixTheme.Hairline)
                                    .clickable { onLanguageChange(code) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (transcriptionLanguage != "en" && transcriptionLanguage != "auto") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Non-English requires a multilingual model (BASE/SMALL Q5 Multi).",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HelixTheme.RecordingRed
                    )
                }
            }

            // API Key
            item {
                Spacer(modifier = Modifier.height(20.dp))
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

        } // end TAB 0: GENERAL

        // ── TAB 1: BACKEND ──
        if (settingsTab == 1) {

            item {
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
                                WhisperModel.TINY_EN -> "Fastest. English only."
                                WhisperModel.BASE_EN_Q5 -> "Fast + accurate. English only."
                                WhisperModel.BASE_EN -> "Full precision. English only."
                                WhisperModel.SMALL_EN_Q5 -> "Best quality. English only."
                                WhisperModel.SMALL_EN -> "Best quality. English only."
                                WhisperModel.BASE_Q5 -> "Fast + accurate. All languages."
                                WhisperModel.SMALL_Q5 -> "Best quality. All languages."
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

        } // end TAB 1: BACKEND

        // ── TAB 2: INTEGRATIONS ──
        if (settingsTab == 2) {
            item { SectionHeader("INTEGRATIONS") }
            item {
                Text(
                    "POST transcripts and summaries to an external endpoint.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HelixTheme.TextMuted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                Text("WEBHOOK URL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = webhookUrl,
                    onValueChange = onWebhookUrlChange,
                    placeholder = { Text("https://example.com/webhook", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = HelixTheme.TextMuted) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, HelixTheme.BorderDefault, HelixTheme.SharpShape),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HelixTheme.TextPrimary),
                    colors = inputColors,
                    shape = HelixTheme.SharpShape
                )
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = "LIVE SEGMENTS",
                    subtitle = "POST each segment as recognized.",
                    enabled = webhookLive,
                    onToggle = { onWebhookLiveChange(!webhookLive) }
                )
            }
            item {
                ToggleRow(
                    label = "SESSION SUMMARY",
                    subtitle = "POST summary when session ends.",
                    enabled = webhookSummary,
                    onToggle = { onWebhookSummaryChange(!webhookSummary) }
                )
            }

        } // end TAB 2: INTEGRATIONS

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}
