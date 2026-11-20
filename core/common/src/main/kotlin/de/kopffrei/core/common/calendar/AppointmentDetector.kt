package de.kopffrei.core.common.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Ein aus Text oder Gesprochenem erkannter Termin-Vorschlag. Er wird dem Nutzer zur Bestätigung
 * gezeigt und erst danach in den Kalender übernommen.
 *
 * @property startMinutes Minuten seit Mitternacht; `null` = ganztägig.
 * @property endMinutes Ende, nur wenn es ausdrücklich genannt wurde ("von 14 bis 16 Uhr").
 * @property dateGuessed Das Datum wurde nicht genannt, sondern aus der Uhrzeit abgeleitet (heute/morgen).
 * @property timeGuessed Nur eine Tageszeit wurde genannt ("morgen früh"); die Uhrzeit ist ein Richtwert.
 * @property weekly "jeden Montag …": Der Termin wiederholt sich wöchentlich.
 */
data class DetectedAppointment(
    val title: String,
    val date: LocalDate,
    val startMinutes: Int?,
    val endMinutes: Int?,
    val sourceText: String,
    val dateGuessed: Boolean = false,
    val timeGuessed: Boolean = false,
    val weekly: Boolean = false,
) {
    val allDay: Boolean get() = startMinutes == null

    /** Datum und Uhrzeit wurden ausdrücklich genannt, nichts ist geraten. */
    val confident: Boolean get() = !dateGuessed && !timeGuessed
}

/**
 * Erkennt deutsche Terminangaben in freiem Text, z. B. aus einer Sprachaufnahme:
 * „Morgen um 10 Uhr Zahnarzt, übermorgen um halb drei Meeting mit Chef und am Montag um 9 Friseur“.
 *
 * Das ist **bewusst eine regelbasierte, vollständig lokale Erkennung** (keine KI, kein Netzwerk): Sie ist
 * vorhersagbar und sofort verfügbar, versteht aber nur gängige Formulierungen. Was sie nicht erkennt,
 * bleibt als normaler Text erhalten. Alles Erkannte wird dem Nutzer vor dem Eintragen gezeigt.
 *
 * Ein Satzteil gilt als Termin, wenn er eine **Uhrzeit** enthält, oder ein Datum **und** ein Stichwort
 * wie „Termin“, „Arzt“ oder „Geburtstag“. „Morgen Wäsche waschen“ ist daher kein Termin, sondern bleibt
 * eine Aufgabe.
 *
 * Nicht unterstützt: Wiederholungen außer „jeden <Wochentag>“, Zeiträume über mehrere Tage, relative
 * Uhrzeiten („in 20 Minuten“), „am Wochenende“.
 */
object AppointmentDetector {
    const val DEFAULT_DURATION_MINUTES = 60

