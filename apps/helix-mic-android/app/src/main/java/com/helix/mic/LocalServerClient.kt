package com.helix.mic

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the Helix STT local server.
 * Connects via WebSocket, sends raw PCM bytes, receives JSON transcription events.
 */
class LocalServerClient(private val serverUrl: String) {

    companion object {
        private const val TAG = "LocalServerClient"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun start() {
        active = true
        reconnectAttempts = 0
        _segments.value = emptyList()
        _partialText.value = ""
        _error.value = null
        connectWebSocket()
        Log.d(TAG, "LocalServerClient started, connecting to $serverUrl")
    }

    fun stop() {
        active = false
        webSocket?.close(1000, "Recording stopped")
        webSocket = null
        _isConnected.value = false
        Log.d(TAG, "LocalServerClient stopped")
    }

    fun clear() {
        _segments.value = emptyList()
        _partialText.value = ""
        _error.value = null
    }

    /**
     * Send raw PCM audio directly — no encoding, no resampling.
     * Server expects 16kHz 16-bit mono little-endian PCM.
     */
    fun feedAudio(pcmData: ByteArray) {
        if (!active || !_isConnected.value) return
        try {
            webSocket?.send(okio.ByteString.of(*pcmData))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio", e)
        }
    }

    private fun connectWebSocket() {
        if (!active) return

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to local server")
                _isConnected.value = true
                _error.value = null
                reconnectAttempts = 0
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
                maybeReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _isConnected.value = false
                _error.value = "Connection failed: ${t.message}"
                maybeReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type", "")) {
                "partial" -> {
                    val partialStr = json.optString("text", "")
                    if (partialStr.isNotEmpty()) {
                        _partialText.value = partialStr
                    }
                }

                "final" -> {
                    val transcript = json.optString("text", "").trim()
                    if (transcript.isNotEmpty()) {
                        val speaker = if (json.has("speaker") && !json.isNull("speaker")) {
                            json.getString("speaker")
                        } else null

                        val segment = TranscriptSegment(
                            text = transcript,
                            timestamp = System.currentTimeMillis(),
                            speaker = speaker
                        )
                        _segments.value = _segments.value + segment
                        Log.d(TAG, "Final [${speaker ?: "?"}]: $transcript")
                    }
                    _partialText.value = ""
                }

                "vad" -> {
                    val speaking = json.optBoolean("speaking", false)
                    Log.v(TAG, "VAD: speaking=$speaking")
                }

                "error" -> {
                    val msg = json.optString("message", "Unknown error")
                    Log.e(TAG, "Server error: $msg")
                    _error.value = msg
                }

                "ping" -> {
                    // Respond to keepalive
                    webSocket?.send("{\"command\":\"ping\"}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $text", e)
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
