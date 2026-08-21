package cz.hh.detektormapy.net

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The rules that keep the app a welcome guest on public map services: identify yourself, do not
 * open more than a handful of connections to one host at a time, and go quiet when told to.
 */
class PoliteHttpTest {

    private var virtualNow = 1_000_000L

    @Before
    fun setUp() {
        PoliteHttp.resetHostsForTest()
        PoliteHttp.resetUsage()
        PoliteHttp.nowMs = { virtualNow }
    }

    @After
    fun tearDown() {
        PoliteHttp.nowMs = System::currentTimeMillis
        PoliteHttp.resetHostsForTest()
        PoliteHttp.resetUsage()
    }

    @Test
    fun `user agent names the app and points at the repository`() {
        assertThat(PoliteHttp.userAgent).startsWith("DetektorMapy/")
        assertThat(PoliteHttp.userAgent).contains("github.com/hupcus/detektor-mapy")
    }

    @Test
    fun `hostOf survives the URL shapes the catalogue actually contains`() {
        assertThat(PoliteHttp.hostOf("https://chartae-antiquae.cz/TMS/Military2/12/2200/1400"))
            .isEqualTo("chartae-antiquae.cz")
        assertThat(PoliteHttp.hostOf("https://ags.cuzk.gov.cz/arcgis/rest/services/x/MapServer/export?f=image"))
            .isEqualTo("ags.cuzk.gov.cz")
        assertThat(PoliteHttp.hostOf("not a url")).isEmpty()
    }

    @Test
    fun `backoff grows exponentially and stops at the ceiling`() {
        assertThat(PoliteHttp.backoffDelayMs(0)).isEqualTo(0)
        assertThat(PoliteHttp.backoffDelayMs(1)).isEqualTo(PoliteHttp.BACKOFF_BASE_MS)
        assertThat(PoliteHttp.backoffDelayMs(2)).isEqualTo(2 * PoliteHttp.BACKOFF_BASE_MS)
        assertThat(PoliteHttp.backoffDelayMs(3)).isEqualTo(4 * PoliteHttp.BACKOFF_BASE_MS)
        assertThat(PoliteHttp.backoffDelayMs(30)).isEqualTo(PoliteHttp.BACKOFF_MAX_MS)
    }

    @Test
    fun `a rejected host is skipped entirely until its window passes`() {
        val host = "geoportal.npu.cz"
        PoliteHttp.noteRejected(host)
        assertThat(PoliteHttp.isBackingOff(host)).isTrue()

        // While cooling off, no request is even attempted -- that is the point of the pause.
        val attempted = AtomicInteger(0)
        assertThat(PoliteHttp.onHost(host) { attempted.incrementAndGet() }).isNull()
        assertThat(attempted.get()).isEqualTo(0)

        virtualNow += PoliteHttp.BACKOFF_BASE_MS + 1
        assertThat(PoliteHttp.isBackingOff(host)).isFalse()
        assertThat(PoliteHttp.onHost(host) { attempted.incrementAndGet() }).isEqualTo(1)
    }

    @Test
    fun `repeated rejections lengthen the pause, a normal answer resets it`() {
        val host = "example.test"
        PoliteHttp.noteRejected(host)
        PoliteHttp.noteRejected(host)
        PoliteHttp.noteRejected(host)
        virtualNow += 4 * PoliteHttp.BACKOFF_BASE_MS - 1
        assertThat(PoliteHttp.isBackingOff(host)).isTrue()

        virtualNow += 2
        PoliteHttp.onHost(host) { PoliteHttp.noteAccepted(host) }
        PoliteHttp.noteRejected(host)
        // Streak forgotten, so the next pause is the short one again rather than the long one.
        assertThat(PoliteHttp.isBackingOff(host)).isTrue()
        virtualNow += PoliteHttp.BACKOFF_BASE_MS + 1
        assertThat(PoliteHttp.isBackingOff(host)).isFalse()
    }

    @Test
    fun `Retry-After wins when the server asks for longer than our schedule`() {
        val host = "slow.test"
        PoliteHttp.noteRejected(host, retryAfterSeconds = 120)
        virtualNow += PoliteHttp.BACKOFF_BASE_MS * 4
        assertThat(PoliteHttp.isBackingOff(host)).isTrue()
        virtualNow += 120_000
        assertThat(PoliteHttp.isBackingOff(host)).isFalse()
    }

    @Test
    fun `Retry-After parsing accepts delta-seconds and refuses anything else`() {
        assertThat(PoliteHttp.parseRetryAfter("30")).isEqualTo(30)
        assertThat(PoliteHttp.parseRetryAfter(" 5 ")).isEqualTo(5)
        assertThat(PoliteHttp.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull()
        assertThat(PoliteHttp.parseRetryAfter(null)).isNull()
        assertThat(PoliteHttp.parseRetryAfter("-1")).isNull()
    }

    @Test
    fun `no more than four requests hit one host at a time`() {
        val host = "cuzk.test"
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CountDownLatch(1)
        val started = CountDownLatch(PoliteHttp.MAX_CONCURRENT_PER_HOST)
        val finished = CountDownLatch(8)

        repeat(8) {
            Thread {
                PoliteHttp.onHost(host) {
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, now) }
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    inFlight.decrementAndGet()
                }
                finished.countDown()
            }.start()
        }

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(peak.get()).isEqualTo(PoliteHttp.MAX_CONCURRENT_PER_HOST)
        release.countDown()
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(peak.get()).isAtMost(PoliteHttp.MAX_CONCURRENT_PER_HOST)
    }

    @Test
    fun `different hosts do not share a ceiling`() {
        val release = CountDownLatch(1)
        val bothIn = CountDownLatch(2)
        listOf("a.test", "b.test").forEach { host ->
            repeat(PoliteHttp.MAX_CONCURRENT_PER_HOST) {
                Thread { PoliteHttp.onHost(host) { release.await(2, TimeUnit.SECONDS) } }.start()
            }
        }
        // A fifth request to each host would block; a first request to a third one must not.
        Thread {
            PoliteHttp.onHost("c.test") { bothIn.countDown() }
            PoliteHttp.onHost("d.test") { bothIn.countDown() }
        }.start()
        assertThat(bothIn.await(5, TimeUnit.SECONDS)).isTrue()
        release.countDown()
    }

    @Test
    fun `the local download counter adds up`() {
        PoliteHttp.recordDownload(1_000)
        PoliteHttp.recordDownload(2_500)
        assertThat(PoliteHttp.usage().tiles).isEqualTo(2)
        assertThat(PoliteHttp.usage().bytes).isEqualTo(3_500)

        PoliteHttp.seedUsage(tiles = 10, bytes = 99)
        assertThat(PoliteHttp.usage()).isEqualTo(PoliteHttp.Usage(10, 99))
    }
}
