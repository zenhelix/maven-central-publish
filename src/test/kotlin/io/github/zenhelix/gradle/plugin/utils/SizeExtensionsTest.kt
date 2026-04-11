package io.github.zenhelix.gradle.plugin.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SizeExtensionsTest {

    @Test
    fun `Int megabytes returns correct byte count`() {
        assertThat(1.megabytes).isEqualTo(1L * 1024 * 1024)
        assertThat(256.megabytes).isEqualTo(256L * 1024 * 1024)
    }

    @Test
    fun `Int gigabytes returns correct byte count`() {
        assertThat(1.gigabytes).isEqualTo(1L * 1024 * 1024 * 1024)
        assertThat(2.gigabytes).isEqualTo(2L * 1024 * 1024 * 1024)
    }

    @Test
    fun `Long megabytes returns correct byte count`() {
        assertThat(512L.megabytes).isEqualTo(512L * 1024 * 1024)
    }

    @Test
    fun `Long gigabytes returns correct byte count`() {
        assertThat(1L.gigabytes).isEqualTo(1L * 1024 * 1024 * 1024)
    }
}
