package com.helix.mic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device transcription backend.
 *
 * Pipeline:
 *   BLE PCM (16 kHz 16-bit LE mono)
 *     → convert to float32
 *     → Silero VAD frames (512 samples)
 *     → accumulate speech-only chunks (with pre-roll + max length)
 *     → whisper.cpp via JNI
 *     → emit TranscriptSegment
 */
class WhisperCppClient(
    private val context: Context,
    private val model: WhisperModel,
    private val modelManager: WhisperModelManager
) : TranscriptionBackend {

    companion object {
        private const val TAG = "WhisperCppClient"
        private const val SAMPLE_RATE = 16000
        private const val VAD_FRAME = SileroVad.FRAME_SIZE_16K         // 512 samples = 32 ms
        private const val VAD_SPEECH_THRESHOLD = 0.35f
        private const val VAD_SILENCE_THRESHOLD = 0.25f
        // The Helix Mic's PDM signal is quiet (~-14 dBFS peaks). Boost in software
        // so Silero — which is trained on typical mic levels — sees a fair signal.
        private const val INPUT_GAIN = 3.0f

        // Chunk boundaries
        private const val PRE_ROLL_MS = 300
        private const val MIN_SILENCE_MS = 500
        private const val MAX_CHUNK_MS = 20000
        private const val MIN_CHUNK_MS = 300

        private const val PRE_ROLL_SAMPLES = SAMPLE_RATE * PRE_ROLL_MS / 1000
        private const val MIN_SILENCE_FRAMES = (SAMPLE_RATE * MIN_SILENCE_MS / 1000) / VAD_FRAME
        private const val MAX_CHUNK_SAMPLES = SAMPLE_RATE * MAX_CHUNK_MS / 1000
        private const val MIN_CHUNK_SAMPLES = SAMPLE_RATE * MIN_CHUNK_MS / 1000
    }

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    override val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val vad = SileroVad(context)

    @Volatile private var active = false
    @Volatile private var whisperHandle: Long = 0L

    @Volatile private var feedBytes = 0L
    @Volatile private var workerSamples = 0L
    @Volatile private var lastProb = 0f
    @Volatile private var peakAbs = 0f
    @Volatile private var sumSq = 0.0
    @Volatile private var sumSqCount = 0L
    private var lastDiagTs = 0L

    private val audioChannel = Channel<FloatArray>(capacity = 64)
    private var feederJob: Job? = null
    private var workerJob: Job? = null
    private var loadJob: Job? = null

    override fun start() {
        if (active) return
        _segments.value = emptyList()
        _partialText.value = ""
        _error.value = null
        active = true
        loadJob = scope.launch { ensureReady() }
    }

    override fun stop() {
        if (!active) return
        active = false
        workerJob?.cancel()
        feederJob?.cancel()
        _isConnected.value = false
    }

    override fun clear() {
        _segments.value = emptyList()
        _partialText.value = ""
        _error.value = null
    }

    override fun feedAudio(pcmData: ByteArray) {
        if (!active) {
            Log.v(TAG, "feedAudio dropped: not active")
            return
        }
        if (!_isConnected.value) {
            Log.v(TAG, "feedAudio dropped: not connected")
            return
        }
        val floats = pcm16ToFloat(pcmData)
        if (floats.isEmpty()) return
        feedBytes += pcmData.size
        var localPeak = peakAbs
        for (f in floats) {
            val a = if (f < 0f) -f else f
            if (a > localPeak) localPeak = a
            sumSq += (f * f).toDouble()
        }
        peakAbs = localPeak
        sumSqCount += floats.size
        val sendOk = audioChannel.trySend(floats).isSuccess
        val now = System.currentTimeMillis()
        if (now - lastDiagTs > 1000) {
            lastDiagTs = now
            val rms = if (sumSqCount > 0) kotlin.math.sqrt(sumSq / sumSqCount).toFloat() else 0f
            Log.d(TAG, "feed ${feedBytes}B → worker ${workerSamples} samples, peak=${"%.4f".format(peakAbs)}, rms=${"%.4f".format(rms)}, prob=${"%.3f".format(lastProb)}, sendOk=$sendOk")
            feedBytes = 0
            workerSamples = 0
            peakAbs = 0f
            sumSq = 0.0
            sumSqCount = 0
        }
    }

    override fun getFullTranscriptText(): String =
        _segments.value.joinToString(" ") { it.text }

    override fun destroy() {
        active = false
        scope.cancel()
        releaseWhisper()
        vad.close()
    }

    // ─── internal ──────────────────────────────────────────────

    private suspend fun ensureReady() {
        if (!WhisperNative.ensureLoaded()) {
            _error.value = "Native library not loaded"
            return
        }
        if (!modelManager.ensureDownloaded(model)) {
            _error.value = "Model not available"
            return
        }
        if (!vad.init()) {
            _error.value = "VAD init failed"
            return
        }

        val path = modelManager.modelFile(model).absolutePath
        whisperHandle = withContext(Dispatchers.IO) {
            WhisperNative.nativeInit(path)
        }
        if (whisperHandle == 0L) {
            _error.value = "Whisper init failed"
            return
        }

        _isConnected.value = true
        startWorker()
    }

    private fun startWorker() {
        workerJob = scope.launch(Dispatchers.Default) {
            val preRoll = ArrayDeque<Float>(PRE_ROLL_SAMPLES)
            val speech = ArrayList<Float>(SAMPLE_RATE * 5)
            var vadFrame = FloatArray(VAD_FRAME)
            var vadFill = 0
            var speaking = false
            var silenceFrames = 0

            fun flushSpeech() {
                val size = speech.size
                if (size < MIN_CHUNK_SAMPLES) {
                    Log.d(TAG, "flush skipped (too short: $size samples)")
                    speech.clear(); speaking = false; silenceFrames = 0
                    return
                }
                val audio = FloatArray(size)
                for (i in speech.indices) audio[i] = speech[i]
                speech.clear()
                speaking = false
                silenceFrames = 0
                Log.d(TAG, "flush -> transcribe $size samples (${size / 16} ms)")
                scope.launch(Dispatchers.IO) { transcribeChunk(audio) }
            }

            Log.d(TAG, "Worker started")
            try {
                for (incoming in audioChannel) {
                    if (!active) break
                    workerSamples += incoming.size
                    for (sample in incoming) {
                        vadFrame[vadFill++] = sample
                        if (!speaking) {
                            preRoll.addLast(sample)
                            while (preRoll.size > PRE_ROLL_SAMPLES) preRoll.removeFirst()
                        } else {
                            speech.add(sample)
                            if (speech.size >= MAX_CHUNK_SAMPLES) {
                                flushSpeech()
                            }
                        }

                        if (vadFill == VAD_FRAME) {
                            val prob = vad.process(vadFrame)
                            lastProb = prob
                            vadFrame = FloatArray(VAD_FRAME)
                            vadFill = 0

                            if (!speaking) {
                                if (prob >= VAD_SPEECH_THRESHOLD) {
                                    speaking = true
                                    silenceFrames = 0
                                    for (s in preRoll) speech.add(s)
                                    Log.d(TAG, "VAD -> speech (prob=${"%.3f".format(prob)}, preRoll=${preRoll.size})")
                                }
                            } else {
                                if (prob < VAD_SILENCE_THRESHOLD) {
                                    silenceFrames++
                                    if (silenceFrames >= MIN_SILENCE_FRAMES) flushSpeech()
                                } else {
                                    silenceFrames = 0
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // normal shutdown
            } catch (e: Throwable) {
                Log.e(TAG, "Worker error", e)
                _error.value = e.message
            }
        }
    }

    private fun transcribeChunk(audio: FloatArray) {
        val h = whisperHandle
        if (h == 0L) { Log.w(TAG, "transcribeChunk: no whisper handle"); return }
        val startMs = System.currentTimeMillis()
        try {
            val text = WhisperNative.nativeTranscribe(h, audio).trim()
            val elapsed = System.currentTimeMillis() - startMs
            if (text.isNotEmpty()) {
                val segment = TranscriptSegment(
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    durationMs = (audio.size.toLong() * 1000L) / SAMPLE_RATE,
                    speaker = null
                )
                _segments.value = _segments.value + segment
                Log.d(TAG, "chunk(${audio.size}, ${elapsed}ms): $text")
            } else {
                Log.d(TAG, "chunk(${audio.size}, ${elapsed}ms): <empty>")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Transcribe failed", t)
            _error.value = t.message
        }
    }

    private fun releaseWhisper() {
        val h = whisperHandle
        if (h != 0L) {
            try { WhisperNative.nativeFree(h) } catch (_: Throwable) {}
        }
        whisperHandle = 0L
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val n = bytes.size / 2
        if (n == 0) return FloatArray(0)
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            var s = (bb.short.toFloat() / 32768.0f) * INPUT_GAIN
            if (s > 1.0f) s = 1.0f else if (s < -1.0f) s = -1.0f
            out[i] = s
        }
        return out
    }
}
