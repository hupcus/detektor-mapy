package cz.hh.detektormapy.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController

/**
 * "Správa úložiště" — where the offline cache went and how to get the space back.
 *
 * The screen exists because the write-through cache has no expiry: tiles are kept until the user
 * says otherwise, which is the right default for historical maps in a forest and a bad one
 * without a visible lever. Everything here is measured from the files themselves, so the numbers
 * match what a file manager on the phone would report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(navController: NavHostController) {
    val viewModel: StorageViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Správa úložiště") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
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
            SettingsGroup("Přehled") {
                LabelValueRow("Uložené mapy (cache)", formatBytes(state.cacheTotal))
                LabelValueRow("Vlastní archivy vrstev", formatBytes(state.archiveTotal))
                LabelValueRow("Volné místo v telefonu", formatBytes(state.freeBytes))
                if (state.lowSpace) {
                    Text(
                        text = "Volného místa je málo — nové dlaždice se neukládají. " +
                            "Uvolni místo nebo smaž cache níže.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                LabelValueRow("Staženo dnes", "${state.downloadedTilesToday} dlaždic")
                LabelValueRow("Objem dnes", formatBytes(state.downloadedBytesToday))
                Text(
                    text = "Čísla se počítají jen v telefonu a nikam se neodesílají. " +
                        "Jsou tu proto, abys viděl, jak moc zatěžuješ mapové služby.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsGroup("Ukládání map") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ukládat mapy pro offline použití", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Co si na mapě prohlédneš, zůstane v telefonu i bez signálu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.cacheEnabled, onCheckedChange = viewModel::setCacheEnabled)
                }
                Text(
                    text = "Vypnutí zastaví ukládání nových dlaždic. Už uložené mapy zůstanou " +
                        "dostupné, dokud je nesmažeš.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsGroup("Vrstvy") {
                if (state.rows.isEmpty()) {
                    Text(
                        text = "Zatím žádné vrstvy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    StorageLayerRow(
                        row = row,
                        busy = state.busy,
                        onToggleCaching = { viewModel.setLayerCaching(row.layerId, it) },
                        onClear = { viewModel.clearLayer(row.layerId, row.title) },
                    )
                }
                if (state.orphanBytes > 0) {
                    HorizontalDivider()
                    LabelValueRow("Cache zrušených vrstev", formatBytes(state.orphanBytes))
                }
            }

            SettingsGroup("Úklid") {
                OutlinedButton(
                    onClick = { confirmClearAll = true },
                    enabled = !state.busy && state.cacheTotal > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Mažu…")
                    } else {
                        Text("Smazat všechny uložené mapy")
                    }
                }
                Text(
                    text = "Nálezy, místa ani pochůzky se nemažou — jde jen o mapové dlaždice, " +
                        "které se dají znovu stáhnout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Přepočítat velikosti")
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Smazat uložené mapy?") },
            text = {
                Text(
                    "Uvolní se ${formatBytes(state.cacheTotal)}. " +
                        "Mapy, které jsi měl offline, budou zase potřebovat signál.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    viewModel.clearAll()
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun StorageLayerRow(row: StorageRow, busy: Boolean, onToggleCaching: (Boolean) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = buildString {
                        if (row.archiveBytes > 0) {
                            append("archiv ").append(formatBytes(row.archiveBytes))
                            append(" • ")
                        }
                        append("uloženo ").append(formatBytes(row.cacheBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.isOnline) {
                Switch(checked = row.cachingEnabled, onCheckedChange = onToggleCaching)
            }
        }
        if (row.cacheBytes > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy, onClick = onClear),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClear, enabled = !busy) { Text("Smazat cache vrstvy") }
            }
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
