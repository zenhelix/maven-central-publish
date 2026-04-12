package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.Failure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PomValidationTest {

    @Test
    fun `valid POM passes validation`() {
        val result = validatePomFields(
            name = "lib", description = "desc", url = "https://example.com",
            hasLicenses = true, hasDevelopers = true, hasScm = true
        )
        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `missing name fails validation`() {
        val result = validatePomFields(
            name = null, description = "desc", url = "https://example.com",
            hasLicenses = true, hasDevelopers = true, hasScm = true
        )
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error.missingFields).contains("name")
    }

    @Test
    fun `multiple missing fields reported together`() {
        val result = validatePomFields(
            name = null, description = null, url = null,
            hasLicenses = false, hasDevelopers = false, hasScm = false
        )
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error.missingFields).containsExactlyInAnyOrder(
            "name", "description", "url", "licenses", "developers", "scm"
        )
    }

    @Test
    fun `empty string name fails validation`() {
        val result = validatePomFields(
            name = "", description = "desc", url = "https://example.com",
            hasLicenses = true, hasDevelopers = true, hasScm = true
        )
        assertThat(result).isInstanceOf(Failure::class.java)
    }
}
