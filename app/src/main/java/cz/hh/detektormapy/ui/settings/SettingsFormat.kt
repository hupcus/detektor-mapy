package cz.hh.detektormapy.ui.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formatting and sharing helpers shared by the settings, tracks, pre-flight and about screens.
 *
 * Sharing goes through [FileProvider] because Android 7+ refuses `file://` uris in an
 * `ACTION_SEND`; the authority matches the provider declared in the manifest.
 */
internal val CS_LOCALE: Locale = Locale.forLanguageTag("cs")

private const val TAG = "Settings"

internal fun formatDateTime(millis: Long?): String =
    if (millis == null) "—" else SimpleDateFormat("d. M. yyyy HH:mm", CS_LOCALE).format(Date(millis))

internal fun formatTime(millis: Long?): String =
    if (millis == null) "—" else SimpleDateFormat("HH:mm", CS_LOCALE).format(Date(millis))

/** `2 h 05 min`, or `12 min` for short walks. */
internal fun formatDuration(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    return if (hours > 0) {
        String.format(CS_LOCALE, "%d h %02d min", hours, minutes)
    } else {
        String.format(CS_LOCALE, "%d min", minutes)
    }
}

internal fun formatKm(meters: Double): String = String.format(CS_LOCALE, "%.2f km", meters / 1000.0)

internal fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(CS_LOCALE, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(CS_LOCALE, "%.0f MB", mb)
}

internal fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

/**
 * Offers [file] through the system share sheet. Returns false instead of throwing when the file
 * sits outside the paths declared in `@xml/file_paths` -- the caller then just shows a message.
 */
internal fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
    true
}.getOrElse {
    Log.w(TAG, "Sdílení souboru ${file.name} selhalo", it)
    false
}
