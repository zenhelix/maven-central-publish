package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.GradleException
import io.github.zenhelix.gradle.plugin.utils.megabytes
import java.io.File
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SplitZipDeploymentTaskTest {

    @TempDir
    private lateinit var projectDir: File

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private fun createFakeArtifact(name: String, sizeBytes: Int): File {
        val file = File(projectDir, name)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(sizeBytes))
        return file
    }

    private fun createPublicationInfo(
        projectPath: String,
        group: String,
        module: String,
        version: String,
        artifactFiles: List<Pair<File, String>>
    ): PublicationInfo {
        val gav = GAV(group, module, version)
        val artifacts = project.objects.listProperty<ArtifactInfo>().apply {
            set(artifactFiles.map { (file, ext) ->
                ArtifactInfo(ArtifactFileInfo(file, null, ext), gav)
            })
        }
        return PublicationInfo(
            projectPath = projectPath,
            gav = gav,
            publicationName = "maven",
            artifacts = artifacts,
            checksumFiles = null
        )
    }

    @Test
    fun `all modules fit in one chunk produces single ZIP`() {
        val jar1 = createFakeArtifact("core/core.jar", 100)
        val jar2 = createFakeArtifact("api/api.jar", 100)

        val pub1 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar1 to "jar"))
        val pub2 = createPublicationInfo(":api", "com.test", "api", "1.0", listOf(jar2 to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub1, pub2))
            maxBundleSize.set(1.megabytes)
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        task.createSplitZips()

        val outputDir = project.layout.buildDirectory.dir("split-zips").get().asFile
        val zips = outputDir.listFiles { f -> f.extension == "zip" }!!
        assertThat(zips).hasSize(1)
        assertThat(zips[0].name).isEqualTo("test-project-1.zip")

        ZipFile(zips[0]).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertThat(entries).contains("com/test/core/1.0/core-1.0.jar")
            assertThat(entries).contains("com/test/api/1.0/api-1.0.jar")
        }
    }

    @Test
    fun `modules exceeding limit produce multiple ZIPs`() {
        val jar1 = createFakeArtifact("core/core.jar", 200)
        val jar2 = createFakeArtifact("api/api.jar", 200)

        val pub1 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar1 to "jar"))
        val pub2 = createPublicationInfo(":api", "com.test", "api", "1.0", listOf(jar2 to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub1, pub2))
            maxBundleSize.set(250L)
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        task.createSplitZips()

        val outputDir = project.layout.buildDirectory.dir("split-zips").get().asFile
        val zips = outputDir.listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }
        assertThat(zips).hasSize(2)
        assertThat(zips[0].name).isEqualTo("test-project-1.zip")
        assertThat(zips[1].name).isEqualTo("test-project-2.zip")
    }

    @Test
    fun `multiple publications from same project stay in same chunk`() {
        val jar1 = createFakeArtifact("core/core.jar", 100)
        val jar2 = createFakeArtifact("core/core-sources.jar", 50)

        val pub1 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar1 to "jar"))
        val pub2 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar2 to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub1, pub2))
            maxBundleSize.set(1.megabytes)
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        task.createSplitZips()

        val outputDir = project.layout.buildDirectory.dir("split-zips").get().asFile
        val zips = outputDir.listFiles { f -> f.extension == "zip" }!!
        assertThat(zips).hasSize(1)
    }

    @Test
    fun `single module exceeding limit fails with clear message`() {
        val jar = createFakeArtifact("big/big.jar", 500)
        val pub = createPublicationInfo(":big", "com.test", "big", "1.0", listOf(jar to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub))
            maxBundleSize.set(200L)
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        assertThatThrownBy { task.createSplitZips() }
            .isInstanceOf(io.github.zenhelix.gradle.plugin.client.model.MavenCentralChunkException::class.java)
            .hasMessageContaining(":big")
    }
}
