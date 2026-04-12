package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.DeploymentRecoveryHandler
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.createApiClient as createDefaultApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.client.model.toGradleException
import java.time.Duration
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishBundleMavenCentralTask @Inject constructor(
    private val objects: ObjectFactory
) : DefaultTask() {

    @get:Input
    public abstract val baseUrl: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val zipFile: RegularFileProperty

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
        description = "Publishes a deployment bundle to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundle() {
        validateInputs()?.let { throw it.toGradleException() }

        val creds = credentials.get().fold(
            onSuccess = { it },
            onFailure = { throw it.toGradleException() }
        )

        val error = executePublishing(creds)
        error?.let { throw it.toGradleException() }
    }

    private fun executePublishing(creds: Credentials): DeploymentError? {
        val bundleFile = zipFile.asFile.get()
        val type = publishingType.orNull
        val name = deploymentName.orNull
        val maxChecks = maxStatusChecks.get()
        val checkDelay = statusCheckDelay.get()

        logger.lifecycle("Publishing deployment bundle: ${bundleFile.name}. Publishing type: ${type ?: PublishingType.AUTOMATIC}. Deployment name: $name")

        return createApiClient(baseUrl.get()).use { apiClient ->
            val recoveryHandler = DeploymentRecoveryHandler(apiClient, creds, logger)

            apiClient.uploadDeploymentBundle(
                credentials = creds, bundle = bundleFile.toPath(), publishingType = type, deploymentName = name
            ).foldHttp(
                onSuccess = { deploymentId, _, _ ->
                    val waitResult = waitForDeploymentCompletion(apiClient, creds, deploymentId, type, maxChecks, checkDelay)
                    waitResult.fold(
                        onSuccess = { null },
                        onFailure = { error -> recoveryHandler.recover(deploymentId, error) }
                    )
                },
                onError = { data, _, httpStatus, _ ->
                    DeploymentError.UploadFailed(httpStatus, data)
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentError.UploadUnexpected(cause)
                }
            )
        }
    }

    private fun validateInputs(): ValidationError? {
        if (!zipFile.isPresent) return ValidationError.MissingProperty("zipFile")
        if (!credentials.isPresent) return ValidationError.MissingProperty("credentials")

        val file = zipFile.asFile.get()
        if (!file.exists()) return ValidationError.InvalidFile(file.absolutePath, "Bundle file does not exist")
        if (!file.isFile) return ValidationError.InvalidFile(file.absolutePath, "Bundle path is not a file")
        if (file.length() == 0L) return ValidationError.InvalidFile(file.absolutePath, "Bundle file is empty")

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) return ValidationError.InvalidValue("maxStatusChecks", "must be at least 1, got: $maxChecks")

        return null
    }

    private fun waitForDeploymentCompletion(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentId: UUID,
        publishingType: PublishingType?,
        maxChecks: Int, checkDelay: Duration
    ): Outcome<Unit, DeploymentError> {
        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1

            val stepResult: DeploymentPollStep = client.deploymentStatus(creds, deploymentId).foldHttp(
                onSuccess = { status, _, _ ->
                    logger.debug("Deployment status check ({}/{}): {}", checkNumber, maxChecks, status.deploymentState)

                    when (evaluateState(status.deploymentState, publishingType)) {
                        DeploymentState.SUCCESS -> {
                            if (publishingType == PublishingType.USER_MANAGED) {
                                logger.lifecycle("Note: USER_MANAGED publishing type - you may need to manually release the deployment in Central Portal")
                            }
                            DeploymentPollStep.Done
                        }
                        DeploymentState.FAILED -> DeploymentPollStep.Terminal(
                            DeploymentError.DeploymentFailed(status.deploymentState, status.errors)
                        )
                        DeploymentState.IN_PROGRESS -> {
                            if (checkNumber >= maxChecks) {
                                DeploymentPollStep.Terminal(DeploymentError.Timeout(status.deploymentState, maxChecks))
                            } else {
                                DeploymentPollStep.Continue
                            }
                        }
                    }
                },
                onError = { data, _, httpStatus, _ ->
                    DeploymentPollStep.Terminal(DeploymentError.StatusCheckFailed(httpStatus, data))
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentPollStep.Terminal(DeploymentError.StatusCheckUnexpected(cause))
                }
            )

            when (stepResult) {
                is DeploymentPollStep.Done -> return Success(Unit)
                is DeploymentPollStep.Terminal -> return Failure(stepResult.error)
                is DeploymentPollStep.Continue -> {
                    try {
                        Thread.sleep(checkDelay.toMillis())
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }

        return Failure(DeploymentError.Timeout(DeploymentStateType.UNKNOWN, maxChecks))
    }

    private fun evaluateState(state: DeploymentStateType, publishingType: PublishingType?): DeploymentState =
        when (state) {
            DeploymentStateType.PENDING, DeploymentStateType.VALIDATING -> DeploymentState.IN_PROGRESS
            DeploymentStateType.VALIDATED -> {
                if (publishingType == PublishingType.USER_MANAGED) DeploymentState.SUCCESS
                else DeploymentState.IN_PROGRESS
            }
            DeploymentStateType.PUBLISHING -> DeploymentState.IN_PROGRESS
            DeploymentStateType.PUBLISHED -> DeploymentState.SUCCESS
            DeploymentStateType.FAILED, DeploymentStateType.UNKNOWN -> DeploymentState.FAILED
        }

    private enum class DeploymentState {
        SUCCESS, IN_PROGRESS, FAILED
    }

    private sealed class DeploymentPollStep {
        data object Done : DeploymentPollStep()
        data object Continue : DeploymentPollStep()
        data class Terminal(val error: DeploymentError) : DeploymentPollStep()
    }
}
