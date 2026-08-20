package cz.hh.detektormapy.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.hh.detektormapy.map.LayerKind
import cz.hh.detektormapy.map.LayerUiState
import kotlin.math.roundToInt

/**
 * Layer panel (issue F1-3).
 *
 * A bottom sheet rather than a drawer: in the field the phone is held one-handed and the
 * bottom third of the screen is the only comfortably reachable area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerPanel(
    layers: List<LayerUiState>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpacity: (String, Float) -> Unit,
    onOpacityCommitted: (String) -> Unit,
    onCalibrate: (String) -> Unit,
    onManageCalibrations: (String) -> Unit,
    onReload: () -> Unit,
    onMoveLayer: (String, Int) -> Unit,
    showFinds: Boolean,
    showPlaces: Boolean,
    showAreas: Boolean,
    onShowFinds: (Boolean) -> Unit,
    onShowPlaces: (Boolean) -> Unit,
    onShowAreas: (Boolean) -> Unit,
    onAddImageOverlay: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reorderMode by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Vrstvy", style = MaterialTheme.typography.titleLarge)
            Row {
                IconButton(onClick = { reorderMode = !reorderMode }) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = "Uspořádat vrstvy",
                        tint = if (reorderMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onReload) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Znovu načíst layers.json")
                }
            }
        }

        val overlays = layers.filterNot { it.def.isBasemap }
        val basemaps = layers.filter { it.def.isBasemap }

        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            if (basemaps.isNotEmpty()) {
                item {
                    SectionLabel("Podklad")
                }
                items(basemaps, key = { it.def.id }) { layer ->
                    LayerRow(layer, onToggle, onOpacity, onOpacityCommitted, onCalibrate, onManageCalibrations)
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            item { SectionLabel("Historické mapy a reliéf") }
            if (reorderMode && overlays.size > 1) {
                item {
                    Text(
                        "Co je v seznamu níž, kreslí se na mapě navrch.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(overlays, key = { _, layer -> layer.def.id }) { index, layer ->
                LayerRow(
                    layer,
                    onToggle,
                    onOpacity,
                    onOpacityCommitted,
                    onCalibrate,
                    onManageCalibrations,
                    reorder = if (reorderMode) {
                        ReorderControls(
                            canMoveUp = index > 0,
                            canMoveDown = index < overlays.lastIndex,
                            onMove = { delta -> onMoveLayer(layer.def.id, delta) },
                        )
                    } else {
                        null
                    },
                )
            }
            if (overlays.isEmpty()) {
                item {
                    Text(
                        "Žádné překryvné vrstvy. Zkopíruj .pmtiles do složky layers " +
                            "a přidej řádek do layers.json.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Na mapě") }
            item {
                PinToggle("Nálezy", showFinds, onShowFinds)
            }
            item {
                PinToggle("Místa", showPlaces, onShowPlaces)
            }
            item {
                PinToggle("Prohledané zóny", showAreas, onShowAreas)
            }
            item {
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onAddImageOverlay) { Text("Přiložit sken…") }
                    TextButton(onClick = onDismiss) { Text("Zavřít") }
                }
            }
        }
    }
}

/** Simple labelled switch for the "what to draw on the map" section. */
@Composable
private fun PinToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Up/down controls shown instead of the visibility switch while the panel is in reorder mode. */
data class ReorderControls(val canMoveUp: Boolean, val canMoveDown: Boolean, val onMove: (Int) -> Unit)

@Composable
private fun LayerRow(
    layer: LayerUiState,
    onToggle: (String, Boolean) -> Unit,
    onOpacity: (String, Float) -> Unit,
    onOpacityCommitted: (String) -> Unit,
    onCalibrate: (String) -> Unit,
    onManageCalibrations: (String) -> Unit,
    reorder: ReorderControls? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(layer.def.title, style = MaterialTheme.typography.titleSmall)
                    if (layer.def.isOnline) {
                        Icon(
                            imageVector = if (layer.available) {
                                Icons.Filled.CloudQueue
                            } else {
                                Icons.Filled.CloudOff
                            },
                            contentDescription = "Online vrstva",
                            modifier = Modifier.padding(start = 6.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                val subtitle = when {
                    !layer.available -> layer.unavailableReason ?: "nedostupná"
                    layer.activeCalibrationId != null -> "kalibrováno"
                    layer.def.kind == LayerKind.PMTILES -> "offline PMTiles"
                    layer.def.isOnline -> "vyžaduje signál"
                    else -> layer.def.note.orEmpty()
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (layer.def.manualAlignment) {
                    Text(
                        "Přibližná poloha — přesné zarovnání jen ručně (Přiložit sken…)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (reorder != null) {
                // The switch hides so a reorder tap can never accidentally toggle a layer.
                IconButton(onClick = { reorder.onMove(-1) }, enabled = reorder.canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Posunout výš")
                }
                IconButton(onClick = { reorder.onMove(+1) }, enabled = reorder.canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Posunout níž")
                }
            } else {
                Switch(
                    checked = layer.visible,
                    onCheckedChange = { onToggle(layer.def.id, it) },
                    enabled = layer.available,
                )
            }
        }

        if (reorder == null && layer.visible && layer.available) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = layer.opacity,
                    onValueChange = { onOpacity(layer.def.id, it) },
                    onValueChangeFinished = { onOpacityCommitted(layer.def.id) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(layer.opacity * 100).roundToInt()} %",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (!layer.def.isBasemap && layer.def.isRaster) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onCalibrate(layer.def.id) }) {
                        Icon(Icons.Filled.Straighten, contentDescription = null)
                        Text("  Sladit", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { onManageCalibrations(layer.def.id) }) {
                        Icon(Icons.Filled.Tune, contentDescription = null)
                        Text("  Kalibrace", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
