package cz.hh.detektormapy.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.ui.map.MarkerIcons
import cz.hh.detektormapy.ui.nav.Routes

/**
 * The "Místa" tab: waypoints planned at home and searched areas drawn in the field.
 *
 * Both lists exist so a trip can be prepared on the sofa and executed in a field without a map
 * gesture: every row carries the live distance and compass direction to the point, which is the
 * only thing that matters once the phone is in one hand and a detector in the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(navController: NavHostController) {
    val viewModel: PlacesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }

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
                title = { Text("Místa") },
                actions = {
                    if (selectedTab == 0) {
                        SortMenu(current = state.sort, onSelect = viewModel::setSort)
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
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Waypointy (${state.places.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Zóny (${state.areas.size})") },
                )
            }
            if (selectedTab == 0) {
                PlacesTab(
                    state = state,
                    onOpen = { navController.navigate(Routes.placeDetail(it.id)) },
                    onNavigate = { navigateToPlaceOnMap(navController, it.id) },
                    onToggleVisited = { viewModel.setVisited(it, !it.visited) },
                    onDelete = { viewModel.deletePlace(it.id) },
                    onToggleFilter = viewModel::toggleTypeFilter,
                    onClearFilter = viewModel::clearTypeFilter,
                )
            } else {
                AreasTab(
                    state = state,
                    onToggleStatus = viewModel::toggleAreaStatus,
                    onDelete = { viewModel.deleteArea(it.id) },
                )
            }
        }
    }
}

@Composable
private fun SortMenu(current: PlaceSort, onSelect: (PlaceSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Řazení")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        PlaceSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(if (sort == current) "✓ ${sort.label}" else sort.label) },
                onClick = {
                    onSelect(sort)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun PlacesTab(
    state: PlacesUiState,
    onOpen: (PlaceEntity) -> Unit,
    onNavigate: (PlaceEntity) -> Unit,
    onToggleVisited: (PlaceEntity) -> Unit,
    onDelete: (PlaceEntity) -> Unit,
    onToggleFilter: (PlaceType) -> Unit,
    onClearFilter: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<PlaceEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.typeFilter.isEmpty(),
                onClick = onClearFilter,
                label = { Text("Vše") },
            )
            PlaceType.entries.forEach { type ->
                FilterChip(
                    selected = type in state.typeFilter,
                    onClick = { onToggleFilter(type) },
                    label = { Text(type.label) },
                )
            }
        }

        if (state.places.isEmpty()) {
            EmptyHint(
                "Zatím žádná místa. Dlouhým stiskem na mapě si označ plánovanou lokalitu, " +
                    "parkování nebo zákaz vstupu.",
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.places, key = { it.place.id }) { row ->
                PlaceListRow(
                    row = row,
                    hasFix = state.hasFix,
                    onOpen = { onOpen(row.place) },
                    onNavigate = { onNavigate(row.place) },
                    onToggleVisited = { onToggleVisited(row.place) },
                    onDelete = { pendingDelete = row.place },
                )
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDeleteDialog(
            title = "Smazat místo?",
            body = target.title.ifBlank { target.type.label },
            onConfirm = {
                onDelete(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun PlaceListRow(
    row: PlaceRow,
    hasFix: Boolean,
    onOpen: () -> Unit,
    onNavigate: () -> Unit,
    onToggleVisited: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val place = row.place

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaceTypeBadge(place.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = place.title.ifBlank { place.type.label },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (place.note.isNotBlank()) {
                    Text(
                        text = place.note,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.navigationLabel
                            ?: if (hasFix) "—" else "bez GPS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (place.visited) {
                        Text(
                            text = "navštíveno",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Akce")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Navigovat") },
                        onClick = {
                            menuOpen = false
                            onNavigate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (place.visited) "Zrušit navštívené" else "Označit navštívené") },
                        onClick = {
                            menuOpen = false
                            onToggleVisited()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Smazat") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceTypeBadge(type: PlaceType) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(MarkerIcons.colorOf(type))),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = type.marker, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun AreasTab(
    state: PlacesUiState,
    onToggleStatus: (SearchedAreaEntity) -> Unit,
    onDelete: (SearchedAreaEntity) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SearchedAreaEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Prohledáno celkem", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = formatHectares(state.totalAreaHa),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "z toho hotovo ${formatHectares(state.doneAreaHa)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.areas.isEmpty()) {
            EmptyHint("Zatím žádné zóny. Na mapě si prstem obkresli plochu, kterou jsi prošel.")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.areas, key = { it.id }) { area ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = area.name.ifBlank { "Zóna #${area.id}" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${formatHectares(area.areaHa)} • ${formatDate(area.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AssistChip(
                            onClick = { onToggleStatus(area) },
                            label = { Text(area.status.label) },
                        )
                        IconButton(onClick = { pendingDelete = area }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Smazat zónu")
                        }
                    }
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDeleteDialog(
            title = "Smazat zónu?",
            body = target.name.ifBlank { "Zóna #${target.id}" },
            onConfirm = {
                onDelete(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("$body\n\nTuto akci nelze vrátit zpět.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Smazat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
