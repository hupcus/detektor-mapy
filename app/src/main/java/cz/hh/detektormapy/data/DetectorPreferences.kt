package cz.hh.detektormapy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.hh.detektormapy.data.model.Terrain
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.detectorDataStore by preferencesDataStore(name = "detector")

/**
 * The one thing the advisor remembers between visits: which terrain was picked last.
 *
 * It exists because the app genuinely cannot tell forest from meadow -- there is no offline
 * land-cover layer, and guessing from coordinates would be inventing data. Remembering the last
 * answer is the honest substitute: on a second outing to the same kind of ground the screen is
 * already right, and when it is wrong the user taps one chip.
 */
@Singleton
class DetectorPreferences @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val terrainKey = stringPreferencesKey("last_terrain")

    /** Last terrain the user selected, or `null` before they ever have. */
    val lastTerrain: Flow<Terrain?> = context.detectorDataStore.data
        .catch { emit(emptyPreferences()) } // A corrupt or unreadable store must not take the screen down.
        .map { prefs -> prefs[terrainKey]?.let { name -> Terrain.entries.firstOrNull { it.name == name } } }

    suspend fun setLastTerrain(terrain: Terrain) {
        context.detectorDataStore.edit { it[terrainKey] = terrain.name }
    }
}
