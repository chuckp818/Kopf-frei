package de.kopffrei.core.ai

import de.kopffrei.core.common.calendar.DetectedAppointment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schickt den Text an die Claude-API (Anthropic) und liest die erkannten Termine heraus. Wird **nur**
 * aufgerufen, wenn der Nutzer die KI-Erkennung eingeschaltet, einen eigenen API-Schlüssel hinterlegt
 * und die Erkennung ausdrücklich ausgelöst hat. Nichts davon wird protokolliert.
 */
@Singleton
class ClaudeAppointmentExtractor @Inject constructor(
    private val keyStore: AiKeyStore,
) {
    /** @throws AiException mit einer für den Nutzer lesbaren Meldung */
    suspend fun extract(text: String, now: LocalDateTime): List<DetectedAppointment> {
        val key = keyStore.load() ?: throw AiException("Kein API-Schlüssel hinterlegt.")
        val body = AppointmentAiParser.requestBody(MODEL, now, text)
        val response = withContext(Dispatchers.IO) { post(key, body) }
        return AppointmentAiParser.parseResponse(response, text)
    }

    private fun post(key: String, body: String): String {
        val connection = try {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", key)
                setRequestProperty("anthropic-version", API_VERSION)
            }
        } catch (_: IOException) {
            throw AiException("Keine Verbindung zum KI-Dienst.")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiException(messageForHttp(code))
            return text
        } catch (e: AiException) {
            throw e
        } catch (_: IOException) {
            throw AiException("Keine Verbindung zum KI-Dienst.")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"

        /** Schnelles, günstiges Modell reicht für diese Aufgabe. Hier zentral änderbar. */
        const val MODEL = "claude-haiku-5-5"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 40_000

        fun messageForHttp(code: Int): String = when (code) {
            401, 403 -> "Der API-Schlüssel wurde abgelehnt. Bitte in den Einstellungen prüfen."
            400 -> "Die Anfrage wurde abgelehnt (z. B. wegen fehlendem Guthaben beim API-Konto)."
            404 -> "Das KI-Modell wurde nicht gefunden."
            429 -> "Zu viele Anfragen oder Limit erreicht."
            in 500..599 -> "Der KI-Dienst ist gerade nicht erreichbar oder überlastet."
            else -> "Die KI-Anfrage ist fehlgeschlagen (Code $code)."
        }
    }
}
