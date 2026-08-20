package cz.hh.detektormapy.ui.finds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.ui.map.MarkerIcons
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Small building blocks shared by the finds gallery, the detail and the capture flow.
//
// They live in one file on purpose: every one of them is a handful of lines and they only ever
// make sense together, so keeping them next to each other makes the three screens read like
// layout instead of like widget plumbing.

/** Czech locale used for every date the user reads; the app is single-user and Czech-only. */
private val CS: Locale = Locale.forLanguageTag("cs")

private val dayFormat = SimpleDateFormat("d.M.yyyy", CS)
private val dayTimeFormat = SimpleDateFormat("d.M.yyyy HH:mm", CS)

/** `12.8.2026` -- the compact form used on gallery cards. */
fun formatFindDate(millis: Long): String = dayFormat.format(Date(millis))

/** `12.8.2026 07:41` -- the full form used in the detail header. */
fun formatFindDateTime(millis: Long): String = dayTimeFormat.format(Date(millis))

/** `50,08123° N, 14,42076° E` -- degrees only, which is what a phone GPS honestly delivers. */
fun formatCoordinates(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return String.format(CS, "%.5f° %s, %.5f° %s", abs(lat), ns, abs(lon), ew)
}

/** `±4 m`, or a dash when the provider did not report accuracy. */
fun formatAccuracy(accuracyM: Float?): String = if (accuracyM == null) "neznámá" else "±${accuracyM.roundToInt()} m"

/** Pin colours are reused from the map so a card and its pin are never a different colour. */
fun categoryColor(category: FindCategory): Color = Color(MarkerIcons.colorOf(category))

/**
 * Coil accepts a `String` only when it parses as a uri with a scheme. Photos captured by the
 * app are stored as absolute paths, so they have to be handed over as a [File] instead.
 */
internal fun photoModel(uri: String): Any = if (uri.startsWith("/")) File(uri) else uri

/** Rounded, colour-coded category label. */
@Composable
fun CategoryChip(category: FindCategory, modifier: Modifier = Modifier) {
    val color = categoryColor(category)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.18f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = category.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** The favourite toggle. Filled star = kept, outline = ordinary find. */
@Composable
fun FavoriteStar(favorite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (favorite) "Odebrat z oblíbených" else "Přidat do oblíbených",
            tint = if (favorite) {
                Color(0xFFFFC107)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The photo of a find, or -- when there is none -- a category-coloured plate with the category
 * marker. A missing photo must still look deliberate, because the "Bez fotky" path is a
 * first-class way of logging a find.
 */
@Composable
fun PhotoThumbnail(
    uri: String?,
    category: FindCategory,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val color = categoryColor(category)
    Box(
        modifier = modifier.background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        if (uri == null) {
            Text(text = category.marker, fontSize = 34.sp)
        } else {
            AsyncImage(
                model = photoModel(uri),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Full-height "nothing here" panel with a hint that tells the user what to do next. */
@Composable
fun FindsEmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "🪙", fontSize = 48.sp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

/** Deleting a find also deletes its photos, so it always asks first. */
@Composable
fun ConfirmDeleteDialog(findTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smazat nález?") },
        text = {
            Text(
                if (findTitle.isBlank()) {
                    "Nález i jeho fotky budou nenávratně smazány."
                } else {
                    "Nález „$findTitle\" i jeho fotky budou nenávratně smazány."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Smazat") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    )
}
