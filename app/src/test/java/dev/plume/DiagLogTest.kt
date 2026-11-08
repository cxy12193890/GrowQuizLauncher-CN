package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagLogTest {

    @Test
    fun `line carries ts boot type and msg`() {
        val l = DiagLog.line(1700000000000L, 12345L, "crash", "boom")
        assertTrue(l.startsWith("{\"ts\":1700000000000"))
        assertTrue(l.contains("\"boot\":12345"))
        assertTrue(l.contains("\"type\":\"crash\""))
        assertTrue(l.contains("\"msg\":\"boom\""))
    }

    @Test
    fun `line without msg omits the field`() {
        assertFalse(DiagLog.line(1L, 0L, "app_start", "").contains("msg"))
    }

    @Test
    fun `line escapes quotes backslashes and newlines`() {
        val l = DiagLog.line(1L, 0L, "crash", "a\"b\\c\nd")
        assertTrue(l.contains("a\\\"b\\\\c\\nd"))
        assertFalse(l.contains('\n')) // the payload holds no raw newline
    }

    @Test
    fun `line appends kv pairs`() {
        val l = DiagLog.line(1L, 0L, "a11y_dead", "", mapOf("vendor" to "tcl"))
        assertTrue(l.contains("\"vendor\":\"tcl\""))
    }

    @Test
    fun `tsOf reads the leading timestamp`() {
        assertEquals(42L, DiagLog.tsOf("{\"ts\":42,\"boot\":0,\"type\":\"x\"}"))
    }

    @Test
    fun `tsOf rejects foreign lines`() {
        assertEquals(0L, DiagLog.tsOf("not json"))
        assertEquals(0L, DiagLog.tsOf("{\"boot\":1}"))
    }

    @Test
    fun `trimmed keeps everything under the cap`() {
        val lines = (1..10).map { "l$it" }
        assertEquals(lines, DiagLog.trimmed(lines, 2000, 1500))
    }

    @Test
    fun `trimmed keeps the newest tail when over the cap`() {
        val lines = (1..3000).map { "l$it" }
        val out = DiagLog.trimmed(lines, 2000, 1500)
        assertEquals(1500, out.size)
        assertEquals("l3000", out.last())
        assertEquals("l1501", out.first())
    }
}
