package cz.hh.detektormapy.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Version comparison, which fails silently when it is wrong: too eager and the app nags about
 * an update that does not exist, too shy and it never mentions the release that fixes the bug
 * the user is living with.
 */
class AppVersionTest {

    @Test
    fun `parses the shapes release tags actually take`() {
        assertThat(AppVersion.parse("v0.5.0")).containsExactly(0, 5, 0).inOrder()
        assertThat(AppVersion.parse("0.5.0")).containsExactly(0, 5, 0).inOrder()
        assertThat(AppVersion.parse("V1.2.3.4")).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(AppVersion.parse("1.2.3-beta1")).containsExactly(1, 2, 3).inOrder()
        assertThat(AppVersion.parse("1.2.3+build9")).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `garbage parses to something rather than throwing`() {
        assertThat(AppVersion.parse(null)).isEmpty()
        assertThat(AppVersion.parse("  ")).isEmpty()
        // A tag nobody meant to publish must not take the app down with it.
        assertThat(AppVersion.parse("nightly")).containsExactly(0)
    }

    @Test
    fun `newer releases are recognised`() {
        assertThat(AppVersion.isNewer("v0.6.0", "0.5.0")).isTrue()
        assertThat(AppVersion.isNewer("v0.5.1", "0.5.0")).isTrue()
        assertThat(AppVersion.isNewer("v1.0.0", "0.9.9")).isTrue()
    }

    @Test
    fun `the version you already run is not an update`() {
        assertThat(AppVersion.isNewer("v0.5.0", "0.5.0")).isFalse()
        assertThat(AppVersion.isNewer("0.5.0", "v0.5.0")).isFalse()
    }

    @Test
    fun `someone running ahead of the last release is never told to downgrade`() {
        // Anyone who built it themselves is here, and every one of them would resent it.
        assertThat(AppVersion.isNewer("v0.5.0", "0.6.0")).isFalse()
    }

    @Test
    fun `missing components count as zero, not as newer`() {
        assertThat(AppVersion.isNewer("v0.5", "0.5.0")).isFalse()
        assertThat(AppVersion.isNewer("v0.5.0", "0.5")).isFalse()
        assertThat(AppVersion.isNewer("v0.5.1", "0.5")).isTrue()
    }

    @Test
    fun `a version that cannot be read is never an update`() {
        assertThat(AppVersion.isNewer(null, "0.5.0")).isFalse()
        assertThat(AppVersion.isNewer("v0.6.0", null)).isFalse()
        assertThat(AppVersion.isNewer("", "0.5.0")).isFalse()
    }

    @Test
    fun `double digit components sort as numbers, not as text`() {
        assertThat(AppVersion.isNewer("v0.10.0", "0.9.0")).isTrue()
        assertThat(AppVersion.isNewer("v0.9.0", "0.10.0")).isFalse()
    }
}
