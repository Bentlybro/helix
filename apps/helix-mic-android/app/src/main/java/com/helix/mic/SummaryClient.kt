package com.helix.mic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SummaryClient(private val apiKey: String) {

    companion object {
        private const val TAG = "SummaryClient"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val SYSTEM_PROMPT = """Analyze this meeting transcript. Provide:
1) Brief summary (2-3 sentences)
2) Key decisions made
3) Action items with owners if mentioned
4) Any deadlines or dates mentioned

Format your response with clear headers using markdown bold (**Summary**, **Key Decisions**, **Action Items**, **Deadlines**). If a section has no items, write "None identified." Keep it concise."""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class SummaryResult(
        val success: Boolean,
        val summary: String? = null,
        val error: String? = null
    )

    suspend fun summarize(transcriptText: String): SummaryResult = withContext(Dispatchers.IO) {
        if (transcriptText.isBlank()) {
            return@withContext SummaryResult(false, error = "No transcript to summarize")
        }

        if (apiKey.isBlank()) {
            return@withContext SummaryResult(false, error = "API key not set")
        }

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Here is the meeting transcript:\n\n$transcriptText")
                })
            }

            val body = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", messages)
                put("max_tokens", 1024)
                put("temperature", 0.3)
            }

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "ChatGPT API error: ${response.code} - $errorBody")
                return@withContext SummaryResult(false, error = "API error: ${response.code}")
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                return@withContext SummaryResult(false, error = "Empty response")
            }

            val json = JSONObject(responseBody)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Log.d(TAG, "Summary generated: ${content.take(100)}...")
            return@withContext SummaryResult(true, summary = content)
        } catch (e: Exception) {
            Log.e(TAG, "Summary error", e)
            return@withContext SummaryResult(false, error = e.message ?: "Unknown error")
        }
    }
}
