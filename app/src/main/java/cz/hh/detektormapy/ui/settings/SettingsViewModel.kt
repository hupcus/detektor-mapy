package cz.hh.detektormapy.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.export.ExportResult
import cz.hh.detektormapy.data.export.ImportResult
import cz.hh.detektormapy.data.export.ProjectExporter
import cz.hh.detektormapy.data.export.ProjectImporter
import cz.hh.detektormapy.data.repository.AreasRepository
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.data.repository.PlacesRepository
import cz.hh.detektormapy.data.repository.TracksRepository
import cz.hh.detektormapy.di.IoDispatcher
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.map.LayerPreferences
import cz.hh.detektormapy.map.LayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Row counts shown in the "Data" group. */
data class DataStats(
    val finds: Int = 0,
    val photos: Int = 0,
    val places: Int = 0,
    val areas: Int = 0,
    val tracks: Int = 0,
)

/** Which long-running data operation, if any, is currently blocking the buttons. */
enum class DataJob { NONE, EXPORTING, IMPORTING }

data class SettingsUiState(
    val layers: List<LayerUiState> = emptyList(),
    val layersDirPath: String = "",
    val preferences: LayerPreferences.State = LayerPreferences.State(),
    val stats: DataStats = DataStats(),
    val job: DataJob = DataJob.NONE,
    val exportResult: ExportResult? = null,
    val importResult: ImportResult? = null,
    val message: String? = null,
)

/**
 * State holder for the settings screen.
 *
 * It is the only place that owns the export / import lifecycle, so the buttons can be disabled
 * while a zip is being written and the result dialogs survive a rotation.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val layerManager: LayerManager,
    private val directories: AppDirectories,
    private val exporter: ProjectExporter,
    private val importer: ProjectImporter,
    private val findsRepository: FindsRepository,
    private val placesRepository: PlacesRepository,
    private val areasRepository: AreasRepository,
    private val tracksRepository: TracksRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val jobState = MutableStateFlow(DataJob.NONE)
    private val exportState = MutableStateFlow<ExportResult?>(null)
    private val importState = MutableStateFlow<ImportResult?>(null)
    private val messageState = MutableStateFlow<String?>(null)

    private val statsFlow = combine(
        findsRepository.observeAll(),
        placesRepository.observeAll(),
        areasRepository.observeAll(),
        tracksRepository.observeAll(),
    ) { finds, places, areas, tracks ->
        DataStats(
            finds = finds.size,
            photos = runCatching { findsRepository.countPhotos() }.getOrDefault(0),
            places = places.size,
            areas = areas.size,
            tracks = tracks.size,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        layerManager.layers,
        layerManager.settings,
        statsFlow,
        jobState,
        combine(exportState, importState, messageState) { export, import, message ->
            Triple(export, import, message)
        },
    ) { layers, preferences, stats, job, dialogs ->
        SettingsUiState(
            layers = layers,
            layersDirPath = directories.layersDir.absolutePath,
            preferences = preferences,
            stats = stats,
            job = job,
            exportResult = dialogs.first,
            importResult = dialogs.second,
            message = dialogs.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        layerManager.ensureStarted()
    }

    // --- layers ----------------------------------------------------------------------

    fun reloadLayers() {
        viewModelScope.launch {
            runCatching { layerManager.reload() }
                .onSuccess { messageState.value = "layers.json načten znovu" }
                .onFailure { messageState.value = "layers.json se nepodařilo načíst" }
        }
    }

    // --- map preferences -------------------------------------------------------------

    fun setRotateWithCompass(enabled: Boolean) {
        layerManager.setRotateWithCompass(enabled)
    }

    fun setFollowMode(enabled: Boolean) {
        layerManager.setFollowMode(enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        layerManager.setKeepScreenOn(enabled)
    }

    // --- data ------------------------------------------------------------------------

    /** Writes the whole project into a zip in the exports directory (PLAN.md F2-5). */
    fun exportAll(nowMillis: Long = System.currentTimeMillis()) {
        if (jobState.value != DataJob.NONE) return
        jobState.value = DataJob.EXPORTING
        viewModelScope.launch {
            runCatching { exporter.export(nowMillis) }
                .onSuccess { exportState.value = it }
                .onFailure {
                    Log.w(TAG, "Export selhal", it)
                    messageState.value = "Export selhal: ${it.message ?: "neznámá chyba"}"
                }
            jobState.value = DataJob.NONE
        }
    }

    /**
     * Imports a backup picked through the document picker.
     *
     * The picked [uri] is copied into the cache first because [ProjectImporter] works on a
     * [File]; a `content://` uri from another app cannot be opened as one.
     */
    fun importBackup(uri: Uri) {
        if (jobState.value != DataJob.NONE) return
        jobState.value = DataJob.IMPORTING
        viewModelScope.launch {
            val staged = stageForImport(uri)
            if (staged == null) {
                messageState.value = "Zálohu se nepodařilo otevřít"
            } else {
                runCatching { importer.import(staged) }
                    .onSuccess { importState.value = it }
                    .onFailure {
                        Log.w(TAG, "Import selhal", it)
                        messageState.value = "Import selhal: ${it.message ?: "neznámá chyba"}"
                    }
                withContext(io) { runCatching { staged.delete() } }
            }
            jobState.value = DataJob.NONE
        }
    }

    fun dismissExportResult() {
        exportState.value = null
    }

    fun dismissImportResult() {
        importState.value = null
    }

    fun consumeMessage() {
        messageState.value = null
    }

    fun notify(message: String) {
        messageState.value = message
    }

    private suspend fun stageForImport(uri: Uri): File? = withContext(io) {
        runCatching {
            val target = File(context.cacheDir, "import-${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target
        }.getOrElse {
            Log.w(TAG, "Kopie zálohy do cache selhala", it)
            null
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
