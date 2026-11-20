package de.kopffrei.core.common

import de.kopffrei.core.common.calendar.AppointmentDetector
import de.kopffrei.core.common.calendar.DetectedAppointment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** „Jetzt“ ist Freitag, 20.11.2026, 12:56 Uhr. */
class AppointmentDetectorTest {
    private val now = LocalDateTime.of(2026, 11, 20, 12, 56)

    private fun detect(text: String): List<DetectedAppointment> = AppointmentDetector.detect(text, now)

    private fun one(text: String): DetectedAppointment {
        val result = detect(text)
        check(result.size == 1) { "Erwartet genau einen Termin in: $text -> $result" }
        return result.single()
    }

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun tomorrowWithTimeAndTitleAfter() {
        val a = one("Morgen um 10 Uhr Zahnarzt")
        assertEquals(LocalDate.of(2026, 11, 21), a.date)
        assertEquals(at(10), a.startMinutes)
        assertEquals("Zahnarzt", a.title)
        assertTrue(a.confident)
    }

    @Test
    fun dayAfterTomorrowWithMinutes() {
        val a = one("Übermorgen um 14:30 Meeting mit Chef")
        assertEquals(LocalDate.of(2026, 11, 22), a.date)
        assertEquals(at(14, 30), a.startMinutes)
        assertEquals("Meeting mit Chef", a.title)
    }

    @Test
    fun weekdayWithHalfPast() {
        val a = one("Am Montag um halb vier Friseur")
        assertEquals(LocalDate.of(2026, 11, 23), a.date)
        assertEquals(at(15, 30), a.startMinutes)
        assertEquals("Friseur", a.title)
    }

