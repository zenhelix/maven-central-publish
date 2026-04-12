package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import org.gradle.api.logging.Logger

internal class DeploymentRecoveryHandler(
    private val client: MavenCentralApiClient,
    private val credentials: Credentials,
    private val logger: Logger
) {
    suspend fun recover(deploymentId: DeploymentId, error: DeploymentError): DeploymentError {
        if (error.isDroppable) {
            logger.warn("Deployment failed, attempting to drop deployment {}", deploymentId)
            client.tryDropDeployment(credentials, deploymentId, logger)
        } else {
            logger.warn(
                "Deployment {} cannot be dropped. Check Maven Central Portal.",
                deploymentId
            )
        }
        return error
    }

    suspend fun recoverAll(
        deploymentIds: List<DeploymentId>,
        lastKnownStates: Map<DeploymentId, DeploymentStateType>,
        error: DeploymentError
    ): DeploymentError {
        val (droppable, nonDroppable) = deploymentIds.partition { id ->
            val state = lastKnownStates[id]
            state == null || state.isDroppable
        }

        if (nonDroppable.isNotEmpty()) {
            logger.warn(
                "Deployments {} are in non-droppable state. Check Maven Central Portal.",
                nonDroppable
            )
        }

        droppable.forEach { client.tryDropDeployment(credentials, it, logger) }
        return error
    }

    suspend fun recoverPublishFailure(
        allIds: List<DeploymentId>,
        publishedIds: Set<DeploymentId>,
        failedId: DeploymentId,
        error: DeploymentError
    ): DeploymentError {
        val unpublished = allIds.filter { it !in publishedIds }
        unpublished.forEach { client.tryDropDeployment(credentials, it, logger) }

        logger.warn(
            "{} deployment(s) may already be published and cannot be rolled back. Dropped {} remaining.",
            publishedIds.size, unpublished.size
        )
        return error
    }
}
