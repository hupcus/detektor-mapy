package cz.hh.detektormapy.ui.finds

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.di.IoDispatcher
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.location.FixQuality
import cz.hh.detektormapy.location.LocationMode
import cz.hh.detektormapy.location.LocationProvider
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.util.Geo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/** The mutable half of the capture screen: what the user typed and what the camera produced. */
private data class CaptureForm(
    val fix: Fix? = null,
    /** False while [fix] is only the last known position rather than a live one. */
    val fixIsLive: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val photoPath: String? = null,
    val category: FindCategory = FindCategory.MINCE,
    val title: String = "",
    val depthText: String = "",
    val note: String = "",
    val saving: Boolean = false,
    val savedId: Long? = null,
    val message: String? = null,
    /** Finds already recorded within [FindsRepository.SAME_SPOT_RADIUS_M] of the fix. */
    val nearbyCount: Int? = null,
)

/** Everything the capture screen renders. */
data class FindCaptureUiState(
    val fix: Fix? = null,
    val fixIsLive: Boolean = false,
    val fixQuality: FixQuality = FixQuality.NONE,
    val locationPermissionGranted: Boolean = false,
    val photoPath: String? = null,
    val category: FindCategory = FindCategory.MINCE,
    val title: String = "",
    val depthText: String = "",
    val note: String = "",
    val saving: Boolean = false,
    val savedId: Long? = null,
    val message: String? = null,
    /** Topmost visible overlay at this moment; stored with the find (PLAN.md F2-6). */
    val layerContextId: String? = null,
    val layerTitle: String? = null,
    /** Finds already recorded around the fix, or null while unknown. */
    val nearbyCount: Int? = null,
) {
    /** No position, no find: `lat`/`lon` are non-null columns and a made-up pin is worthless. */
    val canSave: Boolean get() = fix != null && !saving && savedId == null

    /**
     * "Tvůj lov: N. na tomto místě" — the find being captured is the (count+1)-th one here.
     * Null while the count is unknown, so the screen shows nothing rather than a wrong number.
     */
    val huntRankLabel: String?
        get() = nearbyCount?.let { existing ->
            if (existing == 0) {
                "První nález na tomto místě"
            } else {
                "Tvůj lov: ${existing + 1}. nález na tomto místě"
            }
        }
}

/**
 * State holder for the 15-second capture flow (PLAN.md F2-2).
 *
 * GPS is subscribed the moment the screen opens, so by the time the shutter is pressed there is
 * usually already a live fix; the last known position is used as an explicitly labelled
 * stand-in until then. Nothing here ever waits for the camera -- a find without a photo is still
 * a find, and refusing to save one in the field would be the worst possible failure mode.
 */
