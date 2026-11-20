package de.kopffrei.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kopffrei.core.ai.AppointmentRecognizer
import de.kopffrei.core.ai.Recognition
import de.kopffrei.core.common.calendar.AppointmentDetector
import de.kopffrei.core.common.calendar.DetectedAppointment
import de.kopffrei.core.common.text.BrainDump
import de.kopffrei.core.database.entity.NoteEntity
import de.kopffrei.core.database.entity.TaskEntity
import de.kopffrei.core.database.prefs.SettingsRepository
import de.kopffrei.core.database.repository.DraftRepository
import de.kopffrei.core.database.repository.NoteRepository
import de.kopffrei.core.database.repository.TaskRepository
import de.kopffrei.core.reminders.AppointmentImporter
import de.kopffrei.core.designsystem.components.AppointmentReviewDialog
import de.kopffrei.core.designsystem.components.KfButton
import de.kopffrei.core.designsystem.components.KfButtonStyle
import de.kopffrei.core.designsystem.components.KfScreen
import de.kopffrei.core.designsystem.components.KfTextField
import de.kopffrei.core.designsystem.components.rememberVoiceInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

data class BrainDumpState(
    val text: String = "",
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * „Kopf ausschütten“: Freitext (auch per Spracheingabe), der laufend als Entwurf gesichert wird.
 * Danach lässt er sich in einzelne Gedanken, Aufgaben oder eine Notiz umwandeln. Es wird nichts
 * automatisch sortiert oder bewertet.
 */
@HiltViewModel
class BrainDumpViewModel @Inject constructor(
    private val drafts: DraftRepository,
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val appointmentImporter: AppointmentImporter,
    private val appointmentRecognizer: AppointmentRecognizer,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** KI-Erkennung eingeschaltet (Einstellungen)? Dann wird nur auf ausdrücklichen Tipp erkannt. */
    val aiEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.aiAppointments }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)


    private val _state = MutableStateFlow(BrainDumpState())
    val state: StateFlow<BrainDumpState> = _state.asStateFlow()

    // Eigener Scope, damit das letzte Speichern auch nach dem Verlassen des Bildschirms zu Ende läuft.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var saveJob: Job? = null

    @Volatile
    private var dirty = false

    init {
        viewModelScope.launch {
            val saved = try {
                drafts.get(DraftRepository.KIND_BRAIN_DUMP).orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
            _state.update { it.copy(text = saved, loaded = true) }
        }
    }

    fun onTextChange(text: String) {
        if (!_state.value.loaded) return
        _state.update { it.copy(text = text) }
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            persist()
        }
    }

    /** Hängt eingesprochenen Text als neue Zeile an. */
    fun appendText(extra: String) {
        val current = _state.value.text
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        onTextChange(current + separator + extra.trim())
    }

    fun flushNow() {
        ioScope.launch { persist() }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun recognizeAppointments(onDone: (Recognition) -> Unit) {
        val text = _state.value.text
        viewModelScope.launch { onDone(appointmentRecognizer.recognize(text)) }
    }

    /** Trägt bestätigte, erkannte Termine in den Kalender ein. Der Text bleibt unverändert erhalten. */
    fun importAppointments(items: List<DetectedAppointment>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val message = try {
                val n = appointmentImporter.import(items)
                if (n == 1) "1 Termin im Kalender eingetragen." else "$n Termine im Kalender eingetragen."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Das Eintragen hat nicht geklappt. Dein Text ist noch da."
            }
            _state.update { it.copy(message = message) }
        }
    }

    fun parkAsThoughts() = convert("Gedanke geparkt", "Gedanken geparkt") { line -> notes.addThought(line) }

    fun makeTasks() = convert("Aufgabe angelegt", "Aufgaben angelegt") { line ->
        tasks.save(TaskEntity(title = line), emptyList())
    }

    /** Der ganze Text wird eine einzige Notiz (statt einzelner Zeilen). */
    fun saveAsNote() {
        val text = _state.value.text.trim()
        if (text.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        ioScope.launch {
            try {
                notes.save(NoteEntity(body = text))
                clearDraft()
                _state.update { it.copy(text = "", busy = false, message = "Als Notiz gespeichert") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, message = FAILURE_MESSAGE) }
            }
        }
    }

    /**
     * Legt pro nicht leerer Zeile etwas an. Bei einem Fehler bleiben genau die noch nicht
     * übernommenen Zeilen im Textfeld, damit nichts doppelt oder verloren geht.
     */
    private fun convert(singular: String, plural: String, create: suspend (String) -> Unit) {
        val lines = BrainDump.splitLines(_state.value.text)
        if (lines.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        ioScope.launch {
            var done = 0
            try {
                for (line in lines) {
                    create(line)
                    done++
                }
                clearDraft()
                val label = if (done == 1) "1 $singular" else "$done $plural"
                _state.update { it.copy(text = "", busy = false, message = label) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val rest = lines.drop(done).joinToString("\n")
                dirty = true
                _state.update { it.copy(text = rest, busy = false, message = FAILURE_MESSAGE) }
                persist()
            }
        }
    }

    private suspend fun clearDraft() {
        saveJob?.cancel()
        mutex.withLock {
            dirty = false
            drafts.clear(DraftRepository.KIND_BRAIN_DUMP)
        }
    }

    /** true = gesichert (oder nichts zu sichern). dirty wird VOR dem Lesen zurückgesetzt, damit keine Eingabe verloren geht. */
    private suspend fun persist(): Boolean = mutex.withLock {
        if (!dirty) return@withLock true
        dirty = false
        val text = _state.value.text
        try {
            drafts.save(DraftRepository.KIND_BRAIN_DUMP, text)
            true
        } catch (e: CancellationException) {
            dirty = true
            throw e
        } catch (_: Exception) {
            dirty = true
            false
        }
    }

    override fun onCleared() {
        if (dirty) ioScope.launch { persist() }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 500L
        const val FAILURE_MESSAGE = "Das hat nicht geklappt. Dein Text ist noch da."
    }
}

@Composable
fun BrainDumpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrainDumpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val startVoice = rememberVoiceInput { viewModel.appendText(it) }
    val lineCount = BrainDump.splitLines(state.text).size
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var reviewItems by remember { mutableStateOf<List<DetectedAppointment>?>(null) }
    var recognizing by remember { mutableStateOf(false) }
    // Lokale Erkennung: kurz warten und im Hintergrund rechnen, damit das Tippen flüssig bleibt.
    // Mit KI wird nur auf ausdrücklichen Tipp erkannt (dann verlässt der Text das Gerät).
    val detected by produceState(initialValue = emptyList<DetectedAppointment>(), state.text, aiEnabled) {
        if (aiEnabled || state.text.isBlank()) {
            value = emptyList()
        } else {
            delay(400)
            value = withContext(Dispatchers.Default) { AppointmentDetector.detect(state.text, LocalDateTime.now()) }
        }
    }

    BackHandler {
        viewModel.flushNow()
        onBack()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flushNow() }
    LaunchedEffect(state.message) {
        val text = state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    KfScreen(
        title = "Kopf ausschütten",
        onBack = {
            viewModel.flushNow()
            onBack()
        },
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Schreib oder sprich alles auf, was gerade in deinem Kopf ist. Eine Zeile pro Gedanke. Sortieren kannst du später.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.loaded) {
                KfTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    label = "Was beschäftigt dich?",
                    minLines = 8,
                )
            }
            KfButton(
                "Einsprechen",
                onClick = startVoice,
                icon = Icons.Rounded.Mic,
                style = KfButtonStyle.Outlined,
                modifier = Modifier.fillMaxWidth(),
            )

            if (aiEnabled) {
                if (state.text.isNotBlank()) {
                    KfButton(
                        if (recognizing) "Ich erkenne Termine …" else "Termine mit KI erkennen",
                        onClick = {
                            recognizing = true
                            viewModel.recognizeAppointments { result ->
                                recognizing = false
                                result.note?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                if (result.items.isEmpty()) {
                                    Toast.makeText(context, "Ich habe keine Termine gefunden.", Toast.LENGTH_SHORT).show()
                                } else {
                                    reviewItems = result.items
                                }
                            }
                        },
                        enabled = !recognizing,
                        icon = Icons.Rounded.CalendarToday,
                        style = KfButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (detected.isNotEmpty()) {
                KfButton(
                    if (detected.size == 1) "1 Termin erkannt – eintragen?" else "${detected.size} Termine erkannt – eintragen?",
                    onClick = { reviewItems = detected },
                    icon = Icons.Rounded.CalendarToday,
                    style = KfButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val enabled = lineCount > 0 && !state.busy
            Text(
                if (lineCount == 0) "Noch nichts da." else if (lineCount == 1) "1 Zeile erkannt." else "$lineCount Zeilen erkannt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KfButton(
                "Als Gedanken parken",
                onClick = viewModel::parkAsThoughts,
                icon = Icons.Rounded.Inbox,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            KfButton(
                "Als Aufgaben anlegen",
                onClick = viewModel::makeTasks,
                icon = Icons.Rounded.TaskAlt,
                style = KfButtonStyle.Tonal,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            KfButton(
                "Als eine Notiz speichern",
                onClick = viewModel::saveAsNote,
                icon = Icons.Rounded.StickyNote2,
                style = KfButtonStyle.Tonal,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Dein Text wird laufend als Entwurf gesichert und bleibt, bis du ihn umwandelst.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    reviewItems?.let { items ->
        AppointmentReviewDialog(
            items = items,
            onConfirm = { chosen ->
                reviewItems = null
                viewModel.importAppointments(chosen)
            },
            onDismiss = { reviewItems = null },
        )
    }
}
