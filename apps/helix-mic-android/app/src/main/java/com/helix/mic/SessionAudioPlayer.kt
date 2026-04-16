package com.helix.mic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream

/**
 * Plays back a raw 16 kHz / 16-bit mono PCM file via AudioTrack.
 * Use one instance per playback session; call [stop] to abort.
 */
class SessionAudioPlayer {

    companion object {
        private const val TAG = "SessionAudioPlayer"
        private const val SAMPLE_RATE = 16000
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun play(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "No audio file: ${file.absolutePath}")
            return
        }
        if (_isPlaying.value) return
        job = scope.launch {
            _isPlaying.value = true
            _progressMs.value = 0L
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                val buf = ByteArray(minBuf)
                var streamed = 0L
                FileInputStream(file).use { input ->
                    while (isActive) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        var offset = 0
                        while (offset < n && isActive) {
                            val written = track.write(buf, offset, n - offset)
                            if (written <= 0) break
                            offset += written
                        }
                        streamed += n
                        _progressMs.value = (streamed * 1000L) / (SAMPLE_RATE * 2L)
                    }
                }
                try { track.stop() } catch (_: Throwable) {}
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                try { track.release() } catch (_: Throwable) {}
                _isPlaying.value = false
                _progressMs.value = 0L
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
