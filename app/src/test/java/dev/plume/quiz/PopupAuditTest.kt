package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PopupAuditTest {
    private val alarm = 1_000_000L

    @Test
    fun `no alarm on record says nothing`() {
        assertNull(PopupAudit.swallowed(0L, 0L, 0L, alarm + PopupAudit.GRACE_MS))
    }

    @Test
    fun `quiz opened after the alarm means the pop-up made it`() {
        assertNull(PopupAudit.swallowed(alarm, alarm + 5_000L, 0L, alarm + PopupAudit.GRACE_MS))
    }

    @Test
    fun `quiz opened exactly at alarm time still counts as opened`() {
        assertNull(PopupAudit.swallowed(alarm, alarm, 0L, alarm + PopupAudit.GRACE_MS))
    }

    @Test
    fun `too early to judge inside the pairing window`() {
        assertNull(PopupAudit.swallowed(alarm, 0L, 0L, alarm + PopupAudit.GRACE_MS - 1L))
    }

    @Test
    fun `ancient alarm is not news`() {
        assertNull(PopupAudit.swallowed(alarm, 0L, 0L, alarm + PopupAudit.EXPIRE_MS + 1L))
    }

    @Test
    fun `already logged for this alarm stays quiet`() {
        assertNull(PopupAudit.swallowed(alarm, 0L, alarm, alarm + PopupAudit.GRACE_MS))
    }

    @Test
    fun `swallowed alarm is reported once the grace window passes`() {
        assertEquals(alarm, PopupAudit.swallowed(alarm, 0L, 0L, alarm + PopupAudit.GRACE_MS))
    }

    @Test
    fun `older quiz open does not clear a newer alarm`() {
        assertEquals(
            alarm,
            PopupAudit.swallowed(alarm, alarm - 3_600_000L, 0L, alarm + PopupAudit.GRACE_MS),
        )
    }

    @Test
    fun `an earlier miss already logged does not hide a newer one`() {
        assertEquals(
            alarm,
            PopupAudit.swallowed(alarm, 0L, alarm - 60_000L, alarm + PopupAudit.GRACE_MS),
        )
    }
}
