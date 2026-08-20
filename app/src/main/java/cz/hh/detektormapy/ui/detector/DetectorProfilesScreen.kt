package cz.hh.detektormapy.ui.detector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.data.relation.DetectorWithPresets

/**
 * The user's own detector library: machines, coils and the settings that worked.
 *
 * This screen is the reason the advisor is allowed to give advice at all. The app ships no
 * catalogue of detectors and no factory values, because a number invented for an unknown machine
 * would be worse than silence -- so everything specific comes from here, written by the person
 * who owns the machine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorProfilesScreen(navController: NavHostController) {
    val viewModel: DetectorProfilesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingDetector by remember { mutableStateOf<DetectorEntity?>(null) }
    var creatingDetector by remember { mutableStateOf(false) }
    var deletingDetector by remember { mutableStateOf<DetectorEntity?>(null) }
    var editingPreset by remember { mutableStateOf<DetectorPresetEntity?>(null) }
    var creatingPresetFor by remember { mutableStateOf<Long?>(null) }
    var deletingPreset by remember { mutableStateOf<DetectorPresetEntity?>(null) }

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
                title = { Text("Moje detektory") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creatingDetector = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Detektor") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isEmpty) {
            EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.library, key = { it.detector.id }) { entry ->
                    DetectorCard(
                        entry = entry,
                        onEdit = { editingDetector = entry.detector },
                        onDelete = { deletingDetector = entry.detector },
                        onSetDefault = { viewModel.setDefault(entry.detector.id) },
                        onAddPreset = { creatingPresetFor = entry.detector.id },
                        onEditPreset = { editingPreset = it },
                        onDeletePreset = { deletingPreset = it },
                    )
                }
            }
        }
    }

    if (creatingDetector) {
        DetectorDialog(
            initial = null,
            onDismiss = { creatingDetector = false },
            onConfirm = { name, brand, model, coil, notes ->
                viewModel.addDetector(name, brand, model, coil, notes)
                creatingDetector = false
            },
        )
    }

    val detectorBeingEdited = editingDetector
    if (detectorBeingEdited != null) {
        DetectorDialog(
            initial = detectorBeingEdited,
            onDismiss = { editingDetector = null },
            onConfirm = { name, brand, model, coil, notes ->
                viewModel.updateDetector(
                    detectorBeingEdited.copy(
                        name = name,
                        brand = brand,
                        model = model,
                        coil = coil,
                        notes = notes,
                    ),
                )
                editingDetector = null
            },
        )
    }

    val detectorBeingDeleted = deletingDetector
    if (detectorBeingDeleted != null) {
        ConfirmDialog(
            title = "Smazat detektor?",
            text = "Smaže se i všech ${
                state.library.firstOrNull { it.detector.id == detectorBeingDeleted.id }?.presets?.size ?: 0
            } presetů, které k němu patří.",
            onDismiss = { deletingDetector = null },
            onConfirm = {
                viewModel.deleteDetector(detectorBeingDeleted.id)
                deletingDetector = null
            },
        )
    }

    val presetParentId = creatingPresetFor
    if (presetParentId != null) {
        PresetDialog(
            initial = null,
            onDismiss = { creatingPresetFor = null },
            onConfirm = { draft ->
                viewModel.addPreset(
                    DetectorPresetEntity(
                        detectorId = presetParentId,
                        name = draft.name,
                        terrain = draft.terrain,
                        soil = draft.soil,
                        notes = draft.notes,
                        sensitivity = draft.sensitivity,
                        groundBalance = draft.groundBalance,
                        discrimination = draft.discrimination,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                creatingPresetFor = null
            },
        )
    }

    val presetBeingEdited = editingPreset
    if (presetBeingEdited != null) {
        PresetDialog(
            initial = presetBeingEdited,
            onDismiss = { editingPreset = null },
            onConfirm = { draft ->
                viewModel.updatePreset(
                    presetBeingEdited.copy(
                        name = draft.name,
                        terrain = draft.terrain,
                        soil = draft.soil,
                        notes = draft.notes,
                        sensitivity = draft.sensitivity,
                        groundBalance = draft.groundBalance,
                        discrimination = draft.discrimination,
                    ),
                )
                editingPreset = null
            },
        )
    }

    val presetBeingDeleted = deletingPreset
    if (presetBeingDeleted != null) {
        ConfirmDialog(
            title = "Smazat preset?",
            text = "„${presetBeingDeleted.name}“ zmizí i z rádce.",
            onDismiss = { deletingPreset = null },
            onConfirm = {
                viewModel.deletePreset(presetBeingDeleted.id)
                deletingPreset = null
            },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Zatím tu nic není", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Aplikace nezná tvůj stroj a nebude si vymýšlet, co na něm nastavit. " +
                "Zapiš si sem detektor, cívku a nastavení, která ti fungovala — a k němu terén " +
                "a stav půdy, ve kterých to bylo.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Rádce pak z těchhle tvých vlastních presetů vybere ten, který podmínkám " +
                "sedí nejlíp, a řekne proč.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Hodnoty se zapisují jako text, ne jako číslo — „18/25“, „auto“ i „o dva dolů " +
                "proti základu“ jsou platné odpovědi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetectorCard(
    entry: DetectorWithPresets,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onAddPreset: () -> Unit,
    onEditPreset: (DetectorPresetEntity) -> Unit,
    onDeletePreset: (DetectorPresetEntity) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.detector.name, style = MaterialTheme.typography.titleMedium)
                    val subtitle = listOfNotNull(
                        entry.detector.brand.ifBlank { null },
                        entry.detector.model.ifBlank { null },
                        entry.detector.coil.ifBlank { null },
                    ).joinToString(" • ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onSetDefault, enabled = !entry.detector.isDefault) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Nastavit jako výchozí",
                        tint = if (entry.detector.isDefault) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Upravit") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Smazat") }
            }

            if (entry.detector.notes.isNotBlank()) {
                Text(entry.detector.notes, style = MaterialTheme.typography.bodySmall)
            }

            if (entry.presets.isEmpty()) {
                Text(
                    text = "Žádný preset. Přidej nastavení, které ti na tomhle stroji fungovalo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entry.presets.forEach { preset ->
                    HorizontalDivider()
                    PresetRow(
                        preset = preset,
                        onEdit = { onEditPreset(preset) },
                        onDelete = { onDeletePreset(preset) },
                    )
                }
            }

            TextButton(onClick = onAddPreset) { Text("Přidat preset") }
        }
    }
}

@Composable
private fun PresetRow(preset: DetectorPresetEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${preset.terrain.label} • ${preset.soil.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            val values = presetValueLine(preset)
            if (values.isNotBlank()) {
                Text(values, style = MaterialTheme.typography.bodySmall)
            }
            if (preset.notes.isNotBlank()) {
                Text(
                    text = preset.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Upravit preset") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Smazat preset") }
    }
}

/** "Citlivost 18/25 • Vyvážení auto • Rozlišení 4" -- only the parts the user actually filled in. */
internal fun presetValueLine(preset: DetectorPresetEntity): String = listOfNotNull(
    preset.sensitivity?.takeIf { it.isNotBlank() }?.let { "Citlivost $it" },
    preset.groundBalance?.takeIf { it.isNotBlank() }?.let { "Vyvážení $it" },
    preset.discrimination?.takeIf { it.isNotBlank() }?.let { "Rozlišení $it" },
).joinToString(" • ")

