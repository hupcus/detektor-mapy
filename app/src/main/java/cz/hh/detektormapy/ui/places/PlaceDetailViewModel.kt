package cz.hh.detektormapy.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.data.repository.PlacesRepository
import cz.hh.detektormapy.location.CompassProvider
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.location.LocationProvider
import cz.hh.detektormapy.util.Geo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything [PlaceDetailScreen] renders for a single waypoint. */
data class PlaceDetailUiState(
    val place: PlaceEntity? = null,
    val loaded: Boolean = false,
    val fix: Fix? = null,
    val headingDeg: Float? = null,
    val distanceM: Double? = null,
    val bearingDeg: Double? = null,
    val message: String? = null,
    val deleted: Boolean = false,
) {
    /**
     * Where the arrow has to point on screen: the bearing to the target minus where the phone
     * is currently facing. Null when either half is unknown, in which case the screen shows a
     * plain north-up arrow instead of a lying one.
     */
    val relativeBearingDeg: Float?
        get() {
            val bearing = bearingDeg ?: return null
            val heading = headingDeg ?: return null
            return (((bearing - heading) % 360.0 + 360.0) % 360.0).toFloat()
        }

    val distanceLabel: String? get() = distanceM?.let { Geo.formatDistance(it) }

    val compassLabel: String? get() = bearingDeg?.let { Geo.compassLabel(it) }
}

/**
 * State holder for one waypoint.
 *
 * The compass heading is folded in here (rather than read in the composable) because the arrow
 * has to stay steady while the user rotates the phone -- that only works if bearing and heading
 * come from the same emission.
 */
@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    private val placesRepository: PlacesRepository,
    private val locationProvider: LocationProvider,
    private val compassProvider: CompassProvider,
) : ViewModel() {

    private val placeIdState = MutableStateFlow(-1L)
    private val fixState = MutableStateFlow<Fix?>(null)
    private val headingState = MutableStateFlow<Float?>(null)
    private val messageState = MutableStateFlow<String?>(null)
    private val deletedState = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val placeFlow = placeIdState.flatMapLatest { id ->
        if (id <= 0L) flowOf(null) else placesRepository.observePlace(id)
    }

    val state: StateFlow<PlaceDetailUiState> = combine(
        placeFlow,
        fixState,
        headingState,
        messageState,
        deletedState,
    ) { place, fix, heading, message, deleted ->
        PlaceDetailUiState(
            place = place,
            loaded = true,
            fix = fix,
            headingDeg = heading,
            distanceM = if (place != null && fix != null) {
                Geo.distanceM(fix.lat, fix.lon, place.lat, place.lon)
            } else {
                null
            },
            bearingDeg = if (place != null && fix != null) {
                Geo.bearingDeg(fix.lat, fix.lon, place.lat, place.lon)
            } else {
                null
            },
            message = message,
            deleted = deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceDetailUiState())

    init {
        observeLocation()
        observeCompass()
    }

    /** Called from the composable, which owns the nav argument. Idempotent. */
    fun bind(placeId: Long) {
        if (placeIdState.value != placeId) {
            placeIdState.value = placeId
        }
    }

    fun save(title: String, note: String, type: PlaceType) {
        val current = state.value.place ?: return
        viewModelScope.launch {
            placesRepository.update(
                current.copy(title = title.trim(), note = note.trim(), type = type),
            )
            messageState.value = "Uloženo"
        }
    }

    fun setVisited(visited: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val current = state.value.place ?: return
        viewModelScope.launch {
            placesRepository.markVisited(current.id, visited, if (visited) nowMillis else null)
        }
    }

    fun delete() {
        val current = state.value.place ?: return
        viewModelScope.launch {
            placesRepository.delete(current.id)
            deletedState.value = true
        }
    }

    fun consumeMessage() {
        messageState.value = null
    }

    private fun observeLocation() {
        fixState.value = locationProvider.lastKnown()
        viewModelScope.launch {
            locationProvider.fixes()
                .catch { /* No fix: the screen falls back to coordinates only. */ }
                .collect { fixState.value = it }
        }
    }

    private fun observeCompass() {
        viewModelScope.launch {
            compassProvider.headings { fixState.value }
                .catch { /* No magnetometer: the arrow stays north-up. */ }
                .collect { headingState.value = it }
        }
    }
}
