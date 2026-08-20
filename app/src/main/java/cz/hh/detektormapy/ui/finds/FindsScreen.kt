package cz.hh.detektormapy.ui.finds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.relation.FindWithPhotos
import cz.hh.detektormapy.ui.nav.Routes

/**
 * Gallery of finds (PLAN.md F2-3).
 *
 * A grid of photos is the fastest way to recognise a find you logged three months ago -- much
 * faster than a list of titles, because in practice the title is often empty. Filters sit
 * directly above the grid instead of behind a sheet so that narrowing down "vojenské z posledních
 * 7 dní" stays a two-tap operation with gloves on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindsScreen(navController: NavHostController) {
    val viewModel: FindsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<FindWithPhotos?>(null) }

    LaunchedEffect(state.message) {
        val text = state.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nálezy (${state.finds.size})") },
                actions = {
                    if (state.filtersActive) {
                        IconButton(onClick = viewModel::clearFilters) {
                            Icon(Icons.Filled.FilterAltOff, contentDescription = "Zrušit filtry")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(Routes.FIND_CAPTURE) }) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = "Nový nález")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            FindsFilterBar(
                state = state,
                onToggleCategory = viewModel::toggleCategoryFilter,
                onToggleFavorite = viewModel::toggleFavoriteOnly,
                onSetDateRange = viewModel::setDateRange,
            )

            when {
                state.loading -> Box(Modifier.fillMaxSize())

                state.finds.isEmpty() && state.totalCount == 0 -> FindsEmptyState(
                    title = "Zatím žádné nálezy",
                    hint = "Klepni na tlačítko s fotoaparátem a založ první nález. " +
                        "Stačí fotka, kategorie a hloubka -- zbytek doplníš doma.",
                )

                state.finds.isEmpty() -> FindsEmptyState(
                    title = "Filtru nic neodpovídá",
                    hint = "Máš uloženo ${state.totalCount} nálezů. " +
                        "Zkus rozšířit období nebo zrušit filtr kategorií.",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.finds, key = { it.find.id }) { item ->
                        FindCard(
                            item = item,
                            onClick = { navController.navigate(Routes.findDetail(item.find.id)) },
                            onLongClick = { pendingDelete = item },
                            onToggleFavorite = { viewModel.toggleFavorite(item.find.id) },
                        )
                    }
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDeleteDialog(
            findTitle = target.find.title,
            onConfirm = {
                viewModel.delete(target.find.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Category chips (with counts), the favourites toggle and the date quick filter. */
@Composable
private fun FindsFilterBar(
    state: FindsUiState,
    onToggleCategory: (FindCategory) -> Unit,
    onToggleFavorite: () -> Unit,
    onSetDateRange: (DateRange) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.filter.favoriteOnly,
                    onClick = onToggleFavorite,
                    label = { Text("Oblíbené") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
            items(FindCategory.entries.toList()) { category ->
                val count = state.countsByCategory[category] ?: 0
                FilterChip(
                    selected = category in state.filter.categories,
                    onClick = { onToggleCategory(category) },
                    enabled = count > 0 || category in state.filter.categories,
                    label = { Text("${category.label} ($count)") },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        ) {
            items(DateRange.entries.toList()) { range ->
                FilterChip(
                    selected = state.dateRange == range,
                    onClick = { onSetDateRange(range) },
                    label = { Text(range.label) },
                )
            }
        }
    }
}

/** One card: photo, title, category and date. Long press offers deletion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FindCard(
    item: FindWithPhotos,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val find = item.find
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            PhotoThumbnail(
                uri = item.primaryPhoto?.uri,
                category = find.category,
                modifier = Modifier.fillMaxSize(),
                contentDescription = find.title.ifBlank { find.category.label },
            )
            FavoriteStar(
                favorite = find.favorite,
                onToggle = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = find.title.ifBlank { find.category.label },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryChip(find.category)
                Text(
                    text = formatFindDate(find.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
