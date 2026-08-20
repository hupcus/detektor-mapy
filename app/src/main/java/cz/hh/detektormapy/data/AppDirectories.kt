package cz.hh.detektormapy.data

import android.content.Context
import java.io.File

/**
 * The app's on-disk layout.
 *
 * Everything lives under the app-specific external directory
 * (`Android/data/cz.hh.detektormapy/files/...`) because PLAN.md section 7 point 4 expects the
 * user to drop PMTiles archives in there over USB or Syncthing. If external storage is
 * unavailable (unmounted, emulated-only device) the internal `filesDir` is used instead, so no
 * call site ever has to deal with a null directory.
 */
class AppDirectories(private val context: Context) {

    /** External app-specific root, falling back to internal storage when it is not mounted. */
    val root: File
        get() = ensure(context.getExternalFilesDir(null) ?: context.filesDir)

    /** PMTiles / MBTiles archives and `layers.json`. Populated by the user over USB. */
    val layersDir: File get() = ensure(File(root, LAYERS))

    /** JPEGs captured for finds. */
    val findsPhotoDir: File get() = ensure(File(root, PHOTOS))

    /** Generated export zips. */
    val exportsDir: File get() = ensure(File(root, EXPORTS))

    /** GPX files flushed from finished tracks. */
    val tracksDir: File get() = ensure(File(root, TRACKS))

    /** Scratch space for downloaded / rendered tiles; safe to delete at any time. */
    val tilesCacheDir: File get() = ensure(File(context.cacheDir, TILES))

    /** The layer catalogue described in PLAN.md section 4. */
    val layersCatalogFile: File get() = File(layersDir, LAYERS_JSON)

    /**
     * Resolves a [cz.hh.detektormapy.map.LayerDef.source] relative to [layersDir].
     *
     * `layers.json` is meant to be hand-edited and lives on external storage, where on API
     * 26–29 any app holding WRITE_EXTERNAL_STORAGE can reach it. A `source` of
     * `"../../databases/detektormapy.db"` must therefore not be able to walk out of the layers
     * directory, so the resolved path is checked for containment and anything escaping is
     * refused rather than opened.
     *
     * @throws IllegalArgumentException when [relativePath] escapes [layersDir]
     */
    fun layerFile(relativePath: String): File {
        val base = layersDir.canonicalFile
        val resolved = File(base, relativePath).canonicalFile
        require(
            resolved.path == base.path ||
                resolved.path.startsWith(base.path + File.separator),
        ) {
            "Cesta vrstvy vede mimo adresář layers: $relativePath"
        }
        return resolved
    }

    /** Creates [dir] if needed; returns it either way so callers never see a null. */
    private fun ensure(dir: File): File {
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        return dir
    }

    companion object {
        const val LAYERS = "layers"
        const val PHOTOS = "photos"
        const val EXPORTS = "exports"
        const val TRACKS = "tracks"
        const val TILES = "tiles"
        const val LAYERS_JSON = "layers.json"
    }
}
