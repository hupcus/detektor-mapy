package cz.hh.detektormapy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.ui.calibration.CalibrationReadout
import cz.hh.detektormapy.ui.calibration.CalibrationViewModel
import cz.hh.detektormapy.ui.nav.Routes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage the calibrations stored for one layer (issue F3-2): rename, delete, preview and
 * pick which one wins when two areas overlap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationListScreen(
    navController: NavHostController,
    layerId: String,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    LaunchedEffect(layerId) { viewModel.bind(layerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<LayerCalibrationEntity?>(null) }
    var deleting by remember { mutableStateOf<LayerCalibrationEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.layerTitle) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                "Kalibrace se ukládají per oblast. Při návratu do oblasti se použije ta " +
                    "s nejmenším ohraničením, která obsahuje tvoji polohu.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { viewModel.clearApplied() }) { Text("Zrušit náhled") }
                TextButton(onClick = { navController.navigate(Routes.gcpEditor(layerId)) }) {
                    Text("GCP editor")
                }
            }

            if (state.calibrations.isEmpty()) {
                Text(
                    "Zatím žádná kalibrace. Na mapě otevři Vrstvy → Sladit.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            LazyColumn {
                items(state.calibrations, key = { it.id }) { calibration ->
                    CalibrationRow(
                        calibration = calibration,
                        applied = state.appliedId == calibration.id,
                        onApply = { viewModel.apply(calibration) },
                        onRename = { renaming = calibration },
                        onDelete = { deleting = calibration },
                        onToggleActive = { viewModel.setActive(calibration.id, it) },
                    )
                }
            }
        }
    }

    renaming?.let { target ->
        var label by remember(target.id) { mutableStateOf(target.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Přejmenovat kalibraci") },
            text = {
                OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.rename(target.id, label)
                    renaming = null
                }) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Zrušit") } },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Smazat kalibraci?") },
            text = { Text("„${target.label}“ bude nenávratně odstraněna.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(target.id)
                    deleting = null
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun CalibrationRow(
    calibration: LayerCalibrationEntity,
    applied: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    val formatter = remember { SimpleDateFormat("d. M. yyyy HH:mm", Locale.forLanguageTag("cs")) }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        calibration.label.ifBlank { "Bez názvu" },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        formatter.format(Date(calibration.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        CalibrationReadout.describeAt(
                            Affine2D(
                                calibration.m0,
                                calibration.m1,
                                calibration.m2,
                                calibration.m3,
                                calibration.m4,
                                calibration.m5,
                            ),
                            centerLat = (calibration.south + calibration.north) / 2.0,
                            centerLon = (calibration.west + calibration.east) / 2.0,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Přejmenovat")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Smazat")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onApply) {
                    Text(if (applied) "Použito" else "Zobrazit na mapě")
                }
                TextButton(onClick = { onToggleActive(!calibration.active) }) {
                    Text(if (calibration.active) "Zapnuto" else "Vypnuto")
                }
            }
        }
    }
}
