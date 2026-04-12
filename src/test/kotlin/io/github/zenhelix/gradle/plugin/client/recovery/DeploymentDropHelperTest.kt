package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Test

class DeploymentDropHelperTest {

    private val logger: Logger = mockk(relaxed = true)
    private val client: MavenCentralApiClient = mockk()
    private val creds = BearerTokenCredentials("test-token")
    private val deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")

    @Test
    fun `successful drop logs lifecycle message`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)

        client.tryDropDeployment(creds, deploymentId, logger)

        verify { logger.lifecycle("Dropped deployment {}", deploymentId) }
    }

    @Test
    fun `state conflict 400 logs lifecycle message instead of warning`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Error(
            data = """{"message":"Can only drop deployments that are in a VALIDATED or FAILED state."}""",
            httpStatus = 400
        )

        client.tryDropDeployment(creds, deploymentId, logger)

        verify {
            logger.lifecycle(
                match<String> { it.contains("progressed to a non-droppable state") },
                eq(deploymentId)
            )
        }
        verify(exactly = 0) {
            logger.warn(any<String>(), any(), any(), any<Any>())
        }
    }

    @Test
    fun `non-state-conflict 400 logs warning`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Error(
            data = """{"message":"Some other error"}""",
            httpStatus = 400
        )

        client.tryDropDeployment(creds, deploymentId, logger)

        verify {
            logger.warn(
                match<String> { it.contains("Failed to drop") },
                eq(deploymentId), eq(400), any()
            )
        }
    }

    @Test
    fun `unexpected error logs warning`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } returns
                HttpResponseResult.UnexpectedError(RuntimeException("network error"))

        client.tryDropDeployment(creds, deploymentId, logger)

        verify {
            logger.warn(
                match<String> { it.contains("Failed to drop") },
                eq(deploymentId), eq("network error")
            )
        }
    }

    @Test
    fun `exception does not propagate`() = runTest {
        coEvery { client.dropDeployment(any(), any()) } throws RuntimeException("unexpected crash")

        // Should not throw
        client.tryDropDeployment(creds, deploymentId, logger)
    }
}
