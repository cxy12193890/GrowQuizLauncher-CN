package dev.plume.quiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.plume.DiagLog

/** Restores the next quiz after reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            QuizDeviceOwner.configure(context)
            QuizScheduler.reschedule(context)
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // GrowQuiz: some TVs never deliver this broadcast (TCL's boot manager holds it), so this
            // event firing at all is already a fact worth reporting about the firmware.
            DiagLog.event("boot_completed")
            // GrowQuiz: a power cut in the middle of a question or a forced rest keeps quizActive /
            // restUntil set; bring that page back. The same restore also runs when the Home service
            // binds after a boot. On Android 10+ the start is dropped without the "display over
            // other apps" grant — the service-bound path is the primary one.
            val prefs = QuizPrefs(context)
            val rest = prefs.restGuard && prefs.restUntil > System.currentTimeMillis()
            if (prefs.enabled && (prefs.quizActive || rest)) {
                runCatching {
                    context.startActivity(
                        Intent(context, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { DiagLog.event("boot_quiz_restore_fail", it.message ?: "") }
            }
        }
        // GrowQuiz: the boot question no longer hooks BOOT_COMPLETED (it never fired on some TVs).
        // MainActivity anchors it to the startup admin prompt's OK button instead.
    }
}
