package cz.hh.detektormapy.map

/**
 * Computes the persisted order values after moving one overlay by [delta] steps in the panel.
 *
 * [sortedOverlayIds] is the overlays exactly as the panel shows them (bottom-to-top; the
 * `layers` flow already sorts by the user's override with the catalog order as fallback).
 * The result renumbers *every* overlay to `index * 10`: two default layers often share the
 * same catalog `order`, so writing only the moved layer's value could land in a tie and
 * change nothing visible — normalising on first touch makes every later move deterministic.
 *
 * Returns null when the move is a no-op (unknown id, or already at the edge).
 */
fun reorderedOverlayOrders(sortedOverlayIds: List<String>, layerId: String, delta: Int): Map<String, Int>? {
    val from = sortedOverlayIds.indexOf(layerId)
    if (from < 0) return null
    val to = from + delta
    if (to < 0 || to > sortedOverlayIds.lastIndex || to == from) return null
    val moved = sortedOverlayIds.toMutableList().apply { add(to, removeAt(from)) }
    return moved.mapIndexed { index, id -> id to index * 10 }.toMap()
}
