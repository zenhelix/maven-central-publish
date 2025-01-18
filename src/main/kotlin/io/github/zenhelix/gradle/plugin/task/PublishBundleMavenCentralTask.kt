package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImpl
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.time.Duration

public abstract class PublishBundleMavenCentralTask : DefaultTask() {

    @get:Input
    public abstract val baseUrl: Property<String>

    @get:InputFile
    public abstract val zipFile: RegularFileProperty

    @get:Input
    @get:Optional
    public abstract val publishingType: Property<PublishingType>

    @get:Input
    @get:Optional
    public abstract val deploymentName: Property<String>

    @get:Input
    public abstract val credentials: Property<Credentials>

    @get:Input
    public abstract val maxRetriesStatusCheck: Property<Int>

    @get:Input
    public abstract val delayRetriesStatusCheck: Property<Duration>

    @TaskAction
    public fun uploadZip() {
        if (!zipFile.isPresent) {
            throw IllegalArgumentException("Property 'zipFile' not presents")
        }

        if (!credentials.isPresent) {
            throw IllegalArgumentException("Property 'credentials' not presents")
        }

        val file = zipFile.asFile.get()

        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("'${file.absolutePath}' not found")
        }

        val apiClient = MavenCentralApiClientImpl(baseUrl.get())

        val publishingType = publishingType.orNull

        val deploymentId = apiClient.uploadDeploymentBundle(
            credentials = credentials.get(),
            bundle = file.toPath(),
            publishingType = publishingType,
            deploymentName = deploymentName.orNull
        ).result()

        var attempt = 0
        repeat(maxRetriesStatusCheck.get()) {
            val deploymentStatus = apiClient.deploymentStatus(
                credentials = credentials.get(),
                deploymentId = deploymentId
            ).result()

            val state = when (deploymentStatus.deploymentState) {
                DeploymentStateType.PENDING                                   -> State.PROCESSED
                DeploymentStateType.VALIDATING                                -> State.PROCESSED
                DeploymentStateType.VALIDATED                                 -> if (publishingType == PublishingType.USER_MANAGED) {
                    State.SUCCESS
                } else {
                    State.PROCESSED
                }

                DeploymentStateType.PUBLISHING, DeploymentStateType.PUBLISHED -> State.SUCCESS
                DeploymentStateType.FAILED, DeploymentStateType.UNKNOWN       -> State.FAILED
            }

            when (state) {
                State.SUCCESS   -> {
                    logger.lifecycle("Deployment succeeded.")
                    return
                }

                State.FAILED    -> throw IllegalStateException("Deployment failed. $deploymentStatus")
                State.PROCESSED -> {
                    logger.lifecycle("Deployment in progress (attempt $attempt of ${maxRetriesStatusCheck.get()}).")
                }
            }

            attempt++

            if (attempt < maxRetriesStatusCheck.get()) {
                val delay = delayRetriesStatusCheck.get().toMillis() * (1 shl (attempt - 1)) // exponential delay
                Thread.sleep(delay)
            } else {
                throw IllegalStateException("Max retries reached. Deployment did not complete.")
            }
        }
    }

    private enum class State { SUCCESS, PROCESSED, FAILED }

}