    @Test
    fun threeSpokenAppointmentsWithoutPunctuation() {
        val r = detect("morgen um 10 Uhr Zahnarzt übermorgen um 14 Uhr Meeting am Montag um 9 Uhr Friseur")
        assertEquals(3, r.size)
        assertEquals(listOf("Zahnarzt", "Meeting", "Friseur"), r.map { it.title })
        assertEquals(
            listOf(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 23)),
            r.map { it.date },
        )
        assertEquals(listOf(at(10), at(14), at(9)), r.map { it.startMinutes })
    }

    @Test
    fun titleBeforeTime() {
        val r = detect("Zahnarzt morgen um 10 Uhr und Friseur am Montag um 9 Uhr")
        assertEquals(listOf("Zahnarzt", "Friseur"), r.map { it.title })
        assertEquals(listOf(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 23)), r.map { it.date })
    }

    @Test
    fun secondTimeInheritsDate() {
        val r = detect("Morgen habe ich um 10 Zahnarzt und um 15 Uhr Friseur")
        assertEquals(listOf("Zahnarzt", "Friseur"), r.map { it.title })
        assertTrue(r.all { it.date == LocalDate.of(2026, 11, 21) })
        assertEquals(listOf(at(10), at(15)), r.map { it.startMinutes })
        assertTrue(r.all { !it.dateGuessed })
    }

    @Test
    fun dateWithoutYearInThePastRollsToNextYear() {
        val a = one("Am 12. März um 8 Uhr Vorstellungsgespräch")
        assertEquals(LocalDate.of(2027, 3, 12), a.date)
        assertEquals(at(8), a.startMinutes)
    }

    @Test
    fun ordinalDateWordsAllDayWithKeyword() {
        val a = one("am zwölften Dezember Geburtstag Oma")
        assertEquals(LocalDate.of(2026, 12, 12), a.date)
        assertNull(a.startMinutes)
        assertTrue(a.allDay)
        assertEquals("Geburtstag Oma", a.title)
    }

    @Test
    fun relativeWeeks() {
        val a = one("in zwei Wochen Arzttermin um 9 Uhr")
        assertEquals(LocalDate.of(2026, 12, 4), a.date)
        assertEquals(at(9), a.startMinutes)
        assertEquals("Arzttermin", a.title)
    }

    @Test
    fun nextWeekWeekday() {
        val a = one("Nächste Woche Dienstag um 16 Uhr Elternabend")
        assertEquals(LocalDate.of(2026, 11, 24), a.date)
        assertEquals(at(16), a.startMinutes)
        assertEquals("Elternabend", a.title)
    }

    @Test
    fun timeRangeAndLaterDate() {
        val a = one("von 14 bis 16 Uhr Workshop am Freitag")
        assertEquals(LocalDate.of(2026, 11, 27), a.date)
        assertEquals(at(14), a.startMinutes)
        assertEquals(at(16), a.endMinutes)
        assertEquals("Workshop", a.title)
    }

    @Test
    fun tonightUsesEvening() {
        val a = one("Heute Abend um 8 Kino")
        assertEquals(LocalDate.of(2026, 11, 20), a.date)
        assertEquals(at(20), a.startMinutes)
        assertEquals("Kino", a.title)
    }

    @Test
    fun morningWithoutTimeIsGuessed() {
        val a = one("Ich hab morgen früh einen Termin beim Zahnarzt")
        assertEquals(LocalDate.of(2026, 11, 21), a.date)
        assertEquals(at(8), a.startMinutes)
        assertTrue(a.timeGuessed)
        assertFalse(a.confident)
        assertEquals("Termin beim Zahnarzt", a.title)
    }

    @Test
    fun plainTaskWithDateIsNotAnAppointment() {
        assertTrue(detect("Wäsche waschen morgen").isEmpty())
        assertTrue(detect("Milch kaufen").isEmpty())
        assertTrue(detect("Das Projekt dauert 3 Tage").isEmpty())
        assertTrue(detect("Morgen Milch kaufen und nicht vergessen").isEmpty())
    }

    @Test
    fun spokenNumbers() {
        val a = one("Am Dienstag um fünfzehn Uhr dreißig Besprechung")
        assertEquals(LocalDate.of(2026, 11, 24), a.date)
        assertEquals(at(15, 30), a.startMinutes)
        assertEquals("Besprechung", a.title)
    }

    @Test
    fun quarterPast() {
        val a = one("Viertel nach drei Zahnarzt")
        assertEquals(at(15, 15), a.startMinutes)
        assertEquals(LocalDate.of(2026, 11, 20), a.date)
        assertTrue(a.dateGuessed)
    }

    @Test
    fun timeAlreadyPassedTodayMeansTomorrow() {
        val a = one("um 9 Uhr Sport")
        assertEquals(LocalDate.of(2026, 11, 21), a.date)
        assertTrue(a.dateGuessed)
    }

    @Test
    fun numericDate() {
        val a = one("Am 03.12. um 18 Uhr Weihnachtsfeier")
        assertEquals(LocalDate.of(2026, 12, 3), a.date)
        assertEquals(at(18), a.startMinutes)
        assertEquals("Weihnachtsfeier", a.title)
    }

    @Test
    fun fullNumericDateWithYear() {
        val a = one("Termin 12.03.2027 um 10:15 Uhr Bank")
        assertEquals(LocalDate.of(2027, 3, 12), a.date)
        assertEquals(at(10, 15), a.startMinutes)
    }

    @Test
    fun weeklyRecurrence() {
        val a = one("Jeden Montag um 10 Uhr Teamrunde")
        assertTrue(a.weekly)
        assertEquals(LocalDate.of(2026, 11, 23), a.date)
        assertEquals(at(10), a.startMinutes)
        assertEquals("Teamrunde", a.title)
    }

    @Test
    fun sameWeekdayMeansNextWeek() {
        // Heute ist Freitag: „am Freitag“ ist der nächste Freitag.
        val a = one("Am Freitag um 9 Uhr Friseur")
        assertEquals(LocalDate.of(2026, 11, 27), a.date)
    }

    @Test
    fun linesAreIndependentContexts() {
        val r = detect("Morgen um 10 Uhr Zahnarzt\num 15 Uhr Friseur")
        assertEquals(2, r.size)
        assertEquals(LocalDate.of(2026, 11, 21), r[0].date)
        // Zweite Zeile hat kein Datum und erbt es nicht aus der ersten: geraten (heute, 15 Uhr liegt vor uns).
        assertEquals(LocalDate.of(2026, 11, 20), r[1].date)
        assertTrue(r[1].dateGuessed)
    }

    @Test
    fun sentencesShareDateWithinOneLine() {
        val r = detect("Morgen habe ich viel vor. Um 10 Zahnarzt. Um 15 Uhr Friseur.")
        assertEquals(listOf("Zahnarzt", "Friseur"), r.map { it.title })
        assertTrue(r.all { it.date == LocalDate.of(2026, 11, 21) && !it.dateGuessed })
    }

    @Test
    fun doctorAbbreviationDoesNotSplit() {
        val a = one("Morgen um 10 Uhr Termin bei Dr. Müller")
        assertEquals("Termin bei Dr. Müller", a.title)
    }

    @Test
    fun noFalseTimeFromArticle() {
        assertTrue(detect("Ich muss um einen Termin bitten").isEmpty())
        assertTrue(detect("Das kostet um 5 Euro").isEmpty())
    }

    @Test
    fun emptyAndNoiseInput() {
        assertTrue(detect("").isEmpty())
        assertTrue(detect("   \n  \n").isEmpty())
        assertTrue(detect("Heute war ein guter Tag").isEmpty())
    }
}
