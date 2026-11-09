package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsDiagnosisTest {

    @Test
    fun `no engine on board is a missing engine, not a broken one`() {
        assertEquals(TtsDiagnosis.MISSING, TtsDiagnosis.typeFor(emptyList()))
        assertEquals("tts_missing", TtsDiagnosis.typeFor(emptyList()))
    }

    @Test
    fun `an installed engine that will not start is an init failure`() {
        assertEquals(TtsDiagnosis.INIT_FAILED, TtsDiagnosis.typeFor(listOf("com.google.android.tts")))
        assertEquals("tts_init_fail", TtsDiagnosis.typeFor(listOf("com.k2fsa.sherpa.onnx.tts.engine")))
    }

    @Test
    fun `names are trimmed, deduplicated and sorted`() {
        val raw = listOf(" com.b.tts ", "com.a.tts", "com.b.tts", null, "  ")
        assertEquals(listOf("com.a.tts", "com.b.tts"), TtsDiagnosis.names(raw))
    }

    @Test
    fun `names drop to nothing when the list is only blanks`() {
        assertEquals(emptyList<String>(), TtsDiagnosis.names(listOf("", "   ", null)))
    }

    @Test
    fun `the message carries the status alone when no engine is installed`() {
        assertEquals("status=-1", TtsDiagnosis.msg(-1, emptyList()))
    }

    @Test
    fun `the message names the engines when there are any`() {
        assertEquals(
            "status=-1 engines=com.a.tts,com.b.tts",
            TtsDiagnosis.msg(-1, listOf("com.a.tts", "com.b.tts")),
        )
    }
}
