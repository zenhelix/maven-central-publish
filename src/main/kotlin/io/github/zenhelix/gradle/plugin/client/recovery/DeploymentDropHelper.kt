package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.gradle.api.logging.Logger

/**
 * Best-effort attempt to drop a single deployment. Logs warnings on failure
 * but never throws (except for [CancellationException] which propagates for coroutine cancellation).
 *
 * HTTP 400 with a state-related message is treated as a normal race condition:
 * the deployment may have transitioned to PUBLISHING/PUBLISHED between our last
 * status check and the drop attempt. This is logged at lifecycle level, not as a warning.
 */
internal suspend fun MavenCentralApiClient.tryDropDeployment(
    creds: Credentials, deploymentId: UUID, logger: Logger
) {
    try {
        when (val result = dropDeployment(creds, deploymentId)) {
            is HttpResponseResult.Success -> {
                logger.lifecycle("Dropped deployment {}", deploymentId)
            }
            is HttpResponseResult.Error -> {
                if (result.httpStatus == HTTP_BAD_REQUEST && isStateConflictError(result.data)) {
                    logger.lifecycle(
                        "Deployment {} has progressed to a non-droppable state (race condition). " +
                            "Check Maven Central Portal for current status.", deploymentId
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

private const val HTTP_BAD_REQUEST = 400

/**
 * Detects the Maven Central Portal error returned when trying to drop a deployment
 * that has already moved to a non-droppable state (PUBLISHING, PUBLISHED).
 */
private fun isStateConflictError(responseBody: String?): Boolean =
    responseBody != null && responseBody.contains("VALIDATED or FAILED state", ignoreCase = true)
