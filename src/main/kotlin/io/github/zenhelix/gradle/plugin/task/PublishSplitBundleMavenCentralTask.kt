package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImpl
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.io.File
import java.time.Duration
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
    public abstract val credentials: Property<Credentials>

    @get:Input
    public abstract val maxStatusChecks: Property<Int>

    @get:Input
    public abstract val statusCheckDelay: Property<Duration>

    protected open fun createApiClient(url: String): MavenCentralApiClient {
        return MavenCentralApiClientImpl(url)
    }

    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes split deployment bundles to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundles() {
        validateInputs()

        val bundlesDir = bundlesDirectory.asFile.get()
        val bundleFiles = bundlesDir
            .listFiles { f -> f.extension == "zip" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (bundleFiles.isEmpty()) {
            throw GradleException("No ZIP bundles found in ${bundlesDir.absolutePath}")
        }

        val creds = credentials.get()
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

        try {
            createApiClient(baseUrl.get()).use { client ->
                val deploymentIds = uploadAllBundles(client, creds, bundleFiles, effectiveType, baseName)

                try {
                    waitForAllDeploymentsValidated(client, creds, deploymentIds, effectiveType, maxChecks, checkDelay)

                    if (effectiveType != requestedType) {
                        publishAllDeployments(client, creds, deploymentIds)
                    }
                } catch (e: Exception) {
                    dropAllDeployments(client, creds, deploymentIds)
                    throw e
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to publish deployment bundles: ${e.message}", e)
        }
    }

    private fun uploadAllBundles(
        client: MavenCentralApiClient,
        creds: Credentials,
        bundleFiles: List<File>,
        effectiveType: PublishingType?,
        baseName: String?
    ): List<UUID> {
        val deploymentIds = mutableListOf<UUID>()

        val totalChunks = bundleFiles.size
        bundleFiles.forEachIndexed { index, bundleFile ->
            val chunkNumber = index + 1
            val chunkName = if (baseName != null) "$baseName-chunk-$chunkNumber" else null

            logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks: ${bundleFile.name}...")

            val result = client.uploadDeploymentBundle(
                credentials = creds,
                bundle = bundleFile.toPath(),
                publishingType = effectiveType,
                deploymentName = chunkName
            )

            when (result) {
                is HttpResponseResult.Success -> {
                    val deploymentId = result.data
                    deploymentIds.add(deploymentId)
                    logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks... OK (deployment: $deploymentId)")
                }

                is HttpResponseResult.Error -> {
                    dropAllDeployments(client, creds, deploymentIds)
                    throw GradleException(
                        "Failed to upload chunk $chunkNumber/$totalChunks (${bundleFile.name}): " +
                                "HTTP ${result.httpStatus}, Response: ${result.data}. " +
                                "Rolled back ${deploymentIds.size} previously uploaded deployment(s)."
                    )
                }

                is HttpResponseResult.UnexpectedError -> {
                    dropAllDeployments(client, creds, deploymentIds)
                    throw GradleException(
                        "Unexpected error uploading chunk $chunkNumber/$totalChunks (${bundleFile.name}). " +
                                "Rolled back ${deploymentIds.size} previously uploaded deployment(s).",
                        result.cause
                    )
                }
            }
        }

        return deploymentIds
    }

    private fun waitForAllDeploymentsValidated(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>,
        effectiveType: PublishingType?,
        maxChecks: Int,
        checkDelay: Duration
    ) {
        val terminalStates = mutableMapOf<UUID, DeploymentStateType>()

        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1
            val pendingIds = deploymentIds.filter { it !in terminalStates }

            for (deploymentId in pendingIds) {
                when (val statusResult = client.deploymentStatus(creds, deploymentId)) {
                    is HttpResponseResult.Success -> {
                        val status = statusResult.data
                        val state = status.deploymentState

                        when {
                            state == DeploymentStateType.FAILED || state == DeploymentStateType.UNKNOWN -> {
                                throw GradleException(buildString {
                                    append("Deployment $deploymentId failed with status: $state")
                                    if (!status.errors.isNullOrEmpty()) {
                                        append("\nErrors: ${status.errors}")
                                    }
                                })
                            }

                            state == DeploymentStateType.PUBLISHED -> {
                                terminalStates[deploymentId] = state
                            }

                            state == DeploymentStateType.VALIDATED && effectiveType == PublishingType.USER_MANAGED -> {
                                terminalStates[deploymentId] = state
                            }
                        }
                    }

                    is HttpResponseResult.Error -> throw GradleException(
                        "Failed to check deployment status for $deploymentId: HTTP ${statusResult.httpStatus}, Response: ${statusResult.data}"
                    )

                    is HttpResponseResult.UnexpectedError -> throw GradleException(
                        "Unexpected error checking deployment status for $deploymentId", statusResult.cause
                    )
                }
            }

            val statusSummary = deploymentIds.mapIndexed { i, id ->
                val state = terminalStates[id]?.name ?: "PENDING"
                "${i + 1}/${deploymentIds.size} $state"
            }
            logger.lifecycle("Validating deployments... [${statusSummary.joinToString(", ")}]")

            if (terminalStates.size == deploymentIds.size) {
                logger.lifecycle("All ${deploymentIds.size} deployment(s) validated successfully.")
                return
            }

            if (checkNumber < maxChecks) {
                try {
                    Thread.sleep(checkDelay.toMillis())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }

        throw GradleException(
            "Deployments did not complete after $maxChecks status checks. " +
                    "Check Maven Central Portal for current status."
        )
    }

    private fun publishAllDeployments(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>
    ) {
        logger.lifecycle("Publishing all ${deploymentIds.size} deployment(s)...")

        val publishedIds = mutableSetOf<UUID>()

        for (deploymentId in deploymentIds) {
            when (val result = client.publishDeployment(creds, deploymentId)) {
                is HttpResponseResult.Success -> {
                    publishedIds.add(deploymentId)
                    logger.lifecycle("Published deployment $deploymentId")
                }

                is HttpResponseResult.Error -> {
                    handlePublishFailure(client, creds, deploymentIds, publishedIds, deploymentId,
                        "Failed to publish deployment $deploymentId: HTTP ${result.httpStatus}.", null)
                }

                is HttpResponseResult.UnexpectedError -> {
                    handlePublishFailure(client, creds, deploymentIds, publishedIds, deploymentId,
                        "Unexpected error publishing deployment $deploymentId.", result.cause)
                }
            }
        }

        logger.lifecycle("Published successfully.")
    }

    private fun handlePublishFailure(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>,
        publishedIds: Set<UUID>,
        failedId: UUID,
        message: String,
        cause: Exception?
    ): Nothing {
        val unpublished = deploymentIds.filter { it !in publishedIds && it != failedId }
        dropAllDeployments(client, creds, unpublished)

        throw GradleException(
            "$message WARNING: ${publishedIds.size} deployment(s) may already be published " +
                    "and cannot be rolled back (API limitation). " +
                    "Dropped ${unpublished.size} remaining unpublished deployment(s).",
            cause
        )
    }

    private fun dropAllDeployments(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>
    ) {
        for (deploymentId in deploymentIds) {
            try {
                when (val result = client.dropDeployment(creds, deploymentId)) {
                    is HttpResponseResult.Success -> {
                        logger.lifecycle("Dropped deployment $deploymentId")
                    }

                    is HttpResponseResult.Error -> {
                        logger.warn("Failed to drop deployment $deploymentId: HTTP ${result.httpStatus}, Response: ${result.data}")
                    }

                    is HttpResponseResult.UnexpectedError -> {
                        logger.warn("Failed to drop deployment $deploymentId: ${result.cause.message}")
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("Interrupted while dropping deployment $deploymentId")
            } catch (e: Exception) {
                logger.warn("Failed to drop deployment $deploymentId: ${e.message}")
            }
        }
    }

    private fun validateInputs() {
        if (!bundlesDirectory.isPresent) {
            throw GradleException("Property 'bundlesDirectory' is required but not set")
        }

        if (!credentials.isPresent) {
            throw GradleException("Property 'credentials' is required but not set")
        }

        val dir = bundlesDirectory.asFile.get()
        if (!dir.exists()) {
            throw GradleException("Bundles directory does not exist: ${dir.absolutePath}")
        }

        if (!dir.isDirectory) {
            throw GradleException("Bundles path is not a directory: ${dir.absolutePath}")
        }

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) {
            throw GradleException("maxStatusChecks must be at least 1, got: $maxChecks")
        }
    }
}
