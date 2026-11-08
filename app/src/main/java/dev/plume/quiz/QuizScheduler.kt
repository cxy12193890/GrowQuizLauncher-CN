package dev.plume.quiz

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.plume.MainActivity

/** Exact wall-clock scheduling. setAlarmClock also permits the visible alarm to launch its UI. */
object QuizScheduler {
    fun ensureScheduled(context: Context) {
        val prefs = QuizPrefs(context)
        if (!prefs.enabled || prefs.nextTriggerAt <= System.currentTimeMillis() + 5_000L) {
            reschedule(context)
        }
    }

    fun reschedule(context: Context) {
        val prefs = QuizPrefs(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (!prefs.enabled) {
            alarm.cancel(operation(context))
            prefs.nextTriggerAt = 0L
            return
        }
        val triggerAt = QuizRules.triggerAt(System.currentTimeMillis(), prefs.intervalMinutes)
        val showIntent = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val op = operation(context)
        val exactAllowed = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        if (exactAllowed) {
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), op)
        } else {
            // Fallback to an inexact alarm when the exact-alarm permission is unavailable.
            dev.plume.DiagLog.event("exact_alarm_denied")
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, op)
        }
        prefs.nextTriggerAt = triggerAt
    }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        4107,
        Intent(context, QuizAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
