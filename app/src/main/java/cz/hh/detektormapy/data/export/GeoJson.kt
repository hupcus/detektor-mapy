package cz.hh.detektormapy.data.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Hand-rolled GeoJSON helpers.
 *
 * The app deliberately carries no GeoJSON dependency: everything is built from and read back into
 * `kotlinx.serialization` JSON trees. All readers are lenient -- a malformed or missing property
 * yields the supplied default rather than an exception, because an export written by another tool
 * must never take the import down.
 */
internal object GeoJson {

    fun featureCollection(features: List<JsonObject>): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }

    fun pointFeature(lon: Double, lat: Double, altitude: Double?, properties: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "Feature")
            put("geometry", pointGeometry(lon, lat, altitude))
            put("properties", properties)
        }

    fun feature(geometry: JsonElement, properties: JsonObject): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", properties)
    }

    private fun pointGeometry(lon: Double, lat: Double, altitude: Double?): JsonObject = buildJsonObject {
        put("type", "Point")
        put(
            "coordinates",
            buildJsonArray {
                add(JsonPrimitive(lon))
                add(JsonPrimitive(lat))
                if (altitude != null) add(JsonPrimitive(altitude))
            },
        )
    }

    /** Features of a collection; an unparseable document yields an empty list. */
    fun features(root: JsonElement?): List<JsonObject> {
        val obj = root as? JsonObject ?: return emptyList()
        val array = obj["features"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }
    }

    fun properties(feature: JsonObject): JsonObject = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())

    fun geometry(feature: JsonObject): JsonObject? = feature["geometry"] as? JsonObject

    /** `[lon, lat, alt?]` of a Point geometry, or null when the geometry is not a usable point. */
    fun pointCoordinates(feature: JsonObject): Triple<Double, Double, Double?>? {
        val geometry = geometry(feature) ?: return null
        val coords = geometry["coordinates"] as? JsonArray ?: return null
        if (coords.size < 2) return null
        val lon = (coords[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val lat = (coords[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val alt = coords.getOrNull(2)?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        return Triple(lon, lat, alt)
    }
}

internal fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonPrimitive
}

internal fun JsonObject.stringOrNull(key: String): String? = primitiveOrNull(key)?.contentOrNull

internal fun JsonObject.stringOr(key: String, fallback: String): String = stringOrNull(key) ?: fallback

internal fun JsonObject.longOrNull(key: String): Long? = stringOrNull(key)?.toLongOrNull()

internal fun JsonObject.longOr(key: String, fallback: Long): Long = longOrNull(key) ?: fallback

internal fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toIntOrNull()

internal fun JsonObject.doubleOrNull(key: String): Double? = stringOrNull(key)?.toDoubleOrNull()

internal fun JsonObject.doubleOr(key: String, fallback: Double): Double = doubleOrNull(key) ?: fallback

internal fun JsonObject.floatOrNull(key: String): Float? = stringOrNull(key)?.toFloatOrNull()

internal fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean = when (stringOrNull(key)?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> fallback
}
