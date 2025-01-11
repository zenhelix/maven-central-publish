package io.github.zenhelix

import io.github.zenhelix.client.model.Credentials
import io.github.zenhelix.client.model.PublishingType.AUTOMATIC
import io.github.zenhelix.client.model.PublishingType.USER_MANAGED
import io.github.zenhelix.extension.MavenCentralUploaderExtension
import io.github.zenhelix.extension.MavenCentralUploaderExtension.Companion.EXTENSION_NAME
import io.github.zenhelix.extension.PublishingType
import io.github.zenhelix.task.ArtifactInfo
import io.github.zenhelix.task.CreateChecksumTask
import io.github.zenhelix.task.PublicationInfo
import io.github.zenhelix.task.PublishBundleMavenCentralTask
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
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningPlugin

public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val mavenCentralUploaderExtension = target.extensions.create<MavenCentralUploaderExtension>(EXTENSION_NAME)

        val publishLifecycleTask = target.tasks.named(PUBLISH_LIFECYCLE_TASK_NAME);

        val publishAllPublicationsTask =
            target.tasks.register<Task>("publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository") {
                group = PUBLISH_TASK_GROUP
                description = "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."
            }

        val zipAllPublicationsTask = target.tasks.register<Task>("zipDeploymentAllPublications") {
            group = PUBLISH_TASK_GROUP
            description = "Deployment bundle for all publications"
        }

        target.afterEvaluate {

            val mavenPublications = this.extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublicationInternal>()

            val allTaskDependencies = mutableListOf<TaskDependency>()
            mavenPublications?.forEach { it.allPublishableArtifacts { allTaskDependencies.add(buildDependencies) } }

            mavenPublications?.map { it.asNormalisedPublication() }?.forEach { normalizedPublication ->

                val publication = PublicationInfo(
                    normalizedPublication.projectIdentity,
                    normalizedPublication.allArtifacts.map { ArtifactInfo(artifact = it, gav = normalizedPublication.projectIdentity) }
                )

                val createChecksums = this.tasks.register<CreateChecksumTask>("checksum${normalizedPublication.name.capitalized()}") {
                    group = PUBLISH_TASK_GROUP
                    description = "Generate checksums for ${normalizedPublication.name}"

                    allTaskDependencies.forEach { this.dependsOn(it) }

                    val signatureFiles = tasks.withType<Sign>().flatMap { it.signatureFiles }.toSet()

                    sources = normalizedPublication.allArtifacts.filterNot { signatureFiles.contains(it.file) }.map {
                        ArtifactInfo(artifact = it, gav = normalizedPublication.projectIdentity)
                    }
                }

                val zipTask = this.tasks.register<Zip>("zipDeployment${normalizedPublication.name.capitalized()}Publication") {
                    group = PUBLISH_TASK_GROUP
                    description = "Deployment bundle for ${normalizedPublication.name}"

                    allTaskDependencies.forEach { this.dependsOn(it) }
                    dependsOn(createChecksums)

                    into(publication.artifactPath)
                    publication.artifacts.forEach { artifactInfo ->
                        from(artifactInfo.file()) { rename { artifactInfo.artifactName } }
                    }
                    from(createChecksums)
                }
                zipAllPublicationsTask.configure { dependsOn(zipTask) }

                val publishPublicationTask =
                    this.tasks.register<PublishBundleMavenCentralTask>("publish${normalizedPublication.name.capitalized()}PublicationTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}") {
                        group = PUBLISH_TASK_GROUP
                        description =
                            "Publishes Maven publication '${normalizedPublication.name}' to Maven repository '$MAVEN_CENTRAL_PORTAL_NAME'."

                        dependsOn(zipTask)

                        zipFile.set(zipTask.flatMap { it.archiveFile })

                        baseUrl.set(mavenCentralUploaderExtension.baseUrl)
                        credentials.set(
                            mavenCentralUploaderExtension.credentials.username.flatMap { username ->
                                mavenCentralUploaderExtension.credentials.password.map { password ->
                                    Credentials.UsernamePasswordCredentials(username, password)
                                }
                            }
                        )

                        publishingType.set(mavenCentralUploaderExtension.publishingType.map {
                            when (it) {
                                PublishingType.AUTOMATIC -> AUTOMATIC
                                PublishingType.USER_MANAGED -> USER_MANAGED
                            }
                        })
                        deploymentName.set(mavenCentralUploaderExtension.deploymentName)

                        maxRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.maxRetriesStatusCheck)
                        delayRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.delayRetriesStatusCheck)
                    }

                publishLifecycleTask.configure { dependsOn(publishPublicationTask) }
                publishAllPublicationsTask.configure { dependsOn(publishPublicationTask) }
            }
        }
    }

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }

}