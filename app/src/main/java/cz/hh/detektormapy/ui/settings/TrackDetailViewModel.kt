package cz.hh.detektormapy.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.data.repository.TracksRepository
import cz.hh.detektormapy.location.TrackGeometry
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.util.BBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackDetailUiState(
    val track: TrackEntity? = null,
    val points: List<TrackPointEntity> = emptyList(),
    val bounds: BBox? = null,
    /** Tile URL of the basemap to draw the walk over, once the tile server is up. */
    val basemapUrlTemplate: String? = null,
    val basemapTitle: String? = null,
    val distanceM: Double = 0.0,
    val loading: Boolean = true,
) {
    val hasRoute: Boolean get() = points.size >= 2
}

/**
 * Backs "kudy jsem šel" — one saved walk drawn over the map.
 *
 * It borrows the map machinery rather than duplicating it: the basemap comes from the same
 * [LayerManager] and local tile server the main map uses, so a walk reviewed at home over ZTM
 * and one reviewed in the field from the offline cache are the same code path.
 */
@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tracksRepository: TracksRepository,
    private val layerManager: LayerManager,
) : ViewModel() {

    private val trackId: Long = savedStateHandle.get<Long>("trackId") ?: -1L

    private val pointsState = MutableStateFlow<List<TrackPointEntity>>(emptyList())
    private val loadingState = MutableStateFlow(true)

    /**
     * The basemap the user is actually using, resolved to a servable URL.
     *
     * Falls back to the first available basemap in the catalogue: someone who turned every
     * basemap off should still get a map to look at rather than a blank rectangle.
     */
    private val basemap = layerManager.layers.map { layers ->
        val chosen = layers.firstOrNull { it.def.isBasemap && it.visible && it.available }
            ?: layers.firstOrNull { it.def.isBasemap && it.available }
        chosen?.let { it to layerManager.urlTemplateFor(it.def.id) }
    }

    val state: StateFlow<TrackDetailUiState> = combine(
        tracksRepository.observeAll().map { all -> all.firstOrNull { it.id == trackId } },
        pointsState,
        basemap,
        loadingState,
    ) { track, points, base, loading ->
        TrackDetailUiState(
            track = track,
            points = points,
            bounds = TrackGeometry.boundsOf(points),
            basemapUrlTemplate = base?.second,
            basemapTitle = base?.first?.def?.title,
            distanceM = tracksRepository.totalDistanceM(points),
            loading = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackDetailUiState())

    init {
        layerManager.ensureStarted()
        viewModelScope.launch {
            // A one-shot read, not a flow: a finished walk does not change, and observing it
            // would keep a Room query alive behind a screen that only ever shows a snapshot.
            pointsState.value = runCatching { tracksRepository.getPoints(trackId) }.getOrDefault(emptyList())
            loadingState.value = false
        }
    }
}
