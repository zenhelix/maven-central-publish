package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path

/**
 * No-op API client for functional testing. Returns successful responses
 * without making any HTTP calls. Used when [TEST_BASE_URL] is set as the base URL.
 */
internal class NoOpMavenCentralApiClient : MavenCentralApiClient {

    override suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<DeploymentId, String> = HttpResponseResult.Success(DeploymentId.random())

    override suspend fun deploymentStatus(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<DeploymentStatus, String> = HttpResponseResult.Success(
        DeploymentStatus(
            deploymentId = DeploymentId.random(),
            deploymentName = "",
            deploymentState = DeploymentStateType.PUBLISHED,
            purls = null, errors = null,
        )
    )

    override suspend fun publishDeployment(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override suspend fun dropDeployment(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override fun close() {
        // No resources to close in no-op implementation
    }

}

/**
 * Sentinel URL that triggers the no-op [NoOpMavenCentralApiClient] in publish tasks.
 * Used exclusively in functional tests to avoid real HTTP calls.
 */
internal const val TEST_BASE_URL = "https://test.invalid"

/**
 * Creates an [MavenCentralApiClient] for the given [url].
 * Returns [NoOpMavenCentralApiClient] when [url] matches [TEST_BASE_URL],
 * otherwise creates a real [DefaultMavenCentralApiClient].
 */
internal fun createApiClient(url: String): MavenCentralApiClient =
    if (url == TEST_BASE_URL) NoOpMavenCentralApiClient() else DefaultMavenCentralApiClient(url)
