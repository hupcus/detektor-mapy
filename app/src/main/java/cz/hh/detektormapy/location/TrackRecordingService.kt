package cz.hh.detektormapy.location

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cz.hh.detektormapy.DetektorMapyApp
import cz.hh.detektormapy.MainActivity
import cz.hh.detektormapy.R
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.data.repository.TracksRepository
import cz.hh.detektormapy.util.Geo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground service that records a walk (PLAN.md F4-1) and offers the lock-screen quick
 * actions of F4-4.
 *
 * Design notes that matter for the battery budget (2 h recording under 15 %):
 *  - all decisions about a fix live in [TrackRecorder], so this class only does plumbing;
 *  - GPS cadence follows [LocationMode]: 5 s while moving, 30 s while standing;
 *  - points are buffered in memory and written to Room in batches, never one row per fix;
 *  - the notification is refreshed at most every [NOTIFICATION_INTERVAL_MS];
 *  - nothing here touches the network, and the partial wake lock is held only while recording.
 *
 * The service must survive being killed: the id of the open track is mirrored into
 * shared preferences, so a `START_STICKY` restart (null intent) resumes the same track instead
 * of orphaning it.
 */
@AndroidEntryPoint
class TrackRecordingService : LifecycleService() {

    @Inject lateinit var locationProvider: LocationProvider

    @Inject lateinit var tracksRepository: TracksRepository

    @Inject lateinit var findsRepository: FindsRepository

    @Inject lateinit var directories: AppDirectories

    private val modeFlow = MutableStateFlow(LocationMode.TRACKING_MOVING)

    /** Serialises every write into `track_points` so a stop cannot race an in-flight flush. */
    private val flushMutex = Mutex()

    private var recorder: TrackRecorder? = null
    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackId: Long? = null
    private var trackStartedAt: Long = 0L
    private var markedFinds: Int = 0
    private var lastNotificationAt: Long = 0L
    private var recording: Boolean = false
    private var stopping: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(resume = false)

            ACTION_STOP -> handleStop(StopReason.USER)

            ACTION_MARK_FIND -> handleMarkFind()

            // Null intent: the system restarted us after killing the process (START_STICKY).
            null -> handleStart(resume = true)

