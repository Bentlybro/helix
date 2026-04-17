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
        private const val SYSTEM_PROMPT = """Analyze this transcript and return structured JSON with these fields:
- summary: 2-3 sentence plain-English summary
- decisions: array of strings, key decisions made (empty array if none)
- action_items: array of objects with "task" (string) and "owner" (string or null) fields
- deadlines: array of strings describing deadlines/dates mentioned
- topics: array of 3-5 short topic tags

Return ONLY valid JSON, no markdown fences or commentary."""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ActionItem(val task: String, val owner: String?)

    data class SummaryResult(
        val success: Boolean,
        val summary: String? = null,
        val decisions: List<String> = emptyList(),
        val actionItems: List<ActionItem> = emptyList(),
        val deadlines: List<String> = emptyList(),
        val topics: List<String> = emptyList(),
        val error: String? = null
    ) {
        /** Render as markdown-like text for storage in Session.summary. */
        fun toMarkdown(): String {
            val sb = StringBuilder()
            if (!summary.isNullOrBlank()) sb.appendLine("**Summary**").appendLine(summary).appendLine()
            if (decisions.isNotEmpty()) {
                sb.appendLine("**Key Decisions**")
                decisions.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
            if (actionItems.isNotEmpty()) {
                sb.appendLine("**Action Items**")
                actionItems.forEach {
                    val ownerSuffix = it.owner?.let { o -> " (${o})" } ?: ""
                    sb.appendLine("- ${it.task}$ownerSuffix")
                }
                sb.appendLine()
            }
            if (deadlines.isNotEmpty()) {
                sb.appendLine("**Deadlines**")
                deadlines.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
            if (topics.isNotEmpty()) {
                sb.appendLine("**Topics**")
                sb.appendLine(topics.joinToString(" · "))
            }
            return sb.toString().trim()
        }
    }

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
                put("response_format", JSONObject().apply { put("type", "json_object") })
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

            Log.d(TAG, "Summary raw: ${content.take(200)}")

            // Parse the structured JSON response
            val parsed = parseStructured(content)
            val markdown = parsed.toMarkdown()
            return@withContext parsed.copy(summary = markdown.ifBlank { parsed.summary })
        } catch (e: Exception) {
            Log.e(TAG, "Summary error", e)
            return@withContext SummaryResult(false, error = e.message ?: "Unknown error")
        }
    }

    private fun parseStructured(jsonStr: String): SummaryResult {
        return try {
            val obj = JSONObject(jsonStr)
            val summary = obj.optString("summary", "")
            val decisions = obj.optJSONArray("decisions")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val actionItems = obj.optJSONArray("action_items")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ActionItem(
                        task = o.optString("task", ""),
                        owner = if (o.has("owner") && !o.isNull("owner")) o.getString("owner") else null
                    )
                }.filter { it.task.isNotBlank() }
            } ?: emptyList()
            val deadlines = obj.optJSONArray("deadlines")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val topics = obj.optJSONArray("topics")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            SummaryResult(
                success = true,
                summary = summary,
                decisions = decisions,
                actionItems = actionItems,
                deadlines = deadlines,
                topics = topics
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse structured summary, falling back to raw: ${e.message}")
            SummaryResult(success = true, summary = jsonStr)
        }
    }
}
