package cz.hh.detektormapy.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.model.AreaStatus
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.data.repository.AreasRepository
import cz.hh.detektormapy.data.repository.PlacesRepository
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.location.LocationProvider
import cz.hh.detektormapy.util.Geo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the waypoint list is ordered (PLAN.md F2-4). */
enum class PlaceSort(val label: String) {
    NEAREST("Nejbližší"),
    NEWEST("Nejnovější"),
    BY_TYPE("Podle typu"),
}

/** One waypoint row, already enriched with the live distance / bearing to the current fix. */
data class PlaceRow(val place: PlaceEntity, val distanceM: Double?, val bearingDeg: Double?) {
    /** `1,2 km • SV`, or null when there is no fix to measure from. */
    val navigationLabel: String?
        get() {
            val distance = distanceM ?: return null
            val bearing = bearingDeg ?: return null
            return "${Geo.formatDistance(distance)} • ${Geo.compassLabel(bearing)}"
        }
}

/** Everything the two tabs of [PlacesScreen] render. */
data class PlacesUiState(
    val places: List<PlaceRow> = emptyList(),
    val areas: List<SearchedAreaEntity> = emptyList(),
    val sort: PlaceSort = PlaceSort.NEWEST,
    val typeFilter: Set<PlaceType> = emptySet(),
    val hasFix: Boolean = false,
    val message: String? = null,
) {
    val totalAreaHa: Double get() = areas.sumOf { it.areaHa }
    val doneAreaHa: Double get() = areas.filter { it.status == AreaStatus.HOTOVO }.sumOf { it.areaHa }
}

/**
 * State holder for the waypoint list and the searched-area list (PLAN.md F2-4 and F4-2).
 *
 * Distances are recomputed here rather than in the composable so that a new GPS fix updates every
 * visible row at once and the sort order stays consistent with what is drawn.
 */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placesRepository: PlacesRepository,
    private val areasRepository: AreasRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val fixState = MutableStateFlow<Fix?>(null)
    private val sortState = MutableStateFlow(PlaceSort.NEWEST)
    private val filterState = MutableStateFlow<Set<PlaceType>>(emptySet())
    private val messageState = MutableStateFlow<String?>(null)

    val state: StateFlow<PlacesUiState> = combine(
        placesRepository.observeAll(),
        areasRepository.observeAll(),
        fixState,
        sortState,
        filterState,
        messageState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val places = values[0] as List<PlaceEntity>

        @Suppress("UNCHECKED_CAST")
        val areas = values[1] as List<SearchedAreaEntity>
        val fix = values[2] as Fix?
        val sort = values[3] as PlaceSort

        @Suppress("UNCHECKED_CAST")
        val filter = values[4] as Set<PlaceType>
        val message = values[5] as String?

        PlacesUiState(
            places = buildRows(places, fix, sort, filter),
            areas = areas.sortedByDescending { it.createdAt },
            sort = sort,
            typeFilter = filter,
            hasFix = fix != null,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    init {
        observeLocation()
    }

    fun setSort(sort: PlaceSort) {
        sortState.value = sort
    }

    /** Filter chips are a toggle set; an empty set means "všechny typy". */
    fun toggleTypeFilter(type: PlaceType) {
        val current = filterState.value
        filterState.value = if (type in current) current - type else current + type
    }

    fun clearTypeFilter() {
        filterState.value = emptySet()
    }

    fun setVisited(place: PlaceEntity, visited: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            placesRepository.markVisited(place.id, visited, if (visited) nowMillis else null)
            messageState.value = if (visited) "Označeno jako navštívené" else "Označení zrušeno"
        }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch {
            placesRepository.delete(id)
            messageState.value = "Místo smazáno"
        }
    }

    fun toggleAreaStatus(area: SearchedAreaEntity) {
        val next = if (area.status == AreaStatus.HOTOVO) AreaStatus.ROZPRACOVANO else AreaStatus.HOTOVO
        viewModelScope.launch {
            areasRepository.setStatus(area.id, next)
            messageState.value = "Zóna: ${next.label.lowercase(CS_LOCALE)}"
        }
    }

    fun deleteArea(id: Long) {
        viewModelScope.launch {
            areasRepository.delete(id)
            messageState.value = "Zóna smazána"
        }
    }

    fun consumeMessage() {
        messageState.value = null
    }

    // --- internals -------------------------------------------------------------------

    private fun buildRows(
        places: List<PlaceEntity>,
        fix: Fix?,
        sort: PlaceSort,
        filter: Set<PlaceType>,
    ): List<PlaceRow> {
        val visible = if (filter.isEmpty()) places else places.filter { it.type in filter }
        val rows = visible.map { place ->
            PlaceRow(
                place = place,
                distanceM = fix?.let { Geo.distanceM(it.lat, it.lon, place.lat, place.lon) },
                bearingDeg = fix?.let { Geo.bearingDeg(it.lat, it.lon, place.lat, place.lon) },
            )
        }
        return when (sort) {
            // Without a fix "nejbližší" has no meaning, so it degrades to the newest first.
            PlaceSort.NEAREST -> if (fix == null) {
                rows.sortedByDescending { it.place.createdAt }
            } else {
                rows.sortedBy { it.distanceM ?: Double.MAX_VALUE }
            }

            PlaceSort.NEWEST -> rows.sortedByDescending { it.place.createdAt }

            PlaceSort.BY_TYPE -> rows.sortedWith(
                compareBy<PlaceRow> { it.place.type.ordinal }.thenByDescending { it.place.createdAt },
            )
        }
    }

    private fun observeLocation() {
        fixState.value = locationProvider.lastKnown()
        viewModelScope.launch {
            locationProvider.fixes()
                .catch { /* No permission or no provider: the list simply shows no distances. */ }
                .collect { fixState.value = it }
        }
    }
}
