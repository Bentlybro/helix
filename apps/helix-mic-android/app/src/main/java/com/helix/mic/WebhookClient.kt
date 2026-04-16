package com.helix.mic

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebhookClient {

    companion object {
        private const val TAG = "WebhookClient"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun postSegment(url: String, segment: TranscriptSegment, sessionId: String?) {
        if (url.isBlank()) return
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("type", "segment")
                    put("sessionId", sessionId ?: JSONObject.NULL)
                    put("timestamp", segment.timestamp)
                    put("text", segment.text)
                    put("inferenceMs", segment.inferenceMs)
                    put("backendType", segment.backendType ?: JSONObject.NULL)
                    put("durationMs", segment.durationMs)
                    put("speaker", segment.speaker ?: JSONObject.NULL)
                }
                post(url, json)
            } catch (e: Exception) {
                Log.w(TAG, "postSegment failed: ${e.message}")
            }
        }
    }

    fun postSummary(
        url: String,
        summary: String,
        sessionId: String?,
        segments: List<TranscriptSegment>,
        sessionDurationMs: Long
    ) {
        if (url.isBlank()) return
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("type", "summary")
                    put("sessionId", sessionId ?: JSONObject.NULL)
                    put("summary", summary)
                    put("segmentCount", segments.size)
                    put("totalWords", segments.sumOf { it.text.split("\\s+".toRegex()).size })
                    put("sessionDurationMs", sessionDurationMs)
                }
                post(url, json)
            } catch (e: Exception) {
                Log.w(TAG, "postSummary failed: ${e.message}")
            }
        }
    }

    private fun post(url: String, json: JSONObject) {
        val body = json.toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) {
                Log.d(TAG, "POST $url → ${resp.code}")
            } else {
                Log.w(TAG, "POST $url → ${resp.code}: ${resp.message}")
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
