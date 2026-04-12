package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import kotlin.coroutines.cancellation.CancellationException
import org.gradle.api.logging.Logger

/**
 * Best-effort attempt to drop a single deployment. Logs warnings on failure
 * but never throws (except for [CancellationException] which propagates for coroutine cancellation).
 *
 * HTTP 400 is treated as a normal race condition: the deployment may have transitioned
 * to a non-droppable state (PUBLISHING/PUBLISHED) between our last status check and the
 * drop attempt. This is logged at lifecycle level, not as a warning.
 */
internal suspend fun MavenCentralApiClient.tryDropDeployment(
    creds: Credentials, deploymentId: DeploymentId, logger: Logger
) {
    try {
        when (val result = dropDeployment(creds, deploymentId)) {
            is HttpResponseResult.Success -> {
                logger.lifecycle("Dropped deployment {}", deploymentId)
            }
            is HttpResponseResult.Error -> {
                if (result.httpStatus == HttpStatus.BAD_REQUEST) {
                    logger.lifecycle(
                        "Deployment {} likely transitioned to non-droppable state. Check Maven Central Portal for current status.",
                        deploymentId
                    )
                } else {
                    logger.warn("Failed to drop deployment {}: HTTP {}, Response: {}", deploymentId, result.httpStatus, result.data)
                }
            }
            is HttpResponseResult.UnexpectedError -> {
                logger.warn("Failed to drop deployment {}: {}", deploymentId, result.cause.message)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Failed to drop deployment {}: {}", deploymentId, e.message)
    }
}
