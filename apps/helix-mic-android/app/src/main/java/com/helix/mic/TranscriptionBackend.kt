package com.helix.mic

import kotlinx.coroutines.flow.StateFlow

data class TranscriptSegment(
    val text: String,
    val timestamp: Long,
    val durationMs: Long = 0L,
    val speaker: String? = null
)

enum class BackendType {
    OPENAI_REALTIME,
    LOCAL_SERVER,
    ON_DEVICE;

    companion object {
        fun fromKey(key: String?): BackendType = when (key) {
            "local_server" -> LOCAL_SERVER
            "on_device" -> ON_DEVICE
            else -> OPENAI_REALTIME
        }
    }

    fun toKey(): String = when (this) {
        OPENAI_REALTIME -> "openai_realtime"
        LOCAL_SERVER -> "local_server"
        ON_DEVICE -> "on_device"
    }
}

interface TranscriptionBackend {
    val segments: StateFlow<List<TranscriptSegment>>
    val partialText: StateFlow<String>
    val isConnected: StateFlow<Boolean>
    val error: StateFlow<String?>

    fun start()
    fun stop()
    fun clear()
    fun feedAudio(pcmData: ByteArray)
    fun getFullTranscriptText(): String
    fun destroy()
}
