package cz.hh.detektormapy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.net.NetworkUsageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the storage screen: a layer, what it occupies and whether it may keep growing. */
data class StorageRow(
    val layerId: String,
    val title: String,
    /** Bytes of `<id>.cache.mbtiles` (online layers). */
    val cacheBytes: Long,
    /** Bytes of the layer's own offline archive, if it has one. */
    val archiveBytes: Long,
    val cachingEnabled: Boolean,
    val isOnline: Boolean,
) {
    val totalBytes: Long get() = cacheBytes + archiveBytes
}

data class StorageUiState(
    val rows: List<StorageRow> = emptyList(),
    /** Cache files whose layer is no longer in the catalogue; only reachable via "Smazat vše". */
    val orphanBytes: Long = 0,
    val cacheEnabled: Boolean = true,
    val freeBytes: Long = 0,
    val lowSpace: Boolean = false,
    val downloadedTilesToday: Long = 0,
    val downloadedBytesToday: Long = 0,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val cacheTotal: Long get() = rows.sumOf { it.cacheBytes } + orphanBytes
    val archiveTotal: Long get() = rows.sumOf { it.archiveBytes }
}

/**
 * State holder for "Správa úložiště".
 *
 * Sizes are always read with `File.length()` rather than estimated from tile counts: the whole
 * point of the screen is to answer "where did my gigabytes go", and a number the user can check
 * against their file manager is the only one worth showing.
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val layerManager: LayerManager,
    private val usageStore: NetworkUsageStore,
) : ViewModel() {

    /** What one pass over the filesystem found. */
    private data class DiskSnapshot(
        val caches: Map<String, Long> = emptyMap(),
        val archives: Map<String, Long> = emptyMap(),
        val free: Long = 0,
    )

    /** Bumped by [refresh]; the sizes are also re-measured whenever the catalogue changes. */
    private val refreshTick = MutableStateFlow(0)
    private val busyState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<String?>(null)

    /**
     * Measuring is driven by the layer list, not by a one-shot read in `init`.
     *
     * `LayerManager.layers` starts empty and fills in once the catalogue has been read off disk,
     * so a snapshot taken at construction time reports every archive as 0 bytes and never
     * corrects itself.
     */
    private val diskState = combine(layerManager.layers, refreshTick) { layers, _ -> layers }
        .map { layers ->
            DiskSnapshot(
                caches = layerManager.cacheSizes(),
                archives = layers.associate { it.def.id to layerManager.archiveSize(it.def) },
                free = layerManager.freeSpaceBytes(),
            )
        }

    val state: StateFlow<StorageUiState> = combine(
        layerManager.layers,
        layerManager.settings,
        diskState,
        combine(busyState, messageState, layerManager.cacheLowSpace) { busy, message, low ->
            Triple(busy, message, low)
        },
        usageStore.usage,
    ) { layers, prefs, disk, ui, usage ->
        val rows = layers.map { layer ->
            StorageRow(
                layerId = layer.def.id,
                title = layer.def.title,
                cacheBytes = disk.caches[layer.def.id] ?: 0L,
                archiveBytes = disk.archives[layer.def.id] ?: 0L,
                cachingEnabled = layer.def.id !in prefs.cacheExcluded,
                isOnline = layer.def.isOnline,
            )
        }
        val known = layers.map { it.def.id }.toSet()
        StorageUiState(
            rows = rows.sortedByDescending { it.totalBytes },
            orphanBytes = disk.caches.filterKeys { it !in known }.values.sum(),
            cacheEnabled = prefs.cacheTiles,
            freeBytes = disk.free,
            lowSpace = ui.third,
            downloadedTilesToday = usage.tiles,
            downloadedBytesToday = usage.bytes,
            busy = ui.first,
            message = ui.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    fun refresh() {
        refreshTick.value += 1
        usageStore.refresh()
    }

    fun setCacheEnabled(enabled: Boolean) {
        layerManager.setCacheTiles(enabled)
    }

    fun setLayerCaching(layerId: String, enabled: Boolean) {
        layerManager.setCacheLayer(layerId, enabled)
    }

    fun clearLayer(layerId: String, title: String) {
        if (busyState.value) return
        busyState.value = true
        viewModelScope.launch {
            val ok = layerManager.clearCache(layerId)
            messageState.value = if (ok) "Cache vrstvy $title smazána" else "Cache se nepodařilo smazat"
            busyState.value = false
            refresh()
        }
    }

    fun clearAll() {
        if (busyState.value) return
        busyState.value = true
        viewModelScope.launch {
            val removed = layerManager.clearAllCaches()
            messageState.value = "Smazáno souborů: $removed"
            busyState.value = false
            refresh()
        }
    }

    fun consumeMessage() {
        messageState.value = null
    }
}
