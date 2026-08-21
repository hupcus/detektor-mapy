package cz.hh.detektormapy.net

import android.util.Log
import cz.hh.detektormapy.BuildConfig
import cz.hh.detektormapy.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of asking GitHub whether a newer release exists. */
sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus

    data class Available(val version: String, val url: String, val notes: String?) : UpdateStatus

    /** The check could not be made -- no signal, GitHub down, rate limited. Never an error dialog. */
    data object Unavailable : UpdateStatus
}

/**
 * Asks GitHub Releases whether a newer APK is out.
 *
 * The app is installed by hand from a file, so nothing updates it on its own -- without this
 * the only way to learn about a fix is to think of checking the repository. The check is
 * explicitly **on demand**: nothing polls in the background, because a tool that promises it
 * sends nothing anywhere should not quietly talk to a server on a timer either.
 *
 * It only ever *reports*. Downloading and installing stays with the user, in the browser, where
 * they can see what they are getting.
 */
@Singleton
class UpdateChecker @Inject constructor(@param:IoDispatcher private val io: CoroutineDispatcher) {

    suspend fun check(installedVersion: String = BuildConfig.VERSION_NAME): UpdateStatus = withContext(io) {
        val body = fetchLatestRelease() ?: return@withContext UpdateStatus.Unavailable
        runCatching {
            val json = JSONObject(body)
            // draft/prerelease are skipped: a tag that exists is not the same as a release the
            // author is telling people to install.
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                return@withContext UpdateStatus.UpToDate
            }
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
                ?: return@withContext UpdateStatus.Unavailable
            val url = json.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_URL
            if (!AppVersion.isNewer(tag, installedVersion)) return@withContext UpdateStatus.UpToDate
            UpdateStatus.Available(
                version = tag.removePrefix("v"),
                url = url,
                notes = json.optString("body").takeIf { it.isNotBlank() },
            )
        }.getOrElse {
            Log.w(TAG, "Odpověď GitHubu nešla přečíst", it)
            UpdateStatus.Unavailable
        }
    }

    private fun fetchLatestRelease(): String? {
        val host = PoliteHttp.hostOf(API_URL)
        return PoliteHttp.onHost(host) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    PoliteHttp.identify(this)
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val status = connection.responseCode
                // GitHub rate-limits anonymous callers hard; 403 here means "later", not "broken".
                if (status == 429 || status == 403 || status == 503) {
                    PoliteHttp.noteRejected(host, PoliteHttp.parseRetryAfter(connection.getHeaderField("Retry-After")))
                    return@onHost null
                }
                PoliteHttp.noteAccepted(host)
                if (status !in 200..299) return@onHost null
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } catch (e: Exception) {
                Log.i(TAG, "Kontrola aktualizací není dostupná: ${e.message}")
                null
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"

        const val REPO_URL = "https://github.com/hupcus/detektor-mapy"
        const val RELEASES_URL = "$REPO_URL/releases/latest"
        private const val API_URL = "https://api.github.com/repos/hupcus/detektor-mapy/releases/latest"
        private const val TIMEOUT_MS = 8_000
    }
}
