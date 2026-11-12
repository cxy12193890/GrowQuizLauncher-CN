package dev.plume.quiz

import android.content.Context

/** Small local settings store; no account, network or cloud dependency. */
class QuizPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var age: Int
        get() = prefs.getInt(KEY_AGE, 7).coerceIn(5, 15)
        set(value) = prefs.edit().putInt(KEY_AGE, value.coerceIn(5, 15)).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 30).coerceIn(1, 1440)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(1, 1440)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Parents can turn the question read-aloud off from the web console (e.g. for a silent evening). */
    var voice: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var nextTriggerAt: Long
        get() = prefs.getLong(KEY_NEXT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT, value).apply()

    /** Extra dimension picked by the parents: only these categories are asked. Empty means every category. */
    var categories: Set<String>
        get() = prefs.getStringSet(KEY_CATEGORIES, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_CATEGORIES, value).apply()

    /** One question must be answered before the TV becomes usable after each power-on. On by default. */
    var bootQuiz: Boolean
        get() = prefs.getBoolean(KEY_BOOT_QUIZ, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_QUIZ, value).apply()

    /** Parents can switch the enforced rest after repeated wrong answers off from the web console. */
    var restGuard: Boolean
        get() = prefs.getBoolean(KEY_REST_GUARD, true)
        set(value) = prefs.edit().putBoolean(KEY_REST_GUARD, value).apply()

    /**
     * A question comes back by itself when the child opens another app. On by default: without it a
     * Netflix-style key leaves the question behind, and nothing else catches that. Parents can turn it
     * off from the web console, e.g. while they use the TV themselves.
     */
    var snapBack: Boolean
        get() = prefs.getBoolean(KEY_SNAP_BACK, true)
        set(value) = prefs.edit().putBoolean(KEY_SNAP_BACK, value).apply()

    /**
     * Proof that [snapBack] works on this TV: when it last brought a question back, and how often it has
     * done so. A parent can see it in the console instead of having to sit and watch.
     */
    var snapOkAt: Long
        get() = prefs.getLong(KEY_SNAP_OK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SNAP_OK_AT, value).apply()

    var snapOkCount: Int
        get() = prefs.getInt(KEY_SNAP_OK_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_SNAP_OK_COUNT, value).apply()

    /** Seconds of forced thinking time before the answer buttons respond; 0 turns the gate off. */
    var thinkSeconds: Int
        get() = prefs.getInt(KEY_THINK_SECONDS, QuizRules.DEFAULT_THINK_SECONDS).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_THINK_SECONDS, value.coerceIn(0, 60)).apply()

    /** Consecutive wrong answers in the current session. Resets on any correct answer. Survives restarts. */
    var wrongStreak: Int
        get() = prefs.getInt(KEY_WRONG_STREAK, 0)
        set(value) = prefs.edit().putInt(KEY_WRONG_STREAK, value.coerceAtLeast(0)).apply()

    /** Epoch time until which the child must rest after too many wrong answers in a row. */
    var restUntil: Long
        get() = prefs.getLong(KEY_REST_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_REST_UNTIL, value).apply()

    /** Consecutive snap (too-fast) wrong answers, used to catch blind guessing. */
    var snapWrongs: Int
        get() = prefs.getInt(KEY_SNAP_WRONGS, 0)
        set(value) = prefs.edit().putInt(KEY_SNAP_WRONGS, value.coerceAtLeast(0)).apply()

    /** A verification question is owed: the last fast correct answer looked like a lucky guess. */
    var verifyPending: Boolean
        get() = prefs.getBoolean(KEY_VERIFY_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_VERIFY_PENDING, value).apply()

    /**
     * A quiz session is open: a question or a forced rest waits for an answer. Set when the quiz
     * page opens, cleared only when a question is answered correctly and the page closes. A power
     * cut keeps the flag, so after the next power-on the page comes back on its own.
     */
    var quizActive: Boolean
        get() = prefs.getBoolean(KEY_QUIZ_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIZ_ACTIVE, value).apply()

    /** The "show over other apps" request was shown once; do not send the TV to Settings again. */
    var overlayAsked: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ASKED, value).apply()

    /** The "let alarms run exactly" request was shown once (Android 12+ report data showed it widely denied). */
    var exactAsked: Boolean
        get() = prefs.getBoolean(KEY_EXACT_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_EXACT_ASKED, value).apply()

    /**
     * The boot session (Settings.Global.BOOT_COUNT) in which the parents already dismissed the startup
     * admin prompt. -1 means it has never been shown. A reboot raises the boot count, so the prompt comes
     * back exactly once per power-on and stays away after that.
     */
    var promptBootCount: Int
        get() = prefs.getInt(KEY_PROMPT_BOOT, -1)
        set(value) = prefs.edit().putInt(KEY_PROMPT_BOOT, value).apply()

    /** Last time the quiz alarm went off — even when the pop-up never appeared. Pairs with [lastQuizOpenAt]. */
    var lastAlarmAt: Long
        get() = prefs.getLong(KEY_LAST_ALARM_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ALARM_AT, value).apply()

    /** Last time the quiz page actually opened. Behind [lastAlarmAt] means the system ate the pop-up. */
    var lastQuizOpenAt: Long
        get() = prefs.getLong(KEY_LAST_QUIZ_OPEN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_QUIZ_OPEN_AT, value).apply()

    /** The alarm timestamp already reported as swallowed, so a missed pop-up is logged exactly once. */
    var popupLoggedFor: Long
        get() = prefs.getLong(KEY_POPUP_LOGGED_FOR, 0L)
        set(value) = prefs.edit().putLong(KEY_POPUP_LOGGED_FOR, value).apply()

    /** Parents released the rest early from the web console: wipe every anti-guess counter. */
    fun clearRest() {
        prefs.edit()
            .putLong(KEY_REST_UNTIL, 0L)
            .putInt(KEY_WRONG_STREAK, 0)
            .putInt(KEY_SNAP_WRONGS, 0)
            .putBoolean(KEY_VERIFY_PENDING, false)
            .commit()
    }

    var completedSessionQuestions: Set<String>
        get() = prefs.getStringSet("completed_session_questions", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("completed_session_questions", value.toSet()).apply()

    val term: Int get() = prefs.getInt("term", 0).coerceIn(0, 2)
    val grade: Int get() = prefs.getInt("grade", 0).coerceIn(0, 10)
    val difficulty: Int get() = prefs.getInt("difficulty", 0).coerceIn(0, 5)
    val questionsPerSession: Int get() = prefs.getInt("questions_per_session", 1).coerceIn(1, 10)

    fun save(
        age: Int, interval: Int, enabled: Boolean,
        categories: Set<String> = this.categories, voice: Boolean = this.voice,
        grade: Int = this.grade, difficulty: Int = this.difficulty, questionsPerSession: Int = this.questionsPerSession,
        term: Int = this.term,
    ) {
        prefs.edit()
            .putInt(KEY_AGE, age.coerceIn(5, 15))
            .putInt(KEY_INTERVAL, interval.coerceIn(1, 1440))
            .putBoolean(KEY_ENABLED, enabled)
            .putStringSet(KEY_CATEGORIES, categories)
            .putBoolean(KEY_VOICE, voice)
            .putInt("grade", grade)
            .putInt("term", term)
            .putInt("difficulty", difficulty)
            .putInt("questions_per_session", questionsPerSession)
            .commit()
    }

    companion object {
        const val NAME = "grow_quiz"
        private const val KEY_AGE = "age"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NEXT = "next_trigger_at"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_BOOT_QUIZ = "boot_quiz_enabled"
        private const val KEY_REST_GUARD = "rest_guard_enabled"
        private const val KEY_SNAP_BACK = "snap_back_enabled"
        private const val KEY_SNAP_OK_AT = "snap_back_ok_at"
        private const val KEY_SNAP_OK_COUNT = "snap_back_ok_count"
        private const val KEY_THINK_SECONDS = "think_seconds"
        private const val KEY_WRONG_STREAK = "wrong_streak"
        private const val KEY_REST_UNTIL = "rest_until"
        private const val KEY_SNAP_WRONGS = "snap_wrongs"
        private const val KEY_VERIFY_PENDING = "verify_pending"
        private const val KEY_QUIZ_ACTIVE = "quiz_active"
        private const val KEY_OVERLAY_ASKED = "overlay_asked"
        private const val KEY_EXACT_ASKED = "exact_alarm_asked"
        private const val KEY_PROMPT_BOOT = "prompt_boot_count"
        private const val KEY_LAST_ALARM_AT = "last_alarm_at"
        private const val KEY_LAST_QUIZ_OPEN_AT = "last_quiz_open_at"
        private const val KEY_POPUP_LOGGED_FOR = "popup_logged_for"
    }
}
