package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import io.github.zenhelix.gradle.plugin.task.PublishBundleMavenCentralTask
import io.github.zenhelix.gradle.plugin.task.ZipDeploymentTask
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mavenPublication
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningPlugin

public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val mavenCentralUploaderExtension = target.createExtension()

        val publishLifecycleTask = target.findPublishLifecycleTask()

        val publishAllPublicationsTask = target.registerPublishAllPublicationsTask()
        val zipAllPublicationsTask = target.registerZipAllPublicationsTask()
        val checksumsAllPublicationsTask = target.registerCreateChecksumsAllPublicationsTask()

        target.afterEvaluate {
            val mavenPublications = this.findMavenPublications() ?: emptyList()

            val allTaskDependencies = mutableListOf<TaskDependency>()
            mavenPublications.forEach { it.allPublishableArtifacts { allTaskDependencies.add(buildDependencies) } }

            val createChecksumsTasks = mavenPublications.associate { mavenPublication ->
                val publicationName = mavenPublication.name

                val createChecksumsTask = this.registerCreatePublicationChecksumsTask(publicationName) {
                    allTaskDependencies.forEach { this.dependsOn(it) }
                }

                publicationName to createChecksumsTask
            }
            createChecksumsTasks.values.forEach { checksumsAllPublicationsTask.configure { dependsOn(it) } }

            val zipTasks = createChecksumsTasks.mapValues { (publicationName, createChecksumsTask) ->
                val zipTask = this.registerZipPublicationTask(publicationName) {
                    allTaskDependencies.forEach { this.dependsOn(it) }
                    dependsOn(createChecksumsTask)

                    val publicationInfo = mavenPublication(publicationName).mapModel(createChecksumsTask)

                    if (mavenPublications.size > 1) {
                        this.archiveAppendix.set(publicationName)
                    }
                    this.publicationInfo.set(publicationInfo)

                    configureArtifacts()
                }

                zipTask
            }

            zipTasks.values.forEach { zipAllPublicationsTask.configure { dependsOn(it) } }

            val publishPublicationTasks = zipTasks.mapValues { (publicationName, zipTask) ->
                val publishPublicationTask = registerPublishPublicationTask(publicationName) {
                    dependsOn(zipTask)

                    zipFile.set(zipTask.flatMap { it.archiveFile })

                    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
                    credentials.set(mavenCentralUploaderExtension.mapCredentials())

                    publishingType.set(mavenCentralUploaderExtension.publishingType.map { it.mapModel() })
                    deploymentName.set(mavenCentralUploaderExtension.deploymentName)

                    maxRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.maxRetriesStatusCheck)
                    delayRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.delayRetriesStatusCheck)
                }

                publishPublicationTask
            }

            publishPublicationTasks.values.forEach {
                publishLifecycleTask.configure { dependsOn(it) }
                publishAllPublicationsTask.configure { dependsOn(it) }
            }
        }
    }

    private fun Project.createExtension() = this.extensions.create<MavenCentralUploaderExtension>(MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME)

    private fun Project.registerPublishAllPublicationsTask() = this.tasks
        .register<Task>("publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository") {
            group = PUBLISH_TASK_GROUP
            description = "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."
        }

    private fun Project.registerCreateChecksumsAllPublicationsTask() = this.tasks.register<Task>("checksumAllPublications") {
        group = PUBLISH_TASK_GROUP
        description = "Generate checksums"
    }

    private fun Project.registerCreatePublicationChecksumsTask(publicationName: String, configuration: CreateChecksumTask.() -> Unit = {}) =
        this.tasks.register<CreateChecksumTask>("checksum${publicationName.capitalized()}") {
            group = PUBLISH_TASK_GROUP
            description = "Generate checksums for $publicationName"

            this.publicationName.set(publicationName)

            configuration()
        }

    private fun Project.registerZipAllPublicationsTask() = this.tasks.register<Task>("zipDeploymentAllPublications") {
        group = PUBLISH_TASK_GROUP
        description = "Deployment bundle for all publications"
    }

    private fun Project.registerZipPublicationTask(publicationName: String, configuration: ZipDeploymentTask.() -> Unit = {}) =
        this.tasks.register<ZipDeploymentTask>("zipDeployment${publicationName.capitalized()}Publication") {
            group = PUBLISH_TASK_GROUP
            description = "Deployment bundle for $publicationName"

            configuration()
        }

    private fun Project.registerPublishPublicationTask(publicationName: String, configuration: PublishBundleMavenCentralTask.() -> Unit = {}) =
        this.tasks.register<PublishBundleMavenCentralTask>("publish${publicationName.capitalized()}PublicationTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}") {
            group = PUBLISH_TASK_GROUP
            description = "Publishes Maven publication '$publicationName' to Maven repository '$MAVEN_CENTRAL_PORTAL_NAME'."

            configuration()
        }

    private fun MavenCentralUploaderExtension.mapCredentials() = credentials.username.flatMap { username ->
        credentials.password.map { password -> Credentials.UsernamePasswordCredentials(username, password) }
    }

    private fun MavenPublicationInternal.mapModel(checksumTask: TaskProvider<CreateChecksumTask>) = PublicationInfo(
        gav = GAV.of(this),
        publicationName = this.name,
        artifacts = this.publishableArtifacts.map { ArtifactInfo(artifact = it, gav = GAV.of(this)) },
        checksumTask = checksumTask
    )

    private fun PublishingType.mapModel() = when (this) {
        PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
        PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
    }

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }

}