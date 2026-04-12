package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path

public interface MavenCentralApiClient : AutoCloseable {

    public suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType? = null, deploymentName: String? = null
    ): HttpResponseResult<DeploymentId, String>

    public suspend fun deploymentStatus(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<DeploymentStatus, String>

    public suspend fun publishDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

    public suspend fun dropDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

}
