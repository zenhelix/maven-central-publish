package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.createApiClient as createDefaultApiClient
import io.github.zenhelix.gradle.plugin.client.tryDropDeployment
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.time.Duration
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Task for publishing deployment bundles to Maven Central Portal.
 *
 * This task uploads a ZIP bundle containing artifacts, signatures, and checksums
 * to Maven Central Portal and waits for the deployment to complete.
 */
@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishBundleMavenCentralTask @Inject constructor(
    private val objects: ObjectFactory
) : DefaultTask() {

    /**
     * Base URL for Maven Central Portal API.
     * Default: https://central.sonatype.com
     */
    @get:Input
    public abstract val baseUrl: Property<String>

    /**
     * ZIP file containing the deployment bundle.
     * Must contain artifacts, POM files, signatures, and checksums.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val zipFile: RegularFileProperty

    /**
     * Publishing type - AUTOMATIC or USER_MANAGED.
     * AUTOMATIC: Deployment will be automatically released after validation
     * USER_MANAGED: User must manually release through Central Portal UI
     */
    @get:Input
    @get:Optional
    public abstract val publishingType: Property<PublishingType>

    /**
     * Optional deployment name for identification in Central Portal.
     * If not provided, a default name will be generated.
     */
    @get:Input
    @get:Optional
    public abstract val deploymentName: Property<String>

    /**
     * Credentials for accessing Maven Central Portal API.
     */
    @get:Input
    public abstract val credentials: Property<Credentials>

    /**
     * Maximum number of status checks for deployment completion.
     * Default: 20
     */
    @get:Input
    public abstract val maxStatusChecks: Property<Int>

    /**
     * Delay between status checks.
     * Default: 10 seconds
     */
    @get:Input
    public abstract val statusCheckDelay: Property<Duration>

    /**
     * Creates the API client. Called at execution time (not configuration time)
     * to avoid configuration cache serialization issues with HttpClient.
     */
    protected open fun createApiClient(url: String): MavenCentralApiClient = createDefaultApiClient(url)

    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes a deployment bundle to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundle() {
        validateInputs()

        val bundleFile = zipFile.asFile.get()
        val creds = credentials.get()
        val client = createApiClient(baseUrl.get())
        val type = publishingType.orNull
        val name = deploymentName.orNull
        val maxChecks = maxStatusChecks.get()
        val checkDelay = statusCheckDelay.get()

        logger.lifecycle("Publishing deployment bundle: ${bundleFile.name}. Publishing type: ${type ?: PublishingType.AUTOMATIC}. Deployment name: $name")

        try {
            client.use { apiClient ->
                val uploadResult = apiClient.uploadDeploymentBundle(
                    credentials = creds, bundle = bundleFile.toPath(), publishingType = type, deploymentName = name
                )

                when (uploadResult) {
                    is HttpResponseResult.Success -> {
                        val deploymentId = uploadResult.data
                        try {
                            waitForDeploymentCompletion(apiClient, creds, deploymentId, type, maxChecks, checkDelay)
                        } catch (e: DeploymentFailedException) {
                            if (e.lastState.isDroppable) {
                                tryDropDeployment(apiClient, creds, deploymentId)
                            } else {
                                logger.warn(
                                    "Deployment {} is in {} state and cannot be dropped. Check Maven Central Portal for current status.",
                                    deploymentId, e.lastState
                                )
                            }
                            throw e
                        } catch (e: GradleException) {
                            // Status check HTTP error or parse failure — deployment state unknown,
                            // attempt drop as best-effort (tryDropDeployment never throws)
                            tryDropDeployment(apiClient, creds, deploymentId)
                            throw e
                        }
                    }

                    is HttpResponseResult.Error -> {
                        throw GradleException("Failed to upload bundle: HTTP ${uploadResult.httpStatus}, Response: ${uploadResult.data}")
                    }

                    is HttpResponseResult.UnexpectedError -> {
                        throw GradleException("Unexpected error during bundle upload", uploadResult.cause)
                    }
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to publish deployment bundle: ${e.message}", e)
        }
    }

    private fun validateInputs() {
        if (!zipFile.isPresent) {
            throw GradleException("Property 'zipFile' is required but not set")
        }

        if (!credentials.isPresent) {
            throw GradleException("Property 'credentials' is required but not set")
        }

        val file = zipFile.asFile.get()
        if (!file.exists()) {
            throw GradleException("Bundle file does not exist: ${file.absolutePath}")
        }

        if (!file.isFile) {
            throw GradleException("Bundle path is not a file: ${file.absolutePath}")
        }

        if (file.length() == 0L) {
            throw GradleException("Bundle file is empty: ${file.absolutePath}")
        }

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) {
            throw GradleException("maxStatusChecks must be at least 1, got: $maxChecks")
        }
    }

    /**
     * Polls the deployment status endpoint until the deployment reaches a terminal state.
     *
     * State machine transitions:
     * - PENDING → VALIDATING → VALIDATED → PUBLISHING → PUBLISHED (success for AUTOMATIC)
     * - PENDING → VALIDATING → VALIDATED (success for USER_MANAGED — user releases manually)
     * - Any state → FAILED (error — deployment is dropped by caller)
     * - UNKNOWN is treated as FAILED
     *
     * If the deployment does not reach a terminal state within [maxChecks] polls,
     * a [DeploymentFailedException] is thrown. The caller checks [DeploymentStateType.isDroppable]
     * to decide whether to attempt dropping — deployments in PUBLISHING state cannot be dropped.
     */
    private fun waitForDeploymentCompletion(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentId: UUID,
        publishingType: PublishingType?,
        maxChecks: Int, checkDelay: Duration
    ) {
        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1

            when (val statusResult = client.deploymentStatus(creds, deploymentId)) {
                is HttpResponseResult.Success -> {
                    val status = statusResult.data
                    logger.debug("Deployment status check ({}/{}): {}", checkNumber, maxChecks, status.deploymentState)

                    val state = when (status.deploymentState) {
                        DeploymentStateType.PENDING -> DeploymentState.IN_PROGRESS
                        DeploymentStateType.VALIDATING -> DeploymentState.IN_PROGRESS
                        DeploymentStateType.VALIDATED -> {
                            if (publishingType == PublishingType.USER_MANAGED) {
                                DeploymentState.SUCCESS
                            } else {
                                DeploymentState.IN_PROGRESS
                            }
                        }

                        DeploymentStateType.PUBLISHING -> DeploymentState.IN_PROGRESS
                        DeploymentStateType.PUBLISHED -> DeploymentState.SUCCESS
                        DeploymentStateType.FAILED -> DeploymentState.FAILED
                        DeploymentStateType.UNKNOWN -> DeploymentState.FAILED
                    }

                    when (state) {
                        DeploymentState.SUCCESS -> {
                            if (publishingType == PublishingType.USER_MANAGED) {
                                logger.lifecycle("Note: USER_MANAGED publishing type - you may need to manually release the deployment in Central Portal")
                            }
                            return
                        }

                        DeploymentState.FAILED -> throw DeploymentFailedException(
                            status.deploymentState,
                            buildString {
                                append("Deployment failed with status: ${status.deploymentState}")
                                if (!status.errors.isNullOrEmpty()) {
                                    append("\nErrors: ${status.errors}")
                                }
                            }
                        )

                        DeploymentState.IN_PROGRESS -> {
                            if (checkNumber < maxChecks) {
                                try {
                                    Thread.sleep(checkDelay.toMillis())
                                } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    throw e
                                }
                            } else {
                                throw DeploymentFailedException(
                                    status.deploymentState,
                                    "Deployment did not complete after $maxChecks status checks. Current status: ${status.deploymentState}. Check Maven Central Portal for current status."
                                )
                            }
                        }
                    }
                }

                is HttpResponseResult.Error -> throw GradleException("Failed to check deployment status: HTTP ${statusResult.httpStatus}, Response: ${statusResult.data}")
                is HttpResponseResult.UnexpectedError -> throw GradleException(
                    "Unexpected error while checking deployment status",
                    statusResult.cause
                )
            }
        }
    }

    private fun tryDropDeployment(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentId: UUID
    ) {
        logger.warn("Deployment failed, attempting to drop deployment {}", deploymentId)
        client.tryDropDeployment(creds, deploymentId, logger)
    }

    private enum class DeploymentState {
        SUCCESS, IN_PROGRESS, FAILED
    }

}

/**
 * Exception thrown when a deployment fails or times out.
 * Carries the [lastState] so the caller can decide whether to attempt dropping the deployment.
 */
internal class DeploymentFailedException(
    val lastState: DeploymentStateType,
    message: String
) : GradleException(message)

/**
 * Whether a deployment in this state can be dropped via the Maven Central Portal API.
 * Deployments in PUBLISHING or PUBLISHED state cannot be dropped.
 */
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