package dev.plume.quiz

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

/**
 * The parents' console asks two things about speech: can this TV read aloud, and does it work right now.
 * The engine itself lives in [QuizVoice], so the TV loads one model instead of two — a second engine
 * would double the memory and the startup cost on a TV.
 */
object TtsProbe {

    /** Starts the shared engine (no-op when [QuizVoice] already started it at desktop start). */
    fun start(context: Context) = QuizVoice.warm(context)

    /** Reads a sentence aloud (the console's test button); false when it could not be spoken. */
    fun speak(text: String): Boolean = QuizVoice.speak(text, flush = true)

    /** "available" / "no-engine" / "no-language" / "initializing", the values the console knows. */
    fun describe(): String = when {
        QuizVoice.failureReason() != null -> QuizVoice.failureReason()!!
        QuizVoice.isReady() -> "available"
        else -> "initializing"
    }

    /**
     * The speech engines installed on this TV, by package, empty when there is none. This is the same
     * query [TextToSpeech] makes before it starts an engine, so an empty answer here means the -1 it
     * returns is "no engine", not "broken engine". Needs the TTS intents in the manifest's `<queries>`
     * on Android 11 and later, or the system hides the engines and this answer comes back empty.
     */
    fun engineNames(context: Context): List<String> = try {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }
}
