package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.recovery.DeploymentRecoveryHandler
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.createApiClient as createDefaultApiClient
import io.github.zenhelix.gradle.plugin.client.recovery.tryDropDeployment
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import io.github.zenhelix.gradle.plugin.client.model.getOrThrow
import io.github.zenhelix.gradle.plugin.client.model.toGradleException
import java.io.File
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Gradle task that publishes a set of split deployment bundle chunks to the Maven Central Portal.
 *
 * When a single bundle exceeds [UploaderSettingsExtension.maxBundleSize] the plugin splits it
 * into multiple smaller ZIP chunks and delegates to this task instead of
 * [PublishBundleMavenCentralTask]. The task:
 * 1. Uploads all chunks sequentially, rolling back already-uploaded deployments on any upload failure.
 * 2. Waits until every chunk reaches the `VALIDATED` (or `PUBLISHED`) state before proceeding,
 *    ensuring atomic validation across all chunks.
 * 3. Publishes all validated deployments together when [PublishingType.AUTOMATIC] was requested.
 * 4. Drops all deployments on validation or publish failure to avoid partial releases.
 */
@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishSplitBundleMavenCentralTask : DefaultTask() {

    @get:Input
    public abstract val baseUrl: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val bundlesDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    public abstract val publishingType: Property<PublishingType>

    @get:Input
    @get:Optional
    public abstract val deploymentName: Property<String>

    @get:Input
    public abstract val credentials: Property<Outcome<Credentials, ValidationError>>

    @get:Input
    public abstract val maxStatusChecks: Property<Int>

    @get:Input
    public abstract val statusCheckDelay: Property<Duration>

    protected open fun createApiClient(url: String): MavenCentralApiClient = createDefaultApiClient(url)

    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes split deployment bundles to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundles(): Unit = runBlocking {
        doPublishBundles()
    }

    private suspend fun doPublishBundles() {
        validateInputs().getOrThrow { it.toGradleException() }
        val creds = credentials.get().getOrThrow { it.toGradleException() }

        val error = executePublishing(creds)
        error?.let { throw it.toGradleException() }
    }

    private suspend fun executePublishing(creds: Credentials): DeploymentError? {
        val bundlesDir = bundlesDirectory.asFile.get()
        val bundleFiles = bundlesDir
            .listFiles { f -> f.extension == "zip" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (bundleFiles.isEmpty()) {
            return DeploymentError.UploadFailed(HttpStatus(0), "No ZIP bundles found in ${bundlesDir.absolutePath}")
        }

        val maxChecks = maxStatusChecks.get()
        val checkDelay = statusCheckDelay.get()
        val baseName = deploymentName.orNull

        val requestedType = publishingType.orNull
        val effectiveType = if (bundleFiles.size > 1 && requestedType == PublishingType.AUTOMATIC) {
            logger.lifecycle(
                "Bundle was split into ${bundleFiles.size} chunks. " +
                        "Switching to USER_MANAGED mode for atomic deployment. " +
                        "All chunks will be published after successful validation."
            )
            PublishingType.USER_MANAGED
        } else {
            requestedType
        }

        val client = createApiClient(baseUrl.get())
        return try {
            val recoveryHandler = DeploymentRecoveryHandler(client, creds, logger)
            val lastKnownStates = mutableMapOf<DeploymentId, DeploymentStateType>()

            val uploadResult = uploadAllBundles(client, creds, bundleFiles, effectiveType, baseName)
            val deploymentIds = uploadResult.getOrNull() ?: return uploadResult.errorOrNull()

            val validationResult = waitForAllDeploymentsValidated(
                client, creds, deploymentIds, effectiveType, maxChecks, checkDelay, lastKnownStates
            )
            val validationError = validationResult.errorOrNull()
                ?.let { recoveryHandler.recoverAll(deploymentIds, lastKnownStates, it) }
            if (validationError != null) return validationError

            if (effectiveType != requestedType) {
                val publishError = publishAllDeployments(client, creds, deploymentIds, recoveryHandler).fold(
                    onSuccess = { null },
                    onFailure = { it }
                )
                if (publishError != null) return publishError
            }

            null
        } finally {
            client.close()
        }
    }

    private suspend fun uploadAllBundles(
        client: MavenCentralApiClient,
        creds: Credentials,
        bundleFiles: List<File>,
        effectiveType: PublishingType?,
        baseName: String?
    ): Outcome<List<DeploymentId>, DeploymentError> {
        val deploymentIds = mutableListOf<DeploymentId>()

        val totalChunks = bundleFiles.size
        for ((index, bundleFile) in bundleFiles.withIndex()) {
            val chunkNumber = index + 1
            val chunkName = if (baseName != null) "$baseName-chunk-$chunkNumber" else null

            logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks: ${bundleFile.name}...")

            val result = client.uploadDeploymentBundle(
                credentials = creds,
                bundle = bundleFile.toPath(),
                publishingType = effectiveType,
                deploymentName = chunkName
            )

            val uploadError = result.foldHttp(
                onSuccess = { data, _, _ ->
                    deploymentIds.add(data)
                    logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks... OK (deployment: $data)")
                    null
                },
                onError = { data, _, httpStatus, _ ->
                    DeploymentError.UploadFailed(
                        httpStatus,
                        "Failed to upload chunk $chunkNumber/$totalChunks (${bundleFile.name}): " +
                                "HTTP $httpStatus, Response: $data. " +
                                "Rolled back ${deploymentIds.size} previously uploaded deployment(s)."
                    )
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentError.UploadUnexpected(
                        Exception(
                            "Unexpected error uploading chunk $chunkNumber/$totalChunks (${bundleFile.name}). " +
                                    "Rolled back ${deploymentIds.size} previously uploaded deployment(s).",
                            cause
                        )
                    )
                }
            )

            if (uploadError != null) {
                for (id in deploymentIds) {
                    client.tryDropDeployment(creds, id, logger)
                }
                return Failure(uploadError)
            }
        }

        return Success(deploymentIds)
    }

    private suspend fun waitForAllDeploymentsValidated(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<DeploymentId>,
        effectiveType: PublishingType?,
        maxChecks: Int,
        checkDelay: Duration,
        lastKnownStates: MutableMap<DeploymentId, DeploymentStateType>
    ): Outcome<Unit, DeploymentError> {
        val terminalStates = mutableMapOf<DeploymentId, DeploymentStateType>()

        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1
            val pendingIds = deploymentIds.filter { it !in terminalStates }

            for (deploymentId in pendingIds) {
                val statusResult = client.deploymentStatus(creds, deploymentId)

                val error = statusResult.foldHttp(
                    onSuccess = { status, _, _ ->
                        val state = status.deploymentState
                        lastKnownStates[deploymentId] = state

                        when (state) {
                            DeploymentStateType.FAILED, DeploymentStateType.UNKNOWN -> {
                                DeploymentError.DeploymentFailed(state, status.errors)
                            }
                            DeploymentStateType.PUBLISHED -> {
                                terminalStates[deploymentId] = state
                                null
                            }
                            DeploymentStateType.VALIDATED if effectiveType == PublishingType.USER_MANAGED -> {
                                terminalStates[deploymentId] = state
                                null
                            }
                            else -> null
                        }
                    },
                    onError = { data, _, httpStatus, _ ->
                        DeploymentError.StatusCheckFailed(httpStatus, data)
                    },
                    onUnexpected = { cause, _, _ ->
                        DeploymentError.StatusCheckUnexpected(cause)
                    }
                )

                if (error != null) {
                    return Failure(error)
                }
            }

            val statusSummary = deploymentIds.mapIndexed { i, id ->
                val state = terminalStates[id]?.name ?: "PENDING"
                "${i + 1}/${deploymentIds.size} $state"
            }
            logger.lifecycle("Validating deployments... [${statusSummary.joinToString(", ")}]")

            if (terminalStates.size == deploymentIds.size) {
                logger.lifecycle("All ${deploymentIds.size} deployment(s) validated successfully.")
                return Success(Unit)
            }

            if (checkNumber < maxChecks) {
                delay(checkDelay.toMillis())
            }
        }

        return Failure(
            DeploymentError.Timeout(
                state = lastKnownStates.values.lastOrNull() ?: DeploymentStateType.UNKNOWN,
                maxChecks = maxChecks
            )
        )
    }

    private suspend fun publishAllDeployments(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<DeploymentId>,
        recoveryHandler: DeploymentRecoveryHandler
    ): Outcome<Unit, DeploymentError> {
        logger.lifecycle("Publishing all ${deploymentIds.size} deployment(s)...")

        val publishedIds = mutableSetOf<DeploymentId>()

        for (deploymentId in deploymentIds) {
            val result = client.publishDeployment(creds, deploymentId)

            val error = result.foldHttp(
                onSuccess = { _, _, _ ->
                    publishedIds.add(deploymentId)
                    logger.lifecycle("Published deployment $deploymentId")
                    null
                },
                onError = { _, _, httpStatus, _ ->
                    DeploymentError.PublishFailed(deploymentId, httpStatus)
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentError.PublishUnexpected(deploymentId, cause)
                }
            )

            if (error != null) {
                val recovered = recoveryHandler.recoverPublishFailure(deploymentIds, publishedIds, deploymentId, error)
                return Failure(recovered)
            }
        }

        logger.lifecycle("Published successfully.")
        return Success(Unit)
    }

    private fun validateInputs(): Outcome<Unit, ValidationError> {
        if (!bundlesDirectory.isPresent) return Failure(ValidationError.MissingProperty("bundlesDirectory"))
        if (!credentials.isPresent) return Failure(ValidationError.MissingProperty("credentials"))

        val dir = bundlesDirectory.asFile.get()
        if (!dir.exists()) return Failure(ValidationError.InvalidFile(dir.absolutePath, "Bundles directory does not exist"))
        if (!dir.isDirectory) return Failure(ValidationError.InvalidFile(dir.absolutePath, "Bundles path is not a directory"))

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) return Failure(ValidationError.InvalidValue("maxStatusChecks", "must be at least 1, got: $maxChecks"))

        return Success(Unit)
    }
}
