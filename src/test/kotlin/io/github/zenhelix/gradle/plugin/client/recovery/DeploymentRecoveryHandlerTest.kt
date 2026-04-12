package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Test

class DeploymentRecoveryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)
    private val client: MavenCentralApiClient = mockk(relaxed = true)
    private val creds = Credentials.BearerTokenCredentials("test-token")
    private val deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")

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
        val error = DeploymentError.UploadFailed(HttpStatus.BAD_REQUEST, "Bad Request")

        val result = createHandler().recover(deploymentId, error)

        assertThat(result).isSameAs(error)
        coVerify(exactly = 0) { client.dropDeployment(any(), any()) }
    }

    @Test
    fun `recoverAll drops only droppable deployments`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = DeploymentId.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = DeploymentId.fromString("22222222-2222-2222-2222-222222222222")
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
        val id1 = DeploymentId.fromString("11111111-1111-1111-1111-111111111111")
        val error = DeploymentError.StatusCheckFailed(HttpStatus(503), "Service Unavailable")

        createHandler().recoverAll(listOf(id1), emptyMap(), error)

        coVerify(exactly = 1) { client.dropDeployment(creds, id1) }
    }

    @Test
    fun `recoverPublishFailure drops failed and unpublished deployments`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = DeploymentId.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = DeploymentId.fromString("22222222-2222-2222-2222-222222222222")
        val id3 = DeploymentId.fromString("33333333-3333-3333-3333-333333333333")
        val publishedIds = setOf(id1)
        val failedId = id2
        val error = DeploymentError.PublishFailed(id2, HttpStatus(500))

        createHandler().recoverPublishFailure(listOf(id1, id2, id3), publishedIds, failedId, error)

        coVerify(exactly = 0) { client.dropDeployment(creds, id1) }
        coVerify(exactly = 1) { client.dropDeployment(creds, id2) }
        coVerify(exactly = 1) { client.dropDeployment(creds, id3) }
    }
}
