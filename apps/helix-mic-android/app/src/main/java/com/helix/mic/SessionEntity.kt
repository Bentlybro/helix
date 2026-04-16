package com.helix.mic

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "sessions")
@TypeConverters(TranscriptConverter::class)
data class SessionEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val transcript: List<TranscriptSegment>,
    val summary: String?,
    val title: String?,
    val hasAudio: Boolean,
    val backendType: String?
) {
    fun toSession(): Session = Session(
        id = id,
        startTime = startTime,
        endTime = endTime,
        transcript = transcript,
        summary = summary,
        title = title,
        hasAudio = hasAudio,
        backendType = backendType
    )

    companion object {
        fun fromSession(session: Session): SessionEntity = SessionEntity(
            id = session.id,
            startTime = session.startTime,
            endTime = session.endTime,
            transcript = session.transcript,
            summary = session.summary,
            title = session.title,
            hasAudio = session.hasAudio,
            backendType = session.backendType
        )
    }
}

class TranscriptConverter {
    @TypeConverter
    fun fromTranscript(segments: List<TranscriptSegment>): String {
        val arr = JSONArray()
        segments.forEach { seg ->
            arr.put(JSONObject().apply {
                put("text", seg.text)
                put("timestamp", seg.timestamp)
                put("durationMs", seg.durationMs)
                put("speaker", seg.speaker ?: JSONObject.NULL)
                put("inferenceMs", seg.inferenceMs)
                put("backendType", seg.backendType ?: JSONObject.NULL)
            })
        }
        return arr.toString()
    }

    @TypeConverter
    fun toTranscript(json: String): List<TranscriptSegment> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val seg = arr.getJSONObject(i)
                TranscriptSegment(
                    text = seg.getString("text"),
                    timestamp = seg.getLong("timestamp"),
                    durationMs = seg.optLong("durationMs", 0L),
                    speaker = if (seg.has("speaker") && !seg.isNull("speaker")) seg.getString("speaker") else null,
                    inferenceMs = seg.optLong("inferenceMs", 0L),
                    backendType = if (seg.has("backendType") && !seg.isNull("backendType")) seg.getString("backendType") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
