package dev.plume.quiz

/**
 * Catches the quiz pop-up that never appeared.
 *
 * Android 10+ silently refuses a background `startActivity` — no exception, no crash, the
 * alarm_fired event is written and then nothing happens. The first week of field reports proved
 * it: one phone wrote twenty alarm_fired lines against a single quiz_open, and not one
 * quiz_popup_blocked, because the try/catch around startActivity only sees throws. So the alarm
 * receiver remembers when it last fired, the quiz page remembers when it last opened, and whoever
 * runs next (the next alarm or the next process start) compares the pair: an alarm older than
 * [GRACE_MS] with no quiz_open after it was swallowed by the system, and earns its own
 * quiz_popup_blocked event. Kept free of Android classes so the decision can be tested off the TV.
 */
object PopupAudit {
    /** The report pairs alarm_fired with quiz_open inside this window; do not judge earlier. */
    const val GRACE_MS = 120_000L

    /** Older than this, a miss says nothing new (quiz switched off, app forgotten). */
    const val EXPIRE_MS = 36L * 3_600_000L

    /**
     * The timestamp of the swallowed alarm that still needs an event, or null. A [lastQuizOpenAt]
     * at or after [lastAlarmAt] means the pop-up made it onto the screen; [loggedFor] keeps the
     * event a once-per-miss record instead of one line every process start.
     */
    fun swallowed(lastAlarmAt: Long, lastQuizOpenAt: Long, loggedFor: Long, now: Long): Long? {
        if (lastAlarmAt <= 0L) return null
        if (lastQuizOpenAt >= lastAlarmAt) return null
        if (now - lastAlarmAt < GRACE_MS) return null
        if (now - lastAlarmAt > EXPIRE_MS) return null
        if (loggedFor >= lastAlarmAt) return null
        return lastAlarmAt
    }
}
