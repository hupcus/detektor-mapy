package cz.hh.detektormapy.net

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.hh.detektormapy.di.ApplicationScope
import cz.hh.detektormapy.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.networkDataStore by preferencesDataStore(name = "network")

/**
 * Keeps [PoliteHttp]'s "tiles downloaded today" counter alive across restarts.
 *
 * The number is shown in Nastavení and goes nowhere else. It is the app's answer to "how much am
 * I actually taking from these servers?" without collecting a byte of telemetry: the counting
 * happens in the process that does the downloading and the total is written to the device's own
 * preferences.
 *
 * The counter itself lives in [PoliteHttp] as two atomics, because the tile threads that
 * increment it must not touch coroutines or disk. This class only mirrors it to disk on a slow
 * cadence and handles the midnight rollover.
 */
@Singleton
class NetworkUsageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    data class Usage(val tiles: Long = 0, val bytes: Long = 0)

    private val usageState = MutableStateFlow(Usage())

    /** Today's totals, refreshed on every flush and on [refresh]. */
    val usage: StateFlow<Usage> = usageState

    @Volatile
    private var day: Long = 0

    init {
        scope.launch(io) {
            restore()
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /** Pulls the live counters into [usage] without waiting for the next flush tick. */
    fun refresh() {
        scope.launch(io) { flush() }
    }

    /**
     * Folds today's persisted totals into the live counters.
     *
     * Additive, not a plain assignment: reading a DataStore is asynchronous, and tiles can be
     * downloaded before the read lands. Overwriting would then quietly throw those away and the
     * screen would report zero downloads right after a map had visibly loaded.
     */
    private suspend fun restore() {
        val today = todayEpochDay()
        val live = PoliteHttp.usage()
        val stored = runCatching { context.networkDataStore.data.first() }.getOrNull()
        val storedDay = stored?.get(DAY_KEY) ?: 0L
        if (storedDay == today) {
            PoliteHttp.seedUsage(
                tiles = live.tiles + (stored?.get(TILES_KEY) ?: 0L),
                bytes = live.bytes + (stored?.get(BYTES_KEY) ?: 0L),
            )
        }
        day = today
        publish()
    }

    private suspend fun flush() {
        val today = todayEpochDay()
        if (today != day) {
            // A new day starts from zero; yesterday's number is of no further use to anyone.
            PoliteHttp.resetUsage()
            day = today
        }
        val current = PoliteHttp.usage()
        publish()
        runCatching {
            withContext(io) {
                context.networkDataStore.edit {
                    it[DAY_KEY] = day
                    it[TILES_KEY] = current.tiles
                    it[BYTES_KEY] = current.bytes
                }
            }
        }
    }

    private fun publish() {
        val current = PoliteHttp.usage()
        usageState.value = Usage(current.tiles, current.bytes)
    }

    private fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    private companion object {
        val DAY_KEY = longPreferencesKey("usage_day")
        val TILES_KEY = longPreferencesKey("usage_tiles")
        val BYTES_KEY = longPreferencesKey("usage_bytes")

        /** Slow on purpose: this is a courtesy readout, not a metric anyone acts on in seconds. */
        const val FLUSH_INTERVAL_MS = 20_000L
    }
}
