package com.helix.mic

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers

// ─────────────────────────────────────────────────────────────
//  VOICE ENROLLMENT
// ─────────────────────────────────────────────────────────────

val ENROLLMENT_PHRASES = listOf(
    "The quick brown fox jumps over the lazy dog",
    "I enjoy working on interesting projects every day",
    "Hello, my name is %s and this is my voice",
    "The weather today is quite nice for a walk outside",
    "Technology keeps getting better and more accessible"
)

private const val PHRASE_TIMEOUT_MS = 15000L

enum class EnrollStep { INTRO, LISTENING, MATCHED, PROCESSING, COMPLETE, ERROR }

fun phrasesMatch(spoken: String, target: String): Boolean {
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
