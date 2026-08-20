package cz.hh.detektormapy.ui.detector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import cz.hh.detektormapy.data.repository.DetectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetectorProfilesUiState(val library: List<DetectorWithPresets> = emptyList(), val message: String? = null) {
    val isEmpty: Boolean get() = library.isEmpty()
}

/**
 * State holder for the user's own detector library.
 *
 * Every write goes through the repository so that the "exactly one default machine" rule is
 * enforced in one place; the screen only ever describes intent.
 */
@HiltViewModel
class DetectorProfilesViewModel @Inject constructor(private val repository: DetectorRepository) : ViewModel() {

    private val messageState = MutableStateFlow<String?>(null)

    val state: StateFlow<DetectorProfilesUiState> = combine(
        repository.observeLibrary().catch { emit(emptyList()) },
        messageState,
    ) { library, message ->
        DetectorProfilesUiState(library = library, message = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetectorProfilesUiState())

    fun addDetector(name: String, brand: String, model: String, coil: String, notes: String) {
        val cleanName = name.trim().ifBlank { "Detektor" }
        viewModelScope.launch {
            repository.addDetector(
                DetectorEntity(
                    name = cleanName,
                    brand = brand.trim(),
                    model = model.trim(),
                    coil = coil.trim(),
                    notes = notes.trim(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            messageState.value = "Detektor přidán"
        }
    }

    fun updateDetector(detector: DetectorEntity) {
        viewModelScope.launch {
            repository.updateDetector(detector.copy(name = detector.name.trim().ifBlank { "Detektor" }))
            messageState.value = "Uloženo"
        }
    }

    fun deleteDetector(id: Long) {
        viewModelScope.launch {
            repository.deleteDetector(id)
            messageState.value = "Detektor smazán i s presety"
        }
    }

    fun setDefault(id: Long) {
        viewModelScope.launch {
            repository.setDefaultDetector(id)
            messageState.value = "Nastaveno jako výchozí"
        }
    }

    fun addPreset(preset: DetectorPresetEntity) {
        viewModelScope.launch {
            repository.addPreset(preset.copy(name = preset.name.trim().ifBlank { "Preset" }))
            messageState.value = "Preset uložen"
        }
    }

    fun updatePreset(preset: DetectorPresetEntity) {
        viewModelScope.launch {
            repository.updatePreset(preset.copy(name = preset.name.trim().ifBlank { "Preset" }))
            messageState.value = "Preset uložen"
        }
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch {
            repository.deletePreset(id)
            messageState.value = "Preset smazán"
        }
    }

    fun consumeMessage() {
        messageState.value = null
    }
}
