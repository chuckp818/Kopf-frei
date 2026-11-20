package de.kopffrei.core.reminders

import de.kopffrei.core.common.calendar.DetectedAppointment
import de.kopffrei.core.common.model.OwnerType
import de.kopffrei.core.common.reminder.ReminderDraft
import de.kopffrei.core.common.time.TimeUtils
import de.kopffrei.core.database.repository.AppointmentMapper
import de.kopffrei.core.database.repository.EventRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trägt vom Nutzer bestätigte, erkannte Termine in den Kalender ein. Termine mit Uhrzeit bekommen eine
 * Erinnerung [DEFAULT_REMINDER_MINUTES] Minuten vorher; ganztägige bekommen keine (Phase 1).
 */
@Singleton
class AppointmentImporter @Inject constructor(
    private val events: EventRepository,
    private val reminders: ReminderCoordinator,
) {
    /** @return Anzahl der angelegten Termine. */
    suspend fun import(items: List<DetectedAppointment>): Int {
        if (items.isEmpty()) return 0
        val zone = TimeUtils.systemZone()
        val entities = items.map { AppointmentMapper.toEvent(it, zone) }
        events.saveAll(entities)
        for (event in entities) {
            if (event.allDay) continue
            reminders.replaceForOwner(
                OwnerType.EVENT,
                event.id,
                listOf(ReminderDraft(offsetMinutes = DEFAULT_REMINDER_MINUTES)),
            )
        }
        return entities.size
    }

    companion object {
        const val DEFAULT_REMINDER_MINUTES = 30
    }
}
