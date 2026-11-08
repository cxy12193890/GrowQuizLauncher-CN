package dev.plume

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Ships a daily compatibility report to our server: who this TV is ([DeviceProbe]), what went
 * wrong since the last report ([DiagLog] lines) and the last crash ([CrashLog]). This is how
 * firmware we have never seen tells us what to adapt — the report lands in
 * /root/growquiz-reports/&lt;device&gt;/ on the update server, and the scheduled adaptation pass
 * reads it from there.
 *
 * Privacy by design: the device is identified by a random id created on first run, not by any
 * hardware id; no viewing data, answers or stats leave the TV.
 *
 * Throttled to one attempt per [INTERVAL_MS]; a failed send keeps the events, so the next day's
 * report carries them (the on-disk log is trimmed to the newest 1500 lines either way).
 */
object ReportUpload {
    /** Sibling of the update directory on the same host; the receiver service lives behind it. */
    private const val URL = "https://www.rengouzi.top:8443/growquiz-report/"

    /** Shared secret against casual abuse; not a credential, the APK is public anyway. */
    private const val TOKEN = "growquiz-report-2026"

    private const val PREFS = "report_upload"
    private const val KEY_LAST = "last_upload_at"
    private const val KEY_LAST_OK = "last_upload_ok"
    private const val KEY_ID = "device_id"
    private const val INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val MAX_BODY = 512 * 1024

    @Volatile private var sending = false

    /** One attempt, if the last one is long enough ago and the network is up. Safe to call often. */
    fun maybeUpload(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0L) < INTERVAL_MS) return
        if (!online(app) || sending) return
        sending = true
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        Thread {
            try {
                val ok = send(app, prefs)
                prefs.edit().putBoolean(KEY_LAST_OK, ok).apply()
                if (!ok) DiagLog.event("report_upload_fail")
            } catch (_: Exception) {
                prefs.edit().putBoolean(KEY_LAST_OK, false).apply()
            } finally {
                sending = false
            }
        }.start()
    }

    /** The report goes out; true when the server took it. Never throws. */
    private fun send(context: Context, prefs: android.content.SharedPreferences): Boolean {
        val sentAt = System.currentTimeMillis()
        val id = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_ID, it).apply() }
        val events = JSONArray()
        DiagLog.eventsSince(context, sentAt - INTERVAL_MS * 2).forEach { events.put(it) }
        val body = JSONObject()
            .put("id", id)
            .put("sentAt", sentAt)
            .put("probe", DeviceProbe.collect(context))
            .put("crash", CrashLog.peek(context) ?: "")
            .put("events", events)
            .toString()
        if (body.length > MAX_BODY) return false // a flood stays on disk; the trim keeps the newest
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Report-Token", TOKEN)
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                DiagLog.event("report_upload_ok", "", mapOf("events" to events.length().toString()))
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
