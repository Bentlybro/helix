package com.helix.mic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class WhisperClient(private val apiKey: String) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    companion object {
        private const val TAG = "WhisperClient"
        private const val API_URL = "https://api.openai.com/v1/audio/transcriptions"
    }
    
    enum class TranscriptionState {
        IDLE,
        TRANSCRIBING,
        SUCCESS,
        ERROR
    }
    
    data class TranscriptionResult(
        val state: TranscriptionState,
        val text: String = "",
        val error: String? = null
    )
    
    /**
     * Convert raw PCM 16kHz 16-bit mono audio to WAV format
     */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val sampleRate = 16000
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        
        val output = ByteArrayOutputStream()
        
        // RIFF header
        output.write("RIFF".toByteArray())
        writeLittleEndianInt(output, fileSize)
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        writeLittleEndianInt(output, 16) // chunk size
        writeLittleEndianShort(output, 1) // audio format (1 = PCM)
        writeLittleEndianShort(output, numChannels.toShort())
        writeLittleEndianInt(output, sampleRate)
        writeLittleEndianInt(output, byteRate)
        writeLittleEndianShort(output, blockAlign.toShort())
        writeLittleEndianShort(output, bitsPerSample.toShort())
        
        // data chunk
        output.write("data".toByteArray())
        writeLittleEndianInt(output, dataSize)
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    private fun writeLittleEndianInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }
    
    private fun writeLittleEndianShort(output: ByteArrayOutputStream, value: Short) {
        output.write(value.toInt() and 0xFF)
        output.write((value.toInt() shr 8) and 0xFF)
    }
    
    suspend fun transcribe(pcmData: ByteArray): TranscriptionResult = withContext(Dispatchers.IO) {
        if (pcmData.isEmpty()) {
            return@withContext TranscriptionResult(
                TranscriptionState.ERROR,
                error = "No audio data to transcribe"
            )
        }
        
        if (apiKey.isBlank()) {
            return@withContext TranscriptionResult(
                TranscriptionState.ERROR,
                error = "API key not set"
            )
        }
        
        try {
            // Convert PCM to WAV
            val wavData = pcmToWav(pcmData)
            Log.d(TAG, "Sending ${wavData.size} bytes of WAV audio to Whisper API")
            
            // Build multipart request
            val audioBody = wavData.toRequestBody("audio/wav".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", audioBody)
                .addFormDataPart("model", "whisper-1")
                .build()
            
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Whisper API error: ${response.code} - $errorBody")
                return@withContext TranscriptionResult(
                    TranscriptionState.ERROR,
                    error = "API error: ${response.code}"
                )
            }
            
            val responseBody = response.body?.string()
            if (responseBody == null) {
                return@withContext TranscriptionResult(
                    TranscriptionState.ERROR,
                    error = "Empty response from API"
                )
            }
            
            val json = JSONObject(responseBody)
            val transcript = json.getString("text")
            Log.d(TAG, "Transcription: $transcript")
            
            return@withContext TranscriptionResult(
                TranscriptionState.SUCCESS,
                text = transcript
            )
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            return@withContext TranscriptionResult(
                TranscriptionState.ERROR,
                error = e.message ?: "Unknown error"
            )
        }
    }
}
