package com.helix.mic

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object SessionExporter {

    private const val TAG = "SessionExporter"

    fun exportTxt(context: Context, session: Session): File? {
        return try {
            val file = exportFile(context, session, "txt")
            file.writeText(buildTxt(session))
            file
        } catch (e: Exception) {
            Log.e(TAG, "TXT export failed", e)
            null
        }
    }

    fun exportSrt(context: Context, session: Session): File? {
        return try {
            val file = exportFile(context, session, "srt")
            file.writeText(buildSrt(session))
            file
        } catch (e: Exception) {
            Log.e(TAG, "SRT export failed", e)
            null
        }
    }

    fun shareSession(context: Context, session: Session) {
        val files = mutableListOf<File>()

        exportTxt(context, session)?.let { files.add(it) }
        exportSrt(context, session)?.let { files.add(it) }

        // Include audio WAV if available
        val audioFile = SessionAudioRecorder.fileFor(context, session.id)
        if (audioFile.exists()) {
            files.add(audioFile)
        }

        if (files.isEmpty()) return

        val uris = files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, "Helix Mic — ${session.title ?: formatDate(session.startTime)}")
        }
        context.startActivity(Intent.createChooser(intent, "Export session"))
    }

    private fun buildTxt(session: Session): String {
        val sb = StringBuilder()
        sb.appendLine("Helix Mic Transcript")
        sb.appendLine("Date: ${formatDate(session.startTime)}")
        sb.appendLine("Duration: ${formatDuration((session.endTime - session.startTime) / 1000)}")
        if (session.title != null) sb.appendLine("Title: ${session.title}")
        sb.appendLine()

        if (session.summary != null) {
            sb.appendLine("--- SUMMARY ---")
            sb.appendLine(stripMarkdown(session.summary))
            sb.appendLine()
        }

        sb.appendLine("--- TRANSCRIPT ---")
        session.transcript.forEach { seg ->
            val time = relativeTime(seg.timestamp, session.startTime)
            val speaker = if (seg.speaker != null) " [${seg.speaker}]" else ""
            sb.appendLine("[$time]$speaker ${seg.text}")
        }
        return sb.toString()
    }

    private fun buildSrt(session: Session): String {
        val sb = StringBuilder()
        session.transcript.forEachIndexed { i, seg ->
            val startMs = seg.timestamp - session.startTime
            val endMs = startMs + maxOf(seg.durationMs, 2000L)
            sb.appendLine("${i + 1}")
            sb.appendLine("${srtTimestamp(startMs)} --> ${srtTimestamp(endMs)}")
            if (seg.speaker != null) sb.append("[${seg.speaker}] ")
            sb.appendLine(seg.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun srtTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }

    private fun relativeTime(timestamp: Long, sessionStart: Long): String {
        val elapsed = (timestamp - sessionStart) / 1000
        val m = elapsed / 60
        val s = elapsed % 60
        return "%d:%02d".format(m, s)
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun exportFile(context: Context, session: Session, ext: String): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val name = (session.title ?: "helix-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date(session.startTime))}")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$name.$ext")
    }
}
