package dev.plume

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * A small structured diary of everything that matters for cross-vendor compatibility: crashes,
 * blocked quiz pop-ups, lost foreground, dead accessibility binds, missing speech engines, failed
 * updates. One JSON object per line in files/diag_events.jsonl; [ReportUpload] ships the fresh
 * lines home once a day.
 *
 * Lines are built by hand (not org.json) so the pure parts — [line], [tsOf], [trimmed] — run in
 * plain JVM unit tests. Writes are tiny appends; the file is trimmed to [TRIM_TO] lines whenever
 * it grows past [MAX_LINES], so a TV that never reaches the server still keeps the newest events.
 */
object DiagLog {
    private const val FILE = "diag_events.jsonl"
    private const val MAX_LINES = 2000
    private const val TRIM_TO = 1500
    private const val MAX_MSG = 500

    @Volatile private var app: Context? = null
    @Volatile private var lines = -1 // lines in the file; -1 = not counted yet

    /** Called once from App.onCreate; every event after that lands on disk. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    /**
     * Records one event. [type] is a stable snake_case name the report analysis groups by
     * ("crash", "quiz_popup_blocked", ...); [msg] is free text; [kv] carries small flat values.
     */
    @Synchronized
    fun event(type: String, msg: String = "", kv: Map<String, String> = emptyMap()) {
        val context = app ?: return
        val f = File(context.filesDir, FILE)
        try {
            f.appendText(line(System.currentTimeMillis(), SystemClock.uptimeMillis(), type, msg, kv) + "\n")
            if (lines < 0) lines = f.useLines { it.count() } // once per process
            lines++
            if (lines > MAX_LINES) {
                f.writeText(trimmed(f.readLines(), MAX_LINES, TRIM_TO).joinToString("\n", postfix = "\n"))
                lines = TRIM_TO
            }
        } catch (_: Exception) {
            // Diagnostics must never break the feature they watch.
        }
    }

    /** The newest [limit] event lines with a timestamp after [sinceTs], oldest first. */
    fun eventsSince(context: Context, sinceTs: Long, limit: Int = 500): List<String> {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return emptyList()
        return try {
            f.readLines().filter { tsOf(it) > sinceTs }.takeLast(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** One JSONL line. Field order is fixed: ts first, so [tsOf] can read it without a parser. */
    fun line(ts: Long, bootMs: Long, type: String, msg: String, kv: Map<String, String> = emptyMap()): String {
        val b = StringBuilder("{\"ts\":").append(ts).append(",\"boot\":").append(bootMs)
            .append(",\"type\":\"").append(esc(type)).append("\"")
        if (msg.isNotEmpty()) b.append(",\"msg\":\"").append(esc(msg.take(MAX_MSG))).append("\"")
        for ((k, v) in kv) b.append(",\"").append(esc(k)).append("\":\"").append(esc(v.take(MAX_MSG))).append("\"")
        return b.append("}").toString()
    }

    /** The timestamp of a line written by [line], or 0 when it does not parse. */
    fun tsOf(l: String): Long {
        if (!l.startsWith("{\"ts\":")) return 0L
        val end = l.indexOf(',', 6)
        return l.substring(6, if (end < 0) l.length else end).toLongOrNull() ?: 0L
    }

    /** Keeps the tail when a log grows past [maxLines]: the newest [trimTo] lines survive. */
    fun trimmed(lines: List<String>, maxLines: Int, trimTo: Int): List<String> =
        if (lines.size <= maxLines) lines else lines.takeLast(trimTo)

    /** Minimal JSON string escaping (quotes, backslashes, control characters). */
    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append('?') else b.append(c)
        }
        return b.toString()
    }
}
