package cz.hh.detektormapy.map

import cz.hh.detektormapy.map.pmtiles.TileArchive

/**
 * Wraps an online [TileArchive] so that every tile it fetches is kept forever.
 *
 * Order of lookup is **cache first, network second**, which is the inversion that turns the app
 * from "works offline if you are lucky" into "what you have seen, you have". The previous
 * behaviour asked the network every single time and only fell back to disk when the request
 * failed, so a walk through an area already visited still burned a request (and several seconds
 * of timeout) per tile with a weak signal.
 *
 * The consequence to be aware of: a cached tile is never refreshed on its own. For the historical
 * maps this app exists for that is exactly right -- II. military mapping is not going to be
 * resurveyed -- and for ortophoto, which does get updated, "Správa úložiště" gives the user a
 * button to drop that layer's cache and pull it again.
 *
 * It sits **below** [CalibratedTileComposer] by construction, because the composer asks its
 * archive for source tiles. That is what keeps calibration out of the cache: the file holds the
 * publisher's pixels, and the alignment is applied on every serve.
 */
class CachedTileArchive(
    private val delegate: TileArchive,
    private val layerId: String,
    private val cache: TileCacheStore,
) : TileArchive by delegate {

    override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        cache.read(layerId, z, x, y)?.let { return it }
        val fresh = delegate.getTile(z, x, y) ?: return null
        if (fresh.size >= MIN_CACHEABLE_BYTES) {
            cache.write(layerId, z, x, y, fresh, sniffFormat(fresh))
        }
        return fresh
    }

    companion object {
        /**
         * Format for the cache's MBTiles `metadata`, read off the bytes rather than off
         * `contentType`.
         *
         * The declared type is a guess: the catalogue infers it from the URL suffix, and
         * chartae-antiquae serves JPEG from a template with no extension at all -- so trusting it
         * would stamp `format=png` onto an archive full of JPEGs and mislead every desktop tool
         * that opened it. The magic bytes are not a guess.
         */
        internal fun sniffFormat(bytes: ByteArray): String {
            if (bytes.size < 4) return "png"
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpg"
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return "webp"
            return "png"
        }

        /**
         * Below this size a response is a fully transparent "nothing here" tile -- ArcGIS answers
         * with ~200 bytes outside a service extent, chartae-antiquae with ~334. Those must not be
         * written to disk: today's gap in coverage may be filled tomorrow, and a cached blank
         * would hide the new data forever, because nothing ever expires a cache entry.
         */
        const val MIN_CACHEABLE_BYTES = 400
    }
}
