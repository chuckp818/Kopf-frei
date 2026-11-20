package de.kopffrei.core.designsystem.components

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// ------------------------------------------------------------------ Spracheingabe

private const val LANGUAGE_TAG = "de-DE"

/**
 * Diktieren direkt in der App: Ein Fenster zeigt live, was verstanden wird. Nach Sprechpausen hört die
 * Erkennung von selbst wieder zu, bis der Nutzer „Fertig“ tippt; erst dann wird der gesamte Text
 * übergeben. Die Spracherkennung selbst übernimmt der Spracherkennungsdienst des Geräts (meist Google);
 * ob er offline arbeitet, hängt vom heruntergeladenen Sprachpaket ab.
 *
 * Fehlt die Berechtigung oder ein Dienst, fällt es auf den Systemdialog zurück bzw. meldet das.
 *
 * @return Funktion, die das Diktieren startet.
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    var listening by remember { mutableStateOf(false) }

    val systemDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) currentOnResult(text)
        }
    }
    val startSystemDialog = remember(systemDialog, context) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Sprich einfach los …")
            }
            try {
                systemDialog.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Auf diesem Gerät ist keine Spracherkennung verfügbar.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listening = true
        } else {
            Toast.makeText(
                context,
                "Ohne Mikrofon-Zugriff kann ich nicht zuhören. Du kannst ihn in den Android-Einstellungen der App erlauben.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    if (listening) {
        VoiceListeningDialog(
            onDone = { text ->
                listening = false
                if (text.isNotBlank()) currentOnResult(text)
            },
            onCancel = { listening = false },
            onFailure = { message ->
                listening = false
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
            onUnavailable = {
                listening = false
                startSystemDialog()
            },
        )
    }

    val start: () -> Unit = {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            startSystemDialog()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            listening = true
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return start
}

@Composable
fun VoiceInputButton(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val start = rememberVoiceInput(onResult)
    IconButton(onClick = start, modifier = modifier) {
        Icon(Icons.Rounded.Mic, contentDescription = "Einsprechen")
    }
}

@Composable
private fun VoiceListeningDialog(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf("") }
    val doneNow by rememberUpdatedState(onDone)
    val failureNow by rememberUpdatedState(onFailure)
    val unavailableNow by rememberUpdatedState(onUnavailable)
    val recognizer = remember {
        ContinuousRecognizer(
            context = context,
            onText = { shown = it },
            onDone = { doneNow(it) },
            onFailure = { failureNow(it) },
            onUnavailable = { unavailableNow() },
        )
    }
    DisposableEffect(recognizer) {
        recognizer.start()
        onDispose { recognizer.destroy() }
    }

    AlertDialog(
        onDismissRequest = {
            recognizer.destroy()
            onCancel()
        },
        icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
        title = { Text("Ich höre zu …") },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (shown.isBlank()) "Sprich in Ruhe. Pausen sind okay. Mit „Fertig“ übernehme ich den Text." else shown,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (shown.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = { TextButton(onClick = recognizer::finish) { Text("Fertig") } },
        dismissButton = {
            TextButton(
                onClick = {
                    recognizer.destroy()
                    onCancel()
                },
            ) { Text("Abbrechen") }
        },
    )
}

/**
 * Hört dauerhaft zu: Nach jedem Ergebnis oder jeder Sprechpause wird die Erkennung neu gestartet,
 * bis [finish] aufgerufen wird. Alle Rückrufe kommen im Hauptthread.
 */
