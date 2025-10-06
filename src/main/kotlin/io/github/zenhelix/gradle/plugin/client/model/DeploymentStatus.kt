package io.github.zenhelix.gradle.plugin.client.model

import java.util.UUID

public data class DeploymentStatus(
    val deploymentId: UUID,
    val deploymentName: String,
    val deploymentState: DeploymentStateType,
    val purls: List<String>?,
    val errors: Map<String, Any?>?
)

public enum class DeploymentStateType(internal val id: String) {

    /** A deployment is uploaded and waiting for processing by the validation service **/
    PENDING("PENDING"),

    /** A deployment is being processed by the validation service */
    VALIDATING("VALIDATING"),

    /** A deployment has passed validation and is waiting on a user to manually publish via the Central Portal UI */
    VALIDATED("VALIDATED"),

    /** A deployment has been either automatically or manually published and is being uploaded to Maven Central */
    PUBLISHING("PUBLISHING"),

    /** A deployment has successfully been uploaded to Maven Central */
    PUBLISHED("PUBLISHED"),

    /** A deployment has encountered an error (additional context will be present in an errors field) */
    FAILED("FAILED"),

    UNKNOWN("");

    public companion object {
        public fun ofOrNull(value: String): DeploymentStateType? = values().firstOrNull { it.id.equals(value, true) }
        public fun of(value: String): DeploymentStateType = ofOrNull(value) ?: UNKNOWN
    }
}
