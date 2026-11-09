package dev.plume.quiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.plume.DiagLog

class QuizAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!QuizPrefs(context).enabled) return
        // Schedule first so an unanswered or interrupted quiz never disables future sessions.
        QuizScheduler.reschedule(context)
        // GrowQuiz: the alarm wakes this process anyway, so a TV nobody opens still reports on
        // schedule — day-one data showed most devices only ever started once.
        dev.plume.ReportUpload.maybeUpload(context)
        // GrowQuiz: the report pairs this event with QuizActivity's quiz_open; an alarm_fired with
        // no quiz_open soon after means the firmware swallowed the start (background-start rules).
        DiagLog.event("alarm_fired")
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
}
