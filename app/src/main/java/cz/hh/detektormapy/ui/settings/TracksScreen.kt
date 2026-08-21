package cz.hh.detektormapy.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.ui.nav.Routes

/**
 * List of recorded walks with their GPX export (PLAN.md F4-1).
 *
 * A finished track is the only artefact of a whole day in the field that a desktop GIS can read,
 * so exporting it has to work without the recording service being alive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(navController: NavHostController) {
    val viewModel: TracksViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var renaming by remember { mutableStateOf<TrackEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<TrackEntity?>(null) }

    LaunchedEffect(state.pendingShare) {
        val file = state.pendingShare
        if (file != null) {
            val shared = shareFile(
                context = context,
                file = file,
                mimeType = "application/gpx+xml",
                chooserTitle = "Sdílet GPX",
            )
            if (!shared) viewModel.notify("Sdílení GPX se nepodařilo otevřít")
            viewModel.consumeShare()
        }
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
        topBar = {
            TopAppBar(
                title = { Text("Pochůzky") },
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
                .padding(padding),
        ) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nachozeno celkem", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = formatKm(state.stats.totalDistanceM),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "${state.stats.total} pochůzek • ${formatDuration(state.stats.totalDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.tracks.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Zatím žádné pochůzky. Záznam spustíš na mapě.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onOpen = { navController.navigate(Routes.trackDetail(track.id)) },
                        onRename = { renaming = track },
                        onExport = { viewModel.exportGpx(track) },
                        onDelete = { pendingDelete = track },
                    )
                }
            }
        }
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameDialog(
            initial = renameTarget.name,
            onConfirm = { name ->
                viewModel.rename(renameTarget, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Smazat pochůzku?") },
            text = { Text("Smaže se i její záznam bodů. Vyexportované GPX zůstane.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(deleteTarget.id)
                        pendingDelete = null
                    },
                ) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    // The whole card opens the walk on the map; the icons keep their own hit targets.
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.name.ifBlank { "Pochůzka ${formatDateTime(track.startedAt)}" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDateTime(track.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (track.isRecording) {
                        "nahrává se • ${track.pointCount} bodů"
                    } else {
                        "${formatKm(track.distanceM)} • ${formatDuration(track.durationMs)} • " +
                            "${track.pointCount} bodů"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Přejmenovat")
            }
            IconButton(onClick = onExport, enabled = !track.isRecording) {
                Icon(Icons.Filled.Share, contentDescription = "Export GPX")
            }
            IconButton(onClick = onDelete, enabled = !track.isRecording) {
                Icon(Icons.Filled.Delete, contentDescription = "Smazat")
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přejmenovat pochůzku") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Název") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Uložit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
