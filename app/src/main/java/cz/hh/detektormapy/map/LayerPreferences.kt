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
    )

    private val basemapKey = androidx.datastore.preferences.core.stringPreferencesKey("basemap")
    private val rotateKey = booleanPreferencesKey("rotate_with_compass")
    private val followKey = booleanPreferencesKey("follow_mode")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    private val showFindsKey = booleanPreferencesKey("show_finds")
    private val showPlacesKey = booleanPreferencesKey("show_places")
    private val showAreasKey = booleanPreferencesKey("show_areas")

    private fun visibleKey(id: String) = booleanPreferencesKey("vis_$id")
    private fun opacityKey(id: String) = floatPreferencesKey("op_$id")
    private fun orderKey(id: String) = intPreferencesKey("ord_$id")

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

    private fun Preferences.toState(): State {
        val visible = mutableMapOf<String, Boolean>()
        val opacity = mutableMapOf<String, Float>()
        val order = mutableMapOf<String, Int>()
        asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name.startsWith("vis_") -> (value as? Boolean)?.let { visible[name.removePrefix("vis_")] = it }
                name.startsWith("op_") -> (value as? Float)?.let { opacity[name.removePrefix("op_")] = it }
                name.startsWith("ord_") -> (value as? Int)?.let { order[name.removePrefix("ord_")] = it }
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
        )
    }
}