@Composable
private fun DetectorDialog(
    initial: DetectorEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var brand by remember { mutableStateOf(initial?.brand.orEmpty()) }
    var model by remember { mutableStateOf(initial?.model.orEmpty()) }
    var coil by remember { mutableStateOf(initial?.coil.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nový detektor" else "Upravit detektor") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("Značka") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = coil,
                    onValueChange = { coil = it },
                    label = { Text("Cívka") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Poznámka") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, brand, model, coil, notes) }) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

/** What the preset dialog collects, before it becomes a row. */
internal data class PresetDraft(
    val name: String,
    val terrain: Terrain,
    val soil: SoilCondition,
    val notes: String,
    val sensitivity: String?,
    val groundBalance: String?,
    val discrimination: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDialog(initial: DetectorPresetEntity?, onDismiss: () -> Unit, onConfirm: (PresetDraft) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var terrain by remember { mutableStateOf(initial?.terrain ?: Terrain.DEFAULT) }
    var soil by remember { mutableStateOf(initial?.soil ?: SoilCondition.DEFAULT) }
    var sensitivity by remember { mutableStateOf(initial?.sensitivity.orEmpty()) }
    var groundBalance by remember { mutableStateOf(initial?.groundBalance.orEmpty()) }
    var discrimination by remember { mutableStateOf(initial?.discrimination.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nový preset" else "Upravit preset") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Terén", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Terrain.entries.forEach { option ->
                        FilterChip(
                            selected = terrain == option,
                            onClick = { terrain = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text("Stav půdy", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoilCondition.entries.forEach { option ->
                        FilterChip(
                            selected = soil == option,
                            onClick = { soil = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text(
                    text = "Hodnoty piš tak, jak je čteš na stroji. Text, ne číslo — každý " +
                        "výrobce škáluje jinak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    label = { Text("Citlivost") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = groundBalance,
                    onValueChange = { groundBalance = it },
                    label = { Text("Vyvážení na zem") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = discrimination,
                    onValueChange = { discrimination = it },
                    label = { Text("Rozlišení / diskriminace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Poznámka") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        PresetDraft(
                            name = name,
                            terrain = terrain,
                            soil = soil,
                            notes = notes,
                            sensitivity = sensitivity.trim().ifBlank { null },
                            groundBalance = groundBalance.trim().ifBlank { null },
                            discrimination = discrimination.trim().ifBlank { null },
                        ),
                    )
                },
            ) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Smazat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
