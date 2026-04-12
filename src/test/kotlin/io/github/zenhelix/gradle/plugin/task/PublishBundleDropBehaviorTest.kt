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
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import io.github.zenhelix.gradle.plugin.client.model.MavenCentralDeploymentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import javax.inject.Inject

class PublishBundleDropBehaviorTest {

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

    private fun createBundleFile(): File {
        val file = File(projectDir, "test-bundle.zip")
        file.writeBytes("fake zip content".toByteArray())
        return file
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

    /**
     * Creates a task that uses our mock client, configures it, and executes publishBundle().
     */
    private fun executePublishTask() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val bundleFile = createBundleFile()

        val task = project.tasks.register<TestPublishBundleTask>("testPublish") {
            testClient = mockClient
            baseUrl.set("https://test.example.com")
            zipFile.set(bundleFile)
            credentials.set(io.github.zenhelix.gradle.plugin.client.model.Success(BearerTokenCredentials("test-token")))
            publishingType.set(PublishingType.AUTOMATIC)
            maxStatusChecks.set(2)
            statusCheckDelay.set(Duration.ofMillis(1))
        }.get()

        task.publishBundle()
    }

    @Test
    fun `should drop deployment when timeout occurs in PENDING state`() {
        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PENDING)
        every { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishTask() }
            .isInstanceOf(MavenCentralDeploymentException::class.java)
            .hasMessageContaining("did not complete after 2 status checks")

        verify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should drop deployment when timeout occurs in VALIDATING state`() {
        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.VALIDATING)
        every { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishTask() }
            .isInstanceOf(MavenCentralDeploymentException::class.java)

        verify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should NOT drop deployment when timeout occurs in PUBLISHING state`() {
        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PUBLISHING)

        assertThatThrownBy { executePublishTask() }
            .isInstanceOf(MavenCentralDeploymentException::class.java)
            .hasMessageContaining("PUBLISHING")

        verify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }

    @Test
    fun `should drop deployment when status is FAILED`() {
        every { mockClient.deploymentStatus(any(), any()) } returns HttpResponseResult.Success(
            DeploymentStatus(
                deploymentId = deploymentId,
                deploymentName = "test",
                deploymentState = DeploymentStateType.FAILED,
                purls = null,
                errors = mapOf("error" to "validation failed")
            )
        )
        every { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishTask() }
            .isInstanceOf(MavenCentralDeploymentException::class.java)
            .hasMessageContaining("FAILED")

        verify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should succeed when deployment reaches PUBLISHED state`() {
        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PUBLISHED)

        executePublishTask() // Should not throw

        verify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }

    @Test
    fun `race condition - should handle gracefully when deployment moves to PUBLISHING between check and drop`() {
        // Simulate: last status check sees VALIDATING, but by the time we try to drop,
        // deployment has moved to PUBLISHING and Maven Central returns 400
        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.VALIDATING)
        every { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Error(
            data = """{"httpStatus":400,"errorCode":10400,"message":"Can only drop deployments that are in a VALIDATED or FAILED state."}""",
            httpStatus = 400
        )

        // Should still throw the timeout exception, but NOT crash on the drop failure
        assertThatThrownBy { executePublishTask() }
            .isInstanceOf(MavenCentralDeploymentException::class.java)
            .hasMessageContaining("did not complete after 2 status checks")

        // Drop was attempted (state was droppable at check time) but failed gracefully
        verify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should attempt drop when status check returns HTTP error`() {
        // Status check fails with HTTP error (server temporarily down)
        // The deployment state is unknown, so drop should be attempted as best-effort
        every { mockClient.deploymentStatus(any(), any()) } returns HttpResponseResult.Error(
            data = "Service Unavailable",
            httpStatus = 503
        )
        every { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishTask() }
            .isInstanceOf(MavenCentralDeploymentException::class.java)
            .hasMessageContaining("Failed to check deployment status")

        verify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should succeed for USER_MANAGED when deployment reaches VALIDATED state`() {
        every { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.VALIDATED)

        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val bundleFile = createBundleFile()

        val task = project.tasks.register<TestPublishBundleTask>("testPublish") {
            testClient = mockClient
            baseUrl.set("https://test.example.com")
            zipFile.set(bundleFile)
            credentials.set(io.github.zenhelix.gradle.plugin.client.model.Success(BearerTokenCredentials("test-token")))
            publishingType.set(PublishingType.USER_MANAGED)
            maxStatusChecks.set(2)
            statusCheckDelay.set(Duration.ofMillis(1))
        }.get()

        task.publishBundle() // Should not throw

        verify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }
}

/**
 * Concrete subclass of [PublishBundleMavenCentralTask] for testing.
 * Overrides [createApiClient] to return a mock client.
 */
internal abstract class TestPublishBundleTask @Inject constructor(
    objects: ObjectFactory
) : PublishBundleMavenCentralTask(objects) {

    @get:org.gradle.api.tasks.Internal
    var testClient: MavenCentralApiClient? = null

    override fun createApiClient(url: String): MavenCentralApiClient = testClient!!
}
