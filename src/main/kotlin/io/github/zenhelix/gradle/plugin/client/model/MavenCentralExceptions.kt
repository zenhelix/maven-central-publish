package io.github.zenhelix.gradle.plugin.client.model

import org.gradle.api.GradleException

public class MavenCentralValidationException(
    public val error: ValidationError,
    message: String
) : GradleException(message)

public class MavenCentralDeploymentException(
    public val error: DeploymentError,
    message: String,
    cause: Exception? = null
) : GradleException(message, cause)

public class MavenCentralChunkException(
    public val error: ChunkError,
    message: String
) : GradleException(message)
