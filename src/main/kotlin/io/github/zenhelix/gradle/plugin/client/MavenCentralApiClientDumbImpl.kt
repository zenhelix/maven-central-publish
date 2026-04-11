package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path
import java.util.UUID

/**
 * No-op API client for functional testing. Returns successful responses
 * without making any HTTP calls. Used when [TEST_BASE_URL] is set as the base URL.
 */
internal class MavenCentralApiClientDumbImpl : MavenCentralApiClient {

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

/**
 * Sentinel URL that triggers the no-op [MavenCentralApiClientDumbImpl] in publish tasks.
 * Used exclusively in functional tests to avoid real HTTP calls.
 */
internal const val TEST_BASE_URL: String = "https://test.invalid"

/**
 * Creates an [MavenCentralApiClient] for the given [url].
 * Returns [MavenCentralApiClientDumbImpl] when [url] matches [TEST_BASE_URL],
 * otherwise creates a real [MavenCentralApiClientImpl].
 */
internal fun createApiClient(url: String): MavenCentralApiClient =
    if (url == TEST_BASE_URL) MavenCentralApiClientDumbImpl() else MavenCentralApiClientImpl(url)