            else -> if (!recording) stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            collectJob?.cancel()
            collectJob = null
            recording = false
            running = false
        } finally {
            // The wake lock must go even if something above threw, otherwise the phone never
            // sleeps again for the rest of the day.
            releaseWakeLock()
            super.onDestroy()
        }
    }

    // ---------------------------------------------------------------- start / stop

    private fun handleStart(resume: Boolean) {
        if (recording) return
        if (!locationProvider.hasPermission()) {
            // Nothing to record without the permission; say so instead of dying silently.
            notifyStopped(getString(R.string.track_stopped_permission))
            stopSelf()
            return
        }
        val startedAt = System.currentTimeMillis()
        trackStartedAt = startedAt
        markedFinds = 0
        recorder = TrackRecorder()
        if (!enterForeground()) {
            recorder = null
            stopSelf()
            return
        }
        recording = true
        running = true
        acquireWakeLock()

        collectJob = lifecycleScope.launch {
            val id = resolveTrackId(resume, startedAt)
            if (id == null) {
                Log.w(TAG, "Track could not be opened, aborting the recording")
                handleStop(StopReason.ERROR)
                return@launch
            }
            trackId = id
            storeActiveTrackId(id)
            updateNotification(force = true)
            collectFixes()
        }
    }

    /** Reuses the open track after a restart, otherwise opens a fresh one. */
    private suspend fun resolveTrackId(resume: Boolean, startedAt: Long): Long? = runCatching {
        val active = tracksRepository.getActive()
        if (resume && active != null && active.isRecording) {
            trackStartedAt = active.startedAt
            return@runCatching active.id
        }
        // A leftover open track from a killed session would show up as "still recording"
        // forever in the UI, so close it before starting a new one.
        if (active != null && active.isRecording) closeStaleTrack(active)
        tracksRepository.startTrack(startedAt, defaultTrackName(startedAt))
    }.getOrElse {
        Log.w(TAG, "Track could not be opened", it)
        null
    }

    private suspend fun closeStaleTrack(track: TrackEntity) {
        val lastPointAt = runCatching { tracksRepository.getPoints(track.id) }
            .getOrNull()
            ?.maxOfOrNull { it.timestamp }
        runCatching {
            tracksRepository.finishTrack(track.id, lastPointAt ?: track.startedAt, null)
        }
    }

    private fun handleStop(reason: StopReason) {
        if (stopping) return
        stopping = true
        collectJob?.cancel()
        collectJob = null
        recording = false
        running = false
        val id = trackId
        val startedAt = trackStartedAt

        lifecycleScope.launch {
            if (id != null) {
                runCatching { finishTrack(id, startedAt) }
                    .onFailure { Log.w(TAG, "Track could not be closed cleanly", it) }
            }
            clearActiveTrackId()
            releaseWakeLock()
            when (reason) {
                StopReason.USER -> Unit
                StopReason.PERMISSION -> notifyStopped(getString(R.string.track_stopped_permission))
                StopReason.LOCATION_LOST -> notifyStopped(getString(R.string.track_stopped_no_location))
                StopReason.ERROR -> notifyStopped(getString(R.string.track_stopped_error))
            }
            ServiceCompat.stopForeground(this@TrackRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Flushes the tail of the buffer, writes the GPX file and closes the row in Room. */
    private suspend fun finishTrack(id: Long, startedAt: Long) {
        flushMutex.withLock {
            val remaining = recorder?.drainPending().orEmpty()
            if (remaining.isNotEmpty()) {
                tracksRepository.appendPoints(remaining.map { it.toPoint(id) })
            }
        }
        val points = runCatching { tracksRepository.getPoints(id) }.getOrDefault(emptyList())
        val name = tracksRepository.getTrack(id)?.name ?: defaultTrackName(startedAt)
        val endedAt = points.maxOfOrNull { it.timestamp }
            ?.coerceAtLeast(startedAt)
            ?: System.currentTimeMillis()
        val gpx = writeGpxFile(id, name, startedAt, points)
        tracksRepository.finishTrack(id, endedAt, gpx?.absolutePath)
        recorder = null
        trackId = null
    }

    private suspend fun writeGpxFile(id: Long, name: String, startedAt: Long, points: List<TrackPointEntity>): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val stamp = FILE_STAMP_FORMAT.format(Instant.ofEpochMilli(startedAt))
                val file = File(directories.tracksDir, "track-$id-$stamp.gpx")
                file.bufferedWriter().use { GpxWriter.write(points, name, startedAt, it) }
                file
            }.getOrElse {
                Log.w(TAG, "GPX file could not be written", it)
                null
            }
        }

    // ---------------------------------------------------------------- fixes

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectFixes() {
        modeFlow.value = LocationMode.TRACKING_MOVING
        modeFlow
            .flatMapLatest { mode ->
                locationProvider.fixes(mode).onCompletion { cause ->
                    // A null cause means the provider closed the flow itself -- the permission
                    // was revoked or every provider got turned off. Cancellation (a cadence
                    // switch or a stop) always carries a cause and must be ignored here.
                    if (cause == null && recording) onLocationLost()
                }
            }
            .collect { fix -> onFix(fix) }
    }

    /**
     * Handles one fix. Deliberately non-suspending: the collector body runs inside the flow
     * that [flatMapLatest] cancels on every cadence switch, so any await here could be torn
     * down mid-write. Database work is handed to a separate coroutine instead.
     */
    private fun onFix(fix: Fix) {
        val active = recorder ?: return
        val decision = active.offer(fix)
        if (decision.mode != modeFlow.value) modeFlow.value = decision.mode
        if (!decision.accepted) return
        if (decision.shouldFlush) flush(active.drainPending())
        updateNotification()
    }

    private fun flush(fixes: List<Fix>) {
        if (fixes.isEmpty()) return
        val id = trackId ?: return
        lifecycleScope.launch {
            flushMutex.withLock {
                runCatching { tracksRepository.appendPoints(fixes.map { it.toPoint(id) }) }
                    .onFailure { Log.w(TAG, "Point batch could not be flushed", it) }
            }
        }
    }

    private fun onLocationLost() {
        val reason = if (locationProvider.hasPermission()) {
            StopReason.LOCATION_LOST
        } else {
            StopReason.PERMISSION
        }
        handleStop(reason)
    }

    // ---------------------------------------------------------------- quick find

    /** F4-4: pins a bare find at the current position so the photo can wait until later. */
    private fun handleMarkFind() {
        if (!recording) {
            stopSelf()
            return
        }
        val fix = recorder?.lastFix ?: locationProvider.lastKnown()
        if (fix == null) {
            Log.w(TAG, "Quick find ignored: no position yet")
            return
        }
        val id = trackId
        lifecycleScope.launch {
            val saved = runCatching {
                findsRepository.add(
                    FindEntity(
                        lat = fix.lat,
                        lon = fix.lon,
                        altitude = fix.altitude,
                        accuracyM = fix.accuracyM,
                        createdAt = System.currentTimeMillis(),
                        title = "",
                        category = FindCategory.OSTATNI,
                        trackId = id,
                    ),
                )
            }.onFailure { Log.w(TAG, "Quick find could not be saved", it) }.isSuccess
            if (saved) {
                markedFinds++
                updateNotification(force = true)
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun enterForeground(): Boolean = runCatching {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    }.getOrElse {
        // Android 14+ throws when the location permission is gone at this exact moment.
        Log.w(TAG, "Foreground service could not be started", it)
        false
    }

    private fun updateNotification(force: Boolean = false) {
        if (!recording) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { Log.w(TAG, "Notification could not be updated", it) }
    }

    private fun buildNotification(): Notification {
        val active = recorder
        val text = if (active == null || active.pointCount == 0) {
            getString(R.string.track_notification_starting)
        } else {
            getString(
                R.string.track_notification_stats,
                formatElapsed(System.currentTimeMillis() - trackStartedAt),
                Geo.formatDistance(active.totalDistanceM),
                active.pointCount,
            )
        }

        val builder = NotificationCompat.Builder(this, DetektorMapyApp.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.track_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // F4-4 wants both actions reachable without unlocking the phone.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_add,
                getString(R.string.track_action_mark),
                servicePendingIntent(ACTION_MARK_FIND, REQUEST_MARK),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.track_action_stop),
                servicePendingIntent(ACTION_STOP, REQUEST_STOP),
            )
        if (markedFinds > 0) {
            builder.setSubText(getString(R.string.track_notification_finds, markedFinds))
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_CONTENT,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The notification buttons talk to the service directly. A [android.content.BroadcastReceiver]
     * would only forward the same intent, and the service is already in the foreground, so the
     * background-start restrictions do not apply.
     */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, TrackRecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One-off, dismissible note explaining why the recording ended on its own. */
    private fun notifyStopped(text: String) {
        val notification = NotificationCompat.Builder(this, DetektorMapyApp.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.track_stopped_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_STOPPED, notification)
        }.onFailure { Log.w(TAG, "Stop notification could not be posted", it) }
    }

    // ---------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Bounded on purpose: a forgotten lock would be a dead battery, and no walk
            // realistically runs longer than this.
            runCatching { acquire(MAX_RECORDING_MS) }
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    // ---------------------------------------------------------------- helpers

    private fun storeActiveTrackId(id: Long) {
        runCatching { prefs(this).edit().putLong(KEY_ACTIVE_TRACK, id).apply() }
    }

    private fun clearActiveTrackId() {
        runCatching { prefs(this).edit().remove(KEY_ACTIVE_TRACK).apply() }
    }

    private fun defaultTrackName(startedAt: Long): String =
        getString(R.string.track_default_name, NAME_FORMAT.format(Instant.ofEpochMilli(startedAt)))

    private enum class StopReason { USER, PERMISSION, LOCATION_LOST, ERROR }

    companion object {

        const val ACTION_START = "cz.hh.detektormapy.action.TRACK_START"
        const val ACTION_STOP = "cz.hh.detektormapy.action.TRACK_STOP"
        const val ACTION_MARK_FIND = "cz.hh.detektormapy.action.TRACK_MARK_FIND"

        private const val TAG = "TrackRecording"
        private const val PREFS = "track_recording"
        private const val KEY_ACTIVE_TRACK = "active_track_id"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_ID_STOPPED = 4102
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_MARK = 2
        private const val REQUEST_STOP = 3

        /** Refresh ceiling for the ongoing notification; per-fix updates would spam the system. */
        private const val NOTIFICATION_INTERVAL_MS = 10_000L
        private const val MAX_RECORDING_MS = 12L * 60L * 60L * 1000L
        private const val WAKE_LOCK_TAG = "DetektorMapy:track"

        private val NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d. M. yyyy HH:mm", Locale.forLanguageTag("cs"))
                .withZone(ZoneId.systemDefault())

        private val FILE_STAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault())

        /** In-process mirror of [isRecording]; the preference covers a killed process. */
        @Volatile
        private var running: Boolean = false

        /** Starts (or resumes) the recording. Safe to call when it already runs. */
        fun start(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Recording could not be started", it) }
        }

        /** Stops the recording, writes the GPX file and closes the track row. */
        fun stop(context: Context) = send(context, ACTION_STOP)

        /** F4-4: logs a find at the current position without opening the app. */
        fun markFind(context: Context) = send(context, ACTION_MARK_FIND)

        /** True while a track is open, including one left behind by a killed process. */
        fun isRecording(context: Context): Boolean = running || activeTrackId(context) != null

        /** Id of the open track, or null when nothing is being recorded. */
        fun activeTrackId(context: Context): Long? = runCatching { prefs(context).getLong(KEY_ACTIVE_TRACK, -1L) }
            .getOrDefault(-1L)
            .takeIf { it > 0L }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, TrackRecordingService::class.java).setAction(action)
            // Plain startService: the service is already foreground, and if it is not, there is
            // nothing to stop or mark anyway.
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Command $action could not be delivered", it) }
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/** Formats a duration as `h:mm:ss` (or `m:ss` under an hour) for the notification. */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Maps a fix onto the Room row; keeps the conversion in one place. */
internal fun Fix.toPoint(trackId: Long) = TrackPointEntity(
    trackId = trackId,
    lat = lat,
    lon = lon,
    altitude = altitude,
    timestamp = timestamp,
    accuracyM = accuracyM,
    speedMs = speedMs,
)
