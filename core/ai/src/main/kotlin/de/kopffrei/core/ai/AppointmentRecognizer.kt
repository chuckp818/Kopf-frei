package de.kopffrei.core.ai

import de.kopffrei.core.common.calendar.AppointmentDetector
import de.kopffrei.core.common.calendar.DetectedAppointment
import de.kopffrei.core.database.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ergebnis einer Termin-Erkennung.
 * @property usedAi die Termine stammen von der KI (sonst von der lokalen Erkennung)
 * @property note Hinweis für den Nutzer, z. B. warum auf die lokale Erkennung zurückgegriffen wurde
 */
data class Recognition(
    val items: List<DetectedAppointment>,
    val usedAi: Boolean,
    val note: String? = null,
)

/**
 * Einstiegspunkt der Termin-Erkennung. Ohne eingeschaltete KI läuft alles lokal. Mit KI wird bei jedem
 * Fehler (kein Schlüssel, kein Netz, Limit …) automatisch die lokale Erkennung benutzt.
 */
@Singleton
class AppointmentRecognizer @Inject constructor(
    private val settings: SettingsRepository,
    private val keyStore: AiKeyStore,
    private val extractor: ClaudeAppointmentExtractor,
) {
    suspend fun recognize(text: String, now: LocalDateTime = LocalDateTime.now()): Recognition {
        val local = AppointmentDetector.detect(text, now)
        if (!settings.settings.first().aiAppointments) return Recognition(local, usedAi = false)
        if (!keyStore.hasKey()) {
            return Recognition(local, usedAi = false, note = "Kein API-Schlüssel hinterlegt. Ich habe die lokale Erkennung benutzt.")
        }
        return try {
            Recognition(extractor.extract(text, now), usedAi = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            Recognition(local, usedAi = false, note = "${e.userMessage} Ich habe die lokale Erkennung benutzt.")
        } catch (_: Exception) {
            Recognition(local, usedAi = false, note = "Die KI-Erkennung hat nicht geklappt. Ich habe die lokale Erkennung benutzt.")
        }
    }
}
