package com.helix.mic

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class TranscriptSegment(
    val text: String,
    val timestamp: Long,
    val durationMs: Long = 0L,
    val speaker: String? = null
)

class TranscriptionService(private val apiKey: String) {

    companion object {
        private const val TAG = "TranscriptionService"
        private const val WS_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    // Completed segments
    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    // Current partial text from deltas
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var active = false
    private var reconnectAttempts = 0
    private var sessionConfigured = false

    // Voice profile manager for speaker identification
    var voiceProfileManager: VoiceProfileManager? = null

    // Buffer recent audio for speaker identification per utterance
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioBufferLock = Any()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // keep alive
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun start() {
        active = true
        reconnectAttempts = 0
        sessionConfigured = false
        _segments.value = emptyList()
        _partialText.value = ""
        _error.value = null
        synchronized(audioBufferLock) { audioBuffer.clear() }
        voiceProfileManager?.resetSessionSpeakers()
        connectWebSocket()
        Log.d(TAG, "TranscriptionService started")
    }

    fun stop() {
        active = false
        webSocket?.close(1000, "Recording stopped")
        webSocket = null
        sessionConfigured = false
        _isConnected.value = false
        Log.d(TAG, "TranscriptionService stopped")
    }

    fun clear() {
        _segments.value = emptyList()
        _partialText.value = ""
        _error.value = null
    }

    /**
     * Feed raw PCM audio from BLE (16kHz 16-bit mono).
     * Resamples to 24kHz and sends over WebSocket.
     */
    fun feedAudio(pcmData: ByteArray) {
        if (!active || !sessionConfigured) return

        // Buffer raw PCM for speaker identification
        synchronized(audioBufferLock) {
            audioBuffer.add(pcmData.copyOf())
            // Keep ~10 seconds max at 16kHz 16-bit mono = ~320KB
            while (audioBuffer.sumOf { it.size } > 320_000) {
                audioBuffer.removeFirst()
            }
        }

        scope.launch {
            try {
                val resampled = resample16to24(pcmData)
                val b64 = Base64.encodeToString(resampled, Base64.NO_WRAP)
                val msg = JSONObject().apply {
                    put("type", "input_audio_buffer.append")
                    put("audio", b64)
                }
                webSocket?.send(msg.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio", e)
            }
        }
    }

    /**
     * Get buffered audio and clear the buffer (called when a segment completes).
     */
    private fun consumeAudioBuffer(): ByteArray {
        synchronized(audioBufferLock) {
            if (audioBuffer.isEmpty()) return ByteArray(0)
            val totalSize = audioBuffer.sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in audioBuffer) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            audioBuffer.clear()
            return result
        }
    }

    private fun connectWebSocket() {
        if (!active) return

        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _isConnected.value = true
                _error.value = null
                reconnectAttempts = 0
                sendSessionConfig(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _isConnected.value = false
                sessionConfigured = false
                maybeReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _isConnected.value = false
                sessionConfigured = false
                _error.value = "Connection failed: ${t.message}"
                maybeReconnect()
            }
        })
    }

    private fun sendSessionConfig(ws: WebSocket) {
        val config = JSONObject().apply {
            put("type", "transcription_session.update")
            put("session", JSONObject().apply {
                put("input_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "gpt-4o-mini-transcribe")
                    put("language", "en")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })
                put("input_audio_noise_reduction", JSONObject().apply {
                    put("type", "near_field")
                })
            })
        }
        ws.send(config.toString())
        Log.d(TAG, "Session config sent")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "transcription_session.created", "transcription_session.updated" -> {
                    sessionConfigured = true
                    Log.d(TAG, "Session configured: $type")
                }

                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        _partialText.value = _partialText.value + delta
                    }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "").trim()
                    if (transcript.isNotEmpty()) {
                        // Identify speaker from buffered audio
                        val speaker = try {
                            val bufferedAudio = consumeAudioBuffer()
                            if (bufferedAudio.isNotEmpty()) {
                                voiceProfileManager?.identifySpeaker(bufferedAudio)
                            } else null
                        } catch (e: Exception) {
                            Log.e(TAG, "Speaker identification failed", e)
                            null
                        }

                        val segment = TranscriptSegment(
                            text = transcript,
                            timestamp = System.currentTimeMillis(),
                            speaker = speaker
                        )
                        _segments.value = _segments.value + segment
                        Log.d(TAG, "Completed [${speaker ?: "?"}]: $transcript")
                    }
                    // Clear partial text for next utterance
                    _partialText.value = ""
                }

                "error" -> {
                    val errorObj = json.optJSONObject("error")
                    val errorMsg = errorObj?.optString("message", "Unknown error") ?: "Unknown error"
                    Log.e(TAG, "API error: $errorMsg")
                    _error.value = errorMsg
                }

                else -> {
                    Log.v(TAG, "Event: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    private fun maybeReconnect() {
        if (!active) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _error.value = "Connection lost after $MAX_RECONNECT_ATTEMPTS attempts"
            return
        }
        reconnectAttempts++
        Log.d(TAG, "Reconnecting (attempt $reconnectAttempts)...")
        scope.launch {
            delay(RECONNECT_DELAY_MS * reconnectAttempts)
            if (active) connectWebSocket()
        }
    }

    fun getFullTranscriptText(): String {
        return _segments.value.joinToString(" ") { it.text }
    }

    fun destroy() {
        active = false
        webSocket?.close(1000, "Destroyed")
        webSocket = null
        scope.cancel()
    }
}

/**
 * Resample 16kHz 16-bit LE mono PCM to 24kHz.
 * For every 2 input samples, produce 3 output samples via linear interpolation.
 */
fun resample16to24(input: ByteArray): ByteArray {
    if (input.size < 4) return input // need at least 2 samples

    val inBuf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
    val inCount = input.size / 2
    val inSamples = ShortArray(inCount)
    inBuf.asShortBuffer().get(inSamples)

    // Output: ratio 3:2
    val outCount = (inCount.toLong() * 3 / 2).toInt()
    val outSamples = ShortArray(outCount)

    var outIdx = 0
    var inIdx = 0
    while (inIdx < inCount - 1 && outIdx < outCount) {
        val s0 = inSamples[inIdx].toInt()
        val s1 = inSamples[inIdx + 1].toInt()

        // Output 3 samples for every 2 input samples
        outSamples[outIdx++] = s0.toShort()
        if (outIdx < outCount) outSamples[outIdx++] = ((s0 * 2 + s1) / 3).toShort()
        if (outIdx < outCount) outSamples[outIdx++] = ((s0 + s1 * 2) / 3).toShort()

        inIdx += 2
    }
    // Handle odd last sample
    if (inIdx < inCount && outIdx < outCount) {
        outSamples[outIdx++] = inSamples[inIdx].toShort()
    }

    val result = ByteArray(outIdx * 2)
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outSamples, 0, outIdx)
    return result
}
