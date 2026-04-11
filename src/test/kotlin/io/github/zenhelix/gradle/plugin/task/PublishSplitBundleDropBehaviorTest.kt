package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.time.Duration
import java.util.UUID
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublishSplitBundleDropBehaviorTest {

    @TempDir
    private lateinit var projectDir: File

    private lateinit var mockClient: MavenCentralApiClient
    private val deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")

    @BeforeEach
    fun setUp() {
        mockClient = mockk(relaxed = true)

        every { mockClient.uploadDeploymentBundle(any(), any(), any(), any()) } returns
                HttpResponseResult.Success(deploymentId)
    }

    private fun createBundleFiles(count: Int): File {
        val dir = File(projectDir, "bundles")
        dir.mkdirs()
        repeat(count) { i ->
            File(dir, "bundle-${i + 1}.zip").writeBytes("fake zip content $i".toByteArray())
        }
        return dir
    }

    private fun statusReturning(state: DeploymentStateType): HttpResponseResult<DeploymentStatus, String> =
        HttpResponseResult.Success(
            DeploymentStatus(
                deploymentId = deploymentId,
                deploymentName = "test",
                deploymentState = state,
                purls = null,
                errors = null
            )
        )

    private fun executePublishSplitTask(bundlesDir: File) {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()

        val task = project.tasks.register<TestPublishSplitBundleTask>("testPublishSplit") {
            testClient = mockClient
            baseUrl.set("https://test.example.com")
            bundlesDirectory.set(bundlesDir)
            credentials.set(BearerTokenCredentials("test-token"))
            publishingType.set(PublishingType.AUTOMATIC)
            maxStatusChecks.set(2)
            statusCheckDelay.set(Duration.ofMillis(1))
        }.get()

        task.publishBundles()
    }

    @Test
    fun `should drop deployment when timeout occurs in VALIDATING state`() {
        val bundlesDir = createBundleFiles(1)

        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.VALIDATING)
        every { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishSplitTask(bundlesDir) }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("did not complete after 2 status checks")

        verify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should NOT drop deployment when timeout occurs in PUBLISHING state`() {
        val bundlesDir = createBundleFiles(1)

        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PUBLISHING)

        assertThatThrownBy { executePublishSplitTask(bundlesDir) }
            .isInstanceOf(GradleException::class.java)

        verify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }

    @Test
    fun `should succeed when single deployment reaches PUBLISHED state`() {
        val bundlesDir = createBundleFiles(1)

        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PUBLISHED)

        executePublishSplitTask(bundlesDir) // Should not throw

        verify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }
}

/**
 * Concrete subclass of [PublishSplitBundleMavenCentralTask] for testing.
 * Overrides [createApiClient] to return a mock client.
 */
internal abstract class TestPublishSplitBundleTask : PublishSplitBundleMavenCentralTask() {

    @get:org.gradle.api.tasks.Internal
    var testClient: MavenCentralApiClient? = null

    override fun createApiClient(url: String): MavenCentralApiClient = testClient!!
}
