package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.github.zenhelix.gradle.plugin.client.model.MavenCentralDeploymentException
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.time.Duration
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublishSplitBundleDropBehaviorTest {

    @TempDir
    private lateinit var projectDir: File

    private lateinit var mockClient: MavenCentralApiClient
    private val deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")

    @BeforeEach
    fun setUp() {
        mockClient = mockk(relaxed = true)

        coEvery { mockClient.uploadDeploymentBundle(any(), any(), any(), any()) } returns
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
            credentials.set(io.github.zenhelix.gradle.plugin.client.model.Success(BearerTokenCredentials("test-token")))
            publishingType.set(PublishingType.AUTOMATIC)
            maxStatusChecks.set(2)
            statusCheckDelay.set(Duration.ofMillis(1))
        }.get()

        task.publishBundles()
    }

    @Test
    fun `should drop deployment when timeout occurs in VALIDATING state`() {
        val bundlesDir = createBundleFiles(1)

        coEvery { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.VALIDATING)
        coEvery { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishSplitTask(bundlesDir) }
            .isInstanceOf(MavenCentralDeploymentException::class.java)
            .hasMessageContaining("did not complete after 2 status checks")

        coVerify(exactly = 1) { mockClient.dropDeployment(any(), eq(deploymentId)) }
    }

    @Test
    fun `should NOT drop deployment when timeout occurs in PUBLISHING state`() {
        val bundlesDir = createBundleFiles(1)

        coEvery { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PUBLISHING)

        assertThatThrownBy { executePublishSplitTask(bundlesDir) }
            .isInstanceOf(MavenCentralDeploymentException::class.java)

        coVerify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }

    @Test
    fun `should not double-drop when publishAllDeployments fails after validation`() {
        // Scenario: split bundle with 2 chunks, both validated,
        // then publish of chunk 2 fails → handlePublishFailure drops chunk 1,
        // outer catch should NOT drop again
        val bundlesDir = createBundleFiles(2)
        val id1 = DeploymentId.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = DeploymentId.fromString("22222222-2222-2222-2222-222222222222")

        var uploadCount = 0
        coEvery { mockClient.uploadDeploymentBundle(any(), any(), any(), any()) } answers {
            uploadCount++
            HttpResponseResult.Success(if (uploadCount == 1) id1 else id2)
        }

        // Both validated successfully
        coEvery { mockClient.deploymentStatus(any(), eq(id1)) } returns HttpResponseResult.Success(
            DeploymentStatus(id1, "test", DeploymentStateType.VALIDATED, null, null)
        )
        coEvery { mockClient.deploymentStatus(any(), eq(id2)) } returns HttpResponseResult.Success(
            DeploymentStatus(id2, "test", DeploymentStateType.VALIDATED, null, null)
        )

        // Publish id1 succeeds, publish id2 fails
        coEvery { mockClient.publishDeployment(any(), eq(id1)) } returns HttpResponseResult.Success(Unit)
        coEvery { mockClient.publishDeployment(any(), eq(id2)) } returns HttpResponseResult.Error(
            data = "Internal error", httpStatus = HttpStatus(500)
        )
        coEvery { mockClient.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        assertThatThrownBy { executePublishSplitTask(bundlesDir) }
            .isInstanceOf(MavenCentralDeploymentException::class.java)

        // handlePublishFailure should NOT drop id1 (already published) or id2 (the failed one)
        // It should only drop remaining unpublished deployments (none in this case —
        // id1 is published, id2 is the failed one)
        // The key assertion: drop should NOT be called for id1 (already published)
        coVerify(exactly = 0) { mockClient.dropDeployment(any(), eq(id1)) }
    }

    @Test
    fun `should succeed when single deployment reaches PUBLISHED state`() {
        val bundlesDir = createBundleFiles(1)

        coEvery { mockClient.deploymentStatus(any(), any()) } returns statusReturning(DeploymentStateType.PUBLISHED)

        executePublishSplitTask(bundlesDir) // Should not throw

        coVerify(exactly = 0) { mockClient.dropDeployment(any(), any()) }
    }
}

/**
 * Concrete subclass of [PublishSplitBundleMavenCentralTask] for testing.
 * Overrides [createApiClient] to return a mock client.
 */
internal abstract class TestPublishSplitBundleTask : PublishSplitBundleMavenCentralTask() {

    @get:org.gradle.api.tasks.Internal
    var testClient: MavenCentralApiClient? = null

    override fun createApiClient(
        url: String,
        requestTimeout: java.time.Duration,
        connectTimeout: java.time.Duration,
        maxRetries: Int,
        retryBaseDelay: java.time.Duration
    ): MavenCentralApiClient = testClient!!
}
