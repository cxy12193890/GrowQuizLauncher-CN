package dev.plume.quiz

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * One text-to-speech engine for the whole app, started as soon as the desktop appears.
 *
 * A Chinese engine loads its model when the TextToSpeech object is created, and on TV hardware that
 * takes seconds. Creating the engine inside the quiz page meant the question was on screen long before
 * the voice was ready, and every visit paid the cost again; started once at desktop start, the engine
 * is warm by the time a question appears, and the next question speaks at once.
 */
object QuizVoice {
    private var engine: TextToSpeech? = null
    private var starting = false
    @Volatile private var ready = false
    /** "no-engine" or "no-language" once the engine is known to be unusable; null while it works. */
    @Volatile private var failure: String? = null
    private var utterance = 0
    private var rate = 0.85f

    /** Starts the engine, or does nothing when it is already up. Safe to call from every screen. */
    fun warm(context: Context) {
        if (engine != null || starting) return
        starting = true
        val app = context.applicationContext
        engine = TextToSpeech(app) { status ->
            starting = false
            if (status != TextToSpeech.SUCCESS) {
                // Most TVs here have no engine at all, and the constructor answers the same -1 for
                // that as for an engine that is installed but will not start. The list of engines
                // on board tells the two apart; see TtsDiagnosis.
                val engines = TtsDiagnosis.names(TtsProbe.engineNames(app))
                android.util.Log.w("GrowQuiz", "tts init failed: $status engines=${engines.joinToString(",")}")
                failure = "no-engine"
                dev.plume.DiagLog.event(TtsDiagnosis.typeFor(engines), TtsDiagnosis.msg(status, engines))
                return@TextToSpeech
            }
            configure()
        }
    }

    private fun configure() {
        val e = engine ?: return
        if (ready) return
        // zh-CN first, then plain Chinese, then the device's own locale: an engine that only has the
        // latter still beats silence, and some engines miss the Chinese data until installed.
        val ok = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
            .any { l -> e.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE }
        if (!ok) {
            android.util.Log.w("GrowQuiz", "tts has no usable language")
            failure = "no-language"
            dev.plume.DiagLog.event("tts_no_language")
            return
        }
        // A little slower than default: at full speed the four options run into each other.
        e.setSpeechRate(rate)
        e.setPitch(1f)
        ready = true
    }

    /** True once the engine can speak. Configures it here if the init callback arrived before the field did. */
    fun isReady(): Boolean {
        if (ready) return true
        if (failure == null && engine != null) configure()
        return ready
    }

    /** No engine, or one without a Chinese voice: the caller says so once instead of reading nothing. */
    fun isUnusable(): Boolean = failure != null

    /** "no-engine" / "no-language" for the parents' console, null while the engine is fine. */
    fun failureReason(): String? = failure

    /**
     * Speaks [text]. With [flush] the current sentence stops and this one starts at once, which is what a
     * new question needs; without it the text waits behind what is already being said, so the stem and
     * the options can be sent together and the voice begins before the rest is synthesised.
     */
    fun speak(text: String, flush: Boolean = true): Boolean {
        if (text.isBlank() || !isReady()) return false
        val queue = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return try {
            engine?.speak(text, queue, null, "quiz_${utterance++}") == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "tts speak failed", e)
            false
        }
    }

    fun stop() {
        try { engine?.stop() } catch (e: Exception) {}
    }
}
