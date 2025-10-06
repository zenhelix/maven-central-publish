package io.github.zenhelix.gradle.plugin.task

import java.io.File
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningPlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ZipDeploymentTaskTest {

    @TempDir
    private lateinit var testProjectDir: File

    @Test
    fun `task creates archive with correct artifact files`() {
        val project = createProject()
        val publicationInfos = listOf(
            project.createPublicationInfo(createTestFile("test-1.0.0.jar", "jar content"), extension = "jar"),
            project.createPublicationInfo(
                createTestFile("test-1.0.0.pom", "<?xml version=\"1.0\"?><project/>"), extension = "pom"
            ),
            project.createPublicationInfo(
                createTestFile("test-1.0.0-sources.jar", "sources content"),
                extension = "jar", classifier = "sources"
            )
        )

        val zipTask = project.registerZipTask("functionalTest") {
            publications.addAll(publicationInfos)
            configureContent()
        }.get()

        zipTask.run()

        val archiveFile = zipTask.archiveFile.get().asFile
        assertThat(archiveFile).exists()
        ZipFile(archiveFile).use { zipFile ->
            assertThat(
                zipFile.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            ).containsExactlyInAnyOrder(
                "com/example/test/1.0.0/test-1.0.0.jar",
                "com/example/test/1.0.0/test-1.0.0.pom",
                "com/example/test/1.0.0/test-1.0.0-sources.jar"
            )

            val jarEntry = zipFile.getEntry("com/example/test/1.0.0/test-1.0.0.jar")
            val jarContent = zipFile.getInputStream(jarEntry).use { it.readBytes().decodeToString() }
            assertThat(jarContent).isEqualTo("jar content")

            val pomEntry = zipFile.getEntry("com/example/test/1.0.0/test-1.0.0.pom")
            val pomContent = zipFile.getInputStream(pomEntry).use { it.readBytes().decodeToString() }
            assertThat(pomContent).isEqualTo("<?xml version=\"1.0\"?><project/>")
        }
    }

    @Test
    fun `task creates archive with multiple publications having different GAV`() {
        val project = createProject()

        val publicationInfos = listOf(
            project.createPublicationInfo(
                createTestFile("app-1.0.0.jar", "app content"),
                gav = GAV("com.example", "app", "1.0.0")
            ),
            project.createPublicationInfo(
                createTestFile("lib-2.0.0.jar", "lib content"),
                gav = GAV("com.mycompany", "lib", "2.0.0")
            )
        )

        val zipTask = project.registerZipTask("multiGavTest") {
            publications.addAll(publicationInfos)
            configureContent()
        }.get()

        zipTask.run()

        ZipFile(zipTask.archiveFile.get().asFile).use { zipFile ->
            assertThat(
                zipFile.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            ).containsExactlyInAnyOrder(
                "com/example/app/1.0.0/app-1.0.0.jar",
                "com/mycompany/lib/2.0.0/lib-2.0.0.jar"
            )
        }
    }

    @Test
    fun `task extends Zip correctly`() {
        val zipTask = createProject().registerZipTask("test").get()

        assertThat(zipTask).isInstanceOf(org.gradle.api.tasks.bundling.Zip::class.java)
        assertThat(zipTask.group).isEqualTo("publishing")
        assertThat(zipTask.description).isEqualTo("Creates ZIP deployment bundle for Maven Central Portal API")
    }

    private fun createProject(): Project = ProjectBuilder.builder()
        .withProjectDir(testProjectDir)
        .build().also { project ->
            project.apply<MavenPublishPlugin>()
            project.apply<SigningPlugin>()
        }

    private fun createTestFile(name: String, content: String): File = File(testProjectDir, "build/libs/$name").also {
        it.parentFile.mkdirs()
        it.writeText(content)
    }

    private fun Project.registerZipTask(
        name: String, action: ZipDeploymentTask.() -> Unit = {}
    ) = tasks.register<ZipDeploymentTask>("zipDeployment${name.replaceFirstChar { it.uppercase() }}") {
        archiveFileName.set("deployment-$name.zip")
        destinationDirectory.set(layout.buildDirectory.dir("deployment"))
    }.apply { configure(action) }

    private fun Project.registerChecksumTask(
        publicationName: String, action: CreateChecksumTask.() -> Unit = {}
    ) = tasks.register<CreateChecksumTask>("checksum${publicationName.replaceFirstChar { it.uppercase() }}") {
        this.publicationName.set(publicationName)
        outputDirectory.set(layout.buildDirectory.dir("checksums"))
    }.apply { configure(action) }

    private fun Project.createPublicationInfo(
        artifactFile: File,
        classifier: String? = null,
        extension: String = "jar",
        checksumTask: TaskProvider<CreateChecksumTask>? = null,
        gav: GAV = GAV("com.example", "test", "1.0.0")
    ): PublicationInfo {
        val artifactInfo = ArtifactInfo(ArtifactFileInfo(artifactFile, classifier, extension), gav)

        return PublicationInfo(
            gav = gav,
            publicationName = "java",
            artifacts = this.objects.listProperty<ArtifactInfo>().apply { add(artifactInfo) },
            checksumTask = checksumTask
        )
    }

    private fun ZipDeploymentTask.run() {
        this.destinationDirectory.get().asFile.mkdirs()
        this.actions.forEach { it.execute(this) }
    }
}