package com.helix.mic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class WhisperModel(
    val id: String,
    val fileName: String,
    val approxMb: Int,
    val label: String,
    val englishOnly: Boolean = true
) {
    TINY_EN("tiny.en", "ggml-tiny.en.bin", 39, "TINY.EN (39 MB)"),
    BASE_EN_Q5("base.en-q5", "ggml-base.en-q5_1.bin", 60, "BASE.EN Q5 (60 MB)"),
    BASE_EN("base.en", "ggml-base.en.bin", 142, "BASE.EN F16 (142 MB)"),
    SMALL_EN_Q5("small.en-q5", "ggml-small.en-q5_1.bin", 190, "SMALL.EN Q5 (190 MB)"),
    SMALL_EN("small.en", "ggml-small.en.bin", 466, "SMALL.EN F16 (466 MB)"),
    BASE_Q5("base-q5", "ggml-base-q5_1.bin", 60, "BASE Q5 Multi (60 MB)", englishOnly = false),
    SMALL_Q5("small-q5", "ggml-small-q5_1.bin", 190, "SMALL Q5 Multi (190 MB)", englishOnly = false);

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun fromId(id: String?): WhisperModel =
            entries.firstOrNull { it.id == id } ?: BASE_EN_Q5
    }
}

sealed class DownloadState {
    object Idle : DownloadState()
    object Ready : DownloadState()
    data class Downloading(val progressBytes: Long, val totalBytes: Long) : DownloadState() {
        val percent: Int get() = if (totalBytes <= 0) 0 else ((progressBytes * 100) / totalBytes).toInt()
    }
    data class Failed(val message: String) : DownloadState()
}

/**
 * Manages whisper.cpp model files on device.
 * Files live at: filesDir/whisper-models/<name>.bin
 */
class WhisperModelManager(private val context: Context) {

    companion object {
        private const val TAG = "WhisperModelManager"
        private const val DIR_NAME = "whisper-models"
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .build()

    @Volatile private var cancelRequested = false

    fun modelsDir(): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(model: WhisperModel): File = File(modelsDir(), model.fileName)

    fun isReady(model: WhisperModel): Boolean {
        val f = modelFile(model)
        return f.exists() && f.length() > 1_000_000L
    }

    fun refreshState(model: WhisperModel) {
        _state.value = if (isReady(model)) DownloadState.Ready else DownloadState.Idle
    }

    fun cancel() {
        cancelRequested = true
    }

    suspend fun ensureDownloaded(model: WhisperModel): Boolean = withContext(Dispatchers.IO) {
        if (isReady(model)) {
            _state.value = DownloadState.Ready
            return@withContext true
        }
        cancelRequested = false
        val target = modelFile(model)
        val tmp = File(target.parentFile, target.name + ".part")
        if (tmp.exists()) tmp.delete()

        try {
            _state.value = DownloadState.Downloading(0, (model.approxMb * 1024L * 1024L))
            val req = Request.Builder().url(model.downloadUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _state.value = DownloadState.Failed("HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: run {
                    _state.value = DownloadState.Failed("Empty body")
                    return@withContext false
                }
                val total = body.contentLength().takeIf { it > 0 } ?: (model.approxMb * 1024L * 1024L)
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastTick = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            if (cancelRequested) {
                                tmp.delete()
                                _state.value = DownloadState.Idle
                                return@withContext false
                            }
                            out.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 200) {
                                _state.value = DownloadState.Downloading(downloaded, total)
                                lastTick = now
                            }
                        }
                        out.flush()
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                _state.value = DownloadState.Failed("Rename failed")
                return@withContext false
            }
            _state.value = DownloadState.Ready
            Log.d(TAG, "Model ready: ${target.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            if (tmp.exists()) tmp.delete()
            _state.value = DownloadState.Failed(e.message ?: "Download failed")
            false
        }
    }

    fun delete(model: WhisperModel): Boolean {
        val f = modelFile(model)
        val ok = !f.exists() || f.delete()
        if (ok) _state.value = DownloadState.Idle
        return ok
    }
}
