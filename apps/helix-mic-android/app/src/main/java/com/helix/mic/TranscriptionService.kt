package com.helix.mic

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class TranscriptSegment(
    val text: String,
    val timestamp: Long,
    val durationMs: Long
)

class TranscriptionService(private val whisperClient: WhisperClient) {

    companion object {
        private const val TAG = "TranscriptionService"
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE // 32000

        // 5 seconds of speech audio before sending to Whisper
        private const val CHUNK_DURATION_BYTES = BYTES_PER_SECOND * 5 // 160000

        // 0.5s overlap to avoid cutting words at chunk boundaries
        private const val OVERLAP_BYTES = BYTES_PER_SECOND / 2 // 16000

        // VAD: 100ms frames
        private const val VAD_FRAME_BYTES = BYTES_PER_SECOND / 10 // 3200

        // Default RMS threshold for voice activity
        const val DEFAULT_VAD_THRESHOLD = 500
    }

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var vadThreshold: Int = DEFAULT_VAD_THRESHOLD

    // Accumulator for speech audio
    private val speechBuffer = mutableListOf<Byte>()
    private var overlapBytes = ByteArray(0)
    private var chunkStartTime = System.currentTimeMillis()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var active = false

    fun start() {
        active = true
        speechBuffer.clear()
        overlapBytes = ByteArray(0)
        chunkStartTime = System.currentTimeMillis()
        _segments.value = emptyList()
        _error.value = null
        Log.d(TAG, "TranscriptionService started")
    }

    fun stop() {
        active = false
        // Flush remaining speech buffer
        if (speechBuffer.size > BYTES_PER_SECOND / 2) { // at least 0.5s
            flushChunk()
        }
        Log.d(TAG, "TranscriptionService stopped")
    }

    fun clear() {
        speechBuffer.clear()
        overlapBytes = ByteArray(0)
        _segments.value = emptyList()
        _error.value = null
    }

    /**
     * Feed raw PCM audio data from BLE.
     * Called from BLE callback — must be fast. Actual processing is async.
     */
    fun feedAudio(pcmData: ByteArray) {
        if (!active) return

        // Run VAD + accumulation off the BLE thread
        scope.launch {
            processAudioFrame(pcmData)
        }
    }

    private suspend fun processAudioFrame(pcmData: ByteArray) {
        // Check VAD on this frame (or split into 100ms sub-frames if larger)
        val hasSpeech = containsSpeech(pcmData)

        if (hasSpeech) {
            synchronized(speechBuffer) {
                // If this is the first speech after silence, prepend overlap from previous chunk
                if (speechBuffer.isEmpty() && overlapBytes.isNotEmpty()) {
                    for (b in overlapBytes) speechBuffer.add(b)
                }
                for (b in pcmData) speechBuffer.add(b)

                // Check if we have enough speech (5 seconds)
                if (speechBuffer.size >= CHUNK_DURATION_BYTES) {
                    flushChunk()
                }
            }
        }
    }

    private fun flushChunk() {
        val pcmData: ByteArray
        synchronized(speechBuffer) {
            pcmData = speechBuffer.toByteArray()
            // Save last 0.5s as overlap for next chunk
            overlapBytes = if (pcmData.size > OVERLAP_BYTES) {
                pcmData.copyOfRange(pcmData.size - OVERLAP_BYTES, pcmData.size)
            } else {
                ByteArray(0)
            }
            speechBuffer.clear()
        }

        if (pcmData.isEmpty()) return

        val segmentTimestamp = chunkStartTime
        val durationMs = (pcmData.size.toLong() * 1000L) / BYTES_PER_SECOND
        chunkStartTime = System.currentTimeMillis()

        scope.launch {
            _isProcessing.value = true
            try {
                val result = whisperClient.transcribe(pcmData)
                when (result.state) {
                    WhisperClient.TranscriptionState.SUCCESS -> {
                        val text = result.text.trim()
                        if (text.isNotEmpty()) {
                            val segment = TranscriptSegment(
                                text = text,
                                timestamp = segmentTimestamp,
                                durationMs = durationMs
                            )
                            _segments.value = _segments.value + segment
                            Log.d(TAG, "Segment: $text")
                        }
                        _error.value = null
                    }
                    WhisperClient.TranscriptionState.ERROR -> {
                        Log.e(TAG, "Transcription error: ${result.error}")
                        _error.value = result.error
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription exception", e)
                _error.value = e.message
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Voice Activity Detection — calculate RMS of PCM samples.
     * Returns true if any 100ms frame exceeds the threshold.
     */
    private fun containsSpeech(pcmData: ByteArray): Boolean {
        if (pcmData.size < 2) return false

        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val numSamples = pcmData.size / BYTES_PER_SAMPLE
        val samplesPerFrame = VAD_FRAME_BYTES / BYTES_PER_SAMPLE // 1600 samples per 100ms

        var sampleIndex = 0
        while (sampleIndex < numSamples) {
            var sumSquares = 0.0
            val frameEnd = minOf(sampleIndex + samplesPerFrame, numSamples)
            val frameSamples = frameEnd - sampleIndex

            for (i in sampleIndex until frameEnd) {
                val sample = buffer.getShort(i * BYTES_PER_SAMPLE).toDouble()
                sumSquares += sample * sample
            }

            val rms = sqrt(sumSquares / frameSamples)
            if (rms > vadThreshold) return true

            sampleIndex = frameEnd
        }
        return false
    }

    fun getFullTranscriptText(): String {
        return _segments.value.joinToString(" ") { it.text }
    }

    fun destroy() {
        active = false
        scope.cancel()
    }
}
