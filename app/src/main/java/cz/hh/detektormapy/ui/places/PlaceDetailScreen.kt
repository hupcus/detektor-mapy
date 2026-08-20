package cz.hh.detektormapy.ui.places

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.model.PlaceType

/**
 * Detail of a single waypoint: editing plus the field-facing part -- a large arrow that points at
 * the point relative to the direction the phone is held in.
 *
 * The arrow is the reason this screen exists at all: reading "1,2 km • SV" off a list still means
 * translating a compass rose in your head, while a rotated arrow can be followed directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(navController: NavHostController, placeId: Long) {
    val viewModel: PlaceDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(PlaceType.DEFAULT) }
    var seededId by remember { mutableStateOf(-1L) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(placeId) { viewModel.bind(placeId) }

    val place = state.place
    LaunchedEffect(place?.id) {
        val loaded = place
        if (loaded != null && seededId != loaded.id) {
            title = loaded.title
            note = loaded.note
            type = loaded.type
            seededId = loaded.id
        }
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) navController.popBackStack()
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
                title = { Text("Detail místa") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, enabled = place != null) {
                        Icon(Icons.Filled.Delete, contentDescription = "Smazat")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (place == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.loaded) "Místo neexistuje." else "Načítám…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NavigationCard(state)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Název") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Poznámka") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Typ", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaceType.entries.forEach { candidate ->
                    FilterChip(
                        selected = candidate == type,
                        onClick = { type = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }

            Button(
                onClick = { viewModel.save(title, note, type) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Uložit změny") }

            HorizontalDivider()

            Text("Souřadnice", style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    text = formatCoordinates(place.lat, place.lon),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = "Vytvořeno: ${formatDateTime(place.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (place.visited) {
                    "Navštíveno: ${formatDateTime(place.visitedAt)}"
                } else {
                    "Zatím nenavštíveno"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { navigateToPlaceOnMap(navController, place.id) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = null)
                    Text("  Navigovat")
                }
                OutlinedButton(
                    onClick = {
                        shareCoordinates(
                            context = context,
                            title = place.title.ifBlank { place.type.label },
                            lat = place.lat,
                            lon = place.lon,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  Sdílet")
                }
            }

            OutlinedButton(
                onClick = { viewModel.setVisited(!place.visited) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (place.visited) "Zrušit označení navštívené" else "Označit navštívené")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat místo?") },
            text = { Text("Tuto akci nelze vrátit zpět.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun NavigationCard(state: PlaceDetailUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Navigation,
                contentDescription = "Směr k místu",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(96.dp)
                    .rotate(state.relativeBearingDeg ?: state.bearingDeg?.toFloat() ?: 0f),
            )
            Text(
                text = state.distanceLabel ?: "bez GPS",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = when {
                    state.compassLabel == null -> "Zapni GPS, aby se šipka rozjela."
                    state.headingDeg == null -> "Směr ${state.compassLabel} (bez kompasu, sever nahoře)"
                    else -> "Směr ${state.compassLabel}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Sends the point out as plain text plus a `geo:` link, which every Czech navigation app
 * understands. Deliberately not a file share -- a coordinate is something you paste into a chat.
 */
private fun shareCoordinates(context: Context, title: String, lat: Double, lon: Double) {
    val coordinates = formatCoordinates(lat, lon)
    val body = buildString {
        append(title)
        append('\n')
        append(coordinates)
        append('\n')
        append("geo:$lat,$lon?q=$lat,$lon($title)")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Sdílet souřadnice"))
    }.onFailure { Log.w("PlaceDetail", "Sdílení souřadnic selhalo", it) }
}
