package dev.plume

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last uncaught crash in SharedPreferences. The web console's /api/status exposes it,
 * and the launcher shows it once on the next start — a TV has no logcat within reach.
 */
object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY_TRACE = "trace"
    private const val KEY_AT = "at"
    private const val MAX_CHARS = 6000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val w = StringWriter()
                error.printStackTrace(PrintWriter(w))
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .putString(KEY_TRACE, "[${thread.name}]\n$w".take(MAX_CHARS))
                    .commit()
                // Keep the crash locally for the next-start prompt and password-protected LAN console.
                DiagLog.event("crash", "[${thread.name}] $w".take(400))
            } catch (e: Exception) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** The stored crash, with its time; null when there is none. Does not clear it. */
    fun peek(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trace = p.getString(KEY_TRACE, null) ?: return null
        val at = p.getLong(KEY_AT, 0L)
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
        return "$when_\n$trace"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
