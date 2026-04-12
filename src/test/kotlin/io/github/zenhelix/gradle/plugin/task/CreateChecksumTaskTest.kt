package io.github.zenhelix.gradle.plugin.task

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningPlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateChecksumTaskTest {

    @TempDir
    private lateinit var testProjectDir: File

    @Test
    fun `checksum task generates files for each algorithm`() {
        val project = createProject()
        val artifactFile = createTestFile("test-1.0.0.jar", "some jar content")

        val checksumTask = project.registerChecksumTask("testPub") {
            artifactInfos.add(
                ArtifactInfo(
                    ArtifactFileInfo(artifactFile, null, "jar"),
                    GAV("com.example", "test", "1.0.0")
                )
            )
        }.get()

        executeTask(checksumTask)

        val outputDir = checksumTask.outputDirectory.get().asFile
        assertThat(outputDir).exists()

        val checksumFiles = outputDir.walkTopDown().filter { it.isFile }.toList()
        val extensions = checksumFiles.map { it.extension }.toSet()

        assertThat(extensions).contains("md5", "sha1")
    }

    @Test
    fun `checksum content is valid hex string`() {
        val project = createProject()
        val artifactFile = createTestFile("test-1.0.0.pom", "<?xml version=\"1.0\"?><project/>")

        val checksumTask = project.registerChecksumTask("testPub") {
            artifactInfos.add(
                ArtifactInfo(
                    ArtifactFileInfo(artifactFile, null, "pom"),
                    GAV("com.example", "test", "1.0.0")
                )
            )
        }.get()

        executeTask(checksumTask)

        val outputDir = checksumTask.outputDirectory.get().asFile
        val checksumFiles = outputDir.walkTopDown().filter { it.isFile }.toList()

        assertThat(checksumFiles).isNotEmpty()
        checksumFiles.forEach { file ->
            val content = file.readText()
            assertThat(content)
                .`as`("Checksum file ${file.name} should contain valid hex")
                .matches("[0-9a-f]+")
        }
    }

    private fun createProject(): Project = ProjectBuilder.builder()
        .withProjectDir(testProjectDir)
        .build().also { project ->
            project.apply<MavenPublishPlugin>()
            project.apply<SigningPlugin>()
        }

    private fun createTestFile(name: String, content: String): File =
        File(testProjectDir, "build/libs/$name").also {
            it.parentFile.mkdirs()
            it.writeText(content)
        }

    private fun Project.registerChecksumTask(
        publicationName: String, action: CreateChecksumTask.() -> Unit = {}
    ) = tasks.register<CreateChecksumTask>("checksum${publicationName.replaceFirstChar { it.uppercase() }}") {
        this.publicationName.set(publicationName)
        outputDirectory.set(layout.buildDirectory.dir("checksums"))
    }.apply { configure(action) }

    private fun executeTask(task: CreateChecksumTask) {
        task.outputDirectory.get().asFile.mkdirs()
        task.actions.forEach { it.execute(task) }
    }
}
