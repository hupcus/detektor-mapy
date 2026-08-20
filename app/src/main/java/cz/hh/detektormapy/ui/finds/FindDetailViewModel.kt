package cz.hh.detektormapy.ui.finds

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.relation.FindWithPhotos
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.di.IoDispatcher
import cz.hh.detektormapy.map.LayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Snapshot of one find plus everything the detail screen shows around it. */
data class FindDetailUiState(
    val find: FindWithPhotos? = null,
    val loading: Boolean = true,
    val editing: Boolean = false,
    val saving: Boolean = false,
    /** Title of the historical layer the find was made on (PLAN.md F2-6), null when unknown. */
    val layerTitle: String? = null,
    val deleted: Boolean = false,
    val message: String? = null,
)

/**
 * State holder for the find detail.
 *
 * The id arrives through [load] rather than through `SavedStateHandle` so the screen keeps
 * working no matter how it is reached -- from the gallery, from a map pin tap, or later from a
 * notification -- and so the composable signature required by `AppNavHost` stays the source of
 * truth for which find is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FindDetailViewModel @Inject constructor(
    private val repository: FindsRepository,
    private val layerManager: LayerManager,
    private val directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val findId = MutableStateFlow(INVALID_ID)
    private val editing = MutableStateFlow(false)
    private val saving = MutableStateFlow(false)
    private val deleted = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<FindDetailUiState> = combine(
        findId.flatMapLatest { id ->
            if (id <= 0L) flowOf(null) else repository.observeFindWithPhotos(id)
        },
        layerManager.layers,
        editing,
        combine(saving, deleted) { s, d -> s to d },
        message,
    ) { find, layers, isEditing, savingDeleted, msg ->
        val layerId = find?.find?.layerContextId
        FindDetailUiState(
            find = find,
            loading = find == null && findId.value > 0L && !savingDeleted.second,
            editing = isEditing,
            saving = savingDeleted.first,
            layerTitle = layerId?.let { id ->
                layers.firstOrNull { it.def.id == id }?.def?.title
                    ?: layerManager.definitionOf(id)?.title
                    ?: id
            },
            deleted = savingDeleted.second,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FindDetailUiState())

    /** Idempotent: the screen calls it from a `LaunchedEffect` on every recomposition key. */
    fun load(id: Long) {
        if (findId.value != id) findId.value = id
    }

    fun setEditing(value: Boolean) {
        editing.value = value
    }

    /** Writes the in-place edits back. Blank depth clears the column instead of storing 0. */
    fun save(title: String, category: FindCategory, depthCm: Int?, note: String) {
        val current = state.value.find?.find ?: return
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            runCatching {
                repository.update(
                    current.copy(
                        title = title.trim(),
                        category = category,
                        depthCm = depthCm,
                        note = note.trim(),
                    ),
                )
            }.onSuccess {
                editing.value = false
                message.value = "Uloženo"
            }.onFailure {
                message.value = "Změny se nepodařilo uložit"
            }
            saving.value = false
        }
    }

    fun toggleFavorite() {
        val current = state.value.find?.find ?: return
        viewModelScope.launch {
            runCatching { repository.setFavorite(current.id, !current.favorite) }
                .onFailure { message.value = "Oblíbenost se nepodařilo změnit" }
        }
    }

    /** Deletes the find and the JPEGs we own; the screen pops itself once [deleted] flips. */
    fun delete() {
        val current = state.value.find ?: return
        viewModelScope.launch {
            runCatching { repository.delete(current.find.id) }
                .onSuccess {
                    withContext(io) {
                        current.photos.forEach { deleteOwnedPhotoFile(it.uri) }
                    }
                    deleted.value = true
                }
                .onFailure { message.value = "Nález se nepodařilo smazat" }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun deleteOwnedPhotoFile(uri: String) {
        if (!uri.startsWith("/")) return
        runCatching {
            val file = File(uri)
            val root = directories.findsPhotoDir.canonicalPath
            if (file.canonicalPath.startsWith(root) && file.exists()) {
                file.delete()
            }
        }.onFailure { Log.w(TAG, "Fotku $uri se nepodařilo smazat", it) }
    }

    private companion object {
        const val TAG = "FindDetailViewModel"
        const val INVALID_ID = -1L
    }
}
