package io.github.zenhelix.gradle.plugin.client.model

import java.util.UUID

public sealed class DeploymentError(public val message: String) {
    public data class UploadFailed(val httpStatus: Int, val response: String?)
        : DeploymentError("Failed to upload bundle: HTTP $httpStatus")

    public data class UploadUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error during bundle upload")

    public data class DeploymentFailed(val state: DeploymentStateType, val errors: Map<String, Any?>?)
        : DeploymentError(buildString {
            append("Deployment failed with status: $state")
            if (!errors.isNullOrEmpty()) append("\nErrors: $errors")
        })

    public data class StatusCheckFailed(val httpStatus: Int, val response: String?)
        : DeploymentError("Failed to check deployment status: HTTP $httpStatus")

    public data class StatusCheckUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error while checking deployment status")

    public data class Timeout(val state: DeploymentStateType, val maxChecks: Int)
        : DeploymentError("Deployment did not complete after $maxChecks status checks. Current status: $state. Check Maven Central Portal for current status.")

    public data class PublishFailed(val deploymentId: UUID, val httpStatus: Int)
        : DeploymentError("Failed to publish deployment $deploymentId: HTTP $httpStatus")

    public data class PublishUnexpected(val deploymentId: UUID, val cause: Exception)
        : DeploymentError("Unexpected error publishing deployment $deploymentId")

    public val isDroppable: Boolean get() = when (this) {
        is DeploymentFailed -> state.isDroppable
        is Timeout -> state.isDroppable
        is StatusCheckFailed, is StatusCheckUnexpected -> true
        is UploadFailed, is UploadUnexpected, is PublishFailed, is PublishUnexpected -> false
    }
}

public fun DeploymentError.toGradleException(): MavenCentralDeploymentException = when (this) {
    is DeploymentError.UploadUnexpected -> MavenCentralDeploymentException(error = this, message = message, cause = cause)
    is DeploymentError.StatusCheckUnexpected -> MavenCentralDeploymentException(error = this, message = message, cause = cause)
    is DeploymentError.PublishUnexpected -> MavenCentralDeploymentException(error = this, message = message, cause = cause)
    else -> MavenCentralDeploymentException(error = this, message = message)
}

internal val DeploymentStateType.isDroppable: Boolean
    get() = when (this) {
        DeploymentStateType.PENDING,
        DeploymentStateType.VALIDATING,
        DeploymentStateType.VALIDATED,
        DeploymentStateType.FAILED,
        DeploymentStateType.UNKNOWN -> true
        DeploymentStateType.PUBLISHING,
        DeploymentStateType.PUBLISHED -> false
    }
