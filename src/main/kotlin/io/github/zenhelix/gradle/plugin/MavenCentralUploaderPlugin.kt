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
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.bundling.Zip
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningPlugin

public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val mavenCentralUploaderExtension = target.createExtension()

        val publishLifecycleTask = target.tasks.named(PUBLISH_LIFECYCLE_TASK_NAME);

        val publishAllPublicationsTask = target.registerPublishAllPublicationsTask()
        val zipAllPublicationsTask = target.registerZipAllPublicationsTask()

        target.afterEvaluate {
            val mavenPublications = this.mavenPublications()

            val allTaskDependencies = mutableListOf<TaskDependency>()
            mavenPublications?.forEach { it.allPublishableArtifacts { allTaskDependencies.add(buildDependencies) } }

            mavenPublications?.forEach { publication ->
                val publicationName = publication.name

                val createChecksums = this.registerCreateChecksums(publicationName) {
                    allTaskDependencies.forEach { this.dependsOn(it) }
                }

                val zipTask = this.registerZipPublicationTask(publicationName) {

                    allTaskDependencies.forEach { this.dependsOn(it) }
                    dependsOn(createChecksums)

                    if (mavenPublications.size > 1) {
                        archiveAppendix.set(publicationName)
                    }

                    from(createChecksums) {
                        val mavenPublicationInternal = mavenPublication(publicationName)
                        val publicationInfo = mavenPublicationInternal.mapModel()

                        into(publicationInfo.artifactPath)
                        publicationInfo.artifacts.forEach { artifactInfo ->
                            from(artifactInfo.file()) { rename { artifactInfo.artifactName } }
                        }
                    }
                }
                zipAllPublicationsTask.configure { dependsOn(zipTask) }

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

                publishLifecycleTask.configure { dependsOn(publishPublicationTask) }
                publishAllPublicationsTask.configure { dependsOn(publishPublicationTask) }
            }
        }
    }

    private fun Project.createExtension() = this.extensions.create<MavenCentralUploaderExtension>(MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME)

    private fun Project.mavenPublication(publicationName: String) = project.extensions
        .getByType(PublishingExtension::class.java).publications.getByName(publicationName) as MavenPublicationInternal

    private fun Project.mavenPublications() = this.extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublicationInternal>()

    private fun Project.registerPublishAllPublicationsTask() = this.tasks
        .register<Task>("publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository") {
            group = PUBLISH_TASK_GROUP
            description = "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."
        }

    private fun Project.registerCreateChecksums(publicationName: String, configuration: CreateChecksumTask.() -> Unit = {}) =
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

    private fun Project.registerZipPublicationTask(publicationName: String, configuration: Zip.() -> Unit = {}) =
        this.tasks.register<Zip>("zipDeployment${publicationName.capitalized()}Publication") {
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

    private fun MavenPublicationInternal.mapModel() =
        PublicationInfo(gav = GAV.of(this), artifacts = this.publishableArtifacts.map { ArtifactInfo(artifact = it, gav = GAV.of(this)) })

    private fun PublishingType.mapModel() = when (this) {
        PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
        PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
    }

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }

}