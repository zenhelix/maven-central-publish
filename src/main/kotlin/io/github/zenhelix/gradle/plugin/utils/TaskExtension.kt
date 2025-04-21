package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_NAME
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.PublishBundleMavenCentralTask
import io.github.zenhelix.gradle.plugin.task.ZipDeploymentTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.register

private val PUBLISH_ALL_PUBLICATIONS_TO_CENTRAL_TASK_NAME = "publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"
private const val ZIP_DEPLOYMENT_ALL_PUBLICATIONS_TASK_NAME = "zipDeploymentAllPublications"

internal fun Project.registerCreateChecksumsAllPublicationsTask() = this.tasks.register<Task>("checksumAllPublications") {
    group = PUBLISH_TASK_GROUP
    description = "Generate checksums"
}

internal fun Project.registerCreatePublicationChecksumsTask(publicationName: String, configuration: CreateChecksumTask.() -> Unit = {}) =
    this.tasks.register<CreateChecksumTask>("checksum${publicationName.capitalized()}") {
        group = PUBLISH_TASK_GROUP
        description = "Generate checksums for $publicationName"

        this.publicationName.set(publicationName)

        configuration()
    }

internal fun Project.registerZipAllPublicationsTask() = this.tasks.register<Task>(ZIP_DEPLOYMENT_ALL_PUBLICATIONS_TASK_NAME) {
    group = PUBLISH_TASK_GROUP
    description = "Deployment bundle for all publications"
}

internal fun Project.registerZipAllPublicationsTask(configuration: ZipDeploymentTask.() -> Unit = {}) =
    this.tasks.register<ZipDeploymentTask>(ZIP_DEPLOYMENT_ALL_PUBLICATIONS_TASK_NAME) {
        group = PUBLISH_TASK_GROUP
        description = "Deployment bundle for all publications"

        configuration()
    }

internal fun Project.registerZipPublicationTask(publicationName: String, configuration: ZipDeploymentTask.() -> Unit = {}) =
    this.tasks.register<ZipDeploymentTask>("zipDeployment${publicationName.capitalized()}Publication") {
        group = PUBLISH_TASK_GROUP
        description = "Deployment bundle for $publicationName"

        configuration()
    }

internal fun Project.registerPublishAllPublicationsTask() = this.tasks.register<Task>(PUBLISH_ALL_PUBLICATIONS_TO_CENTRAL_TASK_NAME) {
    group = PUBLISH_TASK_GROUP
    description = "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."
}

internal fun Project.registerPublishAllPublicationsTask(
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
) = registerPublishBundleMavenCentralTask(
    PUBLISH_ALL_PUBLICATIONS_TO_CENTRAL_TASK_NAME, mavenCentralUploaderExtension
) {
    group = PUBLISH_TASK_GROUP
    description = "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."

    configuration()
}

internal fun Project.registerPublishPublicationTask(
    publicationName: String,
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
) = registerPublishBundleMavenCentralTask(
    "publish${publicationName.capitalized()}PublicationTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}",
    mavenCentralUploaderExtension
) {
    group = PUBLISH_TASK_GROUP
    description = "Publishes Maven publication '$publicationName' to Maven repository '$MAVEN_CENTRAL_PORTAL_NAME'."

    configuration()
}

private fun Project.registerPublishBundleMavenCentralTask(
    name: String,
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
) = this.tasks.register<PublishBundleMavenCentralTask>(name) {
    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
    credentials.set(mavenCentralUploaderExtension.mapCredentials())

    publishingType.set(mavenCentralUploaderExtension.publishingType.map { it.mapModel() })
    deploymentName.set(mavenCentralUploaderExtension.deploymentName)

    maxRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.maxRetriesStatusCheck)
    delayRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.delayRetriesStatusCheck)

    configuration()
}