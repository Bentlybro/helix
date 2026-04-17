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
    private val db: AppDatabase,
    private val appContext: Context? = null,
    legacyPrefs: SharedPreferences? = null
) {

    companion object {
        private const val TAG = "SessionManager"
        private const val LEGACY_SESSIONS_KEY = "saved_sessions"
    }

    private val dao: SessionDao = db.sessionDao()
    private var currentSessionStart: Long = 0L

    init {
        // Migrate existing SharedPreferences data into Room on first launch
        if (legacyPrefs != null) {
            migrateFromPrefs(legacyPrefs)
        }
    }

    private fun migrateFromPrefs(prefs: SharedPreferences) {
        val json = prefs.getString(LEGACY_SESSIONS_KEY, null) ?: return
        try {
            val arr = JSONArray(json)
            val count = arr.length()
            if (count == 0) return
            Log.d(TAG, "Migrating $count sessions from SharedPreferences to Room")
            for (i in 0 until count) {
                val session = Session.fromJson(arr.getJSONObject(i))
                dao.insert(SessionEntity.fromSession(session))
            }
            prefs.edit().remove(LEGACY_SESSIONS_KEY).apply()
            Log.d(TAG, "Migration complete, cleared SharedPreferences key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate sessions from SharedPreferences", e)
        }
    }

    fun startSession(): String {
        currentSessionStart = System.currentTimeMillis()
        Log.d(TAG, "Session started at $currentSessionStart")
        return UUID.randomUUID().toString()
    }

    /** Checkpoint an in-progress session so a crash doesn't lose transcript data. */
    fun checkpointSession(
        sessionId: String,
        transcript: List<TranscriptSegment>,
        hasAudio: Boolean = false,
        backendType: String? = null
    ) {
        val session = Session(
            id = sessionId,
            startTime = currentSessionStart,
            endTime = System.currentTimeMillis(),
            transcript = transcript,
            summary = null,
            hasAudio = hasAudio,
            backendType = backendType
        )
        dao.insert(SessionEntity.fromSession(session))
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
        dao.updateSummary(sessionId, summary)
        Log.d(TAG, "Session $sessionId summary updated")
    }

    fun loadSessions(): List<Session> {
        return try {
            dao.getAll().map { it.toSession() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
    }

    fun deleteSession(sessionId: String) {
        dao.delete(sessionId)
        appContext?.let { SessionAudioRecorder.delete(it, sessionId) }
        Log.d(TAG, "Session $sessionId deleted")
    }

    fun deleteAllSessions() {
        dao.deleteAll()
        Log.d(TAG, "All sessions deleted")
    }

    fun updateSessionTitle(sessionId: String, title: String?) {
        dao.updateTitle(sessionId, title)
        Log.d(TAG, "Session $sessionId title updated")
    }

    fun saveSession(session: Session) {
        dao.insert(SessionEntity.fromSession(session))
    }
}
