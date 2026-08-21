package cz.hh.detektormapy.map

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.layerDataStore by preferencesDataStore(name = "layers")

/** Per-layer visibility / opacity / order, persisted so the map comes back exactly as left. */
@Singleton
class LayerPreferences @Inject constructor(@param:ApplicationContext private val context: Context) {

    data class State(
        val visible: Map<String, Boolean> = emptyMap(),
        val opacity: Map<String, Float> = emptyMap(),
        val order: Map<String, Int> = emptyMap(),
        val basemapId: String? = null,
        val rotateWithCompass: Boolean = false,
        val followMode: Boolean = true,
        val keepScreenOn: Boolean = true,
        val showFinds: Boolean = true,
        val showPlaces: Boolean = true,
        val showAreas: Boolean = true,
        /** Master switch for the write-through tile cache ("Ukládat mapy pro offline"). */
        val cacheTiles: Boolean = true,
        /** Layers the user opted out of caching individually; everything else caches. */
        val cacheExcluded: Set<String> = emptySet(),
    )

    private val basemapKey = androidx.datastore.preferences.core.stringPreferencesKey("basemap")
    private val rotateKey = booleanPreferencesKey("rotate_with_compass")
    private val followKey = booleanPreferencesKey("follow_mode")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    private val showFindsKey = booleanPreferencesKey("show_finds")
    private val showPlacesKey = booleanPreferencesKey("show_places")
    private val showAreasKey = booleanPreferencesKey("show_areas")
    private val cacheTilesKey = booleanPreferencesKey("cache_tiles")

    private fun visibleKey(id: String) = booleanPreferencesKey("vis_$id")
    private fun opacityKey(id: String) = floatPreferencesKey("op_$id")
    private fun orderKey(id: String) = intPreferencesKey("ord_$id")

    /**
     * Per-layer caching opt-out.
     *
     * Stored as an exclusion rather than an inclusion so that a layer added to the catalogue
     * later is cached by default, exactly like every layer that came before it.
     */
    private fun cacheKey(id: String) = booleanPreferencesKey("cache_$id")

    val state: Flow<State> = context.layerDataStore.data.map { prefs -> prefs.toState() }

    suspend fun setVisible(layerId: String, visible: Boolean) {
        context.layerDataStore.edit { it[visibleKey(layerId)] = visible }
    }

    suspend fun setOpacity(layerId: String, opacity: Float) {
        context.layerDataStore.edit { it[opacityKey(layerId)] = opacity.coerceIn(0f, 1f) }
    }

    suspend fun setOrder(layerId: String, order: Int) {
        context.layerDataStore.edit { it[orderKey(layerId)] = order }
    }

    /** One reorder = one disk write, not one edit per layer. */
    suspend fun setOrders(orders: Map<String, Int>) {
        context.layerDataStore.edit { prefs ->
            orders.forEach { (layerId, order) -> prefs[orderKey(layerId)] = order }
        }
    }

    suspend fun setBasemap(layerId: String) {
        context.layerDataStore.edit { it[basemapKey] = layerId }
    }

    suspend fun setRotateWithCompass(enabled: Boolean) {
        context.layerDataStore.edit { it[rotateKey] = enabled }
    }

    suspend fun setFollowMode(enabled: Boolean) {
        context.layerDataStore.edit { it[followKey] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.layerDataStore.edit { it[keepScreenOnKey] = enabled }
    }

    suspend fun setShowFinds(enabled: Boolean) {
        context.layerDataStore.edit { it[showFindsKey] = enabled }
    }

    suspend fun setShowPlaces(enabled: Boolean) {
        context.layerDataStore.edit { it[showPlacesKey] = enabled }
    }

    suspend fun setShowAreas(enabled: Boolean) {
        context.layerDataStore.edit { it[showAreasKey] = enabled }
    }

    suspend fun setCacheTiles(enabled: Boolean) {
        context.layerDataStore.edit { it[cacheTilesKey] = enabled }
    }

    suspend fun setCacheLayer(layerId: String, enabled: Boolean) {
        context.layerDataStore.edit { it[cacheKey(layerId)] = enabled }
    }

    private fun Preferences.toState(): State {
        val visible = mutableMapOf<String, Boolean>()
        val opacity = mutableMapOf<String, Float>()
        val order = mutableMapOf<String, Int>()
        val cacheExcluded = mutableSetOf<String>()
        asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name.startsWith("vis_") -> (value as? Boolean)?.let { visible[name.removePrefix("vis_")] = it }

                name.startsWith("op_") -> (value as? Float)?.let { opacity[name.removePrefix("op_")] = it }

                name.startsWith("ord_") -> (value as? Int)?.let { order[name.removePrefix("ord_")] = it }

                // "cache_tiles" is the master switch and must not be read as a layer id.
                name.startsWith("cache_") && name != "cache_tiles" ->
                    if (value == false) cacheExcluded += name.removePrefix("cache_")
            }
        }
        return State(
            visible = visible,
            opacity = opacity,
            order = order,
            basemapId = this[basemapKey],
            rotateWithCompass = this[rotateKey] ?: false,
            followMode = this[followKey] ?: true,
            keepScreenOn = this[keepScreenOnKey] ?: true,
            showFinds = this[showFindsKey] ?: true,
            showPlaces = this[showPlacesKey] ?: true,
            showAreas = this[showAreasKey] ?: true,
            cacheTiles = this[cacheTilesKey] ?: true,
            cacheExcluded = cacheExcluded,
        )
    }
}
