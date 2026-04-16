package com.helix.mic

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Streams raw 16 kHz / 16-bit LE mono PCM from BLE to a file on disk.
 * One instance per session. Safe to call [write] from any thread — internal
 * sync uses a small monitor because BLE callbacks are serialized anyway.
 */
class SessionAudioRecorder(context: Context, sessionId: String) {

    companion object {
        private const val TAG = "SessionAudioRecorder"
        const val DIR_NAME = "session-audio"
        const val EXTENSION = "pcm"

        fun fileFor(context: Context, sessionId: String): File {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "$sessionId.$EXTENSION")
        }

        fun delete(context: Context, sessionId: String) {
            fileFor(context, sessionId).takeIf { it.exists() }?.delete()
        }
    }

    private val file: File = fileFor(context, sessionId)
    private var out: BufferedOutputStream? = null
    @Volatile private var totalBytes: Long = 0L
    private val lock = Any()

    init {
        try {
            out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
            Log.d(TAG, "Recording to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open audio file", e)
        }
    }

    fun write(pcm: ByteArray) {
        val stream = out ?: return
        synchronized(lock) {
            try {
                stream.write(pcm)
                totalBytes += pcm.size
            } catch (e: Exception) {
                Log.e(TAG, "Write failed", e)
            }
        }
    }

    fun close(): File? {
        synchronized(lock) {
            return try {
                out?.flush()
                out?.close()
                out = null
                Log.d(TAG, "Closed, ${totalBytes}B written")
                if (totalBytes > 0) file else { file.delete(); null }
            } catch (e: Exception) {
                Log.e(TAG, "Close failed", e)
                null
            }
        }
    }

    fun bytesWritten(): Long = totalBytes
}
