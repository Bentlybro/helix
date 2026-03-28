package com.helix.mic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Voice profile manager that enrolls via BLE mic audio and the Helix STT server's
 * Resemblyzer-based speaker identification endpoint.
 */
class VoiceProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfileManager"
        private const val PREFS_NAME = "voice_profile_prefs"
        private const val KEY_ENROLLED_NAME = "enrolled_name"
        private const val KEY_IS_ENROLLED = "is_enrolled"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Enrollment audio collection
    private var collectJob: Job? = null
    private val audioBuffer = ByteArrayOutputStream()

    val isEnrolled: Boolean get() = prefs.getBoolean(KEY_IS_ENROLLED, false)
    val enrolledName: String? get() = prefs.getString(KEY_ENROLLED_NAME, null)

    /**
     * Start collecting BLE audio frames for enrollment.
     * Call [stopCollecting] to get the accumulated PCM data.
     */
    fun startCollecting(audioFrames: SharedFlow<ByteArray>, scope: CoroutineScope) {
        audioBuffer.reset()
        collectJob = scope.launch(Dispatchers.Default) {
            audioFrames.collect { frame ->
                synchronized(audioBuffer) {
                    audioBuffer.write(frame)
                }
            }
        }
    }

    /**
     * Stop collecting and return accumulated raw PCM bytes.
     */
    fun stopCollecting(): ByteArray {
        collectJob?.cancel()
        collectJob = null
        val data: ByteArray
        synchronized(audioBuffer) {
            data = audioBuffer.toByteArray()
            audioBuffer.reset()
        }
        Log.d(TAG, "Collected ${data.size} bytes of PCM audio")
        return data
    }

    /**
     * Send collected PCM audio to the server for enrollment.
     * @param pcmData Raw PCM 16kHz 16-bit mono LE bytes
     * @param name Speaker name
     * @param serverWsUrl The WebSocket URL from settings (e.g. ws://192.168.0.35:8770/ws/stream)
     * @return Result with success/error info
     */
    fun enrollOnServer(pcmData: ByteArray, name: String, serverWsUrl: String): EnrollResult {
        val httpUrl = wsUrlToHttpEnroll(serverWsUrl)
        Log.d(TAG, "Enrolling '$name' at $httpUrl with ${pcmData.size} bytes")

        // Build WAV wrapper around raw PCM (server expects UploadFile which auto-detects)
        val wavBytes = pcmToWav(pcmData, 16000, 1, 16)

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart(
                "file", "enrollment.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(httpUrl)
            .post(body)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                if (json.optString("status") == "enrolled") {
                    saveProfile(name)
                    Log.d(TAG, "Enrollment successful for '$name'")
                    EnrollResult(true, null)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Log.e(TAG, "Enrollment failed: $error")
                    EnrollResult(false, error)
                }
            } else {
                val error = try {
                    JSONObject(responseBody).optString("error", "Server error ${response.code}")
                } catch (_: Exception) {
                    "Server error ${response.code}"
                }
                Log.e(TAG, "Enrollment HTTP error: $error")
                EnrollResult(false, error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment network error", e)
            EnrollResult(false, e.message ?: "Network error")
        }
    }

    /**
     * Check the server for enrolled speakers. Returns the first enrolled speaker name, or null.
     */
    fun checkServerEnrollment(serverWsUrl: String): String? {
        val httpBase = wsUrlToHttpBase(serverWsUrl)
        return try {
            val request = Request.Builder()
                .url("$httpBase/api/speakers")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "[]"
            if (response.isSuccessful) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val name = arr.getString(0)
                    // Sync local state
                    saveProfile(name)
                    name
                } else {
                    // No speakers on server — clear local state
                    prefs.edit()
                        .remove(KEY_ENROLLED_NAME)
                        .putBoolean(KEY_IS_ENROLLED, false)
                        .apply()
                    null
                }
            } else {
                Log.w(TAG, "Failed to check speakers: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check server enrollment: ${e.message}")
            null
        }
    }

    /**
     * Delete speaker profile on the server and clear local state.
     */
    fun clearProfile(serverWsUrl: String? = null) {
        val name = enrolledName
        if (name != null && serverWsUrl != null) {
            try {
                val httpBase = wsUrlToHttpBase(serverWsUrl)
                val request = Request.Builder()
                    .url("$httpBase/api/speakers/$name")
                    .delete()
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete server profile: ${e.message}")
            }
        }
        prefs.edit()
            .remove(KEY_ENROLLED_NAME)
            .putBoolean(KEY_IS_ENROLLED, false)
            .apply()
        Log.d(TAG, "Voice profile cleared")
    }

    private fun saveProfile(name: String) {
        prefs.edit()
            .putString(KEY_ENROLLED_NAME, name)
            .putBoolean(KEY_IS_ENROLLED, true)
            .apply()
    }

    /**
     * Convert ws://host:port/ws/stream -> http://host:port/api/enroll
     */
    private fun wsUrlToHttpEnroll(wsUrl: String): String {
        return wsUrlToHttpBase(wsUrl) + "/api/enroll"
    }

    private fun wsUrlToHttpBase(wsUrl: String): String {
        return wsUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .replace("/ws/stream", "")
            .trimEnd('/')
    }

    /**
     * Wrap raw PCM data in a minimal WAV header.
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val headerSize = 44
        val totalSize = headerSize + dataSize

        val wav = ByteArray(totalSize)
        val bb = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        bb.put('R'.code.toByte())
        bb.put('I'.code.toByte())
        bb.put('F'.code.toByte())
        bb.put('F'.code.toByte())
        bb.putInt(totalSize - 8)
        bb.put('W'.code.toByte())
        bb.put('A'.code.toByte())
        bb.put('V'.code.toByte())
        bb.put('E'.code.toByte())

        // fmt chunk
        bb.put('f'.code.toByte())
        bb.put('m'.code.toByte())
        bb.put('t'.code.toByte())
        bb.put(' '.code.toByte())
        bb.putInt(16) // chunk size
        bb.putShort(1) // PCM format
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())

        // data chunk
        bb.put('d'.code.toByte())
        bb.put('a'.code.toByte())
        bb.put('t'.code.toByte())
        bb.put('a'.code.toByte())
        bb.putInt(dataSize)
        bb.put(pcmData)

        return wav
    }

    data class EnrollResult(val success: Boolean, val error: String?)
}
