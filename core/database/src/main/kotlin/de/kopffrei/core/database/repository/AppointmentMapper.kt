package de.kopffrei.core.database.repository

import de.kopffrei.core.common.calendar.AppointmentDetector
import de.kopffrei.core.common.calendar.DetectedAppointment
import de.kopffrei.core.common.model.Frequency
import de.kopffrei.core.common.model.RecurrenceRule
import de.kopffrei.core.common.time.TimeUtils
import de.kopffrei.core.database.entity.CalendarEventEntity
import java.time.LocalTime
import java.time.ZoneId

/** Macht aus einem erkannten Termin-Vorschlag einen Kalendereintrag. Reine Rechenlogik. */
object AppointmentMapper {
    fun toEvent(item: DetectedAppointment, zone: ZoneId = TimeUtils.systemZone()): CalendarEventEntity {
        val startMinutes = item.startMinutes
        val allDay = startMinutes == null
        val startAt: Long
        val endAt: Long
        if (startMinutes == null) {
            // Ganztägig: Ende ist der (exklusive) Beginn des Folgetags.
            startAt = TimeUtils.startOfDay(item.date, zone)
            endAt = TimeUtils.startOfDay(item.date.plusDays(1), zone)
        } else {
            startAt = TimeUtils.millisOf(item.date, LocalTime.of(startMinutes / 60, startMinutes % 60), zone)
            val duration = item.endMinutes?.let { it - startMinutes } ?: AppointmentDetector.DEFAULT_DURATION_MINUTES
            endAt = startAt + duration * TimeUtils.MINUTE_MS
        }
        return CalendarEventEntity(
            title = item.title,
            notes = "Erkannt aus: „${item.sourceText}“",
            allDay = allDay,
            startAt = startAt,
            endAt = endAt,
            recurrence = if (item.weekly) RecurrenceRule(Frequency.WEEKLY) else RecurrenceRule.NONE,
        )
    }
}
