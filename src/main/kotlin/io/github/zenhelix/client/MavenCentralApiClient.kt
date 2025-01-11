package io.github.zenhelix.client

import io.github.zenhelix.client.model.Credentials
import io.github.zenhelix.client.model.DeploymentStatus
import io.github.zenhelix.client.model.HttpResponseResult
import io.github.zenhelix.client.model.PublishingType
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