private class ContinuousRecognizer(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onDone: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onUnavailable: () -> Unit,
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val parts = mutableListOf<String>()
    private var partial = ""
    private var stopping = false
    private var destroyed = false
    private var delivered = false
    private var silentRounds = 0
    private var busyRetries = 0
    private var preferOffline = true

    /** Der Dienst hört gerade zu (zwischen startListening und Ergebnis/Fehler). */
    private var active = false

    fun start() {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
        } catch (_: Exception) {
            onUnavailable()
            return
        }
        listen()
    }

    /** „Fertig“: Es wird noch das letzte Stück ausgewertet, dann wird alles übergeben. */
    fun finish() {
        if (destroyed || stopping) return
        stopping = true
        if (!active) {
            // Gerade zwischen zwei Durchgängen: es kommt kein Ergebnis mehr, also direkt übergeben.
            deliver()
            return
        }
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            deliver()
            return
        }
        // Falls der Dienst nicht mehr antwortet, geben wir nach kurzer Zeit trotzdem das Bisherige zurück.
        handler.postDelayed({ deliver() }, FINISH_TIMEOUT_MS)
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // schon beendet
        }
        recognizer = null
    }

    private fun listen() {
        val r = recognizer ?: return
        if (destroyed) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            // Hinweise an den Dienst, bei Pausen länger zu warten; er darf sie ignorieren.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        }
        try {
            active = true
            r.startListening(intent)
        } catch (_: Exception) {
            active = false
            failOrDeliver("Die Spracherkennung konnte nicht gestartet werden.")
        }
    }

    private fun restartSoon(delayMs: Long = RESTART_DELAY_MS) {
        if (destroyed || stopping) return
        handler.postDelayed({ if (!destroyed && !stopping) listen() }, delayMs)
    }

    private fun publish() {
        val live = (parts + partial.trim()).filter { it.isNotBlank() }.joinToString(" ")
        onText(live)
    }

    private fun deliver() {
        if (delivered || destroyed) return
        delivered = true
        val all = (parts + partial.trim()).filter { it.isNotBlank() }
        // Das letzte Teilergebnis nicht doppelt anhängen, falls es schon als Endergebnis kam.
        val text = all.fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc.add(s); acc }
            .joinToString(" ")
        handler.removeCallbacksAndMessages(null)
        onDone(text)
    }

    private fun failOrDeliver(message: String) {
        if (delivered || destroyed) return
        if (parts.isNotEmpty() || partial.isNotBlank()) {
            deliver()
        } else {
            delivered = true
            handler.removeCallbacksAndMessages(null)
            onFailure(message)
        }
    }

    // --- RecognitionListener ---

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) {
            partial = text
            publish()
        }
    }

    override fun onResults(results: Bundle?) {
        active = false
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        if (text.isNotBlank()) {
            parts += text
            silentRounds = 0
            busyRetries = 0
        }
        partial = ""
        publish()
        if (stopping) deliver() else restartSoon()
    }

    override fun onError(error: Int) {
        active = false
        if (destroyed || delivered) return
        if (stopping) {
            deliver()
            return
        }
        when (error) {
            ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> {
                // Stille: Wenn schon etwas verstanden wurde und es länger ruhig ist, ist der Nutzer wohl fertig.
                silentRounds++
                if (silentRounds >= MAX_SILENT_ROUNDS) {
                    if (parts.isEmpty() && partial.isBlank()) failOrDeliver("Ich habe nichts gehört. Versuch es noch einmal.")
                    else deliver()
                } else {
                    restartSoon()
                }
            }
            ERROR_RECOGNIZER_BUSY -> {
                busyRetries++
                if (busyRetries > MAX_BUSY_RETRIES) failOrDeliver("Die Spracherkennung ist gerade beschäftigt.") else restartSoon(BUSY_DELAY_MS)
            }
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                if (preferOffline) {
                    // Kein Offline-Sprachpaket für Deutsch: es mit Onlineerkennung versuchen.
                    preferOffline = false
                    restartSoon()
                } else {
                    failOrDeliver("Deutsche Spracherkennung ist auf diesem Gerät nicht verfügbar.")
                }
            }
            ERROR_INSUFFICIENT_PERMISSIONS -> failOrDeliver("Mikrofon-Zugriff fehlt.")
            ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_SERVER, ERROR_TOO_MANY_REQUESTS, ERROR_SERVER_DISCONNECTED ->
                failOrDeliver("Die Spracherkennung braucht gerade eine Internetverbindung oder ist nicht erreichbar.")
            else -> failOrDeliver("Die Spracherkennung hat ein Problem gemeldet (Code $error).")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        const val RESTART_DELAY_MS = 250L
        const val BUSY_DELAY_MS = 600L
        const val FINISH_TIMEOUT_MS = 3_000L
        const val MAX_SILENT_ROUNDS = 3
        const val MAX_BUSY_RETRIES = 3

        // Fehlercodes von SpeechRecognizer (Zahlen, damit ältere Android-Versionen keine Rolle spielen).
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_SERVER = 4
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
