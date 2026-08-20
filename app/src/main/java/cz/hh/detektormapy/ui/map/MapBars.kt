package cz.hh.detektormapy.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.util.Geo
import kotlin.math.roundToInt

/** Bottom bar shown while Režim A calibration is active. */
@Composable
fun CalibrationBar(
    state: MapUiState,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var askLabel by remember { mutableStateOf(false) }
    val layerTitle = state.calibrationLayer?.def?.title ?: "vrstva"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Kalibrace: $layerTitle", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dvěma prsty posuň, otoč nebo zvětši mapu. Podklad zůstává na místě.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("Zrušit") }
                TextButton(onClick = onReset, enabled = state.calibrationDirty) { Text("Vynulovat") }
                Button(
                    onClick = { askLabel = true },
                    enabled = state.calibrationDirty,
                ) { Text("Uložit pro tuto oblast") }
            }
        }
    }

    if (askLabel) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLabel = false },
            title = { Text("Pojmenuj kalibraci") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Např. Kostel u Zbraslavic") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                Button(onClick = {
                    askLabel = false
                    onSave(label)
                }) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { askLabel = false }) { Text("Zpět") } },
        )
    }
}

/** Bottom bar for drawing a searched-area polygon. */
@Composable
fun DrawAreaBar(
    pointCount: Int,
    onUndo: () -> Unit,
    onFinish: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var askName by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Kreslení prohledané zóny", style = MaterialTheme.typography.titleMedium)
            Text("Klepej do mapy, body se spojí. Zatím: $pointCount", style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel) { Text("Zrušit") }
                TextButton(onClick = onUndo, enabled = pointCount > 0) { Text("Zpět") }
                Button(onClick = { askName = true }, enabled = pointCount >= 3) { Text("Hotovo") }
            }
        }
    }

    if (askName) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Název zóny") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Např. Pole za mlýnem") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    askName = false
                    onFinish(name)
                }) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Zpět") } },
        )
    }
}

/** Distance + bearing card for the "navigate to waypoint" flow. */
@Composable
fun NavigationHintCard(
    target: PlaceEntity,
    hint: Triple<Double, Double, String>?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(target.title.ifBlank { target.type.label }, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = hint?.let { (distance, bearing, label) ->
                        "${Geo.formatDistance(distance)} • $label (${bearing.roundToInt()}°)"
                    } ?: "čekám na GPS",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onDismiss) { Text("Konec") }
        }
    }
}

/** Long-press dialog: new waypoint here, or start drawing a searched area. */
@Composable
fun NewPlaceDialog(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onConfirm: (PlaceType, String, String) -> Unit,
    onDrawArea: () -> Unit,
) {
    var type by remember { mutableStateOf(PlaceType.PLAN) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nové místo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    String.format(java.util.Locale.forLanguageTag("cs"), "%.5f, %.5f", lat, lon),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlaceType.entries.take(3).forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlaceType.entries.drop(3).forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Název") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Poznámka") },
                    minLines = 2,
                )
                TextButton(onClick = onDrawArea) { Text("Místo toho nakreslit prohledanou zónu") }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(type, title, note) }) { Text("Uložit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

/**
 * Standing-inside-ÚAN warning (issue F4-3).
 *
 * Deliberately loud and non-dismissible while it applies: detecting inside a category I or II
 * area without a permit is illegal, and the whole reason the layer exists is so the user finds
 * that out before digging, not afterwards.
 */
@Composable
fun ProtectedAreaBanner(hit: cz.hh.detektormapy.map.ProtectedAreaHit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "⚠ ${hit.category}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = buildString {
                    if (hit.name.isNotBlank()) {
                        append(hit.name)
                        append(" — ")
                    }
                    append("hledání bez povolení je zde zakázané")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
