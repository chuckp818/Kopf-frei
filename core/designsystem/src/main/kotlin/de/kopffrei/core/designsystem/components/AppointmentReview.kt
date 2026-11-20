package de.kopffrei.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.kopffrei.core.common.calendar.DetectedAppointment
import de.kopffrei.core.common.text.DateText

/** „Sa., 21. Nov. · 10:00–11:00 Uhr“ bzw. „… · ganztägig“. */
fun describeAppointmentTime(item: DetectedAppointment): String {
    val day = DateText.dateShort(item.date)
    val start = item.startMinutes ?: return "$day · ganztägig"
    val end = item.endMinutes?.let { "–" + DateText.clock(it) }.orEmpty()
    return "$day · ${DateText.clock(start)}$end Uhr"
}

/**
 * Zeigt erkannte Termine zur Bestätigung. Sicher erkannte sind vorausgewählt; Termine, bei denen Datum
 * oder Uhrzeit geschätzt wurden, muss der Nutzer bewusst ankreuzen. Nichts wird ohne Bestätigung eingetragen.
 */
@Composable
fun AppointmentReviewDialog(
    items: List<DetectedAppointment>,
    onConfirm: (List<DetectedAppointment>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(items) { mutableStateListOf<Boolean>().apply { addAll(items.map { it.confident }) } }
    val count = selected.count { it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "1 Termin erkannt" else "${items.size} Termine erkannt") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Soll ich das in deinen Kalender eintragen? Du kannst es dort später noch ändern.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected[index] = !selected[index] }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected[index], onCheckedChange = { selected[index] = it })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                describeAppointmentTime(item),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val hints = buildList {
                                if (item.dateGuessed) add("Datum geschätzt")
                                if (item.timeGuessed) add("Uhrzeit geschätzt")
                                if (item.weekly) add("wöchentlich")
                                if (item.byAi) add("von der KI erkannt, bitte prüfen")
                            }
                            if (hints.isNotEmpty()) {
                                Text(
                                    hints.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(items.filterIndexed { i, _ -> selected[i] }) },
                enabled = count > 0,
            ) { Text(if (count == 1) "1 Termin eintragen" else "$count Termine eintragen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nicht eintragen") } },
    )
}
