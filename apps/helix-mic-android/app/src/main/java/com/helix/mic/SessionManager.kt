package com.helix.mic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Session(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val transcript: List<TranscriptSegment>,
    val summary: String?,
    val title: String? = null,
    val hasAudio: Boolean = false,
    val backendType: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startTime", startTime)
        put("endTime", endTime)
        put("summary", summary ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("hasAudio", hasAudio)
        put("backendType", backendType ?: JSONObject.NULL)
        put("transcript", JSONArray().apply {
            transcript.forEach { seg ->
                put(JSONObject().apply {
                    put("text", seg.text)
                    put("timestamp", seg.timestamp)
                    put("durationMs", seg.durationMs)
                    put("speaker", seg.speaker ?: JSONObject.NULL)
                    put("inferenceMs", seg.inferenceMs)
                    put("backendType", seg.backendType ?: JSONObject.NULL)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): Session {
            val transcriptArr = json.getJSONArray("transcript")
            val segments = (0 until transcriptArr.length()).map { i ->
                val seg = transcriptArr.getJSONObject(i)
                TranscriptSegment(
                    text = seg.getString("text"),
                    timestamp = seg.getLong("timestamp"),
                    durationMs = seg.optLong("durationMs", 0L),
                    speaker = if (seg.has("speaker") && !seg.isNull("speaker")) seg.getString("speaker") else null,
                    inferenceMs = seg.optLong("inferenceMs", 0L),
                    backendType = if (seg.has("backendType") && !seg.isNull("backendType")) seg.getString("backendType") else null
                )
            }
            return Session(
                id = json.getString("id"),
                startTime = json.getLong("startTime"),
                endTime = json.getLong("endTime"),
                transcript = segments,
                summary = if (json.isNull("summary")) null else json.getString("summary"),
                title = if (json.has("title") && !json.isNull("title")) json.getString("title") else null,
                hasAudio = json.optBoolean("hasAudio", false),
                backendType = if (json.has("backendType") && !json.isNull("backendType")) json.getString("backendType") else null
            )
        }
    }
}

class SessionManager(
    private val prefs: SharedPreferences,
    private val appContext: Context? = null
) {

    companion object {
        private const val TAG = "SessionManager"
        private const val SESSIONS_KEY = "saved_sessions"
        private const val MAX_SESSIONS = 50
    }

    private var currentSessionStart: Long = 0L

    fun startSession(): String {
        currentSessionStart = System.currentTimeMillis()
        Log.d(TAG, "Session started at $currentSessionStart")
        return UUID.randomUUID().toString()
    }

    fun stopSession(
        sessionId: String,
        transcript: List<TranscriptSegment>,
        summary: String? = null,
        hasAudio: Boolean = false,
        backendType: String? = null
    ): Session {
        val session = Session(
            id = sessionId,
            startTime = currentSessionStart,
            endTime = System.currentTimeMillis(),
            transcript = transcript,
            summary = summary,
            hasAudio = hasAudio,
            backendType = backendType
        )
        saveSession(session)
        Log.d(TAG, "Session $sessionId saved (${transcript.size} segments, audio=$hasAudio)")
        return session
    }

    fun updateSessionSummary(sessionId: String, summary: String) {
        val sessions = loadSessions().toMutableList()
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            sessions[index] = sessions[index].copy(summary = summary)
            saveSessions(sessions)
            Log.d(TAG, "Session $sessionId summary updated")
        }
    }

    fun loadSessions(): List<Session> {
        val json = prefs.getString(SESSIONS_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Session.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
    }

    fun deleteSession(sessionId: String) {
        val sessions = loadSessions().toMutableList()
        sessions.removeAll { it.id == sessionId }
        saveSessions(sessions)
        appContext?.let { SessionAudioRecorder.delete(it, sessionId) }
        Log.d(TAG, "Session $sessionId deleted")
    }

    fun deleteAllSessions() {
        saveSessions(emptyList())
        Log.d(TAG, "All sessions deleted")
    }

    fun updateSessionTitle(sessionId: String, title: String?) {
        val sessions = loadSessions().toMutableList()
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            sessions[index] = sessions[index].copy(title = title)
            saveSessions(sessions)
            Log.d(TAG, "Session $sessionId title updated")
        }
    }

    fun saveSession(session: Session) {
        val sessions = loadSessions().toMutableList()
        sessions.add(0, session) // newest first
        // Trim to max
        while (sessions.size > MAX_SESSIONS) sessions.removeLast()
        saveSessions(sessions)
    }

    private fun saveSessions(sessions: List<Session>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(SESSIONS_KEY, arr.toString()).apply()
    }
}
