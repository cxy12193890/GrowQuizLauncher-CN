package dev.plume.quiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.plume.DiagLog
import dev.plume.Vendor

class QuizAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = QuizPrefs(context)
        if (!prefs.enabled) return
        // GrowQuiz: read the due time before rescheduling overwrites it, so the local event can say how
        // late the alarm really was — the number that tells an inexact fallback from a broken alarm.
        val due = prefs.nextTriggerAt
        // Schedule first so an unanswered or interrupted quiz never disables future sessions.
        QuizScheduler.reschedule(context)
        // GrowQuiz: judge the previous alarm before this one overwrites its timestamp — a fired
        // alarm that never produced a quiz_open was swallowed silently (background-start rules
        // throw nothing), and only this local look-back can detect it.
        auditSwallowed(context)
        prefs.lastAlarmAt = System.currentTimeMillis()
        // GrowQuiz: the local event log pairs this event with QuizActivity's quiz_open; an alarm_fired with
        // no quiz_open soon after means the firmware swallowed the start (background-start rules).
        val kv = mutableMapOf<String, String>()
        if (due > 0) kv["lateMs"] = (System.currentTimeMillis() - due).toString()
        DiagLog.event("alarm_fired", "", kv)
        try {
            context.startActivity(
                Intent(context, QuizActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        } catch (e: Exception) {
            DiagLog.event("quiz_popup_blocked", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /**
         * Writes quiz_popup_blocked("silent") when the last alarm on record never produced a
         * quiz_open. Called from here and from App.onCreate, so a TV whose alarms stop entirely
         * still records the miss locally the next time any part of the app runs.
         */
        fun auditSwallowed(context: Context) {
            val prefs = QuizPrefs(context)
            val now = System.currentTimeMillis()
            val missed = PopupAudit.swallowed(
                prefs.lastAlarmAt, prefs.lastQuizOpenAt, prefs.popupLoggedFor, now,
            ) ?: return
            prefs.popupLoggedFor = missed
            DiagLog.event(
                "quiz_popup_blocked",
                "silent",
                mapOf(
                    "vendor" to Vendor.current,
                    "overlay" to Settings.canDrawOverlays(context).toString(),
                    "sdk" to Build.VERSION.SDK_INT.toString(),
                    "ageMin" to ((now - missed) / 60_000L).toString(),
                ),
            )
        }
    }
}
