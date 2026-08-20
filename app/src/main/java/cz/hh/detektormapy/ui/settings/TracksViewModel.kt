package cz.hh.detektormapy.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.dao.TrackStats
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.export.Gpx
import cz.hh.detektormapy.data.repository.TracksRepository
import cz.hh.detektormapy.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class TracksUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val stats: TrackStats = TrackStats(total = 0, totalDistanceM = 0.0, totalDurationMs = 0L),
    /** A freshly written GPX waiting to be handed to the share sheet, consumed by the screen. */
    val pendingShare: File? = null,
    val message: String? = null,
)

/**
 * State holder for the recorded walks (PLAN.md F4-1, reading half).
 *
 * The recording itself belongs to the foreground service; this screen only ever reads. GPX files
 * are re-serialised into the exports directory on demand instead of sharing
 * [TrackEntity.gpxPath] directly, because only the exports directory is declared in
 * `@xml/file_paths` and a track recorded by an older build may have no file at all.
 */
@HiltViewModel
class TracksViewModel @Inject constructor(
    private val tracksRepository: TracksRepository,
    private val directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val shareState = MutableStateFlow<File?>(null)
    private val messageState = MutableStateFlow<String?>(null)

    val state: StateFlow<TracksUiState> = combine(
        tracksRepository.observeAll(),
        tracksRepository.observeStats().catch { emit(EMPTY_STATS) },
        shareState,
        messageState,
    ) { tracks, stats, share, message ->
        TracksUiState(
            tracks = tracks,
            stats = stats,
            pendingShare = share,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TracksUiState())

    fun rename(track: TrackEntity, name: String) {
        viewModelScope.launch {
            tracksRepository.update(track.copy(name = name.trim()))
            messageState.value = "Přejmenováno"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            tracksRepository.delete(id)
            messageState.value = "Pochůzka smazána"
        }
    }

    /** Serialises [track] into a GPX file and offers it for sharing. */
    fun exportGpx(track: TrackEntity) {
        viewModelScope.launch {
            val file = writeGpx(track)
            if (file == null) {
                messageState.value = "GPX se nepodařilo zapsat"
            } else {
                shareState.value = file
            }
        }
    }

    fun consumeShare() {
        shareState.value = null
    }

    fun consumeMessage() {
        messageState.value = null
    }

    fun notify(message: String) {
        messageState.value = message
    }

    private suspend fun writeGpx(track: TrackEntity): File? = withContext(io) {
        runCatching {
            val points = tracksRepository.getPoints(track.id)
            val file = File(directories.exportsDir, gpxFileName(track))
            file.writeText(Gpx.write(track, points))
            file
        }.getOrElse {
            Log.w(TAG, "Zápis GPX pro pochůzku ${track.id} selhal", it)
            null
        }
    }

    private fun gpxFileName(track: TrackEntity): String {
        val base = track.name.ifBlank { "pochuzka" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
        return "$base-${track.id}.gpx"
    }

    private companion object {
        const val TAG = "TracksViewModel"
        val EMPTY_STATS = TrackStats(total = 0, totalDistanceM = 0.0, totalDurationMs = 0L)
    }
}
