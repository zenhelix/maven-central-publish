package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path
import java.util.UUID

public class MavenCentralApiClientDumbImpl : MavenCentralApiClient {

    override fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<UUID, String> = HttpResponseResult.Success(UUID.randomUUID())

    override fun deploymentStatus(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<DeploymentStatus, String> = HttpResponseResult.Success(
        DeploymentStatus(
            deploymentId = UUID.randomUUID(),
            deploymentName = "",
            deploymentState = DeploymentStateType.PUBLISHED,
            purls = null, errors = null,
        )
    )

    override fun publishDeployment(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override fun dropDeployment(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override fun close() {
        // No resources to close in dummy implementation
    }

}