package cz.hh.detektormapy.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.data.repository.CalibrationRepository
import cz.hh.detektormapy.map.LayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalibrationListState(
    val layerId: String = "",
    val layerTitle: String = "",
    val calibrations: List<LayerCalibrationEntity> = emptyList(),
    val appliedId: Long? = null,
)

/**
 * CRUD over stored calibrations (issue F3-2).
 *
 * A layer can carry many calibrations, one per area -- the 1840s survey is wrong in a
 * different direction in every cadastral district, so a single global correction is useless.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val repository: CalibrationRepository,
    private val layerManager: LayerManager,
) : ViewModel() {

    private val layerIdState = MutableStateFlow("")
    private val appliedState = MutableStateFlow<Long?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<CalibrationListState> = combine(
        layerIdState,
        layerIdState.flatMapLatest { id ->
            if (id.isBlank()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                repository.observeForLayer(id)
            }
        },
        appliedState,
    ) { layerId, calibrations, applied ->
        CalibrationListState(
            layerId = layerId,
            layerTitle = layerManager.definitionOf(layerId)?.title ?: layerId,
            calibrations = calibrations,
            appliedId = applied,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalibrationListState())

    fun bind(layerId: String) {
        layerIdState.value = layerId
    }

    fun rename(id: Long, label: String) = viewModelScope.launch {
        repository.rename(id, label, System.currentTimeMillis())
    }

    fun delete(id: Long) = viewModelScope.launch {
        val layerId = layerIdState.value
        repository.delete(id)
        if (appliedState.value == id) {
            appliedState.value = null
            layerManager.applyCalibration(layerId, null, null)
        }
    }

    fun setActive(id: Long, active: Boolean) = viewModelScope.launch {
        repository.setActive(id, active, System.currentTimeMillis())
    }

    /** Applies a stored calibration immediately so the user can eyeball it on the map. */
    fun apply(calibration: LayerCalibrationEntity) {
        layerManager.applyCalibration(
            calibration.layerId,
            Affine2D(
                calibration.m0,
                calibration.m1,
                calibration.m2,
                calibration.m3,
                calibration.m4,
                calibration.m5,
            ),
            calibration.id,
        )
        appliedState.value = calibration.id
    }

    fun clearApplied() {
        val layerId = layerIdState.value
        layerManager.applyCalibration(layerId, null, null)
        appliedState.value = null
    }
}
