package cz.hh.detektormapy.ui.finds

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.ui.nav.TopDestination

/** At most three photos per find, exactly as PLAN.md section 5 specifies. */
private const val MAX_PHOTOS = 3

/**
 * Detail of a single find.
 *
 * Editing happens in place behind an "Upravit" toggle instead of on a separate form screen:
 * corrections are usually one field ("actually 25 cm, not 20") and a round trip through another
 * screen would cost more taps than the correction itself. The location is shown as a drawn
 * crosshair rather than as a second MapLibre view -- a live map here would mean a second GL
 * surface, a second style download and a visible battery cost for what is a one-glance check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindDetailScreen(navController: NavHostController, findId: Long) {
    val viewModel: FindDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(findId) { viewModel.load(findId) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) navController.popBackStack()
    }

    LaunchedEffect(state.message) {
        val text = state.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    val item = state.find
    val find = item?.find

    // Draft fields live in the composable: they are throw-away until "Uložit" is pressed.
    var titleInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }
    var depthInput by remember { mutableStateOf("") }
    var categoryInput by remember { mutableStateOf(FindCategory.DEFAULT) }

    LaunchedEffect(state.editing, find?.id) {
        if (state.editing && find != null) {
            titleInput = find.title
            noteInput = find.note
            depthInput = find.depthCm?.toString().orEmpty()
            categoryInput = find.category
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detail nálezu") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
                actions = {
                    if (find != null) {
                        FavoriteStar(
                            favorite = find.favorite,
                            onToggle = viewModel::toggleFavorite,
                        )
                        IconButton(onClick = { shareFind(context, find.lat, find.lon, find.title) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Sdílet")
                        }
                        IconButton(
                            enabled = !state.saving,
                            onClick = {
                                if (state.editing) {
                                    viewModel.save(
                                        title = titleInput,
                                        category = categoryInput,
                                        depthCm = depthInput.toIntOrNull(),
                                        note = noteInput,
                                    )
                                } else {
                                    viewModel.setEditing(true)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (state.editing) Icons.Filled.Check else Icons.Filled.Edit,
                                contentDescription = if (state.editing) "Uložit změny" else "Upravit",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (item == null) {
            FindsEmptyState(
                title = if (state.loading) "Načítám nález…" else "Nález nenalezen",
                hint = if (state.loading) {
                    "Moment."
                } else {
                    "Nález už neexistuje -- nejspíš byl smazán na jiné obrazovce."
                },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        val entity = item.find
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FindPhotoPager(
                photos = item.photos.take(MAX_PHOTOS),
                category = entity.category,
                description = entity.title.ifBlank { entity.category.label },
            )

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.editing) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Název") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CategoryPicker(
                        selected = categoryInput,
                        onSelect = { categoryInput = it },
                    )
                    OutlinedTextField(
                        value = depthInput,
                        onValueChange = { value -> depthInput = value.filter { it.isDigit() }.take(3) },
                        label = { Text("Hloubka (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Poznámka") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { viewModel.setEditing(false) }) {
                        Text("Zrušit úpravy")
                    }
                } else {
                    Text(
                        text = entity.title.ifBlank { entity.category.label },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    CategoryChip(entity.category)
                    InfoRow("Hloubka", entity.depthCm?.let { "$it cm" } ?: "neuvedena")
                    if (entity.note.isNotBlank()) {
                        InfoRow("Poznámka", entity.note)
                    }
                }

                InfoRow("Nalezeno", formatFindDateTime(entity.createdAt))
                InfoRow("Souřadnice", formatCoordinates(entity.lat, entity.lon))
                InfoRow("Přesnost", formatAccuracy(entity.accuracyM))
                InfoRow(
                    label = "Nadmořská výška",
                    value = entity.altitude?.let { "${it.toInt()} m n. m." } ?: "neznámá",
                )
                // PLAN.md F2-6: which historical map was on screen when the find was logged.
                InfoRow("Historická vrstva", state.layerTitle ?: "neznámá vrstva")

                LocationPreviewCard(
                    lat = entity.lat,
                    lon = entity.lon,
                    onShowOnMap = {
                        if (!navController.popBackStack(TopDestination.Map.route, false)) {
                            navController.navigate(TopDestination.Map.route)
                        }
                    },
                )

                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Smazat nález", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showDeleteDialog && find != null) {
        ConfirmDeleteDialog(
            findTitle = find.title,
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/** Swipeable photos with dot indicators; falls back to the category plate when there are none. */
@Composable
private fun FindPhotoPager(photos: List<FindPhotoEntity>, category: FindCategory, description: String) {
    if (photos.isEmpty()) {
        PhotoThumbnail(
            uri = null,
            category = category,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            contentDescription = description,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { photos.size })
    Box {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
        ) { page ->
            PhotoThumbnail(
                uri = photos[page].uri,
                category = category,
                modifier = Modifier.fillMaxSize(),
                contentDescription = description,
            )
        }
        if (photos.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                photos.indices.forEach { index ->
                    val active = index == pagerState.currentPage
                    Box(
                        Modifier
                            .size(if (active) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** Category chips reused by the detail editor and by the capture form. */
@Composable
internal fun CategoryPicker(selected: FindCategory, onSelect: (FindCategory) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(FindCategory.entries.toList()) { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category.label) },
            )
        }
    }
}

/** Label above value; the pattern repeats often enough to be worth a helper. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A drawn crosshair standing in for a map preview. Deliberately non-interactive: it costs
 * nothing to render, works offline, and the button next to it takes the user to the real map.
 */
@Composable
private fun LocationPreviewCard(lat: Double, lon: Double, onShowOnMap: () -> Unit) {
    val crosshairColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val step = size.width / 6f
                var x = step
                while (x < size.width) {
                    drawLine(
                        color = gridColor.copy(alpha = 0.15f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(
                        color = gridColor.copy(alpha = 0.15f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                    y += step
                }
                val arm = size.height / 4f
                drawLine(crosshairColor, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 3f)
                drawLine(crosshairColor, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 3f)
                drawCircle(crosshairColor, radius = arm / 2f, center = Offset(cx, cy), style = Stroke(width = 3f))
            }
            Text(
                text = formatCoordinates(lat, lon),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onShowOnMap, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Map, contentDescription = null)
                Text("Zobrazit na mapě", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Shares the position as plain text. Coordinates travel better than a screenshot: they paste
 * into Mapy.cz, Locus or a message to a fellow detectorist without any app in common.
 */
private fun shareFind(context: android.content.Context, lat: Double, lon: Double, title: String) {
    val label = title.ifBlank { "Nález" }
    val text = "$label\n${formatCoordinates(lat, lon)}\n" +
        String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon) +
        "\nhttps://www.google.com/maps/search/?api=1&query=$lat,$lon"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, label)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Sdílet nález"))
    }
}
