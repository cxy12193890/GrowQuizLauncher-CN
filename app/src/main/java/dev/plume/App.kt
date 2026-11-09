package dev.plume

import android.app.Application

/** Process-wide setup that must run before any activity or receiver: crash and diagnostics recording. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        CrashLog.install(this)
        DiagLog.event("app_start", "", mapOf("vendor" to Vendor.current))
        // A process start is a chance to report an alarm whose quiz pop-up never appeared; the
        // audit no-ops when there is nothing overdue (e.g. the alarm that woke this very process).
        dev.plume.quiz.QuizAlarmReceiver.auditSwallowed(this)
    }
}
