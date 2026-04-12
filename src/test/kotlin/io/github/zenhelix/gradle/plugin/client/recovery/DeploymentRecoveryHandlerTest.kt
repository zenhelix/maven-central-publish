package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Test

class DeploymentRecoveryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)
    private val client: MavenCentralApiClient = mockk(relaxed = true)
    private val creds = io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials("test-token")
    private val deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")

    private fun createHandler() = DeploymentRecoveryHandler(client, creds, logger)

    @Test
    fun `recover drops deployment when error is droppable`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val error = DeploymentError.DeploymentFailed(DeploymentStateType.FAILED, null)

        val result = createHandler().recover(deploymentId, error)

        assertThat(result).isSameAs(error)
        coVerify(exactly = 1) { client.dropDeployment(creds, deploymentId) }
    }

    @Test
    fun `recover does not drop when error is not droppable`() = runTest {
        val error = DeploymentError.UploadFailed(400, "Bad Request")

        val result = createHandler().recover(deploymentId, error)

        assertThat(result).isSameAs(error)
        coVerify(exactly = 0) { client.dropDeployment(any(), any()) }
    }

    @Test
    fun `recoverAll drops only droppable deployments`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val lastKnownStates = mapOf(
            id1 to DeploymentStateType.VALIDATED,
            id2 to DeploymentStateType.PUBLISHING
        )
        val error = DeploymentError.Timeout(DeploymentStateType.VALIDATED, 20)

        createHandler().recoverAll(listOf(id1, id2), lastKnownStates, error)

        coVerify(exactly = 1) { client.dropDeployment(creds, id1) }
        coVerify(exactly = 0) { client.dropDeployment(creds, id2) }
    }

    @Test
    fun `recoverAll treats unknown state as droppable`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val error = DeploymentError.StatusCheckFailed(503, "Service Unavailable")

        createHandler().recoverAll(listOf(id1), emptyMap(), error)

        coVerify(exactly = 1) { client.dropDeployment(creds, id1) }
    }

    @Test
    fun `recoverPublishFailure drops unpublished deployments only`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val id3 = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val publishedIds = setOf(id1)
        val failedId = id2
        val error = DeploymentError.PublishFailed(id2, 500)

        createHandler().recoverPublishFailure(listOf(id1, id2, id3), publishedIds, failedId, error)

        coVerify(exactly = 0) { client.dropDeployment(creds, id1) }
        coVerify(exactly = 0) { client.dropDeployment(creds, id2) }
        coVerify(exactly = 1) { client.dropDeployment(creds, id3) }
    }
}
