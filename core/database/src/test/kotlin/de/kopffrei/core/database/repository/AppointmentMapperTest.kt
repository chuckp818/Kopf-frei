package de.kopffrei.core.database.repository

import de.kopffrei.core.common.calendar.DetectedAppointment
import de.kopffrei.core.common.model.Frequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AppointmentMapperTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 11, 21)

    private fun millis(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 21, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun timedAppointmentGetsDefaultHour() {
        val e = AppointmentMapper.toEvent(DetectedAppointment("Zahnarzt", date, 10 * 60, null, "Morgen um 10 Uhr Zahnarzt"), zone)
        assertEquals("Zahnarzt", e.title)
        assertFalse(e.allDay)
        assertEquals(millis(10, 0), e.startAt)
        assertEquals(millis(11, 0), e.endAt)
        assertEquals(Frequency.NONE, e.recurrence.frequency)
    }

    @Test
    fun explicitEndIsUsed() {
        val e = AppointmentMapper.toEvent(DetectedAppointment("Workshop", date, 14 * 60, 16 * 60 + 30, "x"), zone)
        assertEquals(millis(14, 0), e.startAt)
        assertEquals(millis(16, 30), e.endAt)
    }

    @Test
    fun allDayEndsAtNextDayStart() {
        val e = AppointmentMapper.toEvent(DetectedAppointment("Geburtstag Oma", date, null, null, "x"), zone)
        assertTrue(e.allDay)
        assertEquals(millis(0, 0), e.startAt)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), e.endAt)
    }

    @Test
    fun weeklyBecomesWeeklyRecurrence() {
        val e = AppointmentMapper.toEvent(DetectedAppointment("Teamrunde", date, 9 * 60, null, "x", weekly = true), zone)
        assertEquals(Frequency.WEEKLY, e.recurrence.frequency)
        assertEquals(1, e.recurrence.interval)
    }

    @Test
    fun sourceTextIsKeptInNotes() {
        val e = AppointmentMapper.toEvent(DetectedAppointment("A", date, 600, null, "Morgen um 10 Uhr A"), zone)
        assertTrue(e.notes.contains("Morgen um 10 Uhr A"))
    }
}
