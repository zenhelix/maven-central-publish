package io.github.zenhelix.gradle.plugin.extension

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PomExtensionTest {

    @Test
    fun `apache2 license preset returns correct values`() {
        val license = LicensePresets.apache2()
        assertThat(license.name).isEqualTo("The Apache License, Version 2.0")
        assertThat(license.url).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0.txt")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `mit license preset returns correct values`() {
        val license = LicensePresets.mit()
        assertThat(license.name).isEqualTo("MIT License")
        assertThat(license.url).isEqualTo("https://opensource.org/licenses/MIT")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `bsd2 license preset returns correct values`() {
        val license = LicensePresets.bsd2()
        assertThat(license.name).isEqualTo("BSD 2-Clause License")
        assertThat(license.url).isEqualTo("https://opensource.org/licenses/BSD-2-Clause")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `bsd3 license preset returns correct values`() {
        val license = LicensePresets.bsd3()
        assertThat(license.name).isEqualTo("BSD 3-Clause License")
        assertThat(license.url).isEqualTo("https://opensource.org/licenses/BSD-3-Clause")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `fromGithub generates correct SCM URLs`() {
        val scm = ScmDefaults.fromGithub("zenhelix", "maven-central-publish")
        assertThat(scm.connection).isEqualTo("scm:git:git://github.com/zenhelix/maven-central-publish.git")
        assertThat(scm.developerConnection).isEqualTo("scm:git:ssh://github.com/zenhelix/maven-central-publish.git")
        assertThat(scm.url).isEqualTo("https://github.com/zenhelix/maven-central-publish")
    }
}
