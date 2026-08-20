package cz.hh.detektormapy.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.model.PlaceType

/**
 * Pin bitmaps generated at runtime.
 *
 * MapLibre can only render text in a `SymbolLayer` when the style provides a glyph endpoint,
 * which would mean a network dependency -- unacceptable for an offline-first field app. Drawing
 * the marker (shape + a single letter) into a `Bitmap` and registering it with `style.addImage`
 * sidesteps glyphs entirely and keeps everything working in airplane mode.
 */
object MarkerIcons {

    const val FIND_PREFIX = "find-"
    const val PLACE_PREFIX = "place-"
    const val ICON_FAVORITE = "find-favorite"

    private const val SIZE = 96
    private const val PIN_HEIGHT = 120

    fun findIconId(category: FindCategory) = "$FIND_PREFIX${category.name}"

    fun placeIconId(type: PlaceType) = "$PLACE_PREFIX${type.name}"

    fun all(density: Float): Map<String, Bitmap> = buildMap {
        FindCategory.entries.forEach { cat ->
            put(findIconId(cat), pin(colorOf(cat), letterOf(cat), density, diamond = false))
        }
        PlaceType.entries.forEach { type ->
            put(placeIconId(type), pin(colorOf(type), letterOf(type), density, diamond = true))
        }
        put(ICON_FAVORITE, pin(0xFFFFC107.toInt(), "★", density, diamond = false))
    }

    fun colorOf(category: FindCategory): Int = when (category) {
        FindCategory.MINCE -> 0xFFC9A227.toInt()
        FindCategory.KNOFLIK -> 0xFF9E9E9E.toInt()
        FindCategory.VOJENSKE -> 0xFF4A6B3F.toInt()
        FindCategory.SPONA -> 0xFF7E57C2.toInt()
        FindCategory.PRSTEN -> 0xFFD4AF37.toInt()
        FindCategory.NASTROJ -> 0xFF8C5A3C.toInt()
        FindCategory.SROT -> 0xFF616161.toInt()
        FindCategory.OSTATNI -> 0xFF546E7A.toInt()
    }

    fun colorOf(type: PlaceType): Int = when (type) {
        PlaceType.PLAN -> 0xFF1976D2.toInt()
        PlaceType.ZAJIMAVOST -> 0xFF00897B.toInt()
        PlaceType.ZAKAZ -> 0xFFB3261E.toInt()
        PlaceType.SRAZ -> 0xFF6D4C41.toInt()
        PlaceType.PARKOVANI -> 0xFF37474F.toInt()
    }

    private fun letterOf(category: FindCategory): String = when (category) {
        FindCategory.MINCE -> "M"
        FindCategory.KNOFLIK -> "K"
        FindCategory.VOJENSKE -> "V"
        FindCategory.SPONA -> "S"
        FindCategory.PRSTEN -> "P"
        FindCategory.NASTROJ -> "N"
        FindCategory.SROT -> "Š"
        FindCategory.OSTATNI -> "?"
    }

    private fun letterOf(type: PlaceType): String = when (type) {
        PlaceType.PLAN -> "P"
        PlaceType.ZAJIMAVOST -> "★"
        PlaceType.ZAKAZ -> "!"
        PlaceType.SRAZ -> "S"
        PlaceType.PARKOVANI -> "P"
    }

    private fun pin(color: Int, letter: String, density: Float, diamond: Boolean): Bitmap {
        val scale = density.coerceIn(1f, 3.5f)
        val w = (SIZE * scale / 2f).toInt().coerceAtLeast(24)
        val h = (PIN_HEIGHT * scale / 2f).toInt().coerceAtLeast(30)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = w * 0.07f
        }

        val cx = w / 2f
        val cy = w / 2f
        val r = w / 2f - stroke.strokeWidth

        val path = Path()
        if (diamond) {
            path.moveTo(cx, cy - r)
            path.lineTo(cx + r, cy)
            path.lineTo(cx, cy + r)
            path.lineTo(cx - r, cy)
            path.close()
        } else {
            path.addCircle(cx, cy, r, Path.Direction.CW)
        }
        // The needle that points at the actual coordinate.
        path.moveTo(cx - r * 0.35f, cy + r * 0.75f)
        path.lineTo(cx, h.toFloat() - stroke.strokeWidth)
        path.lineTo(cx + r * 0.35f, cy + r * 0.75f)
        path.close()

        canvas.drawPath(path, body)
        canvas.drawPath(path, stroke)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = r * 1.05f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val metrics = text.fontMetrics
        canvas.drawText(letter, cx, cy - (metrics.ascent + metrics.descent) / 2f, text)
        return bmp
    }
}