    /** @param now aktueller Zeitpunkt; bestimmt, was „heute“, „morgen“ und „Montag“ bedeutet. */
    fun detect(text: String, now: LocalDateTime): List<DetectedAppointment> {
        val result = mutableListOf<DetectedAppointment>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            var contextDate: LocalDate? = null
            for (segment in splitSegments(line)) {
                val outcome = analyzeSegment(segment, now, contextDate)
                contextDate = outcome.contextDate
                result += outcome.appointments
            }
        }
        return result.distinctBy { Triple(it.title.lowercase(), it.date, it.startMinutes) }
    }

    // ------------------------------------------------------------------ Satzteile

    private val ABBREVIATIONS = setOf("dr", "prof", "ca", "bzw", "evtl", "usw", "nr", "str", "tel", "ggf", "vgl", "inkl", "ev")

    /** Teilt an . ! ? ; – aber nicht in Zahlen („12.03.“) oder nach Abkürzungen („Dr. Müller“). */
    internal fun splitSegments(line: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for ((i, c) in line.withIndex()) {
            sb.append(c)
            val boundary = when (c) {
                '!', '?', ';' -> true
                '.' -> {
                    val next = line.getOrNull(i + 1)
                    val followedBySpaceOrEnd = next == null || next.isWhitespace()
                    val word = sb.toString().dropLast(1).takeLastWhile { it.isLetter() }.lowercase()
                    val afterDigit = sb.length >= 2 && sb[sb.length - 2].isDigit()
                    followedBySpaceOrEnd && !afterDigit && word !in ABBREVIATIONS
                }
                else -> false
            }
            if (boundary) {
                parts += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotBlank()) parts += sb.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ Zahlwörter

    private val UNITS = mapOf(
        "null" to 0, "ein" to 1, "eins" to 1, "eine" to 1, "einen" to 1, "einem" to 1, "einer" to 1,
        "zwei" to 2, "zwo" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "fuenf" to 5, "sechs" to 6,
        "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12, "zwoelf" to 12,
        "dreizehn" to 13, "vierzehn" to 14, "fünfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17,
        "achtzehn" to 18, "neunzehn" to 19,
    )
    private val TENS = mapOf("zwanzig" to 20, "dreißig" to 30, "dreissig" to 30, "vierzig" to 40, "fünfzig" to 50)
    private val ARTICLE_FORMS = setOf("ein", "eine", "einen", "einem", "einer")
    private val COMPOUND_UNITS = listOf("ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")

    private fun numberWord(raw: String): Int? {
        val w = raw.lowercase()
        UNITS[w]?.let { return it }
        TENS[w]?.let { return it }
        val m = Regex("^(ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun)und(zwanzig|dreißig|dreissig|vierzig|fünfzig)$").find(w)
            ?: return null
        return (UNITS[m.groupValues[1]] ?: return null) + (TENS[m.groupValues[2]] ?: return null)
    }

    private val COMPOUND_WORDS: List<String> = COMPOUND_UNITS.flatMap { u ->
        listOf("zwanzig", "dreißig", "dreissig", "vierzig", "fünfzig").map { t -> "${u}und$t" }
    }

    /** Alle Zahlwörter (auch „eine“, „einen“) – nur sinnvoll direkt vor „Uhr“. */
    private val NUM_ALL: String = (COMPOUND_WORDS + UNITS.keys + TENS.keys)
        .sortedByDescending { it.length }.joinToString("|")

    /** Zahlwörter ohne Artikelformen, damit „um einen Termin bitten“ nicht als Uhrzeit gilt. */
    private val NUM_NOART: String = (COMPOUND_WORDS + UNITS.keys.filter { it !in ARTICLE_FORMS } + TENS.keys)
        .sortedByDescending { it.length }.joinToString("|")

    // ------------------------------------------------------------------ Ordnungszahlen (Datum)

    private val ORDINAL_STEMS: Map<String, Int> = buildMap {
        val low = listOf(
            "erst", "zweit", "dritt", "viert", "fünft", "sechst", "siebt", "acht", "neunt", "zehnt", "elft",
            "zwölft", "dreizehnt", "vierzehnt", "fünfzehnt", "sechzehnt", "siebzehnt", "achtzehnt", "neunzehnt",
        )
        low.forEachIndexed { i, s -> put(s, i + 1) }
        put("zwanzigst", 20)
        COMPOUND_UNITS.forEachIndexed { i, u -> put("${u}undzwanzigst", 21 + i) }
        put("dreißigst", 30)
        put("einunddreißigst", 31)
    }
    private val ORDINAL_ALT: String = ORDINAL_STEMS.keys.sortedByDescending { it.length }.joinToString("|")

    private fun ordinalDay(raw: String): Int? {
        val w = raw.lowercase()
        for (suffix in listOf("en", "em", "er", "es", "e", "")) {
            if (w.endsWith(suffix)) ORDINAL_STEMS[w.removeSuffix(suffix)]?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ Monate, Wochentage, Tageszeiten

    private val MONTHS = listOf(
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober",
        "november", "dezember",
    )
    private val MONTH_ALT = (MONTHS + "maerz").joinToString("|")

    private fun monthNumber(raw: String): Int {
        val w = raw.lowercase()
        return if (w == "maerz") 3 else MONTHS.indexOf(w) + 1
    }

    private val WEEKDAYS = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonnabend" to DayOfWeek.SATURDAY, "sonntag" to DayOfWeek.SUNDAY,
    )
    private val WEEKDAY_ALT = WEEKDAYS.keys.joinToString("|")

    private enum class Daypart(val defaultMinutes: Int) {
        EARLY(8 * 60), FORENOON(10 * 60), NOON(12 * 60), AFTERNOON(15 * 60), EVENING(19 * 60), NIGHT(22 * 60),
    }

    private fun daypartOf(raw: String): Daypart? = when (raw.lowercase().trimEnd('s')) {
        "früh", "morgen" -> Daypart.EARLY
        "vormittag" -> Daypart.FORENOON
        "mittag" -> Daypart.NOON
        "nachmittag" -> Daypart.AFTERNOON
        "abend" -> Daypart.EVENING
        "nacht" -> Daypart.NIGHT
        else -> null
    }

    /** Nach Datumswörtern: „morgen früh“, „heute Abend“, „am Freitag Nachmittag“. */
    private const val DAYPART_DATE_ALT = "früh|morgens|morgen|vormittags|vormittag|mittags|mittag|nachmittags|nachmittag|abends|abend|nachts|nacht"

    /** Nach Uhrzeiten nur Adverbien („um 8 abends“), damit „um 10 Uhr morgen“ nicht missverstanden wird. */
    private const val DAYPART_TIME_ALT = "früh|morgens|vormittags|mittags|nachmittags|abends|nachts"

    /** Uhrzeit abhängig von Tageszeit; ohne Angabe gelten 1–6 Uhr als nachmittags. */
    private fun resolveHour(h: Int, daypart: Daypart?): Int {
        if (h == 0 || h > 12) return h
        return when (daypart) {
            Daypart.EARLY, Daypart.FORENOON -> if (h == 12) 0 else h
            Daypart.NOON -> if (h in 1..5) h + 12 else h
            Daypart.AFTERNOON, Daypart.EVENING -> if (h < 12) h + 12 else h
            Daypart.NIGHT -> if (h in 6..11) h + 12 else if (h == 12) 0 else h
            null -> if (h in 1..6) h + 12 else h
        }
    }

    // ------------------------------------------------------------------ Anker (Datum / Uhrzeit)

    private sealed interface Anchor {
        val start: Int
        val end: Int
    }

    private data class DateAnchor(
        override val start: Int,
        override val end: Int,
        val date: LocalDate,
        val daypart: Daypart?,
        val weekly: Boolean = false,
    ) : Anchor

    /** [hour] ist noch nicht auf 24 Stunden umgerechnet: das hängt von der Tageszeit im Satz ab. */
    private data class TimeAnchor(
        override val start: Int,
        override val end: Int,
        val hour: Int,
        val minute: Int,
        val isEnd: Boolean,
        val daypart: Daypart?,
    ) : Anchor

    private val UNIT_AFTER = """(?!\s*(?:uhr|euro|€|prozent|%|personen|stück|mal|minuten|stunden|tage|tagen|wochen|monate|monaten|jahre|jahren|km|kg|cm)\b)"""
    private val TIME_PREFIX = """(?:\b(um|ab|gegen|von|bis)\s+)?"""
    private val DAYPART_AFTER_DATE = """(?:\s+($DAYPART_DATE_ALT)\b)?"""
    private val DAYPART_AFTER = """(?:\s+($DAYPART_TIME_ALT)\b)?"""
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /**
     * Regex ohne Beachtung der Groß-/Kleinschreibung und mit Unicode-Wortgrenzen: Ab Java 19 kennt `\b`
     * ohne diese Option keine Umlaute („übermorgen“, „fünf“). Auf Android gilt das schon immer.
     */
    private fun rx(pattern: String): Regex = regexCache.getOrPut(pattern) {
        Pattern.compile(
            pattern,
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
        ).toRegex()
    }

    private class Working(val chars: StringBuilder) {
        fun mask(start: Int, end: Int) {
            for (i in start until end) chars.setCharAt(i, ' ')
        }
    }

    private fun findDateAnchors(w: Working, today: LocalDate): List<DateAnchor> {
        val out = mutableListOf<DateAnchor>()

        fun add(m: MatchResult, date: LocalDate?, daypart: Daypart? = null, weekly: Boolean = false) {
            if (date == null) return
            out += DateAnchor(m.range.first, m.range.last + 1, date, daypart, weekly)
            w.mask(m.range.first, m.range.last + 1)
        }

        fun withYear(day: Int, month: Int, yearRaw: String?): LocalDate? = try {
            if (yearRaw != null) {
                val y = if (yearRaw.length == 2) 2000 + yearRaw.toInt() else yearRaw.toInt()
                LocalDate.of(y, month, day)
            } else {
                val thisYear = LocalDate.of(today.year, month, day)
                if (thisYear.isBefore(today)) LocalDate.of(today.year + 1, month, day) else thisYear
            }
        } catch (_: java.time.DateTimeException) {
            null
        }

        // 12.03. oder 12.03.2027
        rx("""(?<![\d.])(?:(?:am|den)\s+)?(\d{1,2})\.\s?(\d{1,2})\.(?:\s?(\d{4}|\d{2}))?(?!\d)""")
            .findAll(w.chars).toList().forEach { m ->
                add(m, withYear(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null }))
            }
        // 12. März / 12 März 2027
        rx("""(?:(?:am|den)\s+)?\b(\d{1,2})\.?\s+($MONTH_ALT)\b(?:\s+(\d{4}))?""")
            .findAll(w.chars).toList().forEach { m ->
                add(m, withYear(m.groupValues[1].toInt(), monthNumber(m.groupValues[2]), m.groupValues[3].ifEmpty { null }))
            }
        // am zwölften März
        rx("""(?:(?:am|den)\s+)?\b($ORDINAL_ALT)(?:en|em|er|es|e)?\s+($MONTH_ALT)\b(?:\s+(\d{4}))?""")
            .findAll(w.chars).toList().forEach { m ->
                val day = ordinalDay(m.groupValues[1]) ?: return@forEach
                add(m, withYear(day, monthNumber(m.groupValues[2]), m.groupValues[3].ifEmpty { null }))
            }
        // am 12. (nächster 12. des Monats)
        rx("""\b(?:am|den)\s+(\d{1,2})\.(?!\s?\d)""").findAll(w.chars).toList().forEach { m ->
            val day = m.groupValues[1].toInt()
            val date = try {
                val thisMonth = LocalDate.of(today.year, today.month, day)
                if (thisMonth.isBefore(today)) {
                    val next = today.withDayOfMonth(1).plusMonths(1)
                    LocalDate.of(next.year, next.month, day)
                } else thisMonth
            } catch (_: java.time.DateTimeException) {
                null
            }
            add(m, date)
        }
        // in zwei Tagen / in einer Woche / in einem Monat
        rx("""\bin\s+(\d{1,2}|$NUM_ALL)\s+(tagen|tag|wochen|woche|monaten|monat)\b""")
            .findAll(w.chars).toList().forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: numberWord(m.groupValues[1]) ?: return@forEach
                val unit = m.groupValues[2].lowercase()
                val date = when {
                    unit.startsWith("tag") -> today.plusDays(n.toLong())
                    unit.startsWith("woche") -> today.plusWeeks(n.toLong())
                    else -> today.plusMonths(n.toLong())
                }
                add(m, date)
            }
        // heute / übermorgen / morgen  (+ optionale Tageszeit: „morgen früh“, „heute Abend“)
        rx("""\bheute$DAYPART_AFTER_DATE""").findAll(w.chars).toList().forEach { m ->
            add(m, today, m.groupValues[1].ifEmpty { null }?.let(::daypartOf))
        }
        rx("""\bübermorgen$DAYPART_AFTER_DATE""").findAll(w.chars).toList().forEach { m ->
            add(m, today.plusDays(2), m.groupValues[1].ifEmpty { null }?.let(::daypartOf))
        }
        rx("""\bmorgen$DAYPART_AFTER_DATE""").findAll(w.chars).toList().forEach { m ->
            val before = w.chars.substring(0, m.range.first).trimEnd().substringAfterLast(' ').lowercase()
            if (before in NOT_TOMORROW_PREFIX) return@forEach
            add(m, today.plusDays(1), m.groupValues[1].ifEmpty { null }?.let(::daypartOf))
        }
        // Wochentage mit optionalem Zusatz
        rx("""\b(?:(nächste|kommende|übernächste|diese)\s+woche\s+(?:am\s+)?|(am|nächsten|kommenden|übernächsten|diesen|jeden)\s+)?($WEEKDAY_ALT)\b$DAYPART_AFTER_DATE""").findAll(w.chars).toList().forEach { m ->
            val weekModifier = m.groupValues[1].lowercase()
            val dayModifier = m.groupValues[2].lowercase()
            val dow = WEEKDAYS[m.groupValues[3].lowercase()] ?: return@forEach
            val daypart = m.groupValues[4].ifEmpty { null }?.let(::daypartOf)
            val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val date = when (weekModifier) {
                "nächste", "kommende" -> thisWeekMonday.plusWeeks(1).plusDays((dow.value - 1).toLong())
                "übernächste" -> thisWeekMonday.plusWeeks(2).plusDays((dow.value - 1).toLong())
                "diese" -> thisWeekMonday.plusDays((dow.value - 1).toLong()).let { if (it.isBefore(today)) it.plusWeeks(1) else it }
                else -> if (dayModifier == "diesen") today.with(TemporalAdjusters.nextOrSame(dow)) else today.with(TemporalAdjusters.next(dow))
            }
            add(m, date, daypart, weekly = dayModifier == "jeden")
        }
        return out
    }

    private val NOT_TOMORROW_PREFIX = setOf("am", "jeden", "diesen", "den", "guten", "dem", "des", "einem", "einen", "heute", "gestern", "frühen", "nächsten", "vom", "zum", "im", "bis")

    private fun findTimeAnchors(w: Working): List<TimeAnchor> {
        val out = mutableListOf<TimeAnchor>()

        fun finish(m: MatchResult, hour: Int, minute: Int, prefix: String, daypartRaw: String?) {
            if (hour !in 0..24 || minute !in 0..59) return
            val daypart = daypartRaw?.takeIf { it.isNotEmpty() }?.let(::daypartOf)
            out += TimeAnchor(m.range.first, m.range.last + 1, hour, minute, prefix.lowercase() == "bis", daypart)
            w.mask(m.range.first, m.range.last + 1)
        }

        // von 14 bis 16 Uhr / von 9:30 bis 11
        rx("""\bvon\s+(\d{1,2})(?::(\d{2}))?\s+bis\s+(\d{1,2})(?::(\d{2}))?(?:\s*uhr\b)?$UNIT_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                val h1 = m.groupValues[1].toInt()
                val m1 = m.groupValues[2].ifEmpty { "0" }.toInt()
                val h2 = m.groupValues[3].toInt()
                val m2 = m.groupValues[4].ifEmpty { "0" }.toInt()
                if (h1 !in 0..24 || h2 !in 0..24 || m1 !in 0..59 || m2 !in 0..59) return@forEach
                out += TimeAnchor(m.range.first, m.range.first + 1, h1, m1, false, null)
                out += TimeAnchor(m.range.first + 1, m.range.last + 1, h2, m2, true, null)
                w.mask(m.range.first, m.range.last + 1)
            }
        // 15:30 (Uhr)
        rx("""$TIME_PREFIX(?<![\d.:])(\d{1,2}):(\d{2})(?:\s*uhr\b)?(?![\d:])$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                finish(m, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1], m.groupValues[4])
            }
        // 15.30 Uhr
        rx("""$TIME_PREFIX(?<![\d.:])(\d{1,2})\.(\d{2})\s*uhr\b$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                finish(m, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1], m.groupValues[4])
            }
        // 15 Uhr / 15 Uhr 30
        rx("""$TIME_PREFIX(?<![\d.:])(\d{1,2})\s*uhr\b(?:\s+(\d{2})(?![\d:.]))?$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                finish(m, m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt(), m.groupValues[1], m.groupValues[4])
            }
        // fünfzehn Uhr dreißig / um drei Uhr
        rx("""$TIME_PREFIX\b($NUM_ALL)\s+uhr\b(?:\s+($NUM_NOART)\b)?$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                val h = numberWord(m.groupValues[2]) ?: return@forEach
                val min = m.groupValues[3].ifEmpty { null }?.let { numberWord(it) } ?: 0
                finish(m, h, min, m.groupValues[1], m.groupValues[4])
            }
        // halb vier (= 3:30)
        rx("""$TIME_PREFIX\bhalb\s+(\d{1,2}|$NUM_NOART)\b(?:\s+uhr\b)?$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                val h = m.groupValues[2].toIntOrNull() ?: numberWord(m.groupValues[2]) ?: return@forEach
                val base = if (h == 1) 12 else h - 1
                finish(m, base, 30, m.groupValues[1], m.groupValues[3])
            }
        // dreiviertel vier (= 3:45), viertel vier (= 3:15), viertel nach/vor drei
        rx("""(?:\b(um|ab|gegen)\s+)?\b(dreiviertel|viertel\s+nach|viertel\s+vor|viertel)\s+(\d{1,2}|$NUM_NOART)\b(?:\s+uhr\b)?$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                val h = m.groupValues[3].toIntOrNull() ?: numberWord(m.groupValues[3]) ?: return@forEach
                val kind = m.groupValues[2].lowercase().replace(Regex("\\s+"), " ")
                val (hour, minute) = when (kind) {
                    "viertel nach" -> h to 15
                    "viertel vor" -> (if (h == 1) 12 else h - 1) to 45
                    "dreiviertel" -> (if (h == 1) 12 else h - 1) to 45
                    else -> (if (h == 1) 12 else h - 1) to 15
                }
                finish(m, hour, minute, m.groupValues[1], m.groupValues[4])
            }
        // zehn nach drei / fünf vor drei  (nur mit „um“)
        rx("""\bum\s+(fünf|zehn|zwanzig|5|10|20)\s+(nach|vor)\s+(\d{1,2}|$NUM_NOART)\b(?!\s+(?:tag|woche|monat|jahr|stunde|minute))(?:\s+uhr\b)?$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                val mins = m.groupValues[1].toIntOrNull() ?: numberWord(m.groupValues[1]) ?: return@forEach
                val h = m.groupValues[3].toIntOrNull() ?: numberWord(m.groupValues[3]) ?: return@forEach
                if (m.groupValues[2].lowercase() == "nach") finish(m, h, mins, "um", m.groupValues[4])
                else finish(m, if (h == 1) 12 else h - 1, 60 - mins, "um", m.groupValues[4])
            }
        // um 9 / gegen 14 / ab 10  (Ziffern ohne „Uhr“)
        rx("""\b(um|ab|gegen)\s+(\d{1,2})\b(?![\d.:,])$UNIT_AFTER$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                finish(m, m.groupValues[2].toInt(), 0, m.groupValues[1], m.groupValues[3])
            }
        // um neun / gegen vierzehn  (Zahlwort ohne „Uhr“)
        rx("""\b(um|ab|gegen)\s+($NUM_NOART)\b$UNIT_AFTER$DAYPART_AFTER""")
            .findAll(w.chars).toList().forEach { m ->
                val h = numberWord(m.groupValues[2]) ?: return@forEach
                finish(m, h, 0, m.groupValues[1], m.groupValues[3])
            }
        // „bis 16 Uhr“ ist durch TIME_PREFIX schon erfasst; „14 bis 16 Uhr“ ebenfalls (zweite Zeit mit „bis“).
        return out
    }

    // ------------------------------------------------------------------ Titel

    private val FILLER = setOf(
        "ich", "habe", "hab", "hätte", "muss", "soll", "will", "werde", "wir", "haben", "hat", "ist", "sind", "bin",
        "noch", "dann", "und", "oder", "außerdem", "ausserdem", "also", "ähm", "äh", "ach", "ja", "mal", "nicht",
        "vergessen", "denk", "dran", "daran", "erinnere", "erinnern", "mich", "bitte", "eintragen", "trag", "ein",
        "notiere", "merken", "merke", "auch", "dazu", "ca", "circa", "etwa", "so", "gleich", "um", "am", "den", "dem",
        "gegen", "ab", "von", "bis", "vor", "nach", "es", "gibt", "steht", "an", "dort", "da", "hier", "dass",
    )
    private val LEADING_ONLY = setOf(
        "bei", "beim", "zum", "zur", "zu", "im", "in", "einen", "eine", "einem", "einer", "der", "die", "das",
        "mein", "meinen", "meinem", "meine", "meiner", "wieder", "nochmal", "für",
    )
    private val TRAILING_ONLY = setOf("bei", "beim", "zum", "zur", "zu", "im", "in", "mit", "für", "bis")

    /** Stichwörter, die irgendwo im Wort vorkommen dürfen („Zahnarzttermin“). */
    private val KEYWORDS_ANYWHERE = listOf(
        "termin", "arzt", "ärztin", "therapie", "geburtstag", "meeting", "besprechung", "gespräch", "konzert",
        "hochzeit", "vortrag", "seminar", "praxis", "untersuchung", "sprechstunde", "prüfung", "klausur",
        "friseur", "frisör", "elternabend", "vorstellungsgespräch", "abendessen", "mittagessen", "verabredung",
    )

    /** Stichwörter nur am Wortanfang, damit „vergessen“ nicht wegen „essen“ zählt. */
    private val KEYWORDS_WORD_START = listOf(
        "call", "telefonat", "treffen", "treff", "party", "feier", "kino", "verabredet", "besuch", "essen",
        "frühstück", "kaffee", "date", "training", "unterricht", "impfung", "operation", "flug", "abflug",
        "werkstatt", "doktor", "physio", "weihnacht", "ostern", "silvester", "jubiläum", "einladung",
    )

    private val KEYWORD_REGEX = rx(
        "(?:" + KEYWORDS_ANYWHERE.joinToString("|") + ")|(?:\\b(?:" + KEYWORDS_WORD_START.joinToString("|") + "))",
    )

    private fun cleanTitle(raw: String): String {
        val words = raw.replace(Regex("""[,;:!?"„“]+"""), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        fun stripEdges() {
            while (words.isNotEmpty() && (words.first().lowercase() in FILLER || words.first().lowercase() in LEADING_ONLY)) words.removeAt(0)
            while (words.isNotEmpty() && (words.last().lowercase() in FILLER || words.last().lowercase() in TRAILING_ONLY)) words.removeAt(words.size - 1)
        }
        stripEdges()
        val s = words.joinToString(" ").trim().trim('.', '-', '–').trim()
        if (s.isEmpty()) return ""
        return (s.take(80)).replaceFirstChar { it.uppercase() }
    }

    private fun hasKeyword(title: String): Boolean = KEYWORD_REGEX.containsMatchIn(title)

    // ------------------------------------------------------------------ Auswertung eines Satzteils

    private class Group {
        var date: DateAnchor? = null
        var start: TimeAnchor? = null
        var end: TimeAnchor? = null
        var from: Int = Int.MAX_VALUE
        var to: Int = -1

        fun add(a: Anchor) {
            from = minOf(from, a.start)
            to = maxOf(to, a.end)
            when (a) {
                is DateAnchor -> date = a
                is TimeAnchor -> if (a.isEnd) end = a else start = a
            }
        }

        fun canTake(a: Anchor): Boolean = when (a) {
            is DateAnchor -> date == null
            is TimeAnchor -> if (a.isEnd) end == null && start != null else start == null
        }

        /** Tageszeit, die für die Umrechnung der Uhrzeit gilt: eigene Angabe, sonst die des Datums („heute Abend“). */
        private fun effectiveDaypart(): Daypart? = start?.daypart ?: date?.daypart

        /** Beginn in Minuten: genannte Uhrzeit, sonst Richtwert aus der Tageszeit, sonst `null` (ganztägig). */
        fun startMinutes(): Int? {
            val s = start ?: return date?.daypart?.defaultMinutes
            val h = resolveHour(s.hour, effectiveDaypart())
            return (if (h == 24) 0 else h) * 60 + s.minute
        }

        fun endMinutes(): Int? {
            val e = end ?: return null
            val h = resolveHour(e.hour, e.daypart ?: effectiveDaypart())
            return (if (h == 24) 0 else h) * 60 + e.minute
        }
    }

    private class Outcome(val appointments: List<DetectedAppointment>, val contextDate: LocalDate?)

    private val MERGE_FILLER = setOf("um", "gegen", "ab", "am", "den", "dem", "ca", "circa", "etwa", "so", "gleich", "bis", "von", "-", "–")

    /** Der Text, aus dem der Titel entsteht, mit allen Datums-/Zeitangaben durch Leerzeichen ersetzt. */
    private fun blankAnchors(segment: String, anchors: List<Anchor>, upTo: Int): String {
        val sb = StringBuilder(segment.substring(0, upTo))
        for (a in anchors) for (i in a.start until minOf(a.end, upTo)) sb.setCharAt(i, ' ')
        return sb.toString()
    }

    private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun analyzeSegment(segment: String, now: LocalDateTime, incomingContext: LocalDate?): Outcome {
        val today = now.toLocalDate()
        val w = Working(StringBuilder(segment))
        val dates = findDateAnchors(w, today)
        val times = findTimeAnchors(w)
        val anchors = (dates + times).sortedBy { it.start }
        if (anchors.isEmpty()) return Outcome(emptyList(), incomingContext)

        // Anker zu Gruppen zusammenfassen („morgen“ + „um 10 Uhr“ = ein Termin)
        val groups = mutableListOf<Group>()
        for (a in anchors) {
            val current = groups.lastOrNull()
            val gap = if (current == null) "" else segment.substring(current.to, a.start)
            val gapWords = gap.lowercase().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            val mergeable = current != null && gapWords.all { it in MERGE_FILLER } && current.canTake(a)
            if (mergeable) current!!.add(a) else groups += Group().also { it.add(a) }
        }

        // Steht der Titel vor oder nach der Uhrzeit? Text vor der ersten Uhrzeit (ohne Datumsangaben) -> davor.
        val firstTimed = groups.firstOrNull { it.start != null } ?: groups.first()
        val lead = blankAnchors(segment, anchors.filterIsInstance<DateAnchor>(), firstTimed.from)
        val titleBefore = cleanTitle(lead).isNotEmpty()

        val pieces = groups.mapIndexed { index, g ->
            val prevEnd = if (index == 0) 0 else groups[index - 1].to
            val nextStart = if (index == groups.lastIndex) segment.length else groups[index + 1].from
            val before = segment.substring(prevEnd, g.from)
            val after = segment.substring(g.to, nextStart)
            val raw = if (titleBefore) {
                // Text zwischen zwei Gruppen gehört zur nächsten; nur hinter der letzten zählt der Rest.
                val tail = if (index == groups.lastIndex) after else ""
                if (wordCount(before) > 5 && cleanTitle(tail).isNotEmpty()) tail else "$before $tail"
            } else {
                // Text zwischen zwei Gruppen gehört zur vorherigen; vor der ersten zählt der Anfang.
                val head = if (index == 0) before else ""
                if (wordCount(after) > 5 && cleanTitle(head).isNotEmpty()) head else "$head $after"
            }
            cleanTitle(raw)
        }

        var context = incomingContext
        val found = mutableListOf<DetectedAppointment>()
        groups.forEachIndexed { index, g ->
            val title = pieces[index]
            var date = g.date?.date
            var dateGuessed = false
            var weekly = g.date?.weekly == true
            val start = g.startMinutes()

            if (date == null && g.start != null) {
                // „um 10 Uhr Zahnarzt am Freitag“: ein nachfolgendes, sonst bedeutungsloses Datum gehört dazu.
                val next = groups.getOrNull(index + 1)
                val borrowed = if (next != null && next.date != null && next.start == null && pieces[index + 1].isEmpty()) next.date else null
                if (borrowed != null) {
                    date = borrowed.date
                    weekly = borrowed.weekly
                } else if (context != null) {
                    date = context
                } else {
                    val nowMin = now.hour * 60 + now.minute
                    date = if (start != null && start > nowMin) today else today.plusDays(1)
                    dateGuessed = true
                }
            }
            g.date?.date?.let { context = it }
            if (date == null) return@forEachIndexed

            val hasTime = g.start != null
            if (!hasTime && !hasKeyword(title)) return@forEachIndexed

            var end = g.endMinutes()
            if (end != null && start != null && end <= start) end = null
            found += DetectedAppointment(
                title = title.ifEmpty { "Termin" },
                date = date,
                startMinutes = start,
                endMinutes = end,
                sourceText = segment.trim(),
                dateGuessed = dateGuessed,
                timeGuessed = !hasTime && start != null,
                weekly = weekly,
            )
        }
        return Outcome(found, context)
    }
}
