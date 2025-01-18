package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path
import java.util.UUID

public interface MavenCentralApiClient {

    public fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType? = null, deploymentName: String? = null
    ): HttpResponseResult<UUID, String>

    public fun deploymentStatus(credentials: Credentials, deploymentId: UUID): HttpResponseResult<DeploymentStatus, String>

    public fun publishDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String>

    public fun dropDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String>

}