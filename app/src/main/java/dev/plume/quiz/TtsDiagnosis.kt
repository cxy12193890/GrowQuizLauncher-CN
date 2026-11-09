package dev.plume.quiz

/**
 * Which report event an unusable speech engine deserves.
 *
 * `TextToSpeech` answers the same error (-1) for a TV with no engine at all and for an engine that
 * is installed but will not start, so the event name alone has to carry the difference: most TVs in
 * China ship with no engine, and one such TV per report must not look like a broken engine on every
 * set. The engine list decides. Kept free of Android classes so it can be tested off the TV.
 */
object TtsDiagnosis {
    /** No engine on board: the parents must install one. */
    const val MISSING = "tts_missing"

    /** An engine answers, but it will not start: its data or version is wrong. */
    const val INIT_FAILED = "tts_init_fail"

    /** The engine package names, trimmed, without duplicates, sorted, so reports compare by eye. */
    fun names(raw: List<String?>): List<String> =
        raw.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /** The event type for an init failure, decided by what the TV has installed. */
    fun typeFor(engines: List<String>): String = if (engines.isEmpty()) MISSING else INIT_FAILED

    /** The message that goes with [typeFor]: the status, plus the engines when there are any. */
    fun msg(status: Int, engines: List<String>): String =
        if (engines.isEmpty()) "status=$status" else "status=$status engines=${engines.joinToString(",")}"
}
