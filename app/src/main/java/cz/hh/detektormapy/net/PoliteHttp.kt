package cz.hh.detektormapy.net

import android.util.Log
import cz.hh.detektormapy.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The single door every outgoing HTTP request goes through.
 *
 * Why it exists: the app is about to be handed to strangers, and the servers behind its layers
 * are public institutions that publish maps as a service to the public, not as a CDN. Three
 * things make the difference between a welcome client and one that gets blocked:
 *
 * 1. **Identity.** Every request carries `DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`,
 *    so an operator looking at a log can tell what we are and where to complain.
 * 2. **A ceiling per host.** Panning a map fans out into dozens of tile requests at once, and a
 *    warped tile alone pulls a 3x3 neighbourhood. Without a cap, one gesture is a small burst
 *    against a single server. [MAX_CONCURRENT_PER_HOST] parallel requests per hostname is
 *    roughly what a browser allows itself and is plenty for a smooth map.
 * 3. **Backing off when told to.** A 429 or 503 answered with an immediate retry is how a client
 *    turns a busy server into a dead one. After one, the whole host is put on ice for an
 *    exponentially growing window and every request to it fails fast without touching the socket.
 *
 * Failing fast is deliberate everywhere here: a tile that cannot be fetched right now simply is
 * not drawn, which is already the app's behaviour offline.
 */
object PoliteHttp {

    private const val TAG = "PoliteHttp"

    /** Identifies the app in the logs of every service it touches. */
    val userAgent: String = "DetektorMapy/${BuildConfig.VERSION_NAME} (github.com/hupcus/detektor-mapy)"

    /** Parallel requests allowed against one hostname. */
    const val MAX_CONCURRENT_PER_HOST = 4

    /**
     * How long a request waits for a slot before giving up. Long enough to ride out a burst of
     * panning, short enough that a stalled host cannot pin a tile-server worker for a minute.
     */
    const val SLOT_WAIT_MS = 10_000L

    /** First cool-off after a 429/503; doubles per consecutive rejection. */
    const val BACKOFF_BASE_MS = 2_000L

    /** Cool-off ceiling. Beyond a few minutes the user has long since put the phone away. */
    const val BACKOFF_MAX_MS = 5 * 60_000L

    /** Overridable clock so backoff windows can be tested without sleeping. */
    @Volatile
    internal var nowMs: () -> Long = System::currentTimeMillis

    private class HostState {
        val slots = Semaphore(MAX_CONCURRENT_PER_HOST, true)
        val consecutiveRejections = AtomicInteger(0)

        @Volatile
        var blockedUntilMs = 0L
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    private val downloadedTiles = AtomicLong(0)
    private val downloadedBytes = AtomicLong(0)

    // ------------------------------------------------------------------ request decoration

    /** Stamps the identity headers every request must carry. */
    fun identify(connection: HttpURLConnection) {
        connection.setRequestProperty("User-Agent", userAgent)
    }

    // ------------------------------------------------------------------ gating

    /** Hostname of [url], or an empty string when it cannot be parsed (treated as one bucket). */
    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase()

    /** True while [host] is inside a backoff window and must not be contacted. */
    fun isBackingOff(host: String): Boolean {
        val state = hosts[host] ?: return false
        return nowMs() < state.blockedUntilMs
    }

    /**
     * Runs [block] holding one of [host]'s slots, or returns null when the host is cooling off or
     * no slot became free within [SLOT_WAIT_MS].
     */
    fun <T> onHost(host: String, block: () -> T): T? {
        val state = hosts.computeIfAbsent(host) { HostState() }
        if (nowMs() < state.blockedUntilMs) {
            Log.d(TAG, "$host je v backoffu, požadavek přeskočen")
            return null
        }
        if (!state.slots.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.d(TAG, "$host: nevolný slot do ${SLOT_WAIT_MS}ms")
            return null
        }
        return try {
            block()
        } finally {
            state.slots.release()
        }
    }

    /**
     * Records a "slow down" answer and opens (or extends) the host's cool-off window.
     *
     * @param retryAfterSeconds value of the `Retry-After` header when the server sent one; it
     *   wins whenever it asks for longer than our own schedule would.
     */
    fun noteRejected(host: String, retryAfterSeconds: Long? = null) {
        val state = hosts.computeIfAbsent(host) { HostState() }
        val rejections = state.consecutiveRejections.incrementAndGet()
        val ours = backoffDelayMs(rejections)
        val theirs = retryAfterSeconds?.let { (it * 1_000L).coerceAtMost(BACKOFF_MAX_MS) } ?: 0L
        val delay = maxOf(ours, theirs)
        state.blockedUntilMs = nowMs() + delay
        Log.w(TAG, "$host odmítl obsluhu (${rejections}x), pauza ${delay}ms")
    }

    /** Clears a host's rejection streak after a request it answered normally. */
    fun noteAccepted(host: String) {
        val state = hosts[host] ?: return
        if (state.consecutiveRejections.get() != 0) state.consecutiveRejections.set(0)
    }

    /** Exponential schedule, exposed as a pure function so the growth curve is testable. */
    fun backoffDelayMs(consecutiveRejections: Int): Long {
        if (consecutiveRejections <= 0) return 0L
        // Shifting past 62 would overflow; the cap bites long before that anyway.
        val exponent = (consecutiveRejections - 1).coerceAtMost(20)
        val raw = BACKOFF_BASE_MS shl exponent
        return if (raw <= 0L) BACKOFF_MAX_MS else raw.coerceAtMost(BACKOFF_MAX_MS)
    }

    /** Parses `Retry-After` in its delta-seconds form; the HTTP-date form is ignored. */
    fun parseRetryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    // ------------------------------------------------------------------ local usage counter

    /**
     * Counts one tile actually pulled over the network.
     *
     * This never leaves the device -- it exists so the user can see the load they put on the
     * services they are borrowing, which is the only honest substitute for telemetry we refuse
     * to collect.
     */
    fun recordDownload(bytes: Int) {
        downloadedTiles.incrementAndGet()
        downloadedBytes.addAndGet(bytes.toLong())
    }

    /** Tiles and bytes downloaded since [resetUsage] was last called. */
    fun usage(): Usage = Usage(downloadedTiles.get(), downloadedBytes.get())

    /** Seeds the counters, e.g. from the value persisted for today. */
    fun seedUsage(tiles: Long, bytes: Long) {
        downloadedTiles.set(tiles)
        downloadedBytes.set(bytes)
    }

    fun resetUsage() = seedUsage(0, 0)

    data class Usage(val tiles: Long, val bytes: Long)

    /** Test hook: forgets every host's slots and backoff window. */
    internal fun resetHostsForTest() {
        hosts.clear()
    }
}
