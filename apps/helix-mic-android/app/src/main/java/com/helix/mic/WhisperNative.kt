package com.helix.mic

/**
 * JNI bridge to whisper.cpp.
 *
 * Contract (implemented in cpp/whisper_jni.cpp):
 *   nativeInit(modelPath)         -> handle (non-zero on success)
 *   nativeTranscribe(h, pcmF32)   -> transcribed text (empty on failure)
 *   nativeFree(h)                 -> releases the whisper context
 *
 * Input PCM must be 16kHz mono float32 in [-1.0, 1.0].
 */
object WhisperNative {
    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("whisper_jni")
            loaded = true
            true
        } catch (t: Throwable) {
            android.util.Log.e("WhisperNative", "Failed to load libwhisper_jni.so", t)
            false
        }
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeTranscribe(handle: Long, pcm: FloatArray): String
    external fun nativeFree(handle: Long)
}
