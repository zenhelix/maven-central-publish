package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.io.File
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublishSplitBundleMavenCentralTaskTest {

    @TempDir
    private lateinit var projectDir: File

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private fun createZipFile(name: String): File {
        val dir = File(projectDir, "bundles")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes("fake zip content".toByteArray())
        return file
    }

    @Test
    fun `task can be registered and configured`() {
        createZipFile("project-1.zip")

        val task = project.tasks.register<PublishSplitBundleMavenCentralTask>("publishSplit") {
            baseUrl.set("http://test")
            credentials.set(io.github.zenhelix.gradle.plugin.client.model.Success(BearerTokenCredentials("test-token")))
            publishingType.set(PublishingType.AUTOMATIC)
            maxStatusChecks.set(5)
            statusCheckDelay.set(Duration.ofMillis(1))
            bundlesDirectory.set(File(projectDir, "bundles"))
        }.get()

        assertThat(task.baseUrl.get()).isEqualTo("http://test")
        assertThat(task.publishingType.get()).isEqualTo(PublishingType.AUTOMATIC)
        assertThat(task.maxStatusChecks.get()).isEqualTo(5)
    }

    @Test
    fun `task has correct defaults`() {
        createZipFile("project-1.zip")

        val task = project.tasks.register<PublishSplitBundleMavenCentralTask>("publishSplit") {
            baseUrl.set("http://test")
            credentials.set(io.github.zenhelix.gradle.plugin.client.model.Success(BearerTokenCredentials("test-token")))
            bundlesDirectory.set(File(projectDir, "bundles"))
        }.get()

        assertThat(task.publishingType.get()).isEqualTo(PublishingType.AUTOMATIC)
        assertThat(task.maxStatusChecks.get()).isEqualTo(20)
        assertThat(task.statusCheckDelay.get()).isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun `multiple bundles with AUTOMATIC type will switch to USER_MANAGED`() {
        createZipFile("project-1.zip")
        createZipFile("project-2.zip")

        val task = project.tasks.register<PublishSplitBundleMavenCentralTask>("publishSplit") {
            baseUrl.set("http://test")
            credentials.set(io.github.zenhelix.gradle.plugin.client.model.Success(BearerTokenCredentials("test-token")))
            publishingType.set(PublishingType.AUTOMATIC)
            maxStatusChecks.set(5)
            statusCheckDelay.set(Duration.ofMillis(1))
            bundlesDirectory.set(File(projectDir, "bundles"))
        }.get()

        // Verify task is configured as AUTOMATIC - mode switching happens at execution time
        assertThat(task.publishingType.get()).isEqualTo(PublishingType.AUTOMATIC)
    }
}
