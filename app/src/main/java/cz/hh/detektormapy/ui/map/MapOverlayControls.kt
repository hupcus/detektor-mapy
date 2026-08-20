package cz.hh.detektormapy.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.hh.detektormapy.location.FixQuality
import cz.hh.detektormapy.ui.theme.DangerRed
import cz.hh.detektormapy.ui.theme.OkGreen
import cz.hh.detektormapy.ui.theme.WarnAmber
import kotlin.math.roundToInt

/** Floating controls over the map: layers, follow, compass, zoom and the big find button. */
@Composable
fun MapOverlayControls(
    state: MapUiState,
    onToggleLayers: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleCompass: () -> Unit,
    onAddFind: () -> Unit,
    onToggleRecording: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        FixBadge(
            state = state,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(onClick = onToggleLayers) {
                Icon(Icons.Filled.Layers, contentDescription = "Vrstvy")
            }
            SmallFloatingActionButton(
                onClick = onToggleFollow,
                containerColor = if (state.followMode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Sledovat polohu")
            }
            SmallFloatingActionButton(
                onClick = onToggleCompass,
                containerColor = if (state.rotateWithCompass) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Icon(Icons.Filled.Explore, contentDescription = "Otáčet dle kompasu")
            }
            SmallFloatingActionButton(
                onClick = onToggleRecording,
                containerColor = if (state.recording) {
                    DangerRed
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Icon(
                    imageVector = if (state.recording) {
                        Icons.Filled.Stop
                    } else {
                        Icons.Filled.FiberManualRecord
                    },
                    contentDescription = if (state.recording) {
                        "Ukončit záznam pochůzky"
                    } else {
                        "Spustit záznam pochůzky"
                    },
                )
            }
            SmallFloatingActionButton(onClick = onZoomIn) {
                Icon(Icons.Filled.Add, contentDescription = "Přiblížit")
            }
            SmallFloatingActionButton(onClick = onZoomOut) {
                Icon(Icons.Filled.Remove, contentDescription = "Oddálit")
            }
        }

        if (state.mode == MapMode.NAVIGATE) {
            FloatingActionButton(
                onClick = onAddFind,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.secondary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Nový nález")
            }
        }
    }
}

/** GPS quality, active calibration and coverage warnings in one compact chip. */
@Composable
private fun FixBadge(state: MapUiState, modifier: Modifier = Modifier) {
    val fix = state.fix
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(qualityColor(state.fixQuality)),
                )
                Text(
                    text = when {
                        fix == null -> "  bez GPS"
                        fix.accuracyM == null -> "  GPS"
                        else -> "  ±${fix.accuracyM.roundToInt()} m"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            state.activeCalibrationLabel?.let {
                Text(
                    text = "kalibrace: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.recording) {
                Text(
                    text = "záznam pochůzky běží",
                    style = MaterialTheme.typography.labelSmall,
                    color = DangerRed,
                )
            }
        }
    }
}

private fun qualityColor(quality: FixQuality): Color = when (quality) {
    FixQuality.NONE -> DangerRed
    FixQuality.POOR -> DangerRed
    FixQuality.OK -> WarnAmber
    FixQuality.GOOD -> OkGreen
}