@HiltViewModel
class FindCaptureViewModel @Inject constructor(
    private val repository: FindsRepository,
    private val locationProvider: LocationProvider,
    private val layerManager: LayerManager,
    private val directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val form = MutableStateFlow(CaptureForm())
    private var locationJob: Job? = null

    val state: StateFlow<FindCaptureUiState> = combine(
        form,
        layerManager.layers,
    ) { current, layers ->
        val layer = layers.lastOrNull { it.visible && !it.def.isBasemap }
        FindCaptureUiState(
            fix = current.fix,
            fixIsLive = current.fixIsLive,
            fixQuality = FixQuality.of(current.fix?.accuracyM),
            locationPermissionGranted = current.locationPermissionGranted,
            photoPath = current.photoPath,
            category = current.category,
            title = current.title,
            depthText = current.depthText,
            note = current.note,
            saving = current.saving,
            savedId = current.savedId,
            message = current.message,
            layerContextId = layer?.def?.id,
            layerTitle = layer?.def?.title,
            nearbyCount = current.nearbyCount,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FindCaptureUiState())

    init {
        startLocation()
    }

    /** (Re)subscribes to GPS; called again after the user grants the location permission. */
    fun startLocation() {
        val granted = locationProvider.hasPermission()
        form.update {
            it.copy(
                locationPermissionGranted = granted,
                fix = it.fix ?: locationProvider.lastKnown(),
            )
        }
        form.value.fix?.let(::refreshNearbyCount)
        if (!granted || locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            locationProvider.fixes(LocationMode.INTERACTIVE).collect { fix ->
                form.update { it.copy(fix = fix, fixIsLive = true) }
                refreshNearbyCount(fix)
            }
        }
    }

    /**
     * Recounts finds around the fix for the "Tvůj lov" line. Fixes arrive about once a second,
     * so the query only reruns after the user actually walked somewhere else.
     */
    private var nearbyCountedAt: Pair<Double, Double>? = null

    private fun refreshNearbyCount(fix: Fix) {
        val previous = nearbyCountedAt
        if (previous != null &&
            Geo.distanceM(previous.first, previous.second, fix.lat, fix.lon) < RECOUNT_AFTER_M
        ) {
            return
        }
        nearbyCountedAt = fix.lat to fix.lon
        viewModelScope.launch {
            runCatching { repository.countNear(fix.lat, fix.lon) }
                .onSuccess { count -> form.update { it.copy(nearbyCount = count) } }
                .onFailure { Log.w(TAG, "Počet nálezů v okolí se nepodařilo spočítat", it) }
        }
    }

    // --- form ------------------------------------------------------------------------

    fun setCategory(category: FindCategory) = form.update { it.copy(category = category) }

    fun setTitle(value: String) = form.update { it.copy(title = value) }

    /** Digits only, capped at three: 999 cm is deeper than any detector reaches. */
    fun setDepthText(value: String) = form.update { it.copy(depthText = value.filter { ch -> ch.isDigit() }.take(3)) }

    fun setNote(value: String) = form.update { it.copy(note = value) }

    fun consumeMessage() = form.update { it.copy(message = null) }

    // --- photo -----------------------------------------------------------------------

    /** Target file for the next shot; timestamped so two finds never collide. */
    fun newPhotoFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directories.findsPhotoDir, "IMG_${stamp}_${System.nanoTime() % 1_000L}.jpg")
    }

    /** Stores the path and stamps the current position into the JPEG's EXIF header. */
    fun onPhotoCaptured(file: File) {
        val fix = form.value.fix
        form.update { it.copy(photoPath = file.absolutePath) }
        viewModelScope.launch { withContext(io) { writeExifGps(file, fix) } }
    }

    fun onPhotoFailed() {
        form.update { it.copy(message = "Fotku se nepodařilo uložit, nález ulož bez ní") }
    }

    /** Drops the photo taken so far, deleting the file we just wrote. */
    fun discardPhoto() {
        val path = form.value.photoPath ?: return
        form.update { it.copy(photoPath = null) }
        viewModelScope.launch {
            withContext(io) {
                runCatching { File(path).delete() }
                    .onFailure { Log.w(TAG, "Fotku $path se nepodařilo smazat", it) }
            }
        }
    }

    // --- save ------------------------------------------------------------------------

    /** Writes the find (and its photo row, when there is one) and flips `savedId`. */
    fun save(nowMillis: Long = System.currentTimeMillis()) {
        val snapshot = state.value
        val fix = snapshot.fix
        if (fix == null) {
            form.update { it.copy(message = "Bez GPS pozice nelze nález uložit") }
            return
        }
        if (snapshot.saving || snapshot.savedId != null) return
        form.update { it.copy(saving = true) }

        viewModelScope.launch {
            runCatching {
                val findId = repository.add(
                    FindEntity(
                        lat = fix.lat,
                        lon = fix.lon,
                        altitude = fix.altitude,
                        accuracyM = fix.accuracyM,
                        createdAt = nowMillis,
                        title = snapshot.title.trim(),
                        category = snapshot.category,
                        depthCm = snapshot.depthText.toIntOrNull(),
                        note = snapshot.note.trim(),
                        layerContextId = snapshot.layerContextId,
                    ),
                )
                val photoPath = snapshot.photoPath
                if (photoPath != null) {
                    repository.addPhoto(
                        FindPhotoEntity(
                            findId = findId,
                            uri = photoPath,
                            createdAt = nowMillis,
                            isPrimary = true,
                        ),
                    )
                }
                findId
            }.onSuccess { id ->
                form.update { it.copy(saving = false, savedId = id) }
            }.onFailure { error ->
                Log.e(TAG, "Nález se nepodařilo uložit", error)
                form.update { it.copy(saving = false, message = "Nález se nepodařilo uložit") }
            }
        }
    }

    /**
     * Writes GPS latitude / longitude / altitude / timestamp into the JPEG.
     *
     * The EXIF header is what makes the photo useful outside this app -- dropped into QGIS or a
     * photo manager it still knows where it was taken, even if the database is ever lost.
     */
    private fun writeExifGps(file: File, fix: Fix?) {
        if (fix == null || !file.exists()) return
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.setLatLong(fix.lat, fix.lon)
            fix.altitude?.let { exif.setAltitude(it) }
            val utc = TimeZone.getTimeZone("UTC")
            val stampDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utc }
            // EXIF stores the GPS time as three rationals, hence the "hh/1,mm/1,ss/1" shape.
            val stampTime = SimpleDateFormat("HH/1,mm/1,ss/1", Locale.US).apply { timeZone = utc }
            val moment = Date(if (fix.timestamp > 0L) fix.timestamp else System.currentTimeMillis())
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, stampDate.format(moment))
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, stampTime.format(moment))
            exif.saveAttributes()
        }.onFailure { Log.w(TAG, "EXIF GPS se nepodařilo zapsat", it) }
    }

    private companion object {
        const val TAG = "FindCaptureViewModel"

        /** How far the fix must move before the nearby-finds count is recomputed. */
        const val RECOUNT_AFTER_M = 30.0
    }
}
