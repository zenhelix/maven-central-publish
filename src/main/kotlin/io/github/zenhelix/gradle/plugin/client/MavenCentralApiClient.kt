package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path

/**
 * HTTP client for the Maven Central Publisher API that covers the full deployment lifecycle:
 * upload → validate → publish (or drop on failure).
 *
 * @see <a href="https://central.sonatype.org/publish/publish-portal-api/">Maven Central Portal API docs</a>
 */
public interface MavenCentralApiClient : AutoCloseable {

    /**
     * Uploads a deployment bundle (ZIP file) to Maven Central and returns the assigned
     * deployment identifier on success.
     *
     * @param credentials Authentication credentials (bearer token or username/password).
     * @param bundle Path to the ZIP file containing the artifacts to deploy.
     * @param publishingType Controls whether the deployment is published automatically after
     * validation or left in a `VALIDATED` state for manual release. `null` defaults to
     * [PublishingType.AUTOMATIC].
     * @param deploymentName Optional human-readable label shown in the Portal UI. When `null`
     * the Portal assigns a name automatically.
     * @return [HttpResponseResult.Success] with the new [DeploymentId], or an error result
     * containing the HTTP status and response body.
     */
    public suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType? = null, deploymentName: String? = null
    ): HttpResponseResult<DeploymentId, String>

    /**
     * Retrieves the current status of a deployment.
     *
     * @param credentials Authentication credentials.
     * @param deploymentId Identifier of the deployment to query, as returned by [uploadDeploymentBundle].
     * @return [HttpResponseResult.Success] with the current [DeploymentStatus], or an error result.
     */
    public suspend fun deploymentStatus(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<DeploymentStatus, String>

    /**
     * Triggers publication of a deployment that is in the `VALIDATED` state.
     *
     * This is typically called after [uploadDeploymentBundle] when using
     * [PublishingType.USER_MANAGED] and the caller decides to release the artifacts.
     *
     * @param credentials Authentication credentials.
     * @param deploymentId Identifier of the deployment to publish.
     * @return [HttpResponseResult.Success] with [Unit] on success, or an error result.
     */
    public suspend fun publishDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

    /**
     * Drops (deletes) a deployment, removing it from Maven Central.
     *
     * Used for cleanup when validation or publishing fails, or when the caller wants to
     * abort an in-progress deployment.
     *
     * @param credentials Authentication credentials.
     * @param deploymentId Identifier of the deployment to drop.
     * @return [HttpResponseResult.Success] with [Unit] on success, or an error result.
     */
    public suspend fun dropDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

}
