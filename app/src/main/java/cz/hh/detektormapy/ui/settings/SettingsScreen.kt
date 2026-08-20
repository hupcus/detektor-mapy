package cz.hh.detektormapy.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.export.ExportResult
import cz.hh.detektormapy.data.export.ImportResult
import cz.hh.detektormapy.map.LayerUiState
import cz.hh.detektormapy.ui.nav.Routes

/**
 * The "Nastavení" tab: layers, map behaviour, backups and the entry points to the tracks,
 * pre-flight and about screens.
 *
 * The layers group deliberately shows the absolute path of the layers directory as selectable
 * text -- copying PMTiles archives over USB is the documented way to get maps into the app
 * (PLAN.md section 7 point 4), and the path differs per device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importBackup(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Nastavení") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LayersGroup(
                state = state,
                onReload = viewModel::reloadLayers,
                onCalibrate = { navController.navigate(Routes.calibrations(it)) },
            )
            MapGroup(
                state = state,
                onRotate = viewModel::setRotateWithCompass,
                onFollow = viewModel::setFollowMode,
                onKeepScreenOn = viewModel::setKeepScreenOn,
            )
            DataGroup(
                state = state,
                onExport = { viewModel.exportAll() },
                onImport = {
                    importPicker.launch(
                        arrayOf("application/zip", "application/octet-stream", "*/*"),
                    )
                },
            )
            SettingsGroup("Záznam") {
                NavigationRow("Pochůzky (tracky)", "Seznam nahraných tras a export GPX") {
                    navController.navigate(Routes.TRACKS)
                }
            }
            SettingsGroup("O aplikaci") {
                NavigationRow("Pre-flight", "Kontrola před výjezdem: slunce, baterie, vrstvy") {
                    navController.navigate(Routes.PREFLIGHT)
                }
                HorizontalDivider()
                NavigationRow("O aplikaci a atribuce", "Zdroje dat, licence, právní minimum") {
                    navController.navigate(Routes.ABOUT)
                }
            }
        }
    }

    val export = state.exportResult
    if (export != null) {
        ExportResultDialog(
            result = export,
            onShare = {
                val shared = shareFile(
                    context = context,
                    file = export.archive,
                    mimeType = "application/zip",
                    chooserTitle = "Sdílet zálohu",
                )
                if (!shared) viewModel.notify("Sdílení se nepodařilo otevřít")
            },
            onDismiss = viewModel::dismissExportResult,
        )
    }

    val import = state.importResult
    if (import != null) {
        ImportResultDialog(result = import, onDismiss = viewModel::dismissImportResult)
    }
}

@Composable
private fun LayersGroup(state: SettingsUiState, onReload: () -> Unit, onCalibrate: (String) -> Unit) {
    SettingsGroup("Vrstvy") {
        Text(
            text = "Načteno ${state.layers.size} vrstev",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Soubory .pmtiles / .mbtiles a layers.json kopíruj sem:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = state.layersDirPath.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
            Text("Znovu načíst layers.json")
        }
        if (state.layers.isEmpty()) {
            Text(
                text = "Zatím žádné vrstvy. Po prvním spuštění se do adresáře zapíše výchozí layers.json.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.layers.forEach { layer ->
                HorizontalDivider()
                LayerRow(layer = layer, onCalibrate = { onCalibrate(layer.def.id) })
            }
        }
    }
}

@Composable
private fun LayerRow(layer: LayerUiState, onCalibrate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(layer.def.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = buildString {
                    append(if (layer.def.isLocal) "offline" else "jen online")
                    append(" • ")
                    append(layer.def.kind.name.lowercase(CS_LOCALE))
                    if (!layer.available) {
                        append(" • nedostupná: ")
                        append(layer.unavailableReason ?: "chybí soubor")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCalibrate) { Text("Kalibrace") }
    }
}

@Composable
private fun MapGroup(
    state: SettingsUiState,
    onRotate: (Boolean) -> Unit,
    onFollow: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    SettingsGroup("Mapa") {
        SwitchRow(
            title = "Otáčet dle kompasu",
            subtitle = "Mapa se natáčí podle směru chůze",
            checked = state.preferences.rotateWithCompass,
            onCheckedChange = onRotate,
        )
        SwitchRow(
            title = "Sledovat polohu",
            subtitle = "Mapa se drží na aktuální pozici",
            checked = state.preferences.followMode,
            onCheckedChange = onFollow,
        )
        SwitchRow(
            title = "Nechat displej rozsvícený",
            subtitle = "Šetří odemykání v terénu, ubírá baterii",
            checked = state.preferences.keepScreenOn,
            onCheckedChange = onKeepScreenOn,
        )
    }
}

@Composable
private fun DataGroup(state: SettingsUiState, onExport: () -> Unit, onImport: () -> Unit) {
    SettingsGroup("Data") {
        StatRow("Nálezy", state.stats.finds)
        StatRow("Fotky", state.stats.photos)
        StatRow("Místa", state.stats.places)
        StatRow("Zóny", state.stats.areas)
        StatRow("Pochůzky", state.stats.tracks)
        HorizontalDivider()
        Button(
            onClick = onExport,
            enabled = state.job == DataJob.NONE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.job == DataJob.EXPORTING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Exportuji…")
            } else {
                Text("Exportovat vše")
            }
        }
        OutlinedButton(
            onClick = onImport,
            enabled = state.job == DataJob.NONE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.job == DataJob.IMPORTING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Importuji…")
            } else {
                Text("Importovat zálohu")
            }
        }
    }
}

@Composable
private fun ExportResultDialog(result: ExportResult, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export hotov") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.archive.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Nálezy ${result.counts.finds} • fotky ${result.counts.photos} • " +
                        "místa ${result.counts.places} • zóny ${result.counts.areas} • " +
                        "pochůzky ${result.counts.tracks}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (result.missingPhotoFiles > 0) {
                    Text(
                        text = "Chybějící soubory fotek: ${result.missingPhotoFiles}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Sdílet zip") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

@Composable
private fun ImportResultDialog(result: ImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import dokončen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Přidáno: nálezy ${result.imported.finds}, fotky ${result.imported.photos}, " +
                        "místa ${result.imported.places}, zóny ${result.imported.areas}, " +
                        "pochůzky ${result.imported.tracks}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Přeskočeno (už existuje): nálezy ${result.skipped.finds}, " +
                        "místa ${result.skipped.places}, zóny ${result.skipped.areas}, " +
                        "pochůzky ${result.skipped.tracks}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.warnings.isNotEmpty()) {
                    Text(
                        text = "Upozornění (${result.warnings.size}):",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    result.warnings.take(5).forEach {
                        Text(text = "• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

// --- small building blocks -----------------------------------------------------------

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}
